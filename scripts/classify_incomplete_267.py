#!/usr/bin/env python3
"""Classify KR4 incomplete four-metric records without changing production values.

This is a diagnostic gate. It reads the promoted snapshot and splits every
incomplete issuer into structural N/A vs source-recoverable buckets. Existing
numeric metric values are never modified.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path
from typing import Any

METRICS = ("m01", "m02", "m03", "m04")


def subtract_months(d: date, months: int) -> date:
    month_index = d.year * 12 + (d.month - 1) - months
    year, month0 = divmod(month_index, 12)
    month = month0 + 1
    # Only the comparison boundary matters here. Clamp to a valid day.
    import calendar

    day = min(d.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def metric_class(metric: str, reason: str | None, listing_date: str | None, snapshot_date: date) -> str:
    reason = reason or ""
    if metric == "m02" and reason == "FINANCIAL_SECTOR_EXCLUDED":
        return "STRUCTURAL_NA_FINANCIAL_M02"
    if metric == "m03" and reason == "ZERO_EPS":
        return "STRUCTURAL_NA_ZERO_EPS"
    if metric == "m04" and reason == "PRICE_HISTORY_SHORTER_THAN_6M":
        if listing_date:
            try:
                listed = date.fromisoformat(listing_date)
                if listed > subtract_months(snapshot_date, 6):
                    return "STRUCTURAL_NA_NEW_LISTING_M04"
            except ValueError:
                pass
        return "RECOVERABLE_PRICE_HISTORY"
    if metric == "m03" and reason == "NAVER_EPS_MISSING":
        return "RECOVERABLE_EPS_SOURCE"
    if metric == "m01" and reason in {
        "DART_NO_REVENUE",
        "DART_NO_COMPARABLE_PRIOR_REVENUE",
    }:
        return "RECOVERABLE_ACCOUNTING_SOURCE"
    if metric == "m02" and reason in {
        "DART_NO_OPERATING_INCOME",
        "DART_NO_COMPARABLE_OPERATING_MARGIN",
        "DART_OPERATING_MARGIN_OUTLIER_GUARD",
    }:
        return "RECOVERABLE_ACCOUNTING_SOURCE"
    return f"INVESTIGATE_{metric.upper()}_{reason or 'UNKNOWN'}"


def summarize(snapshot: dict[str, Any]) -> dict[str, Any]:
    records = snapshot["records"]
    snapshot_date = date.fromisoformat(snapshot["snapshot_date_kst"])

    available = Counter()
    missing = Counter()
    actual_reasons: dict[str, Counter[str]] = {m: Counter() for m in METRICS}
    combo_counts: Counter[str] = Counter()
    class_counts: Counter[str] = Counter()
    issuer_bucket_counts: Counter[str] = Counter()
    rows: list[dict[str, Any]] = []

    for code, rec in sorted(records.items()):
        missing_metrics: list[str] = []
        reasons: dict[str, str | None] = {}
        classes: dict[str, str] = {}

        for metric in METRICS:
            obj = rec.get(metric) or {}
            raw = obj.get("raw")
            reason = obj.get("reason")
            if raw is None:
                missing[metric] += 1
                missing_metrics.append(metric)
                actual_reasons[metric][reason or "UNKNOWN"] += 1
                klass = metric_class(metric, reason, rec.get("listing_date"), snapshot_date)
                classes[metric] = klass
                class_counts[klass] += 1
                reasons[metric] = reason
            else:
                available[metric] += 1

        if not missing_metrics:
            continue

        combo = "+".join(m.upper() for m in missing_metrics)
        combo_counts[combo] += 1
        class_values = list(classes.values())
        if class_values and all(v.startswith("STRUCTURAL_NA_") for v in class_values):
            issuer_bucket = "STRUCTURAL_ONLY"
        elif any(v.startswith("RECOVERABLE_") for v in class_values):
            issuer_bucket = "RECOVERABLE"
        else:
            issuer_bucket = "INVESTIGATE"
        issuer_bucket_counts[issuer_bucket] += 1

        rows.append(
            {
                "code": code,
                "name": rec.get("name"),
                "market": rec.get("market"),
                "sector": rec.get("sector"),
                "listing_date": rec.get("listing_date"),
                "missing_metrics": [m.upper() for m in missing_metrics],
                "reasons": {m.upper(): reasons[m] for m in missing_metrics},
                "classes": {m.upper(): classes[m] for m in missing_metrics},
                "issuer_bucket": issuer_bucket,
            }
        )

    coverage = snapshot.get("coverage") or {}
    expected_available = {
        "m01": coverage.get("m01_available"),
        "m02": coverage.get("m02_available"),
        "m03": coverage.get("m03_available"),
        "m04": coverage.get("m04_available"),
    }
    actual_available = {m: available[m] for m in METRICS}
    coverage_match = all(expected_available[m] == actual_available[m] for m in METRICS)

    output = {
        "snapshot_date_kst": snapshot["snapshot_date_kst"],
        "universe_count": len(records),
        "complete_count": len(records) - len(rows),
        "incomplete_count": len(rows),
        "available_counts": {m.upper(): available[m] for m in METRICS},
        "missing_counts": {m.upper(): missing[m] for m in METRICS},
        "missing_combination_counts": dict(combo_counts.most_common()),
        "missing_reason_counts_actual_records": {
            m.upper(): dict(actual_reasons[m].most_common()) for m in METRICS
        },
        "metric_class_counts": dict(class_counts.most_common()),
        "issuer_bucket_counts": dict(issuer_bucket_counts.most_common()),
        "coverage_metadata_matches_records": coverage_match,
        "coverage_expected": {m.upper(): expected_available[m] for m in METRICS},
        "records": rows,
    }

    # Hard gate against silently analyzing the wrong snapshot.
    if len(records) != 2649:
        raise SystemExit(f"universe gate failed: {len(records)} != 2649")
    if len(rows) != 267:
        raise SystemExit(f"incomplete gate failed: {len(rows)} != 267")
    if not coverage_match:
        raise SystemExit(
            f"coverage mismatch: expected={expected_available} actual={actual_available}"
        )
    return output


def to_markdown(result: dict[str, Any]) -> str:
    lines = [
        "# KR4 incomplete 267 classification",
        "",
        f"- Snapshot: `{result['snapshot_date_kst']}`",
        f"- Universe: **{result['universe_count']}**",
        f"- Complete: **{result['complete_count']}**",
        f"- Incomplete: **{result['incomplete_count']}**",
        "",
        "## Missing by metric",
        "",
        "| Metric | Available | Missing |",
        "|---|---:|---:|",
    ]
    for metric in ("M01", "M02", "M03", "M04"):
        lines.append(
            f"| {metric} | {result['available_counts'][metric]} | {result['missing_counts'][metric]} |"
        )

    lines += ["", "## Issuer buckets", ""]
    for key, value in result["issuer_bucket_counts"].items():
        lines.append(f"- **{key}**: {value}")

    lines += ["", "## Missing combinations", ""]
    for key, value in result["missing_combination_counts"].items():
        lines.append(f"- `{key}`: {value}")

    lines += ["", "## Metric classes", ""]
    for key, value in result["metric_class_counts"].items():
        lines.append(f"- `{key}`: {value}")

    lines += ["", "## Actual missing reasons", ""]
    for metric in ("M01", "M02", "M03", "M04"):
        lines.append(f"### {metric}")
        for key, value in result["missing_reason_counts_actual_records"][metric].items():
            lines.append(f"- `{key}`: {value}")
        lines.append("")

    lines += [
        "## Policy",
        "",
        "- Structural N/A: financial-sector M02, zero-EPS M03, and M04 for issuers listed after the six-calendar-month target date.",
        "- Recoverable: DART accounting-source gaps, Naver EPS missing, and price-history gaps in issuers old enough to have six-month history.",
        "- This diagnostic never changes existing numeric values.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", default="evidence/real_quant_snapshot.json")
    parser.add_argument("--json-out", default="evidence/incomplete_267_analysis.json")
    parser.add_argument("--md-out", default="evidence/incomplete_267_analysis.md")
    args = parser.parse_args()

    snapshot = json.loads(Path(args.snapshot).read_text(encoding="utf-8"))
    result = summarize(snapshot)
    Path(args.json_out).write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    Path(args.md_out).write_text(to_markdown(result), encoding="utf-8")
    print(json.dumps({k: result[k] for k in (
        "universe_count", "complete_count", "incomplete_count", "available_counts",
        "missing_counts", "issuer_bucket_counts", "missing_combination_counts",
        "metric_class_counts")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
