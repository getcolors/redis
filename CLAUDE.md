# CLAUDE.md

Guidance for agents working in this repository. Read
`~/code/getcolors/CLAUDE.md` first for the cross-repository conventions; this
file covers only what is specific to `redis`.

## What this is

A Package Skill: one Redis 7.2 server on one Vultr instance, one
DigitalOcean droplet or one AWS EC2 instance: one Docker Compose service,
published on loopback only, reached over an SSH tunnel, with RDB backup sets
in Cloudflare R2 or in a deployment-owned S3 bucket, and a rehearsal verb
that proves one of them restores. The first consumer is `../redis-vultr`.
Code and tests are authoritative; the shape was taken from `../neon` (single
node, no DNS, tunnel client path), the backup-set protocol and Redis pieces
from `../langfuse`, and the managed bucket from `../neon-multi-node`.

## Layout

The repository carries the tri-colour layout of `../neon`. Green and blue
exist today: `green/` holds `bb.edn`, `deps.edn`, `src/`, `tasks/` and
`test/clj/`, and `green/green` is a symlink to the payload
`skills/package-redis-green/green`; `blue/` holds `pyproject.toml`,
`src/package_redis_blue/` and `tests/`, and `blue/blue` is a symlink to the
payload `skills/package-redis-blue/blue`. There is no launcher at the root.
Fixtures and goldens are shared at the root (`test/fixtures/`,
`test/resources/golden/<backend>/<profile>/`) with symlinks from
`green/test/`; the blue tests read them through `tests/conftest.py`. The
root `package.json` is the facade the red port drops into,
`scripts/parity.sh` renders every fixture through green and blue and carries
the disabled red line, and `green/tasks/pin.clj` stamps the green and blue
sites and is shaped so the red site can be added. Blue's
`src/package_redis_blue/resources` is a byte-identical copy of green's
template tree, enforced by `scripts/parity.sh`; a port must render every
fixture byte-identically to green and must not own templates of its own.

## Things to understand before touching anything

- **Exposure is decided by what Compose publishes.** Inside the container
  Redis binds `0.0.0.0`; the one host binding in `compose.yml`, `127.0.0.1`,
  is the whole of what can reach it, and the smoke gate asks the kernel
  (`ss -ltn`) that exactly that one listens. There is no private-address
  binding any more: the VPC and its `{{ vpc_ip }}` were dropped when the
  package adopted the Compute Provider Standard, because a single-node
  package creates no private network and nothing ever used it.
- **colors-compute owns compute.** The package requests one public host and
  an SSH-only firewall through `redis.compute`. Provider recipes, credentials,
  network selection, remote S3/R2 state, ownership coordination and SSH key
  lifecycle come from the pinned library. Do not add a package provider
  registry or compute templates. Vultr remains the default.
- **Owned state is required.** The library refuses legacy monolithic state,
  conflicting providers and unreadable state. A failed state read never means
  absence. Delete, rehearse and describe inspect the recorded normalized node
  before running application work. Real inventories never use build addresses.
- **Docker's published ports bypass ufw.** The Vultr image ships ufw enabled
  with 22 alone, the DigitalOcean one ships none; the provider firewall (22
  only) and the loopback binding are the load-bearing layers, and the
  workstation-side acceptance proves the public address does not answer on
  the Redis port.
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
- **The managed bucket is a stage, and its pair is an output.** With
  `redis-storage-managed: true` (AWS only) `storage.clj` renders
  `tools/storage/main.tf`: the bucket named by `redis-backup-r2-bucket`,
  its public access block and AES256 encryption, `force_destroy = true`,
  `prevent_destroy` from `compute-prevent-destroy`, an IAM user
  `<profile>-storage-backup` with a bucket-scoped policy, and one access
  key as the sensitive `credentials` output. `tools/run-play` hands that
  pair to ansible-playbook as `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID`
  and `_SECRET_ACCESS_KEY`, so `main.yml` keeps its `lookup('env', ...)`
  expressions and `golden.sh` keeps asserting them. The pair never enters a
  template value: `ansible-data`, `ansible-local-data` and `storage/specs`
  all drop `:redis/storage-credentials`. `secret-errors` skips the
  operator's pair when storage is managed. The SDK's
  `ansible/ansible-with-spec` takes no environment, which is why
  `run-play` exists; it mirrors that function's build, delete and create
  behaviour, host-key handling and recap parsing.
- **The create DAG on AWS is start, infrastructure, storage, ssh-config,
  ansible, acceptance. The delete DAG is start, load-infrastructure,
  ansible, ssh-config, infrastructure, storage, backend-finalize.** The
  bucket outlives the machine the way the keypair does, so the last backup
  timer run cannot fail against a missing bucket, and the managed S3 state
  bucket (`s3-bucket-mode: managed`) goes last because every other stage's
  state lives in it. A repeat delete must exit 0: inspection reporting the
  compute destroyed or absent sets `:redis/already-destroyed` and `next-fn`
  routes to storage and then the finalizer; an inspection error under a
  managed backend sets `:redis/finalize-only` and routes straight to the
  finalizer, which proves absence or owned retirement itself. An error with
  nothing managed stays an error: a failed read never means absence.
- **Rehearse reads the pair back from storage state** (`read-credentials!`)
  because it runs a play without converging the stage; describe reads
  nothing. AWS credentials come from the ambient chain, or from
  `COLORS_PAR_AWS_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` and `_SESSION_TOKEN`
  overlaid onto `AWS_*` by `storage/aws-env` for tofu, the AWS CLI, the
  library's orchestration and inspection, and the finalizer.
- **AWS renders differently from the other two providers, by design.** The
  library owns a VPC, a subnet and a security group on AWS because an
  instance cannot exist without them, so the node carries a `vpc_ip` nothing
  in this package reads; and the key pair is registered from a public key in
  both SSH modes, so `shared-keygen.tf.json` exists for the opt-out fixture
  too. `scripts/compute-contract.py` encodes both.
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
`<provider>-name`, and `../workspace/standards/compute-provider.md` before
changing the compute request or state inspection. Build and
dry-run render `/home/build-placeholder/.ssh/<profile>` rather than reading
`~/.ssh`.

## Commands

```sh
cd green && bb test
cd green && bb golden      # six fixtures: keygen and opt-out on Vultr, DigitalOcean and AWS
cd blue && uv sync && uv run pytest
cd green && bb golden:accept   # only after reading the diff
cd green && bb syntax      # offline ansible-playbook --syntax-check + bash -n
./scripts/launcher.sh      # from the repository root
./scripts/parity.sh        # every fixture through green and blue, byte for byte
cd green && ./green build
cd green && ./green create --dry-run
cd green && ./green create     # requires explicit authorization
cd green && ./green rehearse   # against a live deployment
cd green && ./green describe
cd green && ./green delete     # guarded and destructive
```

`bb syntax` and the acceptance gate need the devenv toolchain (`direnv
allow`): ansible-playbook and redis-cli come from it. The AWS fixtures
build and dry-run without credentials like the others; the opt-out one
names a public key file the build never opens.

Never read `.envrc.private`, edit `.colors/`, export `COLORS_PAR_PROFILE`, or
weaken `compute-prevent-destroy`. Build and dry-run are credential-free and
must not touch `~/.ssh`.

## Coupling

`green/deps.edn` pins Green and colors-compute; `blue/pyproject.toml` pins
the blue SDK and colors-compute at the commits the green pins correspond
to, and the blue payload's PEP 723 block repeats them once `bb pin` stamps
it. Provider support changes
belong in colors-compute and reach Redis through a library version bump; the
managed S3 backend (`compute-managed-backend`) and AWS arrived with the pin
at `09ec539`. Use `GREEN_LIB_ROOT`, `COLORS_COMPUTE_LIB_ROOT` and
`REDIS_LIB_ROOT` for development; `REDIS_LIB_ROOT` names the repository root
and the launcher adds `green` itself, the way `NEON_LIB_ROOT` works. `bb pin`
(from `green/`) stamps the payload from a clean pushed HEAD; deployment
launchers are copies, not symlinks.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable, and the
self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`.
Never add one tag without the other.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
