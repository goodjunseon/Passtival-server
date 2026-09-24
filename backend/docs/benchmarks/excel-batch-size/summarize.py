"""Validate three JVM runs; summarize timings and paired marginal gains using only stdlib."""

import csv
import random
import statistics
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_csv(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path, rows):
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys(), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def quantile(values, fraction):
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def median(group, field):
    return statistics.median(float(row[field]) for row in group)


def bootstrap_interval(groups):
    # Resample JVM groups and paired rounds. With only three JVMs this is descriptive,
    # not a guarantee of performance on other machines or future executions.
    rng = random.Random(20260924)
    estimates = []
    for _ in range(5000):
        sample = []
        for _ in range(3):
            group = rng.choice(groups)
            sample.extend(rng.choices(group, k=len(group)))
        estimates.append(statistics.median(sample))
    return quantile(estimates, 0.025), quantile(estimates, 0.975)


def main(directory):
    records, correctness, rollback = [], [], []
    for fork in (1, 2, 3):
        path = directory / f"fork-{fork}"
        rows = read_csv(path / "measurements.csv")
        require(all(int(row["fork"]) == fork for row in rows), "Wrong fork label")
        records.extend(rows)
        correctness.extend(read_csv(path / "correctness.csv"))
        rollback.extend(read_csv(path / "rollback.csv"))
        require((path / "input-sha256.txt").read_bytes() ==
                (directory / "fork-1" / "input-sha256.txt").read_bytes(), "Inputs differ across JVMs")
    sizes = (10000, 100000)
    batches = sorted({int(row["batch_size"]) for row in records})
    require(len(records) == 3 * len(batches) * 22, "Wrong measurement count")
    for row in records:
        count, batch = int(row["rows"]), int(row["batch_size"])
        expected_batches = (count + batch - 1) // batch
        require(row["phase"] in ("warmup", "measured") and count in sizes, "Unexpected group")
        require(int(row["stored_rows"]) == count and row["exact_values"] == "true", "Incorrect stored data")
        require(row["commits"] == "1" and row["rollbacks"] == "0", "Wrong transaction behavior")
        require(row["insert_prepares"] == "1" and row["single_executes"] == "0"
                and int(row["add_batch"]) == count
                and int(row["execute_batch"]) == int(row["mysql_inserts"]) == expected_batches,
                "Wrong JDBC/MySQL execution counts")
        require(abs(float(row["write_commit_ms"]) - float(row["jdbc_write_ms"])
                    - float(row["commit_ms"])) < 0.00001, "Inconsistent timer sums")
        require(int(row["thread_allocated_bytes"]) >= int(row["write_allocated_bytes"]) > 0
                and int(row["heap_sampled_peak_bytes"]) >= int(row["heap_before_bytes"]), "Invalid memory metrics")
    for fork in (1, 2, 3):
        for batch in batches:
            warmups = [row for row in records if row["phase"] == "warmup"
                       and int(row["fork"]) == fork and int(row["batch_size"]) == batch]
            require(len(warmups) == 2 and {int(row["round"]) for row in warmups} == {1, 2}
                    and all(int(row["rows"]) == 100000 for row in warmups), "Missing warmups")

    measured = [row for row in records if row["phase"] == "measured"]
    summaries, per_fork, marginal = [], [], []
    for size in sizes:
        groups = {batch: [row for row in measured if int(row["rows"]) == size
                          and int(row["batch_size"]) == batch] for batch in batches}
        for batch, group in groups.items():
            for fork in (1, 2, 3):
                current = [row for row in group if int(row["fork"]) == fork]
                require(len(current) == 10 and {int(row["round"]) for row in current} == set(range(1, 11)),
                        "Missing/duplicate measured rounds")
                per_fork.append({"rows": size, "batch_size": batch, "fork": fork,
                                 "write_commit_median_ms": round(median(current, "write_commit_ms"), 6),
                                 "total_median_ms": round(median(current, "total_ms"), 6)})
            timings = [float(row["write_commit_ms"]) for row in group]
            summaries.append({
                "rows": size, "batch_size": batch, "samples": len(group),
                "write_commit_median_ms": round(statistics.median(timings), 6),
                "write_commit_p25_ms": round(quantile(timings, 0.25), 6),
                "write_commit_p75_ms": round(quantile(timings, 0.75), 6),
                "write_commit_min_ms": round(min(timings), 6),
                "write_commit_max_ms": round(max(timings), 6),
                "jdbc_write_median_ms": round(median(group, "jdbc_write_ms"), 6),
                "commit_median_ms": round(median(group, "commit_ms"), 6),
                "parse_median_ms": median(group, "parse_ms"),
                "total_median_ms": round(median(group, "total_ms"), 6),
                "gc_ms_median": median(group, "gc_ms"),
                "write_allocated_mib_median": round(median(group, "write_allocated_bytes") / 2**20, 3),
                "total_allocated_mib_median": round(median(group, "thread_allocated_bytes") / 2**20, 3),
                "heap_sampled_peak_mib_median": round(median(group, "heap_sampled_peak_bytes") / 2**20, 3),
                "mysql_inserts": int(group[0]["mysql_inserts"]),
            })
        for smaller, larger in zip(batches, batches[1:]):
            before = {(int(row["fork"]), int(row["round"])): row for row in groups[smaller]}
            after = {(int(row["fork"]), int(row["round"])): row for row in groups[larger]}
            deltas, percentages, by_fork = [], [], []
            for fork in (1, 2, 3):
                current = []
                for round_number in range(1, 11):
                    key = (fork, round_number)
                    a, b = float(before[key]["write_commit_ms"]), float(after[key]["write_commit_ms"])
                    deltas.append(a - b)
                    gain = 100 * (a - b) / a
                    percentages.append(gain)
                    current.append(gain)
                by_fork.append(current)
            low, high = bootstrap_interval(by_fork)
            marginal.append({
                "rows": size, "from_batch": smaller, "to_batch": larger,
                "paired_saved_ms_median": round(statistics.median(deltas), 6),
                "paired_gain_pct_median": round(statistics.median(percentages), 3),
                "gain_bootstrap_low_pct": round(low, 3), "gain_bootstrap_high_pct": round(high, 3),
                "faster_pairs": sum(value > 0 for value in deltas), "total_pairs": len(deltas),
                "fork1_gain_median_pct": round(statistics.median(by_fork[0]), 3),
                "fork2_gain_median_pct": round(statistics.median(by_fork[1]), 3),
                "fork3_gain_median_pct": round(statistics.median(by_fork[2]), 3),
            })

    require(len(correctness) == len(rollback) == 3 * len(batches), "Missing correctness cases")
    expected_groups = {(fork, batch) for fork in (1, 2, 3) for batch in batches}
    for collection in (correctness, rollback):
        require({(int(row["fork"]), int(row["batch_size"])) for row in collection} == expected_groups,
                "Missing correctness group")
    for row in correctness:
        require(int(row["input_rows"]) == int(row["stored_rows"]) == 2 * int(row["batch_size"]) + 1
                and row["execute_batch"] == "3" and row["commits"] == "1"
                and row["exact_values"] == "true", "Partial final batch failed")
    for row in rollback:
        batch = int(row["batch_size"])
        require(int(row["input_rows"]) == 2 * batch + 1
                and int(row["rejected_key"]) == batch + batch // 2
                and row["successful_batches"] == "1" and row["execute_batch_attempts"] == "2"
                and row["commits"] == "0" and row["rollbacks"] == row["remaining_rows"] == "1"
                and row["mysql_error_code"] == "3819" and row["sentinel_preserved"] == "true",
                "Whole-file rollback failed")
    write_csv(directory / "summary.csv", summaries)
    write_csv(directory / "per-fork-summary.csv", per_fork)
    write_csv(directory / "marginal-gains.csv", marginal)
    print(f"Validated {len(measured)} measured runs, {len(records)-len(measured)} warmups, "
          f"{len(correctness)} partial-batch cases and {len(rollback)} rollback cases.")
    for row in summaries:
        print(f"rows={row['rows']} batch={row['batch_size']} "
              f"write+commit={row['write_commit_median_ms']:.3f}ms "
              f"IQR=[{row['write_commit_p25_ms']:.3f},{row['write_commit_p75_ms']:.3f}] "
              f"total={row['total_median_ms']:.3f}ms writeAllocated={row['write_allocated_mib_median']}MiB")
    print("Marginal gains (positive means faster with the larger batch):")
    for row in marginal:
        print(row)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
