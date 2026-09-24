#!/usr/bin/env bash
# Local experiment; starts and stops only its own disposable MySQL instance.
set -euo pipefail

batch_source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
backend_dir=$(cd -- "$batch_source_dir/../../.." && pwd)
mysql_bin=${MYSQL_BIN:-/opt/homebrew/opt/mysql@8.0/bin}
batch_candidates=${BATCH_CANDIDATES:-100,500,1000,2000,5000}
batch_dir=$(mktemp -d /private/tmp/passtival-excel-batch.XXXXXX)
mysql_started=false
cleanup() {
  if [ "$mysql_started" = true ]; then
    "$mysql_bin/mysqladmin" --no-defaults --socket="$batch_dir/mysql.sock" --user=root shutdown
  fi
  echo "Experiment files: $batch_dir"
}
trap cleanup EXIT

cd "$backend_dir"
./gradlew --offline -I docs/benchmarks/excel-import-baseline/classpath.init.gradle \
  -Dexcel.baseline.classpathFile="$batch_dir/classpath.txt" exportExcelBaselineClasspath
batch_classpath=$(cat "$batch_dir/classpath.txt")
mkdir "$batch_dir/classes" "$batch_dir/results"
"$JAVA_HOME/bin/javac" -cp "$batch_classpath" -d "$batch_dir/classes" \
  "$batch_source_dir/ExcelBatchSizeBenchmark.java"
"$mysql_bin/mysqld" --no-defaults --initialize-insecure \
  --datadir="$batch_dir/data" --log-error="$batch_dir/mysql-init.log" --innodb-buffer-pool-size=128M
"$mysql_bin/mysqld" --no-defaults --datadir="$batch_dir/data" \
  --bind-address=127.0.0.1 --port=13319 --mysqlx=0 \
  --socket="$batch_dir/mysql.sock" --pid-file="$batch_dir/mysql.pid" \
  --log-error="$batch_dir/mysql.log" --daemonize \
  --innodb-buffer-pool-size=128M --innodb-redo-log-capacity=64M \
  --innodb-flush-log-at-trx-commit=1 --performance-schema=ON --skip-log-bin
mysql_started=true
"$mysql_bin/mysql" --no-defaults --socket="$batch_dir/mysql.sock" --user=root \
  --execute='CREATE DATABASE passtival_excel_batch_size CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'
for batch_fork in 1 2 3; do
  "$JAVA_HOME/bin/java" -Xms512m -Xmx1024m -Dbatch.sizes="$batch_candidates" \
    -cp "$batch_dir/classes:$batch_classpath" ExcelBatchSizeBenchmark \
    "$batch_dir/results/fork-$batch_fork" "$batch_fork" | tee "$batch_dir/fork-$batch_fork.log"
done
git rev-parse HEAD > "$batch_dir/results/source-commit.txt"
shasum -a 256 "$batch_source_dir/ExcelBatchSizeBenchmark.java" \
  "$batch_source_dir/run.sh" > "$batch_dir/results/harness-sha256.txt"
