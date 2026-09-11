#!/usr/bin/env bash
set -euo pipefail

# One desired state, three colours, byte for byte. golden.sh is green's
# regression net against the committed goldens; this is the net across
# colours: each fixture is rendered by every colour into a separate work
# directory and the trees must be identical, and the template trees each
# colour carries must be identical too, because the copies are the mechanism
# (red/resources and blue's embedded resources are copies of green's tree,
# not references to it).
#
# Six fixtures: both SSH keypair modes on each of the three providers, and
# on AWS the managed backup bucket beside the operator-owned one.
#
# Renders resolve each colour's package from this working tree (the
# *_LIB_ROOT overrides) while the SDKs and colors-compute stay on their pins,
# so a change that lands here passes parity before it is pushed or pinned.
#
# Green only for now: the red and blue lines are present and disabled. When
# a port lands, set its variable to 1 (or delete the guard) and its render
# and diffs join the loop; the template-tree diffs at the end likewise.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

RED=${REDIS_PARITY_RED:-0}    # set to 1 once red/ exists
BLUE=${REDIS_PARITY_BLUE:-0}  # set to 1 once blue/ exists

build_variant() {
  local variant=$1
  for colour in green red blue; do
    sed "s#WORKDIR#$tmp/$variant/$colour#" "$root/test/fixtures/$variant.yml" \
      > "$tmp/$variant-$colour.yml"
  done
  (cd "$root/green" && REDIS_LIB_ROOT="$root" ./green build -f "$tmp/$variant-green.yml" >/dev/null)
  [[ -d "$tmp/$variant/green/$(sed -n 's/^profile: //p' "$tmp/$variant-green.yml")/redis-ansible" ]]
  if [[ $RED == 1 ]]; then
    (cd "$root/red" && REDIS_LIB_ROOT="$root/red" ./red build -f "$tmp/$variant-red.yml" >/dev/null)
    diff -r "$tmp/$variant/green" "$tmp/$variant/red"
  fi
  if [[ $BLUE == 1 ]]; then
    (cd "$root/blue" && uv run python -m package_redis_blue build -f "$tmp/$variant-blue.yml" >/dev/null)
    diff -r "$tmp/$variant/green" "$tmp/$variant/blue"
  fi
}

for variant in colors optout colors-digitalocean optout-digitalocean colors-aws optout-aws; do
  build_variant "$variant"
done

[[ $RED == 1 ]] && diff -r "$root/green/src/resources/io/github/getcolors/redis" "$root/red/resources"
[[ $BLUE == 1 ]] && diff -r "$root/green/src/resources/io/github/getcolors/redis" "$root/blue/src/package_redis_blue/resources"

colours=green
[[ $RED == 1 ]] && colours="$colours, red"
[[ $BLUE == 1 ]] && colours="$colours, blue"
echo "Redis artifacts render for every fixture in: $colours (red and blue join here when their ports land)"
