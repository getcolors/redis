# redis

A Package Skill that provisions **one Redis 7.2 server on one Vultr
instance, one DigitalOcean droplet or one AWS EC2 instance**: one Docker
Compose service with `maxmemory-policy noeviction`, an append-only file on a
named volume, a password generated on the host, published on loopback and
nowhere else. RDB backup sets go to an S3-compatible bucket (Cloudflare R2,
or on AWS a bucket the deployment owns) with a completion protocol, and
`./green rehearse` proves one of them restores.

The green (Clojure/Babashka) implementation lives in `green/`; the repository
carries the tri-colour layout so that red (TypeScript/Bun) and blue
(Python/uv) ports can land beside it as byte-identical siblings.

Nothing is published beyond loopback and the package creates no private
network of its own (on AWS the library owns the VPC an instance cannot exist
without). The provider firewall opens **22 only**, there is no DNS record,
and the supported client path is an SSH tunnel through the `~/.ssh/config`
alias the package writes. Compute is supplied by the pinned colors-compute
library; fixtures cover Vultr, DigitalOcean and AWS in both SSH key modes.
Provider selection, credentials, SSH key ownership and S3/R2 state are
library concerns. Legacy `<profile>/redis-infrastructure.tfstate` deployments
require explicit migration; unreadable or foreign state never becomes a
fresh deployment.

## Install

```sh
npx skills add getcolors/redis
cp .agents/skills/package-redis-green/green ./green
chmod +x green
```

The launcher in your project root is a **copy**, not a symlink. After
`npx skills update -p`, copy it again or the project keeps running the old pin.

## Use

```sh
./green build              # render .colors/<profile>/ — contacts nothing
./green create --dry-run   # walk the workflow, skip every side effect
./green create             # converge for real; the gates run inside it
./green rehearse           # fresh set, restore into a scratch instance, read back
./green describe           # the host's last monitor result, over SSH
./green delete             # guarded; see below
```

`build` and `--dry-run` work on a fresh checkout with an empty environment,
which makes them the safe way to check a `colors.yml` edit. Exit code 2 means
validation failure and lists every problem at once.

## Configuration

`colors.yml` is the only file you edit; see
`skills/package-redis-green/references/configuration.md` for every key.
Credentials are `COLORS_PAR_*` environment variables in a gitignored
`.envrc.private`:

| Variable | For |
|---|---|
| `COLORS_PAR_VULTR_API_KEY` | compute, with `provider-compute: vultr` |
| `COLORS_PAR_DO_TOKEN` | compute, with `provider-compute: digitalocean` |
| `COLORS_PAR_AWS_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` / `_SESSION_TOKEN` | optional with `provider-compute: aws`; overlaid onto `AWS_*` for OpenTofu and the AWS CLI, which otherwise use the ambient credential chain |
| `COLORS_PAR_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | OpenTofu state in R2 (operator machine only); an S3 state bucket uses the AWS chain |
| `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | the backup sets, the one pair that reaches the host; scope it to the backup bucket. Not needed with `redis-storage-managed: true`: the package mints that pair itself |

There is no DNS credential because there is no DNS. The Redis password is
generated on the server during convergence and is never operator-supplied.

### AWS with a managed backup bucket

On AWS the deployment can own its backup bucket. With
`redis-storage-managed: true` the package adds a `redis-storage` stage that
creates the bucket named by `redis-backup-r2-bucket`, blocks public access,
turns on AES256 encryption, and creates one IAM user scoped to that bucket
with one access key. The pair is a sensitive OpenTofu output: the package
reads it back and hands it to the converge and the rehearsal under the same
`COLORS_PAR_REDIS_BACKUP_R2_*` names an operator would export, so no backup
credential ever appears in `.envrc.private`, in `colors.yml`, or in generated
output. The managed bucket requires `provider-backend: s3` with
`s3-bucket-mode: managed`, and the region and endpoint keys must name the
same AWS region as the state bucket:

```yaml
provider-compute: aws
provider-backend: s3
s3-bucket: <profile>-state
s3-region: us-east-1
s3-bucket-mode: managed
redis-storage-managed: true
redis-backup-r2-bucket: <profile>-backup
redis-backup-r2-endpoint: https://s3.us-east-1.amazonaws.com
redis-backup-r2-region: us-east-1
```

Never export `COLORS_PAR_PROFILE`: the profile keys remote state, and
overlaying it points one deployment at another's.

## After a create

```sh
ssh -L 6379:127.0.0.1:6379 <profile>                       # the alias the package wrote
REDISCLI_AUTH=$(ssh <profile> sudo -n cat /etc/redis/secrets/password) redis-cli -p 6379
ssh <profile> redis-status                                 # monitor, sets, marker, container
```

## What convergence proves

On the host, every converge: a `SET`/`GET` round-trip; `noeviction`,
`appendonly yes`, `appendfsync everysec` and `aof_enabled:1` read back from
the running server; an unauthenticated `PING` answers `NOAUTH` and a wrong
password is refused; the kernel lists exactly `127.0.0.1` on the port; the
key survives `docker compose restart`; a first
backup set lands with its `.complete` marker. From the workstation: the SSH
tunnel round-trip with the generated password, the two refusals through it,
and the public address **not** answering on the Redis port.

## Backups and recovery

Every `redis-backup-oncalendar`, a set under `<profile>/redis/<stamp>/`:
`dump.rdb` streamed from the server over the replication protocol
(`redis-cli --rdb -`), verified by `redis-check-rdb` from the pinned image,
a manifest, and `.complete` last — after the uploaded bytes were read back
and hashed. `./green rehearse` restores the newest completed set into a
scratch container of the pinned image with the AOF off (a Redis 7 with AOF
on and no `appendonlydir/` ignores `dump.rdb`), reads `colors:smoke` back,
and writes `<profile>/.colors-recovery-verified`.

| Failure | Recovers from | RPO |
|---|---|---|
| a Redis restart | the append-only file (proven on every converge) | ≤ 1 s |
| the host | the newest completed set, copied into a fresh host's data volume | the backup interval |

## Delete

`delete` is protected by `compute-prevent-destroy: true`. Lift it for one
run with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false ./green delete`; never
edit the committed flag. The `~/.ssh/config` block is removed before the
destroy, the machine keypair after it, and the backup sets in an
operator-owned bucket not at all.

A managed backup bucket is destroyed after the machine, with its sets
(`force_destroy`), so the last backup timer run never fails against a
missing bucket; a managed S3 state bucket is finalized last of all, once
the library has proven it holds nothing but retired state. A repeat
`delete` exits 0: with the machine already gone it continues with the
storage stage and the finalizer, and with the state bucket already gone it
goes straight to the finalizer, which proves the absence.

## Development

```sh
cd green && bb test && bb golden && bb syntax
./scripts/launcher.sh
./scripts/parity.sh
```

Green is canonical. `scripts/parity.sh` renders every fixture and, once the
red and blue ports land, diffs their trees against green's byte for byte.
See `CLAUDE.md` for the traps this package has already paid for.
