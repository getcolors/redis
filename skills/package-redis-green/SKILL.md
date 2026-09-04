---
name: package-redis-green
description: Provision and manage one Redis 7.2 server on one Vultr instance or DigitalOcean droplet — published on loopback only, reached over an SSH tunnel, with an append-only file for persistence and RDB backup sets in Cloudflare R2 that a rehearsal verb restores and reads back — using OpenTofu and Ansible. Use when asked to deploy, converge, back up, rehearse recovery for, inspect or tear down a single-node Redis, or to work on a colors.yml for a redis deployment.
---

# Redis Package Skill (Green)

Provisions one machine on **Vultr or DigitalOcean** (`provider-compute`)
and converges **Redis 7.2** on it as one Docker Compose service:
`maxmemory-policy noeviction`, an append-only file (`appendfsync everysec`)
on a named volume, a password generated on the host, published on
`127.0.0.1` and nowhere else. No private network is created on either
provider. The provider firewall opens **22 only**; the client path is an SSH
tunnel through the `~/.ssh/config` alias the package writes. RDB snapshot
sets go to Cloudflare R2 with a completion protocol, and `rehearse` proves
one of them restores.

## Install the launcher

```sh
npx skills add getcolors/redis
cp .agents/skills/package-redis-green/green ./green
chmod +x green
```

The root `green` is a **copy** of the payload, not a symlink. `npx skills
update -p` rewrites the payload and leaves the copy alone, so copy it again
after every update or the project keeps running the old pin.

## Verbs

```sh
./green build              # render .colors/<profile>/ — no provider calls, no credentials
./green create --dry-run   # walk the workflow, skip every side effect
./green create             # converge for real; the gates run inside it
./green rehearse           # fresh backup set, restore it into a scratch instance, read it back
./green describe           # the host's last monitor result, over SSH
./green delete             # guarded by compute-prevent-destroy; removes nothing in R2
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
| `COLORS_PAR_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | OpenTofu state only; reaches no host |
| `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | the backup sets — the one pair that reaches the host; Object Read & Write on the backup bucket only |

The Redis password is generated on the host during convergence and read
over SSH: `ssh <profile> cat /etc/redis/secrets/password`. It is never
operator-supplied.

## What it builds

| Stage | What it manages |
|---|---|
| `redis-infrastructure` | one Vultr instance or one DigitalOcean droplet, a provider firewall opening 22 only, and in keygen mode the account SSH key named after the profile; the template is chosen by `provider-compute` and `params.provider` records which one produced the state |
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

`./green rehearse` takes a fresh set, restores the newest completed one into
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

`provider-compute` selects `vultr` (the default) or `digitalocean`; each has
its own `<provider>-*` keys and template, one `colors.yml` may carry both
blocks, and the unselected block is ignored. **Switching is a rebuild, never
an apply:** every provider shares one state key, so a real `create` or
`delete` on a profile whose state records a different provider is refused
with `state holds a <provider> machine; set provider-compute back to
<provider> and delete first`, before any credential is checked. A deployment
created before the package recorded a provider counts as Vultr. On a real
`delete`, a backend that cannot be read is an error, never an empty state.

## Reference

`references/configuration.md` documents every `colors.yml` key.
