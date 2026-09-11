#!/usr/bin/env bash
set -euo pipefail

# Green's regression net against the committed goldens: render every fixture
# and diff against committed output. scripts/parity.sh is the net across
# colours.
#
# Six fixtures: one per advertised compute provider per SSH keypair mode
# (Compute Provider Standard §7). `colors*.yml` are keygen mode (no
# `<provider>-ssh-keys` or `-ssh-authorized-keys`): the compute template must
# declare the profile-named account key resource and reference it by
# attribute. `optout*.yml` supply an explicit key and must render the
# historical shape, byte for byte, creating nothing. `colors-aws.yml` also
# carries a managed backup bucket and a managed S3 state backend, so it is the
# one fixture with a `redis-storage` stage; `optout-aws.yml` keeps an
# operator-owned bucket and an external state bucket, so it must render none.
#
# Goldens live under test/resources/golden/<provider-backend>/<profile>/.
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
for variant in colors optout colors-digitalocean optout-digitalocean colors-aws optout-aws; do
  fixture="$tmp/$variant.yml"
  sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/$variant.yml" > "$fixture"
  (cd "$root/green" && REDIS_LIB_ROOT="$root" ./green build -f "$fixture" >/dev/null)

  profile=$(sed -n 's/^profile: //p' "$fixture")
  provider=$(sed -n 's/^provider-compute: //p' "$fixture")
  backend=$(sed -n 's/^provider-backend: //p' "$fixture")
  managed=$(sed -n 's/^redis-storage-managed: //p' "$fixture")
  actual="$tmp/work/$profile"
  golden="$root/test/resources/golden/$backend/$profile"

  # No rendered artefact may carry a real secret into a committed golden.
  if grep -rEq 'BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_|AKIA[0-9A-Z]{16}' "$actual"; then
    echo "golden: a credential-shaped value was rendered in $profile" >&2; exit 1
  fi
  # The backup pair must reach the host as an Ansible lookup resolved at
  # execution time, never as a value templated into generated output. With a
  # managed bucket the package injects the scoped pair under the same names,
  # so the same expression holds for both. If it stops appearing, something
  # started rendering the secret itself and the next `bb golden:accept` would
  # commit it.
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
  # An amazonaws.com endpoint switches rclone to its AWS provider; every other
  # endpoint keeps Cloudflare. Both keep the two skips that R2 taught us.
  if ! grep -q 'RCLONE_CONFIG_BACKUP_PROVIDER=AWS' "$actual/redis-ansible/r2-env.sh" \
     || ! grep -q 'RCLONE_CONFIG_BACKUP_NO_CHECK_BUCKET=true RCLONE_CONFIG_BACKUP_NO_HEAD=true' "$actual/redis-ansible/r2-env.sh"; then
    echo "golden: $profile r2-env.sh lost the AWS provider switch or the rclone skips" >&2; exit 1
  fi
  mode=external
  [[ $variant == colors* ]] && mode=managed
  python3 "$root/scripts/compute-contract.py" "$actual/redis-infrastructure" "$provider" "$mode"

  # The storage stage exists exactly when the bucket is managed, and then it
  # is the deployment-owned bucket with the scoped identity and no credential.
  storage="$actual/redis-storage"
  if [[ $managed == true ]]; then
    [[ -f $storage/main.tf && -f $storage/backend.tf.json ]] || { echo "golden: $profile has no storage stage" >&2; exit 1; }
    bucket=$(sed -n 's/^redis-backup-r2-bucket: //p' "$fixture")
    for needle in "backup = \"$bucket\"" 'force_destroy = true' 'prevent_destroy = true' \
                  'resource "aws_iam_user_policy" "application"' '"s3:PutObject"' \
                  'resource "aws_s3_bucket_public_access_block" "application"' 'sse_algorithm = "AES256"' \
                  "name = \"$profile-storage-\${each.key}\"" 'sensitive = true'; do
      grep -qF -- "$needle" "$storage/main.tf" || { echo "golden: $profile storage main.tf lacks $needle" >&2; exit 1; }
    done
    if grep -qE 'access_key_id *= *"|secret_access_key *= *"|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY' "$storage/main.tf" "$storage/backend.tf.json"; then
      echo "golden: $profile storage stage rendered credential material" >&2; exit 1
    fi
    grep -q "\"key\" : \"$profile/redis-storage.tfstate\"" "$storage/backend.tf.json" \
      || { echo "golden: $profile storage state is not under <profile>/" >&2; exit 1; }
  else
    [[ ! -e $storage ]] || { echo "golden: $profile rendered a storage stage without managed storage" >&2; exit 1; }
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

[[ $status == 0 ]] && echo 'all Redis goldens and safety assertions pass'
exit "$status"
