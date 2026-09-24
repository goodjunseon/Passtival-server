"""Validate recorded measurements and summarize medians (Python standard library only)."""

import csv
import statistics
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_csv(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def main(directory):
    variants = ("JPA", "JDBC_SINGLE", "JDBC_BATCH", "JDBC_BATCH_REWRITE")
    sizes = (1000, 10000, 50000, 100000)
    records = read_csv(directory / "comparison.csv")
    require(len(records) == 88, "Expected 8 warmups and 80 measurements")
    for variant in variants:
        warmups = [row for row in records if row["variant"] == variant and row["phase"] == "warmup"]
        require(len(warmups) == 2 and {int(row["round"]) for row in warmups} == {1, 2}
                and all(int(row["rows"]) == 10000 for row in warmups), "Missing warmup")
    for row in records:
        count = int(row["rows"])
        variant = row["variant"]
        require(variant in variants and row["phase"] in ("warmup", "measured"), "Unexpected group")
        require(count in sizes, "Unexpected size")
        require(int(row["stored_rows"]) == int(row["distinct_keys"]) == count, "Wrong stored count")
        require(int(row["transaction_commits"]) == 1 and int(row["transaction_rollbacks"]) == 0,
                "Wrong transaction count")
        batched = variant in ("JDBC_BATCH", "JDBC_BATCH_REWRITE")
        require(int(row["jdbc_insert_execute_calls"]) == (0 if batched else count), "Wrong single executions")
        require(int(row["jdbc_insert_prepare_calls"]) == (1 if batched else count), "Wrong prepares")
        require(int(row["jdbc_execute_batch_calls"]) == (count // 1000 if batched else 0), "Wrong batches")
        require(int(row["jdbc_add_batch_calls"]) == (count if batched else 0), "Wrong addBatch")
        require(int(row["mysql_insert_statements"]) ==
                (count // 1000 if variant == "JDBC_BATCH_REWRITE" else count), "Wrong server INSERTs")

    summaries = []
    for size in sizes:
        groups = {variant: [row for row in records if row["phase"] == "measured"
                            and int(row["rows"]) == size and row["variant"] == variant]
                  for variant in variants}
        baseline_total = statistics.median(float(row["total_commit_ms"]) for row in groups["JPA"])
        baseline_save = statistics.median(float(row["save_flush_ms"]) for row in groups["JPA"])
        for variant, group in groups.items():
            require(len(group) == 5 and {int(row["round"]) for row in group} == set(range(1, 6)),
                    f"Expected five distinct rounds: {size}/{variant}")
            totals = [float(row["total_commit_ms"]) for row in group]
            median_total = statistics.median(totals)
            median_save = statistics.median(float(row["save_flush_ms"]) for row in group)
            summaries.append({
                "rows": size, "variant": variant,
                "parse_median_ms": statistics.median(float(row["parse_ms"]) for row in group),
                "save_median_ms": median_save, "total_median_ms": median_total,
                "total_min_ms": min(totals), "total_max_ms": max(totals),
                "save_reduction_vs_jpa_pct": round(100 * (1 - median_save / baseline_save), 2),
                "total_reduction_vs_jpa_pct": round(100 * (1 - median_total / baseline_total), 2),
                "total_speedup_vs_jpa": round(baseline_total / median_total, 3),
                "rows_per_second": round(size * 1000 / median_total),
                "gc_ms_median": statistics.median(float(row["gc_ms"]) for row in group),
            })
    with (directory / "summary.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=summaries[0].keys())
        writer.writeheader()
        writer.writerows(summaries)

    correctness = read_csv(directory / "correctness.csv")
    rollback = read_csv(directory / "rollback.csv")
    require(len(correctness) == len(rollback) == 4, "Missing correctness or rollback results")
    require({row["variant"] for row in correctness} == set(variants)
            and {row["variant"] for row in rollback} == set(variants), "Missing variants")
    for row in correctness:
        require(row["input_rows"] == row["stored_rows"] == "2501"
                and row["exact_values"] == "true" and row["commits"] == "1", "Incorrect partial batch")
        batched = row["variant"] in ("JDBC_BATCH", "JDBC_BATCH_REWRITE")
        require(int(row["execute_batch_calls"]) == (3 if batched else 0), "Wrong partial batch count")
    for row in rollback:
        require(row["input_rows"] == "2501" and row["rejected_key"] == "01500"
                and row["mysql_error_code"] == "3819", "Wrong injected failure")
        require(row["commits"] == "0" and row["rollbacks"] == row["remaining_rows"] == "1"
                and row["sentinel_preserved"] == "true", "Partial commit or existing data damaged")
        require(int(row["successful_single_inserts"]) >= 1000 or int(row["successful_batches"]) >= 1,
                "Did not verify rollback after completed writes")
    print("Validated 80 measurements, 8 warmups, 4 partial-batch cases and 4 rollback cases.")
    for row in summaries:
        if row["rows"] == 100000:
            print(row)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
