# Configuration

Every key `colors.yml` may carry, and every credential the package reads.
Non-secret values only: credentials are `COLORS_PAR_*` environment variables.

## Identity and providers

| Key | Meaning |
|---|---|
| `profile` | Names the work directory, the OpenTofu state key (`<profile>/<stage>.tfstate`), the machine keypair, the `~/.ssh/config` alias, the Vultr resources, and the backup prefix (`<profile>/redis/`). Never overlay it from the environment. |
| `workdir` | Where rendered output goes. Conventionally `.colors`. |
| `provider-compute` | Must be `vultr`. |
| `provider-backend` | `local`, `s3` or `r2`. |
| `compute-prevent-destroy` | Keep `true` in committed desired state. |

There is deliberately no `provider-dns`: nothing in this package is reachable
by name. The firewall opens 22 only and the client path is an SSH tunnel.

## Redis

| Key | Meaning |
|---|---|
| `redis-image` | The server image. Must be pinned by digest (`tag@sha256:...`): Docker Hub republishes the `7.2` and `7.2.16` tags whenever the base image is rebuilt. |
| `redis-port` | The port published on `127.0.0.1` and on the VPC address (1–65535). Inside the container Redis listens on 6379 regardless. |

The server always runs with `maxmemory-policy noeviction`, `appendonly yes`,
`appendfsync everysec`, `protected-mode yes` and `requirepass` set to the
password generated at `/etc/redis/secrets/password` on the host. These are
not keys: the smoke gate asserts them on every converge.

## Backups in R2

| Key | Meaning |
|---|---|
| `redis-backup-r2-bucket` | The bucket the sets live in, under `<profile>/redis/<stamp>/`. Must already exist. |
| `redis-backup-r2-endpoint` | The account's S3 endpoint (`https://<account>.r2.cloudflarestorage.com` or the EU variant). |
| `redis-backup-r2-region` | `auto` for R2. |
| `redis-backup-oncalendar` | A systemd `OnCalendar` expression, e.g. `"*-*-* 00/6:00:00"` for every six hours. |
| `redis-backup-retention-days` | Completed sets older than this are pruned while a newer completed set exists; incomplete sets older than a day are pruned as debris. |
| `redis-backup-max-age-hours` | The monitor (and therefore `describe`) reports unhealthy when the newest completed set is older than this. |

A set is `dump.rdb`, `manifest.txt` (stamp, image, server version, key
count, sha256, bytes) and `.complete`, written last and only after the
uploaded snapshot was read back and hashed. `./green rehearse` writes
`<profile>/.colors-recovery-verified` beside the sets after a restore and
read-back succeed.

## Vultr

| Key | Meaning |
|---|---|
| `vultr-name` | Optional. The machine, its firewall group and its VPC are named after the profile (Compute Name Standard); set this only to override. |
| `vultr-region` | e.g. `ams`. |
| `vultr-plan` | e.g. `vc2-1c-2gb`. |
| `vultr-os-id` | Numeric OS id; 2284 is Ubuntu 24.04 LTS x64. |
| `vultr-vpc-subnet` | The VPC's IPv4 CIDR, e.g. `10.60.0.0/24`. Redis is published on the instance's address in it beside loopback. A Vultr firewall group filters the private interface too, so a future peer needs a `/32` rule as well as the binding. |
| `vultr-ssh-keys` | Optional. Absent selects keygen mode (the package owns `~/.ssh/<profile>`); an existing account key id selects opt-out mode. |
| `vultr-ssh-sources` | CIDRs allowed to reach 22 — the only open port. |

## State backend

| Key | Meaning |
|---|---|
| `r2-bucket`, `r2-endpoint` | Where `<profile>/<stage>.tfstate` lives when `provider-backend: r2`. |

## Credentials

| Variable | Used for |
|---|---|
| `COLORS_PAR_VULTR_API_KEY` | The VPC, the instance, the firewall, and the account SSH key. |
| `COLORS_PAR_R2_ACCESS_KEY_ID` / `COLORS_PAR_R2_SECRET_ACCESS_KEY` | The tofu state backend (operator machine only). |
| `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID` / `COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY` | The one pair that reaches the host: the backup sets. Use a token scoped to the backup bucket with Object Read & Write. |

Generated on the server, never operator-supplied: the Redis password
(`/etc/redis/secrets/password`, create-once).

## Recovery

A restart recovers from the append-only file; the smoke gate proves it on
every converge. Losing the host loses the writes since the newest completed
set: `delete` (guarded), `create` (a fresh host with a fresh password and an
empty store), then on the host `redis-restore-check <stamp>` to verify the
set, and copy the verified `dump.rdb` into the data volume with Redis stopped
and the AOF rebuilt from it (`redis-server --appendonly yes` writes a new
`appendonlydir/` from the loaded RDB on first start). The rehearsal verb
proves the set restores; the copy-in is the operator's deliberate step.
