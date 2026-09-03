# CLAUDE.md

Guidance for agents working in this repository. Read
`~/code/getcolors/CLAUDE.md` first for the cross-repository conventions; this
file covers only what is specific to `redis`.

## What this is

A green-only Package Skill: one Redis 7.2 server on one Vultr instance in
its own VPC — one Docker Compose service, published on loopback and the VPC
address only, reached over an SSH tunnel, with RDB backup sets in Cloudflare
R2 and a rehearsal verb that proves one of them restores. The first consumer
is `../redis-vultr`. Code and tests are authoritative; the shape was taken
from `../neon` (single node, no DNS, tunnel client path) and the backup-set
protocol and Redis pieces from `../langfuse`.

## Things to understand before touching anything

- **Exposure is decided by what Compose publishes.** Inside the container
  Redis binds `0.0.0.0`; the two host bindings in `compose.yml` —
  `127.0.0.1` and `{{ vpc_ip }}` — are the whole of what can reach it, and
  the smoke gate asks the kernel (`ss -ltn`) that exactly those two listen.
  The VPC address is a run-time fact: it reaches the Compose file through
  the inventory and Ansible's `template:`, never through Selmer, so the
  rendered tree in `.colors/` carries no address of its own.
- **Docker's published ports bypass ufw.** The Vultr image ships ufw enabled
  with 22 alone; the provider firewall group (22 only) and the bindings are
  the load-bearing layers, and the workstation-side acceptance proves the
  public address does not answer on the Redis port.
- **The password is create-once on the host** (`/etc/redis/secrets/password`)
  and lives in `redis.conf`, readable by uid 999 alone — never on a command
  line, never in the container environment. Scripts hand it to `redis-cli`
  through `REDISCLI_AUTH`.
- **Backups stream over the replication protocol** (`redis-cli --rdb -`): a
  point-in-time fork, no reads from the data volume, verified by
  `redis-check-rdb` inside the pinned image before the set counts. The
  completion marker is written last and only after the uploaded bytes were
  read back and hashed; emptiness counts as absence.
- **The restore scratch runs with `--appendonly no`.** A Redis 7 started with
  AOF on and no `appendonlydir/` beside `dump.rdb` ignores the RDB and starts
  empty, which would make an intact set read as a lost one.
- **The smoke gate restarts Redis** on every converge to prove the AOF: a
  second or so of unavailability, by design, on a cache/queue tier.
- **Secrets never reach rendered output.** The backup pair appears in
  `main.yml` as literal `{{ lookup('env', …) }}` expressions that
  `preserve-jinja-delimiters` passes through; `scripts/golden.sh` fails if
  they stop appearing. Routing them through the Selmer data map would
  HTML-escape the quotes.
- **Ansible splits shell blocks before running them**, counting quotes across
  comments. Quoting-heavy shell lives in the installed scripts; `bb syntax`
  reproduces every load-time failure offline in a second.
- **`state-output` keeps `:ssh_key_id` with the underscore.** ONCE's create
  matrix reads it from the map `state-fn` returns; renaming it makes the
  deployment's own key read as foreign and the never-adopt rule refuses it.
  `:vpc-ip` is added beside `:vpc_ip`, never instead.

## Verbs beyond the lifecycle

`rehearse` takes a fresh set, restores the newest completed one into a
scratch container of the pinned image, reads `colors:smoke` back, and writes
`<profile>/.colors-recovery-verified`. `describe` reads the host's last
monitor result over the generated SSH alias. Both need compute in state.

## The SSH keypair and `~/.ssh/config`

Born conforming to three workspace standards. Read
`../workspace/standards/ssh-keypair.md` before touching `ssh.clj`,
`../workspace/standards/ssh-config.md` before touching `ssh_config.clj`, and
`../workspace/standards/compute-name.md` for why there is no required
`vultr-name`. Build and dry-run render `/home/build-placeholder/.ssh/<profile>`
rather than reading `~/.ssh`.

## Commands

```sh
bb test
bb golden                  # two fixtures: keygen and opt-out
bb golden:accept           # only after reading the diff
bb syntax                  # offline ansible-playbook --syntax-check + bash -n
./scripts/launcher.sh
./green build
./green create --dry-run
./green create             # requires explicit authorization
./green rehearse           # against a live deployment
./green describe
./green delete             # guarded and destructive
```

`bb syntax` and the acceptance gate need the devenv toolchain (`direnv
allow`): ansible-playbook and redis-cli come from it.

Never read `.envrc.private`, edit `.colors/`, export `COLORS_PAR_PROFILE`, or
weaken `compute-prevent-destroy`. Build and dry-run are credential-free and
must not touch `~/.ssh`.

## Coupling

`deps.edn` pins Green and ONCE (never below `bc06f2f`). Use `GREEN_LIB_ROOT`,
`ONCE_LIB_ROOT` and `REDIS_LIB_ROOT` for working-tree development. `bb pin`
stamps the payload from a clean pushed HEAD; deployment launchers are copies,
not symlinks.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable, and the
self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`.
Never add one tag without the other.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
