#!/usr/bin/env bash
set -euo pipefail

# The regression net against the committed goldens: render every fixture and
# diff against committed output.
#
# Two fixtures, because the SSH Keypair Standard has two modes and a package
# conforms only if both hold. `colors.yml` is keygen mode (no vultr-ssh-keys):
# the compute template must declare the profile-named vultr_ssh_key resource
# and reference it by attribute. `optout.yml` supplies an explicit key id and
# must render the historical shape, byte for byte, creating nothing.
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

status=0
for variant in colors optout; do
  fixture="$tmp/$variant.yml"
  sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/$variant.yml" > "$fixture"
  (cd "$root" && REDIS_LIB_ROOT="$root" ./green build -f "$fixture" >/dev/null)

  profile=$(sed -n 's/^profile: //p' "$fixture")
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
  # The VPC address is a run-time fact the Compose template reads from the
  # inventory on the host; the rendered file must carry the expression.
  grep -q '{{ vpc_ip }}' "$actual/redis-ansible/compose.yml" \
    || { echo "golden: $profile compose.yml no longer reads vpc_ip from the inventory" >&2; exit 1; }
  # The password never enters a rendered file: it is generated on the host.
  if grep -rEq 'requirepass [0-9a-f]{16}' "$actual"; then
    echo "golden: $profile rendered a Redis password" >&2; exit 1
  fi
  # Every rendered script must at least parse.
  for sh in "$actual"/redis-ansible/*.sh; do
    bash -n "$sh" || { echo "golden: $sh does not parse" >&2; exit 1; }
  done
  # One machine, one VPC, a firewall opening 22 alone.
  infra="$actual/redis-infrastructure/main.tf"
  for r in 'resource "vultr_instance" "redis"' 'resource "vultr_vpc" "redis"' \
           'resource "vultr_firewall_rule" "ssh"' 'port              = "22"'; do
    grep -q "$r" "$infra" || { echo "golden: $profile infrastructure lacks: $r" >&2; exit 1; }
  done
  grep -q 'prevent_destroy = true' "$infra"
  if grep -qE 'port += +"(6379|80|443)"' "$infra"; then
    echo "golden: $profile infrastructure opens a port other than 22" >&2; exit 1
  fi

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

# Keygen mode owns a profile-named account key resource; opt-out creates none
# and keeps the literal id it was given (SSH Keypair Standard §4.3, §5).
keygen="$root/test/resources/golden/local/redis-fixture/redis-infrastructure/main.tf"
optout="$root/test/resources/golden/local/redis-optout-fixture/redis-infrastructure/main.tf"
if [[ $accept == 1 ]]; then
  keygen="$tmp/work/redis-fixture/redis-infrastructure/main.tf"
  optout="$tmp/work/redis-optout-fixture/redis-infrastructure/main.tf"
fi
grep -q 'resource "vultr_ssh_key" "machine"' "$keygen"
grep -q 'ssh_key_ids = \[vultr_ssh_key.machine.id\]' "$keygen"
if grep -q 'vultr_ssh_key' "$optout"; then
  echo 'golden: opt-out mode rendered an account key resource' >&2; exit 1
fi
grep -q 'ssh_key_ids = \["00000000-0000-4000-8000-000000000000"\]' "$optout"

[[ $status == 0 ]] && echo 'all Redis goldens and safety assertions pass'
exit "$status"
