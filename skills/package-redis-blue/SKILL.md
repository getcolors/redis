---
name: package-redis-blue
description: Provision and manage one Redis 7.2 server on one Vultr instance, DigitalOcean droplet or AWS EC2 instance, published on loopback only, reached over an SSH tunnel, with an append-only file for persistence and RDB backup sets in Cloudflare R2 or a deployment-owned S3 bucket that a rehearsal verb restores and reads back, using OpenTofu and Ansible. Use when asked to deploy, converge, back up, rehearse recovery for, inspect or tear down a single-node Redis, or to work on a colors.yml for a redis deployment.
---

# Redis Package Skill (Blue)

Provisions one machine on **Vultr, DigitalOcean or AWS** (`provider-compute`)
and converges **Redis 7.2** on it as one Docker Compose service:
`maxmemory-policy noeviction`, an append-only file (`appendfsync everysec`)
on a named volume, a password generated on the host, published on
`127.0.0.1` and nowhere else. The package creates no private network of its
own; on AWS the library owns the VPC an instance cannot exist without. The
provider firewall opens **22 only**; the client path is an SSH tunnel
through the `~/.ssh/config` alias the package writes. RDB snapshot sets go
to an S3-compatible bucket (Cloudflare R2, or on AWS a bucket the
deployment owns) with a completion protocol, and `rehearse` proves one of
them restores.

## Install the launcher

```sh
npx skills add getcolors/redis
cp .agents/skills/package-redis-blue/blue ./blue
chmod +x blue
```

The root `blue` is a **copy** of the payload, not a symlink. `npx skills
update -p` rewrites the payload and leaves the copy alone, so copy it again
after every update or the project keeps running the old pin.

## Verbs

```sh
./blue build              # render .colors/<profile>/ — no provider calls, no credentials
./blue create --dry-run   # walk the workflow, skip every side effect
./blue create             # converge for real; the gates run inside it
./blue rehearse           # fresh backup set, restore it into a scratch instance, read it back
./blue describe           # the host's last monitor result, over SSH
./blue delete             # guarded by compute-prevent-destroy; a managed bucket goes after the machine, an operator-owned one is untouched
```

`build` and `--dry-run` work on a fresh checkout with an empty environment.
Exit code 2 means validation failure and lists every problem at once. The
launcher walks up from the working directory to find `colors.yml`.

## Rules that are not negotiable

- **`colors.yml` is the only file you edit.** Kebab-case keys, non-secret
  values only.
- **Credentials are `COLORS_PAR_*` environment variables** in a gitignored
  `.envrc.private`. Never in `colors.yml`, generated output, or documentation.
- **Never export `COLORS_PAR_PROFILE`.** The profile keys remote state; the
  package refuses to run when it is set, and that refusal is the guard working.
- **`.colors/` is generated output.** Never edit it, never read it as source,
  never commit it.
- **`delete` is guarded** by `compute-prevent-destroy: true`, liftable only
  with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for one run. Never edit the
  committed flag. Never run a real `create`, `rehearse` or `delete` against a
  live deployment without explicit authorization.

## Credentials

Only the selected provider's credential is required.

| Variable | For |
|---|---|
| `COLORS_PAR_VULTR_API_KEY` | `provider-compute: vultr` — the firewall group, the instance, the account SSH key |
| `COLORS_PAR_DO_TOKEN` | `provider-compute: digitalocean` — the firewall, the droplet, the account SSH key |
| `COLORS_PAR_AWS_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` / `_SESSION_TOKEN` | `provider-compute: aws`, optional: overlaid onto `AWS_*` for OpenTofu and the AWS CLI. Absent, the ambient AWS credential chain is used |
| `COLORS_PAR_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | OpenTofu state in R2 only; reaches no host. An S3 state bucket uses the AWS chain |
| `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | the backup sets, the one pair that reaches the host; Object Read & Write on the backup bucket only. Not required with `redis-storage-managed: true`: the package creates that pair and hands it to Ansible itself |

The Redis password is generated on the host during convergence and read
over SSH: `ssh <profile> cat /etc/redis/secrets/password`. It is never
operator-supplied.

## What it builds

| Stage | What it manages |
|---|---|
| `redis-infrastructure` | one Vultr instance, one DigitalOcean droplet or one AWS EC2 instance, a provider firewall opening 22 only, and in keygen mode the account SSH key named after the profile; the shared library renders separate shared/node documents and records provider ownership |
| `redis-storage` | only with `redis-storage-managed: true` on AWS: the backup bucket named by `redis-backup-r2-bucket` with public access blocked and AES256 encryption, one IAM user `<profile>-storage-backup` scoped to that bucket, and one access key as a sensitive output |
| `redis-ssh-config` | the `~/.ssh/config` block, so `ssh <profile>` works |
| `redis-ansible` | Docker Compose with the pinned image, the generated password, the smoke gate, the backup and monitor timers, and the first backup set |
| acceptance | the operator path from the workstation: an SSH tunnel through the generated alias, a `SET`/`GET` round-trip with the generated password, an unauthenticated `PING` refused, a wrong password refused, and the public address **not** answering on the Redis port |

## What convergence proves

Gates that run on every converge and fail it if they fail:

- `SET`/`GET` round-trip with the generated password
- `maxmemory-policy noeviction`, `appendonly yes`, `appendfsync everysec`,
  `aof_enabled:1`, a 7.2 server — read back from the running server, not
  from the file
- an unauthenticated `PING` answers `NOAUTH`; a wrong password is refused
- the kernel lists exactly one listener on the port, `127.0.0.1`; the
  public address does not answer
- the key written above survives `docker compose restart` and
  `aof_last_write_status:ok` holds afterwards
- a first backup set lands in R2 with its `.complete` marker

## Backups and recovery

Every `redis-backup-oncalendar` a set is written under
`<profile>/redis/<stamp>/` in the backup bucket: `dump.rdb` streamed from
the server over the replication protocol (`redis-cli --rdb -`, a
point-in-time fork that never reads the data volume), verified by
`redis-check-rdb` from the pinned image, a manifest with its sha256 and key
count, and the `.complete` marker last, after the uploaded bytes were read
back and hashed. Sets older than `redis-backup-retention-days` are pruned
while a newer completed set exists.

### The managed backup bucket on AWS

With `provider-compute: aws`, `redis-storage-managed: true` makes the
deployment own its backup bucket. The `redis-storage` stage creates the
bucket named by `redis-backup-r2-bucket`, blocks public access, enables
AES256 encryption, and creates one IAM user and access key scoped to that
bucket. The package reads the pair back from the stage output and hands it
to the converge and the rehearsal as `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID`
and `_SECRET_ACCESS_KEY`, so no backup credential is ever operator-supplied
and none appears in generated output. The managed bucket requires
`provider-backend: s3` with `s3-bucket-mode: managed`, `redis-backup-r2-region`
equal to `s3-region`, `redis-backup-r2-endpoint` equal to
`https://s3.<region>.amazonaws.com`, and a bucket name without dots.

`./blue rehearse` takes a fresh set, restores the newest completed one into
a scratch container of the pinned image (`--appendonly no`, so the RDB is
what loads — a Redis 7 started with AOF on and no `appendonlydir/` ignores
`dump.rdb` and starts empty), reads `colors:smoke` back from it, and only
then writes `<profile>/.colors-recovery-verified` beside the sets.

| Failure | Recovers from | RPO |
|---|---|---|
| a Redis restart | the append-only file | ≤ 1 s of acknowledged writes |
| the host | the newest completed set: `delete`, `create`, then `redis-restore-check <stamp>` and copy the data in | the backup interval |

## Connecting

```sh
ssh -L 6379:127.0.0.1:6379 <profile>
REDISCLI_AUTH=$(ssh <profile> cat /etc/redis/secrets/password) redis-cli -p 6379
```

`ssh <profile> redis-status` prints the monitor result, the completed sets,
the recovery marker and the container state.

## Compute providers

The pinned colors-compute library selects providers and validates their
settings. This package passes a singleton topology and an SSH-only policy;
it has no compute provider registry or templates. New provider support arrives
through a library version bump. Vultr remains the default, and fixtures cover
Vultr, DigitalOcean and AWS in both SSH modes. On AWS the library owns a VPC,
a subnet and a security group, and registers the SSH key pair from a public
key in both modes: keygen registers the key the package generates, opt-out
registers the operator's `aws-ssh-authorized-keys` file.

State is remote in S3 or R2, split into shared and node objects under
`<profile>/compute/`. The ownership journal prevents conflicting operations.
Legacy `<profile>/redis-infrastructure.tfstate` deployments require explicit
migration. Unreadable state and provider mismatches are errors on every real
operation. Delete, rehearse and describe read the recorded normalized node;
only build output uses documentation addresses.

## Reference

`references/configuration.md` documents every `colors.yml` key.
