#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-redis-green/green"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
checks=0
fail(){ echo "launcher: FAIL — $*" >&2; exit 1; }
ok(){ checks=$((checks+1)); echo "  ok — $*"; }

[ -f "$launcher" ] || fail 'payload launcher is missing'
grep -q 'io.github.getcolors.redis.workflow/workflow' "$launcher" || fail 'workflow dispatch is missing'
grep -q '(lib-coord "REDIS_LIB_ROOT" redis-git-url redis-sha "green")' "$launcher" || fail 'the redis coordinate must carry :deps/root green'
for bad in 'defn.*-step' 'tofu/' 'ansible/'; do
  ! grep -qE "$bad" "$launcher" || fail "launcher contains package logic: $bad"
done
ok 'dispatches to the library and contains no lifecycle logic'

grep -qE '\(def \^:private redis-sha (nil|"[0-9a-f]{40}")\)' "$launcher" || fail 'invalid pin site'
[[ $(grep -c 'def \^:private redis-sha' "$launcher") == 1 ]] || fail 'more than one pin site'
ok 'has one managed immutable pin site'

mkdir "$tmp/bare"
cp "$launcher" "$tmp/bare/green"; chmod +x "$tmp/bare/green"
if grep -q '(def \^:private redis-sha nil)' "$launcher"; then
  out=$(cd "$tmp/bare" && ./green build 2>&1 || true)
  grep -q REDIS_LIB_ROOT <<<"$out" || fail 'an unpinned launcher did not explain REDIS_LIB_ROOT'
  ok 'unstamped payload fails with an actionable working-tree override'
else
  ok 'payload carries a real package commit pin'
fi

mkdir "$tmp/project"
cp "$launcher" "$tmp/project/green"; chmod +x "$tmp/project/green"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/project/colors.yml"
(cd "$tmp/project" && REDIS_LIB_ROOT="$root" ./green build >/dev/null) || fail 'REDIS_LIB_ROOT build failed'
[ -f "$tmp/project/.colors/redis-fixture/redis-infrastructure/nodes/0/node-none.tf.json" ] || fail 'copied payload rendered nothing'
[ -f "$tmp/project/.colors/redis-fixture/redis-ansible/compose.yml" ] || fail 'no ansible stage'
[ -f "$tmp/project/.colors/redis-fixture/redis-ansible-local/main.yml" ] || fail 'no ssh-config stage'
ok 'working-tree override renders from a copied payload'
mkdir -p "$tmp/project/deep/path"
(cd "$tmp/project/deep/path" && REDIS_LIB_ROOT="$root" ../../green build >/dev/null) || fail 'upward desired-state search failed'
ok 'finds colors.yml by walking upward'

out=$(cd "$tmp/project" && REDIS_LIB_ROOT="$root" COLORS_PAR_PROFILE=wrong ./green build 2>&1 || true)
grep -q COLORS_PAR_PROFILE <<<"$out" || fail 'COLORS_PAR_PROFILE was not refused'
[[ ! -d "$tmp/project/.colors/wrong" ]] || fail 'a profile overlay rendered a stage'
ok 'refuses the profile overlay'

out=$(cd "$tmp/project" && REDIS_LIB_ROOT="$root" ./green nonsense 2>&1 || true)
grep -q Usage <<<"$out" || fail 'unknown command has no usage'
for verb in build create delete rehearse describe; do
  grep -q "\"$verb\"" "$launcher" || fail "missing command $verb"
done
ok 'lifecycle, rehearsal and describe commands are dispatchable'

[ -L "$root/green/green" ] && [ "$(readlink "$root/green/green")" = ../skills/package-redis-green/green ] || fail 'green/green is not the payload symlink'
[ ! -e "$root/green" ] || [ -d "$root/green" ] || fail 'the repository root must carry no launcher of its own'
ok 'green/green is the payload symlink and the root carries no launcher'

# --- red -------------------------------------------------------------------
# The same contract for the red payload: it dispatches to the library and
# nothing else, carries exactly one pin site in the form `bb pin` rewrites,
# resolves colors-compute at the commit green's deps.edn pins, and a copy
# outside the repository renders every stage, finds colors.yml upward and
# refuses the profile overlay.

red_launcher="$root/skills/package-redis-red/red"
[ -f "$red_launcher" ] || fail 'red payload launcher is missing'
grep -q 'import("package-redis-red")' "$red_launcher" || fail 'red workflow dispatch is missing'
grep -q 'REDIS_LIB_ROOT' "$red_launcher" || fail 'red launcher has no working-tree override'
for bad in 'tofu' 'ansible-playbook' 'red/exit'; do
  ! grep -q "$bad" "$red_launcher" || fail "red launcher contains package logic: $bad"
done
ok 'red dispatches to the library and contains no lifecycle logic'

grep -qE '^  "package-redis-red": (null|"github:getcolors/redis#[0-9a-f]{40}"),$' "$red_launcher" || fail 'invalid red pin site'
[[ $(grep -c '"package-redis-red":' "$red_launcher") == 1 ]] || fail 'more than one red pin site'
ok 'red has one managed immutable pin site'

compute_sha=$(awk '/colors-compute\.git/ {found=1} found && match($0, /:git\/sha "[0-9a-f]{40}"/) {print substr($0, RSTART+10, 40); exit}' "$root/green/deps.edn")
[[ -n $compute_sha ]] || fail 'green/deps.edn carries no colors-compute pin'
grep -q "getcolors/colors-compute#$compute_sha" "$red_launcher" || fail 'red launcher pins colors-compute at a different commit than green'
grep -q "getcolors/colors-compute#$compute_sha" "$root/red/package.json" || fail 'red/package.json pins colors-compute at a different commit than green'
grep -q "getcolors/colors-compute#$compute_sha" "$root/package.json" || fail 'the root package.json pins colors-compute at a different commit than green'
ok 'every red record of the colors-compute pin matches green'

# colors-compute-red declares the Red SDK as a peer, so a cold launcher cache
# installs the SDK only because PINS names it. The pin must be the one
# red/package.json tests against, and a cold cache must actually resolve it:
# the working-tree builds below reuse red/node_modules and cannot see a
# missing peer.
red_sdk_sha=$(grep -oE '"red": "github:getcolors/red#[0-9a-f]{40}"' "$root/red/package.json" | grep -oE '[0-9a-f]{40}')
[[ -n $red_sdk_sha ]] || fail 'red/package.json carries no Red SDK pin'
grep -q "\"red\": \"github:getcolors/red#$red_sdk_sha\"" "$red_launcher" || fail 'red payload PINS the Red SDK at a different commit than red/package.json'
ok 'the red payload PINS the Red SDK at the red/package.json commit'
mkdir "$tmp/red-cold"
cp "$red_launcher" "$tmp/red-cold/red"; chmod +x "$tmp/red-cold/red"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/red-cold/colors.yml"
# One retry: a cold install fetches three GitHub tarballs and a transient
# fetch failure is not a payload defect. Each attempt starts from empty caches.
cold_ok=0
for attempt in 1 2; do
  rm -rf "$tmp/red-cold/xdg" "$tmp/red-cold/bun" "$tmp/red-cold/.colors"
  if (cd "$tmp/red-cold" && XDG_CACHE_HOME="$tmp/red-cold/xdg" BUN_INSTALL_CACHE_DIR="$tmp/red-cold/bun" ./red build >"$tmp/red-cold/build.log" 2>&1); then cold_ok=1; break; fi
done
[[ $cold_ok == 1 ]] || { tail -5 "$tmp/red-cold/build.log" >&2; fail 'red payload does not build from a cold cache'; }
ok 'red payload builds from a cold cache with only its PINS'

mkdir "$tmp/red-project"
cp "$red_launcher" "$tmp/red-project/red"; chmod +x "$tmp/red-project/red"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/red-project/colors.yml"
(cd "$tmp/red-project" && REDIS_LIB_ROOT="$root/red" ./red build >/dev/null) || fail 'red REDIS_LIB_ROOT build failed'
[ -f "$tmp/red-project/.colors/redis-fixture/redis-infrastructure/nodes/0/node-none.tf.json" ] || fail 'red copied payload rendered nothing'
[ -f "$tmp/red-project/.colors/redis-fixture/redis-ansible/compose.yml" ] || fail 'red rendered no ansible stage'
[ -f "$tmp/red-project/.colors/redis-fixture/redis-ansible-local/main.yml" ] || fail 'red rendered no ssh-config stage'
ok 'red working-tree override renders from a copied payload'
mkdir -p "$tmp/red-project/deep/path"
(cd "$tmp/red-project/deep/path" && REDIS_LIB_ROOT="$root/red" ../../red build >/dev/null) || fail 'red upward desired-state search failed'
ok 'red finds colors.yml by walking upward'

out=$(cd "$tmp/red-project" && REDIS_LIB_ROOT="$root/red" COLORS_PAR_PROFILE=wrong ./red build 2>&1 || true)
grep -q COLORS_PAR_PROFILE <<<"$out" || fail 'red did not refuse COLORS_PAR_PROFILE'
[[ ! -d "$tmp/red-project/.colors/wrong" ]] || fail 'red rendered a stage for a profile overlay'
ok 'red refuses the profile overlay'

out=$(cd "$tmp/red-project" && REDIS_LIB_ROOT="$root/red" ./red nonsense 2>&1 || true)
grep -q Usage <<<"$out" || fail 'red unknown command has no usage'
for verb in create delete rehearse describe; do
  (cd "$tmp/red-project" && REDIS_LIB_ROOT="$root/red" ./red "$verb" --dry-run >/dev/null 2>&1) || fail "red $verb --dry-run failed"
done
ok 'red lifecycle, rehearsal and describe commands are dispatchable'

[ -L "$root/red/red" ] && [ "$(readlink "$root/red/red")" = ../skills/package-redis-red/red ] || fail 'red/red is not the payload symlink'
[ ! -e "$root/red" ] || [ -d "$root/red" ] || fail 'the repository root must carry no red launcher of its own'
ok 'red/red is the payload symlink and the root carries no launcher'
# --- the blue payload ---------------------------------------------------------
# Blue reproduces green's goldens byte for byte (scripts/parity.sh); what is
# checked here is the payload shape a deployment installs: the launcher names
# its package, dispatches to it, carries exactly the pin form `bb pin`
# rewrites, and works from a copy outside the repository.
blue_launcher="$root/skills/package-redis-blue/blue"
[ -f "$blue_launcher" ] || fail 'blue payload launcher is missing'
grep -q 'from package_redis_blue import exec' "$blue_launcher" || fail 'blue launcher does not dispatch to its package'
grep -q '"-m", "package_redis_blue"' "$blue_launcher" || fail 'blue launcher does not run the working-tree package'
grep -q 'REDIS_LIB_ROOT' "$blue_launcher" || fail 'blue launcher has no working-tree override'
for bad in 'def .*_step' 'tofu' 'ansible'; do
  ! grep -qE "$bad" "$blue_launcher" || fail "blue launcher contains package logic: $bad"
done
ok 'blue: dispatches to the library and contains no lifecycle logic'

if grep -qF '# dependencies = []' "$blue_launcher"; then
  ! grep -q 'redis\.git", rev = "' "$blue_launcher" || fail 'blue launcher is both unpinned and pinned'
  grep -q '^# UNPINNED:' "$blue_launcher" || fail 'unpinned blue launcher lacks the UNPINNED paragraph'
  ok 'blue: unstamped payload carries the birth metadata shape'
else
  [[ $(grep -cE 'redis\.git", rev = "[0-9a-f]{40}", subdirectory = "blue"' "$blue_launcher") == 1 ]] || fail 'blue launcher has no single pin site'
  grep -q 'blue.git", rev = "' "$blue_launcher" || fail 'pinned blue launcher names no blue SDK pin'
  grep -q 'colors-compute.git@' "$blue_launcher" || fail 'pinned blue launcher names no colors-compute pin'
  ok 'blue: payload carries a real package commit pin'
fi

compute_sha=$(sed -n 's/.*colors-compute.git"[^"]*:git\/sha "\([0-9a-f]\{40\}\)".*/\1/p' "$root/green/deps.edn")
[ -n "$compute_sha" ] || compute_sha=$(awk '/colors-compute\.git/ {found=1} found && match($0, /:git\/sha "[0-9a-f]{40}"/) {print substr($0, RSTART+10, 40); exit}' "$root/green/deps.edn")
[ -n "$compute_sha" ] || fail 'green/deps.edn carries no colors-compute pin'
grep -q "rev = \"$compute_sha\"" "$root/blue/pyproject.toml" || fail 'blue/pyproject.toml colors-compute pin differs from green'
grep -q "colors-compute.git@$compute_sha" "$root/green/tasks/pin.clj" || fail 'the blue pin site in pin.clj carries a different colors-compute pin'
blue_sha=$(sed -n 's/^blue = { git = "https:\/\/github.com\/getcolors\/blue.git", rev = "\([0-9a-f]\{40\}\)" }$/\1/p' "$root/blue/pyproject.toml")
[ -n "$blue_sha" ] || fail 'blue/pyproject.toml carries no blue SDK pin'
grep -qF "blue.git\\\", rev = \\\"$blue_sha" "$root/green/tasks/pin.clj" || fail 'the blue pin site in pin.clj carries a different blue SDK pin'
ok 'blue: the colors-compute and blue SDK pins agree between green, blue and the pin site'

mkdir "$tmp/blue"
cp "$blue_launcher" "$tmp/blue/blue"; chmod +x "$tmp/blue/blue"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/blue/colors.yml"
(cd "$tmp/blue" && REDIS_LIB_ROOT="$root" ./blue build >/dev/null 2>&1) || fail 'blue: REDIS_LIB_ROOT build failed from a copied payload'
diff -r "$root/test/resources/golden/r2/redis-fixture" "$tmp/blue/.colors/redis-fixture" >/dev/null \
  || fail 'blue: a copied payload rendered something other than the golden'
ok 'blue: working-tree override renders the golden from a copied payload'
mkdir -p "$tmp/blue/deep/path"
(cd "$tmp/blue/deep/path" && REDIS_LIB_ROOT="$root" ../../blue build >/dev/null 2>&1) || fail 'blue: upward desired-state search failed'
ok 'blue: finds colors.yml by walking upward'
out=$(cd "$tmp/blue" && REDIS_LIB_ROOT="$root" COLORS_PAR_PROFILE=wrong ./blue build 2>&1 || true)
grep -q COLORS_PAR_PROFILE <<<"$out" || fail 'blue: COLORS_PAR_PROFILE was not refused'
[[ ! -d "$tmp/blue/.colors/wrong" ]] || fail 'blue: a profile overlay rendered a stage'
ok 'blue: refuses the profile overlay'
out=$(cd "$tmp/blue" && REDIS_LIB_ROOT="$root" ./blue nonsense 2>&1 || true)
grep -q Usage <<<"$out" || fail 'blue: unknown command has no usage'
for verb in build create delete rehearse describe; do
  grep -q "\"$verb\"" "$root/blue/src/package_redis_blue/cli.py" || fail "blue: missing command $verb"
done
ok 'blue: lifecycle, rehearsal and describe commands are dispatchable'
[ -L "$root/blue/blue" ] && [ "$(readlink "$root/blue/blue")" = ../skills/package-redis-blue/blue ] || fail 'blue/blue is not the payload symlink'
ok 'blue/blue is the payload symlink'
echo "launcher: $checks checks passed"
