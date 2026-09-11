# Configuration

Every key `colors.yml` may carry, and every credential the package reads.
Non-secret values only: credentials are `COLORS_PAR_*` environment variables.

## Identity and providers

| Key | Meaning |
|---|---|
| `profile` | Names the work directory, the OpenTofu state key (`<profile>/<stage>.tfstate`), the machine keypair, the `~/.ssh/config` alias, the provider resources, and the backup prefix (`<profile>/redis/`). Never overlay it from the environment. |
| `workdir` | Where rendered output goes. Conventionally `.colors`. |
| `provider-compute` | `vultr` (default), `digitalocean` or `aws`. Selects the library recipe and which `<provider>-*` keys are required; the other providers' keys are ignored. Changing it on a profile whose state holds a machine is refused on create and delete until that machine is deleted under its own provider. |
| `provider-backend` | `s3` or `r2` (default). |
| `compute-prevent-destroy` | Keep `true` in committed desired state. It also guards the managed backup bucket. |
| `redis-storage-managed` | `false` (default) or `true`. `true` makes the deployment own its backup bucket on AWS; see "The managed backup bucket" below. |

There is deliberately no `provider-dns`: nothing in this package is reachable
by name. The firewall opens 22 only and the client path is an SSH tunnel.

## Redis

| Key | Meaning |
|---|---|
| `redis-image` | The server image. Must be pinned by digest (`tag@sha256:...`): Docker Hub republishes the `7.2` and `7.2.16` tags whenever the base image is rebuilt. |
| `redis-port` | The port published on `127.0.0.1` and nowhere else (1–65535). Inside the container Redis listens on 6379 regardless. |

The server always runs with `maxmemory-policy noeviction`, `appendonly yes`,
`appendfsync everysec`, `protected-mode yes` and `requirepass` set to the
password generated at `/etc/redis/secrets/password` on the host. These are
not keys: the smoke gate asserts them on every converge.

## Backups

The key names keep their `r2` prefix on every provider: the bucket is any
S3-compatible one, Cloudflare R2 or native S3.

| Key | Meaning |
|---|---|
| `redis-backup-r2-bucket` | The bucket the sets live in, under `<profile>/redis/<stamp>/`. Must already exist unless `redis-storage-managed` is `true`, in which case the package creates it. |
| `redis-backup-r2-endpoint` | The bucket's S3 endpoint: `https://<account>.r2.cloudflarestorage.com` (or the EU variant) for R2, `https://s3.<region>.amazonaws.com` for AWS. An amazonaws.com endpoint switches rclone on the host to its AWS provider. |
| `redis-backup-r2-region` | `auto` for R2; the AWS region for S3. |
| `redis-backup-oncalendar` | A systemd `OnCalendar` expression, e.g. `"*-*-* 00/6:00:00"` for every six hours. |
| `redis-backup-retention-days` | Completed sets older than this are pruned while a newer completed set exists; incomplete sets older than a day are pruned as debris. |
| `redis-backup-max-age-hours` | The monitor (and therefore `describe`) reports unhealthy when the newest completed set is older than this. |

A set is `dump.rdb`, `manifest.txt` (stamp, image, server version, key
count, sha256, bytes) and `.complete`, written last and only after the
uploaded snapshot was read back and hashed. `./blue rehearse` writes
`<profile>/.colors-recovery-verified` beside the sets after a restore and
read-back succeed.

## The managed backup bucket (`redis-storage-managed: true`)

Only on AWS, and only with the managed S3 state backend. The package adds a
`redis-storage` stage owning the bucket named by `redis-backup-r2-bucket`:
public access blocked, AES256 server-side encryption, `force_destroy` so
`delete` removes the sets with the bucket, `prevent_destroy` following
`compute-prevent-destroy`, one IAM user `<profile>-storage-backup` with a
policy scoped to that bucket, and one access key as a sensitive output. The
package reads that pair back and hands it to the converge and the rehearsal
under `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID` and `_SECRET_ACCESS_KEY`,
so the operator supplies no backup credential. Validation refuses:

- `provider-backend` other than `s3`, or `s3-bucket-mode` other than `managed`
- `redis-backup-r2-region` different from `s3-region`
- `redis-backup-r2-endpoint` other than `https://s3.<s3-region>.amazonaws.com`
- a bucket name containing dots, or equal to `s3-bucket`

An existing bucket the stage does not own is refused on create; the package
never adopts a bucket. On delete the bucket goes after the machine and
before the state bucket.

## Vultr (`provider-compute: vultr`)

| Key | Meaning |
|---|---|
| `vultr-name` | Optional. The machine and its firewall group are named after the profile (Compute Name Standard); set this only to override. |
| `vultr-region` | e.g. `ams`. |
| `vultr-plan` | e.g. `vc2-1c-2gb`. |
| `vultr-os-id` | Numeric OS id; 2284 is Ubuntu 24.04 LTS x64. |
| `vultr-ssh-keys` | Optional. Absent selects keygen mode (the package owns `~/.ssh/<profile>`); an existing account key id selects opt-out mode. |
| `vultr-ssh-sources` | CIDRs allowed to reach 22 — the only open port. At least one entry, every entry a syntactically valid IPv4 or IPv6 CIDR; refused before any provider call otherwise. |

No VPC is attached. There is no `vultr-vpc-subnet` any more: the binding on
a private address was removed when the package adopted the Compute Provider
Standard, and a key left over from an older `colors.yml` is ignored.

## DigitalOcean (`provider-compute: digitalocean`)

| Key | Meaning |
|---|---|
| `digitalocean-name` | Optional. The droplet and its firewall are named after the profile; set this only to override. Must be hostname-like (lowercase letters, digits, dots, hyphens). |
| `digitalocean-region` | e.g. `ams3`. |
| `digitalocean-size` | e.g. `s-1vcpu-2gb`. |
| `digitalocean-image` | e.g. `ubuntu-24-04-x64`. |
| `digitalocean-ssh-keys` | Optional. Absent selects keygen mode; an existing account key id or fingerprint selects opt-out mode. |
| `digitalocean-ssh-sources` | CIDRs allowed to reach 22 — the only open port. Same validation as the Vultr key. |

The droplet uses the region's default VPC implicitly. No VPC lookup, explicit
VPC setting or private CIDR is required. The library owns no private network
for this public-only singleton.

## AWS (`provider-compute: aws`)

| Key | Meaning |
|---|---|
| `aws-region` | e.g. `us-east-1`. |
| `aws-availability-zone` | e.g. `us-east-1a`. |
| `aws-instance-type` | e.g. `t3.small`. |
| `aws-image-id` | An Ubuntu 24.04 AMI id for the region. |
| `aws-vpc-cidr`, `aws-subnet-cidr` | The VPC and public subnet the library creates for the instance; AWS has no VPC-less instance. |
| `aws-root-volume-size-gb` | The root volume size. |
| `aws-ssh-authorized-keys` | Optional. Absent selects keygen mode (the package owns `~/.ssh/<profile>`); the path of an operator `.pub` file selects opt-out mode. Build never opens the file; create reads it and registers it as the key pair named after the profile. |
| `redis-ssh-sources` or `ssh-sources` | CIDRs allowed to reach 22 on any provider. At least one IPv4 CIDR on AWS. |

Credentials come from the ambient AWS credential chain, optionally overlaid
from `COLORS_PAR_AWS_ACCESS_KEY_ID`, `COLORS_PAR_AWS_SECRET_ACCESS_KEY` and
`COLORS_PAR_AWS_SESSION_TOKEN`.

## State backend

`provider-backend` is `r2` or `s3`. S3 uses the ambient AWS credential chain
and library settings `s3-bucket` and `s3-region`; R2 uses the pair below.
The backup bucket settings remain independent of the state backend unless
storage is managed, when both must sit in the same AWS region.

| Key | Meaning |
|---|---|
| `r2-bucket`, `r2-endpoint` | Where `<profile>/compute/shared.tfstate`, node state and the ownership journal live for R2. |
| `s3-bucket`, `s3-region` | The same for S3. |
| `s3-bucket-mode` | `external` (default): the state bucket exists and is never touched. `managed`: the library creates it on the first create and finalizes it at the end of delete once nothing but retired state remains. Required for `redis-storage-managed: true`. |

## Credentials

| Variable | Used for |
|---|---|
| `COLORS_PAR_VULTR_API_KEY` | With `provider-compute: vultr`: the instance, the firewall group, and the account SSH key. |
| `COLORS_PAR_DO_TOKEN` | With `provider-compute: digitalocean`: the droplet, the firewall, and the account SSH key. |
| `COLORS_PAR_AWS_ACCESS_KEY_ID` / `COLORS_PAR_AWS_SECRET_ACCESS_KEY` / `COLORS_PAR_AWS_SESSION_TOKEN` | Optional with `provider-compute: aws`; overlaid onto `AWS_*` for OpenTofu, the AWS CLI and the library. |
| `COLORS_PAR_R2_ACCESS_KEY_ID` / `COLORS_PAR_R2_SECRET_ACCESS_KEY` | The tofu state backend in R2 (operator machine only). |
| `COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID` / `COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY` | The one pair that reaches the host: the backup sets. Use a token scoped to the backup bucket with Object Read & Write. Not read when `redis-storage-managed` is `true`. |

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
