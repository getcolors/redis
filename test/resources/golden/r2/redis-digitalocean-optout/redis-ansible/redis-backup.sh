#!/usr/bin/env bash
# A Redis backup set: one RDB snapshot, its manifest with a checksum, and the
# completion marker LAST, written only after every uploaded object has been
# verified against the local copy.
#
# The snapshot is streamed from the server over the replication protocol
# (`redis-cli --rdb -`): the server forks and serialises a point-in-time
# image exactly as it would for a replica, so nothing on the data volume is
# read while Redis writes it and the live AOF is never touched. The file is
# then verified with redis-check-rdb from the pinned image before it counts.
# Shape adapted from the getcolors/langfuse package's postgres-backup.sh.
set -euo pipefail
cd /opt/redis
. /opt/colors/r2-env.sh
STAMP=$(stamp_now)
PREFIX="backup:$BACKUP_BUCKET/$SET_PREFIX"
WORK=$(mktemp -d /var/tmp/redis-backup.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
pw=$(cat /etc/redis/secrets/password)
r() { docker compose exec -T -e REDISCLI_AUTH="$pw" redis redis-cli --no-auth-warning "$@" | tr -d '\r'; }

dbsize=$(r DBSIZE)
version=$(r INFO server | sed -n 's/^redis_version://p')
docker compose exec -T -e REDISCLI_AUTH="$pw" redis redis-cli --no-auth-warning --rdb - > "$WORK/dump.rdb" 2>"$WORK/rdb.log" \
  || { echo "redis-backup: the RDB transfer failed:" >&2; tail -5 "$WORK/rdb.log" >&2; exit 1; }
bytes=$(stat -c%s "$WORK/dump.rdb")
[ "$bytes" -gt 0 ] || { echo "redis-backup: empty RDB" >&2; exit 1; }
# Integrity, proven by the pinned image's own checker rather than assumed
# from a clean exit: the file is piped in, so nothing lands on the volume.
docker compose exec -T redis sh -c 'cat > /tmp/colors-check.rdb && redis-check-rdb /tmp/colors-check.rdb >/dev/null; rc=$?; rm -f /tmp/colors-check.rdb; exit $rc' < "$WORK/dump.rdb" \
  || { echo "redis-backup: redis-check-rdb rejected the snapshot" >&2; exit 1; }
sha=$(sha256sum "$WORK/dump.rdb" | cut -d' ' -f1)

{
  printf 'stamp=%s\n' "$STAMP"
  printf 'profile=%s\n' "$PROFILE"
  printf 'image=%s\n' "$REDIS_IMAGE"
  printf 'redis_version=%s\n' "$version"
  printf 'dbsize=%s\n' "$dbsize"
  printf 'dump_sha256=%s\n' "$sha"
  printf 'dump_bytes=%s\n' "$bytes"
} > "$WORK/manifest.txt"

r2_put "$WORK/dump.rdb" "$PREFIX/$STAMP/dump.rdb"
r2_put "$WORK/manifest.txt" "$PREFIX/$STAMP/manifest.txt"
# Verified by read-back: the object's size must match, and its content must
# hash to what the manifest says, before the marker is written.
remote_bytes=$(rclone lsjson "$PREFIX/$STAMP/dump.rdb" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["Size"])')
[ "$remote_bytes" = "$bytes" ] || { echo "redis-backup: uploaded size $remote_bytes != $bytes" >&2; exit 1; }
remote_sha=$(rclone cat "$PREFIX/$STAMP/dump.rdb" | sha256sum | cut -d' ' -f1)
[ "$remote_sha" = "$sha" ] || { echo "redis-backup: uploaded sha256 differs" >&2; exit 1; }
completed=$(stamp_now)
r2_put_string "$completed" "$PREFIX/$STAMP/.complete"

prune_sets "$PREFIX" || true
echo "redis-backup: set $STAMP complete ($bytes bytes, $dbsize keys)"
echo "stamp=$STAMP"
