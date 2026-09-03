# Shared runtime preamble for the R2 scripts. Sourced, never executed.
#
# One rclone remote, `backup`, from the credential file the host holds at
# /etc/colors/backup-r2.env. Adapted from the getcolors/langfuse package's
# r2-env.sh, whose flags are traps already paid for by the getcolors/neon
# build and recorded in the neon-single-node Context Skill: without
# no_check_bucket every upload is preceded by a CreateBucket the token denies
# (AccessDenied on what looks like a plain write); without no_head the
# post-upload verification trips a 501; and `rclone rcat` is a 501 outright,
# so uploads are always a copyto of a file with a known size.
# No config file: rclone otherwise prints a NOTICE about the missing one on
# every invocation, which reads like a fault in gate output.
export RCLONE_CONFIG=/dev/null
export RCLONE_CONFIG_BACKUP_TYPE=s3 RCLONE_CONFIG_BACKUP_PROVIDER=Cloudflare
export RCLONE_CONFIG_BACKUP_ENDPOINT="https://fixture.r2.cloudflarestorage.com" RCLONE_CONFIG_BACKUP_REGION="auto"
export RCLONE_CONFIG_BACKUP_NO_CHECK_BUCKET=true RCLONE_CONFIG_BACKUP_NO_HEAD=true
if [ -f /etc/colors/backup-r2.env ]; then
  RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID=$(sed -n 's/^BACKUP_R2_ACCESS_KEY_ID=//p' /etc/colors/backup-r2.env)
  RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY=$(sed -n 's/^BACKUP_R2_SECRET_ACCESS_KEY=//p' /etc/colors/backup-r2.env)
  export RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY
fi

PROFILE="redis-optout-fixture"
BACKUP_BUCKET="redis-backup-fixture"
SET_PREFIX="redis-optout-fixture/redis"
RETENTION_DAYS=7
MAX_AGE_HOURS=8
REDIS_IMAGE="docker.io/library/redis:7.2.16@sha256:74566c6910d13ae61e7ce73ebd3127438a1fe805b309b097c323142719ec8a5b"

stamp_now() { date -u +%Y%m%dT%H%M%SZ; }

r2_put() { # $1 local file, $2 remote path (remote:bucket/key)
  rclone copyto "$1" "$2"
}
r2_put_string() { # $1 content, $2 remote path -- verified by read-back
  local t back; t=$(mktemp); printf '%s' "$1" > "$t"
  rclone copyto "$t" "$2"; rm -f "$t"
  back=$(rclone cat "$2" 2>/dev/null || true)
  [ "$back" = "$1" ] || { echo "r2: $2 read back as '$back', expected '$1'" >&2; return 1; }
}
r2_cat() { rclone cat "$1" 2>/dev/null; }

# Backup sets live under <remote-prefix>/<stamp>/ and count only when their
# `.complete` marker is non-empty: emptiness is absence, because a 0-byte
# object satisfies an existence check forever while proving nothing.
list_sets() { # $1 remote prefix (remote:bucket/path) -> stamps, oldest first
  rclone lsf "$1/" --dirs-only 2>/dev/null | grep -E '^[0-9]{8}T[0-9]{6}Z/$' | tr -d '/' | sort
}
set_complete() { # $1 remote prefix, $2 stamp -> prints the marker content
  local c; c=$(r2_cat "$1/$2/.complete" || true)
  [ -n "$c" ] && printf '%s' "$c"
}
completed_sets() { # $1 remote prefix -> stamps of completed sets, oldest first
  local s; for s in $(list_sets "$1"); do [ -n "$(set_complete "$1" "$s")" ] && echo "$s"; done; return 0
}
newest_completed_set() { completed_sets "$1" | tail -1; }

# Retention: completed sets older than RETENTION_DAYS go only while a newer
# completed set exists; incomplete sets older than a day are debris.
prune_sets() { # $1 remote prefix
  local prefix="$1" cutoff debris newest s
  cutoff=$(date -u -d "-${RETENTION_DAYS} days" +%Y%m%dT%H%M%SZ)
  debris=$(date -u -d "-1 day" +%Y%m%dT%H%M%SZ)
  newest=$(newest_completed_set "$prefix")
  for s in $(list_sets "$prefix"); do
    if [ -n "$(set_complete "$prefix" "$s")" ]; then
      if [ "$s" \< "$cutoff" ] && [ -n "$newest" ] && [ "$s" != "$newest" ]; then
        rclone purge "$prefix/$s" && echo "pruned completed set $s"
      fi
    elif [ "$s" \< "$debris" ]; then
      rclone purge "$prefix/$s" && echo "pruned incomplete set $s"
    fi
  done
}

# Age in hours of the newest completed set, or 999999 when none exists.
newest_set_age_hours() { # $1 remote prefix
  local s; s=$(newest_completed_set "$1")
  [ -n "$s" ] || { echo 999999; return; }
  local then now
  then=$(date -u -d "$(echo "$s" | sed 's/T/ /; s/Z//; s/\(....\)\(..\)\(..\) \(..\)\(..\)\(..\)/\1-\2-\3 \4:\5:\6/')" +%s)
  now=$(date -u +%s)
  echo $(( (now - then) / 3600 ))
}

write_monitor() { # $1 file, $2 healthy (0/1), $3... problems
  local f="$1" ok="$2"; shift 2
  python3 - "$f" "$ok" "$@" <<'PY'
import json, sys, datetime
f, ok, problems = sys.argv[1], sys.argv[2] == "0", sys.argv[3:]
json.dump({"checked": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "healthy": ok, "problems": problems}, open(f, "w"))
PY
}
