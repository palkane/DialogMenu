#!/usr/bin/env python3
"""Summarize JMH JSON or compare matched configurations; no third-party packages."""
import argparse
import json
import math
from pathlib import Path


def key(row):
    return row["benchmark"], row["mode"], tuple(sorted(row.get("params", {}).items()))


def load(path):
    rows = json.loads(Path(path).read_text())
    if not isinstance(rows, list) or not rows:
        raise ValueError("Expected a nonempty JMH result array")
    result = {}
    for row in rows:
        metric = row["primaryMetric"]
        if not math.isfinite(metric["score"]) or metric["score"] <= 0:
            raise ValueError("Nonpositive or nonfinite score: " + row["benchmark"])
        identity = key(row)
        if identity in result:
            raise ValueError("Duplicate benchmark configuration: " + str(identity))
        result[identity] = row
    return result


def number(value):
    return "—" if value is None or not isinstance(value, (int, float)) or not math.isfinite(value) else f"{value:.3f}"


def title(row):
    name = row["benchmark"].removeprefix("dev.dialogmenu.")
    params = ", ".join(f"{k}={v}" for k, v in sorted(row.get("params", {}).items()))
    return name + (" (" + params + ")" if params else "")


def compatible(old, new):
    for field in ("mode", "threads", "forks", "warmupIterations", "warmupTime", "warmupBatchSize",
                  "measurementIterations", "measurementTime", "measurementBatchSize", "jdkVersion",
                  "vmName", "vmVersion", "jvmArgs"):
        if old.get(field) != new.get(field):
            raise ValueError(f"Incomparable {field}: {title(new)}")
    if old["primaryMetric"]["scoreUnit"] != new["primaryMetric"]["scoreUnit"]:
        raise ValueError("Different score units: " + title(new))


def report(candidate, baseline=None):
    lines = ["JMH results. Sample-time percentiles describe sampled operations; single-shot tails with few samples are descriptive only.", ""]
    if baseline is None:
        lines += ["| Scenario | Mean | P50 | P95 | P99 | Unit | Allocation B/op |", "| --- | ---: | ---: | ---: | ---: | --- | ---: |"]
        for identity in sorted(candidate):
            row = candidate[identity]; m = row["primaryMetric"]; p = m.get("scorePercentiles", {})
            allocation = row.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
            lines.append(f"| {title(row)} | {number(m['score'])} | {number(p.get('50.0'))} | {number(p.get('95.0'))} | {number(p.get('99.0'))} | {m['scoreUnit']} | {number(allocation)} |")
    else:
        lines += ["Positive change means more time per operation. Ratios are not significance tests; inspect raw samples and load metadata.", "", "| Scenario | Baseline mean | Candidate mean | Change | Unit | P95 change | Allocation change |", "| --- | ---: | ---: | ---: | --- | ---: | ---: |"]
        for identity in sorted(candidate.keys() & baseline.keys()):
            old, new = baseline[identity], candidate[identity]; compatible(old, new)
            a, b = old["primaryMetric"], new["primaryMetric"]
            def change(x, y):
                return "—" if x is None or y is None or x <= 0 else f"{(y / x - 1) * 100:+.1f}%"
            p95 = change(a.get("scorePercentiles", {}).get("95.0"), b.get("scorePercentiles", {}).get("95.0"))
            alloc = change(old.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score"), new.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score"))
            lines.append(f"| {title(new)} | {number(a['score'])} | {number(b['score'])} | {change(a['score'], b['score'])} | {b['scoreUnit']} | {p95} | {alloc} |")
        for label, identities, data in (("Candidate only", candidate.keys() - baseline.keys(), candidate), ("Baseline only", baseline.keys() - candidate.keys(), baseline)):
            if identities:
                lines += ["", label + ": " + "; ".join(title(data[k]) for k in sorted(identities)) + "."]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate")
    parser.add_argument("--baseline")
    parser.add_argument("--output")
    args = parser.parse_args()
    text = report(load(args.candidate), load(args.baseline) if args.baseline else None)
    if args.output:
        Path(args.output).write_text(text)
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
