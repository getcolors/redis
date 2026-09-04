#!/usr/bin/env bash
# Restore a completed backup set into a SCRATCH instance of the pinned image
# and read the smoke key back from it. A checksum proves the set is intact;
# the boot proves it is a recovery. The live service is never touched.
#
# The scratch instance runs with the append-only file OFF, deliberately: a
# Redis 7 started with `appendonly yes` and no appendonlydir/ beside the RDB
# ignores dump.rdb and starts EMPTY, which would make an intact set read as
# a lost one. It publishes no port and carries no password -- it is reached
# only through docker exec and removed on exit, whatever happened.
#
# Usage: redis-restore-check [<stamp>]   (default: the newest completed set)
set -euo pipefail
cd /opt/redis
. /opt/colors/r2-env.sh
PREFIX="backup:$BACKUP_BUCKET/$SET_PREFIX"
SET="${1:-$(newest_completed_set "$PREFIX")}"
[ -n "$SET" ] || { echo "redis-restore-check: no completed backup set under $SET_PREFIX/" >&2; exit 1; }
[ -n "$(set_complete "$PREFIX" "$SET")" ] || { echo "redis-restore-check: set $SET is not complete" >&2; exit 1; }
NAME=redis-restore-check
WORK=$(mktemp -d /var/tmp/redis-restore.XXXXXX)
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

rclone copyto "$PREFIX/$SET/dump.rdb" "$WORK/dump.rdb"
rclone copyto "$PREFIX/$SET/manifest.txt" "$WORK/manifest.txt"
. "$WORK/manifest.txt"
[ "$(sha256sum "$WORK/dump.rdb" | cut -d' ' -f1)" = "$dump_sha256" ] \
  || { echo "redis-restore-check: dump checksum mismatch for set $SET" >&2; exit 1; }
# Readable by the image's redis user (uid 999); the entrypoint chowns what it
# finds in /data, but the directory itself must be traversable first.
chmod 0755 "$WORK"; chmod 0644 "$WORK/dump.rdb"

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -v "$WORK":/data "$image" \
  redis-server --appendonly no --save "" --dir /data --dbfilename dump.rdb >/dev/null
s() { docker exec "$NAME" redis-cli --no-auth-warning "$@" 2>/dev/null | tr -d '\r'; }
for _ in $(seq 1 30); do
  [ "$(s PING)" = "PONG" ] && [ "$(s INFO persistence | sed -n 's/^loading://p')" = "0" ] && break
  sleep 1
done
[ "$(s PING)" = "PONG" ] || { echo "redis-restore-check: the scratch instance never answered PING" >&2; docker logs "$NAME" 2>&1 | tail -5 >&2; exit 1; }
keys=$(s DBSIZE)
[ "${keys:-0}" -ge 1 ] || { echo "redis-restore-check: the restored set holds no keys" >&2; exit 1; }
smoke=$(s GET colors:smoke)
[ -n "$smoke" ] || { echo "redis-restore-check: colors:smoke is absent from the restored data; this is not this deployment's data" >&2; exit 1; }
[ "$keys" = "$dbsize" ] || echo "redis-restore-check: WARN restored $keys keys, manifest recorded $dbsize (writes between DBSIZE and the snapshot)"
echo "redis-restore-check: restored $SET into a scratch $image ($keys keys, colors:smoke=$smoke)"
echo "set=$SET"
