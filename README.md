# redis

A green (Clojure/Babashka) Package Skill that provisions **one Redis 7.2
server on one Vultr instance or one DigitalOcean droplet**: one Docker
Compose service with `maxmemory-policy noeviction`, an append-only file on a
named volume, a password generated on the host, published on loopback and
nowhere else. RDB backup sets go to Cloudflare R2 with a completion
protocol, and `./green rehearse` proves one of them restores.

Nothing is published beyond loopback and no private network is created. The
provider firewall opens **22 only**, there is no DNS record, and the
supported client path is an SSH tunnel through the `~/.ssh/config` alias the
package writes. `provider-compute` picks `vultr` or `digitalocean` per the
workspace Compute Provider Standard: one `colors.yml` may carry both key
blocks, and switching on a profile that already holds a machine is refused
until that machine is deleted.

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
| `COLORS_PAR_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | OpenTofu state (operator machine only) |
| `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | the backup sets — the one pair that reaches the host; scope it to the backup bucket |

There is no DNS credential because there is no DNS. The Redis password is
generated on the server during convergence and is never operator-supplied.

Never export `COLORS_PAR_PROFILE`: the profile keys remote state, and
overlaying it points one deployment at another's.

## After a create

```sh
ssh -L 6379:127.0.0.1:6379 <profile>                       # the alias the package wrote
REDISCLI_AUTH=$(ssh <profile> cat /etc/redis/secrets/password) redis-cli -p 6379
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
destroy, the machine keypair after it, and the backup sets in R2 not at all.

## Development

```sh
bb test && bb golden && bb syntax
./scripts/launcher.sh
```

See `CLAUDE.md` for the traps this package has already paid for.
