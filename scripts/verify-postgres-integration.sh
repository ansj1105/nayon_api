#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cloud_dir="${NAYON_CLOUD_DIR:-/home/ubuntu/work/nayon_cloud}"
pg_bin="${PG_BIN:-/home/ubuntu/work/.tools/postgresql/16.3/bin}"
gradle_home="${NAYON_GRADLE_HOME:-/home/ubuntu/work/.gradle-cache}"
port="${NAYON_TEST_DB_PORT:-55432}"
test_pattern="${NAYON_TEST_PATTERN:-*PostgresTest}"

work_dir="$(mktemp -d /tmp/nayon-api-pg.XXXXXX)"
data_dir="$work_dir/data"

cleanup() {
  if test -f "$data_dir/postmaster.pid"; then
    "$pg_bin/pg_ctl" -D "$data_dir" -m fast stop >/dev/null
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

"$pg_bin/initdb" -D "$data_dir" -A trust -U postgres >/dev/null
"$pg_bin/pg_ctl" -D "$data_dir" \
  -o "-F -h 127.0.0.1 -p $port" -w start >/dev/null

while IFS= read -r migration; do
  "$pg_bin/psql" -h 127.0.0.1 -p "$port" -U postgres -d postgres \
    -v ON_ERROR_STOP=1 -f "$migration" >/dev/null
done < <(find "$cloud_dir/db/migration" -maxdepth 1 -name 'V*.sql' -print | sort -V)

cd "$repo_dir"
env \
  E2E_DB=1 \
  DB_URL="jdbc:postgresql://127.0.0.1:$port/postgres" \
  DB_USERNAME=postgres \
  DB_PASSWORD=test-only-password \
  GRADLE_USER_HOME="$gradle_home" \
  ./gradlew cleanTest test --tests "$test_pattern" --console=plain --no-daemon
