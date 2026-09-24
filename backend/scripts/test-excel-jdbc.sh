#!/usr/bin/env bash
set -euo pipefail

# Homebrew defaults; override EXCEL_MYSQL_BIN / JAVA_HOME when installed elsewhere.
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
backend_dir=$(cd -- "$script_dir/.." && pwd)
mysql_bin=${EXCEL_MYSQL_BIN:-/opt/homebrew/opt/mysql@8.0/bin}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
# Keep the UNIX socket path below its length limit (macOS TMPDIR can be long).
test_dir=$(mktemp -d /tmp/passtival-excel-jdbc.XXXXXX)
mysql_started=false

cleanup() {
  local exit_status=$?
  if "$mysql_started"; then
    if ! "$mysql_bin/mysqladmin" --no-defaults --socket="$test_dir/mysql.sock" --user=root shutdown; then
      printf 'Failed to stop the temporary MySQL instance at %s\n' "$test_dir" >&2
      exit_status=1
    fi
  fi
  printf 'MySQL logs retained at %s\n' "$test_dir"
  exit "$exit_status"
}
trap cleanup EXIT

# A fresh datadir and private socket prevent access to any existing MySQL instance.
# If port 13320 is occupied, mysqld fails before the test database is created.
"$mysql_bin/mysqld" --no-defaults --initialize-insecure \
  --datadir="$test_dir/data" --log-error="$test_dir/mysql-init.log" \
  --innodb-buffer-pool-size=128M
"$mysql_bin/mysqld" --no-defaults --datadir="$test_dir/data" \
  --bind-address=127.0.0.1 --port=13320 --mysqlx=0 \
  --socket="$test_dir/mysql.sock" --pid-file="$test_dir/mysql.pid" \
  --log-error="$test_dir/mysql.log" --daemonize \
  --innodb-buffer-pool-size=128M --innodb-redo-log-capacity=64M \
  --performance-schema=ON --skip-log-bin
mysql_started=true
"$mysql_bin/mysql" --no-defaults --socket="$test_dir/mysql.sock" --user=root \
  --execute='CREATE DATABASE passtival_excel_refactor_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'

export EXCEL_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:13320/passtival_excel_refactor_test
cd -- "$backend_dir"
./gradlew --offline --no-daemon test --rerun-tasks \
  --tests '*AuthenticationKeyTestControllerTests' \
  --tests '*AuthenticationKeyImportMySqlTests'
