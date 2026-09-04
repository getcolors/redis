#!/usr/bin/env bash
set -euo pipefail

# The regression net against the committed goldens: render every fixture and
# diff against committed output.
#
# Four fixtures: one per advertised compute provider per SSH keypair mode
# (Compute Provider Standard §7). `colors.yml` and `colors-digitalocean.yml`
# are keygen mode (no `<provider>-ssh-keys`): the compute template must
# declare the profile-named account key resource and reference it by
# attribute. `optout.yml` and `optout-digitalocean.yml` supply an explicit key
# id and must render the historical shape, byte for byte, creating nothing.
#
# Keygen paths are rendered from a fixed placeholder home on :build, never from
# $HOME, so these goldens mean the same thing on every workstation.
#
#   ./scripts/golden.sh            check
#   ./scripts/golden.sh --accept   regenerate after an intended change

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

accept=0
[[ ${1:-} == --accept ]] && accept=1

# What each provider's template must and must not render: one machine, a
# firewall opening 22 alone, the params contract's provider line, and no
# private network of any kind.
declare -A instance=([vultr]='resource "vultr_instance" "redis"'
                     [digitalocean]='resource "digitalocean_droplet" "redis"')
declare -A firewall=([vultr]='resource "vultr_firewall_rule" "ssh"'
                     [digitalocean]='resource "digitalocean_firewall" "redis"')
declare -A port22=([vultr]='port              = "22"'
                   [digitalocean]='port_range       = "22"')
declare -A keyres=([vultr]='resource "vultr_ssh_key" "machine"'
                   [digitalocean]='resource "digitalocean_ssh_key" "machine"')
declare -A keyref=([vultr]='ssh_key_ids = \[vultr_ssh_key.machine.id\]'
                   [digitalocean]='ssh_keys = \[digitalocean_ssh_key.machine.id\]')
declare -A keylit=([vultr]='ssh_key_ids = \["00000000-0000-4000-8000-000000000000"\]'
                   [digitalocean]='ssh_keys = \["00000000"\]')

status=0
for variant in colors optout colors-digitalocean optout-digitalocean; do
  fixture="$tmp/$variant.yml"
  sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/$variant.yml" > "$fixture"
  (cd "$root" && REDIS_LIB_ROOT="$root" ./green build -f "$fixture" >/dev/null)

  profile=$(sed -n 's/^profile: //p' "$fixture")
  provider=$(sed -n 's/^provider-compute: //p' "$fixture")
  actual="$tmp/work/$profile"
  golden="$root/test/resources/golden/local/$profile"

  # No rendered artefact may carry a real secret into a committed golden.
  if grep -rEq 'BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_' "$actual"; then
    echo "golden: a credential-shaped value was rendered in $profile" >&2; exit 1
  fi
  # The operator secret must reach the host as an Ansible lookup resolved at
  # execution time, never as a value templated into generated output. If this
  # expression stops appearing, something started rendering the secret itself
  # and the next `bb golden:accept` would commit it.
  for par in REDIS_BACKUP_R2_ACCESS_KEY_ID REDIS_BACKUP_R2_SECRET_ACCESS_KEY; do
    grep -q "lookup('env','COLORS_PAR_$par')" "$actual/redis-ansible/main.yml" \
      || { echo "golden: $profile no longer renders COLORS_PAR_$par as a lookup" >&2; exit 1; }
  done
  # Loopback is the only host binding; a second one is a second address to
  # reason about, and the VPC one is gone by design.
  if [[ $(grep -c ':<{ redis-port }>:6379\|:6379:6379' "$actual/redis-ansible/compose.yml") != 1 ]] \
     || ! grep -q '"127.0.0.1:6379:6379"' "$actual/redis-ansible/compose.yml"; then
    echo "golden: $profile compose.yml must publish the port on 127.0.0.1 and nowhere else" >&2; exit 1
  fi
  # The password never enters a rendered file: it is generated on the host.
  if grep -rEq 'requirepass [0-9a-f]{16}' "$actual"; then
    echo "golden: $profile rendered a Redis password" >&2; exit 1
  fi
  # Every rendered script must at least parse.
  for sh in "$actual"/redis-ansible/*.sh; do
    bash -n "$sh" || { echo "golden: $sh does not parse" >&2; exit 1; }
  done
  # One machine, a firewall opening 22 alone, the provider recorded in params,
  # and no private network.
  infra="$actual/redis-infrastructure/main.tf"
  for r in "${instance[$provider]}" "${firewall[$provider]}" "${port22[$provider]}" \
           "provider = \"$provider\""; do
    grep -qF "$r" "$infra" || { echo "golden: $profile infrastructure lacks: $r" >&2; exit 1; }
  done
  grep -q 'prevent_destroy = true' "$infra"
  if grep -qE 'port(_range)? += +"(6379|80|443)"' "$infra"; then
    echo "golden: $profile infrastructure opens a port other than 22" >&2; exit 1
  fi
  if grep -qE 'resource "(vultr_vpc|vultr_vpc2|digitalocean_vpc)"' "$infra"; then
    echo "golden: $profile infrastructure creates a private network" >&2; exit 1
  fi
  # Keygen mode owns a profile-named account key resource; opt-out creates
  # none and keeps the literal id it was given (SSH Keypair Standard §4.3, §5).
  case $variant in
    colors*)
      grep -q "${keyres[$provider]}" "$infra" || { echo "golden: $profile lacks the account key resource" >&2; exit 1; }
      grep -q "${keyref[$provider]}" "$infra" || { echo "golden: $profile does not reference the key by attribute" >&2; exit 1; } ;;
    optout*)
      if grep -q "${keyres[$provider]}" "$infra"; then
        echo "golden: $profile (opt-out) rendered an account key resource" >&2; exit 1
      fi
      grep -q "${keylit[$provider]}" "$infra" || { echo "golden: $profile does not keep the literal key id" >&2; exit 1; } ;;
  esac

  # A build that reached the real ~/.ssh would leak the operator's home into
  # committed bytes and make the goldens workstation-specific.
  if grep -rq "$HOME/.ssh" "$actual"; then
    echo "golden: $profile rendered a real home directory; build must use the placeholder" >&2; exit 1
  fi
  # SSH Config Standard §6: the local stage takes the address, the user and the
  # alias as Ansible extra-vars, never through Selmer, so its rendered playbook
  # carries no address at all.
  if grep -rEq '([0-9]{1,3}\.){3}[0-9]{1,3}' "$actual/redis-ansible-local"; then
    echo "golden: $profile rendered an address into the local ssh_config stage" >&2; exit 1
  fi

  if [[ $accept == 1 ]]; then
    rm -rf "$golden"; mkdir -p "$(dirname "$golden")"; cp -a "$actual" "$golden"; continue
  fi
  [[ -d "$golden" ]] || { echo "golden missing for $profile; inspect build then run bb golden:accept" >&2; exit 1; }
  diff -ru "$golden" "$actual" || status=1
done

[[ $status == 0 ]] && echo 'all Redis goldens and safety assertions pass'
exit "$status"
