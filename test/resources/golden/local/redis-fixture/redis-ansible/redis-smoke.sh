#!/usr/bin/env bash
# Server-side acceptance for the Redis host, run during convergence.
#
# Exit codes are not evidence; each gate asks the system what it actually has.
# Usage: redis-smoke <vpc-ip> <public-ip>
set -euo pipefail
vpc_ip="${1:?usage: redis-smoke <vpc-ip> <public-ip>}"
public_ip="${2:?usage: redis-smoke <vpc-ip> <public-ip>}"
port=6379
cd /opt/redis
pw=$(cat /etc/redis/secrets/password)
fail() { echo "redis-smoke: $*" >&2; exit 1; }
r() { docker compose exec -T -e REDISCLI_AUTH="$pw" redis redis-cli --no-auth-warning "$@" 2>&1 | tr -d '\r'; }
raw() { docker compose exec -T redis redis-cli --no-auth-warning "$@" 2>&1 | tr -d '\r'; }
cfg() { r CONFIG GET "$1" | tail -1; }
wait_pong() {
  local i; for i in $(seq 1 30); do [ "$(r PING)" = "PONG" ] && return 0; sleep 2; done
  fail "Redis did not answer PING within 60s"
}

# --- S1 the round-trip -------------------------------------------------------
stamp=$(date -u +%Y%m%dT%H%M%SZ)
[ "$(r SET colors:smoke "$stamp")" = "OK" ] || fail "SET colors:smoke answered '$(r SET colors:smoke "$stamp")', expected OK"
[ "$(r GET colors:smoke)" = "$stamp" ] || fail "GET colors:smoke answered '$(r GET colors:smoke)', expected $stamp"

# --- S2 the configuration Redis actually runs with ---------------------------
[ "$(cfg maxmemory-policy)" = "noeviction" ] || fail "maxmemory-policy is '$(cfg maxmemory-policy)', not noeviction"
[ "$(cfg appendonly)" = "yes" ] || fail "appendonly is '$(cfg appendonly)', not yes"
[ "$(cfg appendfsync)" = "everysec" ] || fail "appendfsync is '$(cfg appendfsync)', not everysec"
persistence=$(r INFO persistence)
grep -q '^aof_enabled:1' <<<"$persistence" || fail "aof_enabled is not 1"
server=$(r INFO server)
grep -qE '^redis_version:7\.2\.' <<<"$server" || fail "redis is not 7.2.x: $(sed -n 's/^redis_version://p' <<<"$server")"

# --- S3 the negatives ----------------------------------------------------------
anon=$(raw PING)
grep -q NOAUTH <<<"$anon" || fail "an unauthenticated PING answered '$anon' instead of NOAUTH"
wrong=$(docker compose exec -T -e REDISCLI_AUTH=not-the-password redis redis-cli --no-auth-warning PING 2>&1 | tr -d '\r')
grep -qE 'WRONGPASS|NOAUTH' <<<"$wrong" || fail "a wrong password answered '$wrong' instead of a refusal"
grep -q PONG <<<"$wrong" && fail "a wrong password was accepted"

# --- S4 the bind addresses -------------------------------------------------------
# Only loopback and the VPC address may listen on the port: never the public
# address, never a wildcard. Docker publishes exactly what the Compose file
# says, and this is where that claim is checked against the kernel.
listeners=$(ss -ltnH "sport = :$port" | awk '{print $4}' | sort -u)
expected=$(printf '127.0.0.1:%s\n%s:%s\n' "$port" "$vpc_ip" "$port" | sort -u)
[ "$listeners" = "$expected" ] || fail "port $port listeners are [$(tr '\n' ' ' <<<"$listeners")], expected [$(tr '\n' ' ' <<<"$expected")]"
if timeout 3 bash -c "exec 3<>/dev/tcp/$public_ip/$port" 2>/dev/null; then
  fail "the public address $public_ip answers on port $port"
fi

# --- S5 persistence across a restart --------------------------------------------
# The key written above must survive a graceful restart: that is the
# append-only file doing its job, proven rather than configured.
docker compose restart -t 30 redis >/dev/null 2>&1
wait_pong
[ "$(r GET colors:smoke)" = "$stamp" ] || fail "colors:smoke did not survive a restart (got '$(r GET colors:smoke)')"
persistence=$(r INFO persistence)
grep -q '^aof_last_write_status:ok' <<<"$persistence" || fail "aof_last_write_status is not ok after the restart"

echo "redis-smoke: round-trip, configuration, auth negatives, bind addresses and restart persistence all hold"
