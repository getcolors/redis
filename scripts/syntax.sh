#!/usr/bin/env bash
# Offline syntax gate for the rendered convergence tree: ansible-playbook
# --syntax-check reproduces every load-time failure in about a second, with
# no credentials, no host and no money. Renders the keygen fixture first.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/colors.yml" > "$tmp/colors.yml"
(cd "$root" && REDIS_LIB_ROOT="$root" ./green build -f "$tmp/colors.yml" >/dev/null)
dir="$tmp/work/redis-fixture/redis-ansible"
rc=0
for pb in main.yml cleanup.yml rehearsal.yml; do
  if (cd "$dir" && ansible-playbook --syntax-check -i inventory.json "$pb" >/dev/null 2>&1); then
    echo "  ok    $pb"
  else
    echo "  FAIL  $pb" >&2
    (cd "$dir" && ansible-playbook --syntax-check -i inventory.json "$pb" 2>&1 | tail -8 | sed 's/^/        /') >&2
    rc=1
  fi
done
local_dir="$tmp/work/redis-fixture/redis-ansible-local"
if (cd "$local_dir" && ansible-playbook --syntax-check -i inventory.ini main.yml >/dev/null 2>&1); then
  echo "  ok    ansible-local/main.yml"
else
  echo "  FAIL  ansible-local/main.yml" >&2; rc=1
fi
for sh in "$dir"/*.sh; do
  bash -n "$sh" && echo "  ok    $(basename "$sh") (bash -n)" || { echo "  FAIL  $(basename "$sh")" >&2; rc=1; }
done
exit "$rc"
