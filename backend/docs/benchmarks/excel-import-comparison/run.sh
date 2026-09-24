#!/usr/bin/env bash
# Requires Java 17 and Homebrew MySQL 8.0; all data stays in a fresh temporary directory.
set -euo pipefail

comparison_source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
backend_dir=$(cd -- "$comparison_source_dir/../../.." && pwd)
mysql_bin=${MYSQL_BIN:-/opt/homebrew/opt/mysql@8.0/bin}
comparison_dir=$(mktemp -d /private/tmp/passtival-excel-comparison.XXXXXX)
mysql_started=false
cleanup() {
  if [ "$mysql_started" = true ]; then
    "$mysql_bin/mysqladmin" --no-defaults --socket="$comparison_dir/mysql.sock" --user=root shutdown
  fi
  echo "Experiment files: $comparison_dir"
}
trap cleanup EXIT

cd "$backend_dir"
./gradlew --offline -I docs/benchmarks/excel-import-baseline/classpath.init.gradle \
  -Dexcel.baseline.classpathFile="$comparison_dir/classpath.txt" exportExcelBaselineClasspath
comparison_classpath=$(cat "$comparison_dir/classpath.txt")
mkdir "$comparison_dir/classes"
"$JAVA_HOME/bin/javac" -cp "$comparison_classpath" -d "$comparison_dir/classes" \
  "$comparison_source_dir/ExcelImportComparison.java"

"$mysql_bin/mysqld" --no-defaults --initialize-insecure \
  --datadir="$comparison_dir/data" --log-error="$comparison_dir/mysql-init.log" \
  --innodb-buffer-pool-size=128M
"$mysql_bin/mysqld" --no-defaults --datadir="$comparison_dir/data" \
  --bind-address=127.0.0.1 --port=13318 --mysqlx=0 \
  --socket="$comparison_dir/mysql.sock" --pid-file="$comparison_dir/mysql.pid" \
  --log-error="$comparison_dir/mysql.log" --daemonize \
  --innodb-buffer-pool-size=128M --innodb-redo-log-capacity=64M \
  --innodb-flush-log-at-trx-commit=1 --performance-schema=ON --skip-log-bin
mysql_started=true
"$mysql_bin/mysql" --no-defaults --socket="$comparison_dir/mysql.sock" --user=root \
  --execute='CREATE DATABASE passtival_excel_comparison CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'

"$JAVA_HOME/bin/java" -Xms512m -Xmx1024m -cp "$comparison_dir/classes:$comparison_classpath" \
  ExcelImportComparison "$comparison_dir/results" | tee "$comparison_dir/run.log"
git rev-parse HEAD > "$comparison_dir/results/source-commit.txt"
shasum -a 256 "$comparison_source_dir/ExcelImportComparison.java" \
  "$comparison_source_dir/run.sh" > "$comparison_dir/results/harness-sha256.txt"
