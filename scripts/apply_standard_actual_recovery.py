#!/usr/bin/env python3
"""Durable KR4 recovery for immediately-completable standard-fiscal issuers.

This stage runs *after* the primary DART collector, one-quarter DART fallback,
and non-December fiscal-calendar recovery.

Safety contract
---------------
- Only issuers whose remaining missing metrics are M01/M02 are candidates.
- Existing numeric values are never overwritten.
- Financial-sector M02 exclusion is never overridden.
- Naver finance periods must be completed, actual (non-consensus) periods.
- M01 requires revenue plus prior-year same-calendar-month actual revenue.
- M02 prefers Naver's published actual operating-margin row. Only if absent is
  actual operating-income/revenue used.
- abs(M02) > 10,000% is rejected and the next older actual period is tried.
- M03/M04 are never modified.
- Any live source error fails closed before output is written.
- Percentiles/composite/ranks and metadata are rebuilt from the resulting rows.
"""
from __future__ import annotations

import argparse
import json
import math
from copy import deepcopy
from datetime import date
from pathlib import Path
from typing import Any

import apply_fiscal_calendar_recovery as fiscal
import apply_recent_dart_fallback as recent
import collect_real_quant as base
import collect_real_quant_v2 as v2

M01_RECOVERABLE = {"DART_NO_REVENUE", "DART_NO_COMPARABLE_PRIOR_REVENUE"}
M02_RECOVERABLE = {
    "DART_NO_OPERATING_INCOME",
    "DART_NO_COMPARABLE_OPERATING_MARGIN",
    "DART_OPERATING_MARGIN_OUTLIER_GUARD",
}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--out", default=None)
    p.add_argument("--self-test", action="store_true")
    return p.parse_args()


def target_codes(quant: dict[str, Any]) -> list[str]:
    result: list[str] = []
    for code, row in quant["records"].items():
        missing = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        if not missing or any(m not in {"m01", "m02"} for m in missing):
            continue
        if row["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
            continue
        if all(
            (m == "m01" and row[m].get("reason") in M01_RECOVERABLE)
            or (m == "m02" and row[m].get("reason") in M02_RECOVERABLE)
            for m in missing
        ):
            result.append(code)
    return sorted(result)


def _actual_keys(payload: Any, as_of: date) -> list[str]:
    fi = payload.get("financeInfo") if isinstance(payload, dict) else None
    if not isinstance(fi, dict):
        return []
    keys: list[str] = []
    for item in fi.get("trTitleList") or []:
        if not isinstance(item, dict) or not fiscal._is_actual_title(item):
            continue
        key = str(item.get("key") or "").strip()
        end = fiscal._period_end(key)
        if end is not None and end <= as_of:
            keys.append(key)
    return sorted(set(keys), reverse=True)


def _finance_rows(payload: Any) -> dict[str, Any]:
    fi = payload.get("financeInfo") if isinstance(payload, dict) else None
    if not isinstance(fi, dict):
        return {}
    rows: dict[str, Any] = {}
    for row in fi.get("rowList") or []:
        if not isinstance(row, dict):
            continue
        title = str(row.get("title") or row.get("name") or "").replace(" ", "").strip()
        if title in {"매출액", "영업이익", "영업이익률"}:
            rows[title] = row.get("columns")
    return rows


def resolve_m01(payload: Any, as_of: date) -> dict[str, Any] | None:
    keys = _actual_keys(payload, as_of)
    rows = _finance_rows(payload)
    revenue = rows.get("매출액")
    key = next((k for k in keys if fiscal._cell_value(revenue, k) is not None), None)
    if key is None:
        return None
    current = fiscal._cell_value(revenue, key)
    prior_key = f"{int(key[:4]) - 1:04d}{key[4:]}"
    if prior_key not in keys:
        return {"key": key, "prior_key": prior_key, "reason": "PRIOR_PERIOD_NOT_ACTUAL"}
    prior = fiscal._cell_value(revenue, prior_key)
    yoy = fiscal.safe_revenue_yoy(current, prior)
    if yoy is None:
        return {"key": key, "prior_key": prior_key, "reason": "NO_SAFE_PRIOR_REVENUE"}
    return {
        "key": key,
        "prior_key": prior_key,
        "value": round(float(yoy), 6),
        "current_revenue": current,
        "prior_revenue": prior,
    }


def resolve_m02(payload: Any, as_of: date) -> dict[str, Any] | None:
    keys = _actual_keys(payload, as_of)
    rows = _finance_rows(payload)
    rejected: list[dict[str, Any]] = []
    for key in keys:
        direct = fiscal._cell_value(rows.get("영업이익률"), key)
        revenue = fiscal._cell_value(rows.get("매출액"), key)
        op_income = fiscal._cell_value(rows.get("영업이익"), key)
        computed = None
        if revenue not in (None, 0.0) and op_income is not None:
            candidate = float(op_income) / float(revenue) * 100.0
            if math.isfinite(candidate):
                computed = candidate

        if direct is not None:
            value, source = float(direct), "DIRECT"
        elif computed is not None:
            value, source = float(computed), "COMPUTED"
        else:
            continue

        if not math.isfinite(value) or abs(value) > 10000:
            rejected.append({"key": key, "value": value, "source": source, "reason": "MARGIN_OUTLIER_GUARD"})
            continue
        return {
            "key": key,
            "value": round(value, 6),
            "source": source,
            "direct": direct,
            "computed_from_rounded_cells": computed,
            "rejected_newer_periods": rejected,
        }
    if rejected:
        return {"reason": "ALL_AVAILABLE_MARGINS_REJECTED", "rejected_periods": rejected}
    return None


def _rebuild_metadata(snapshot: dict[str, Any]) -> None:
    records = snapshot["records"]
    reason_counts: dict[str, dict[str, int]] = {}
    for metric in ("m01", "m02", "m03", "m04"):
        counts: dict[str, int] = {}
        for row in records.values():
            m = row[metric]
            key = "AVAILABLE" if m.get("raw") is not None else str(m.get("reason") or "UNKNOWN")
            counts[key] = counts.get(key, 0) + 1
        reason_counts[metric] = counts
    snapshot["reason_counts"] = reason_counts

    # Keep sample cards exactly synchronized with canonical records.
    samples = snapshot.get("samples")
    if isinstance(samples, dict):
        for code in list(samples):
            if code in records:
                samples[code] = deepcopy(records[code])

    # Historical one-time scripts appended this sentence more than once.
    rules = snapshot.get("rules") or {}
    for mid in ("M01", "M02"):
        text = str(rules.get(mid) or "")
        parts = [p.strip() for p in text.split(";") if p.strip()]
        deduped: list[str] = []
        for part in parts:
            if part not in deduped:
                deduped.append(part)
        rules[mid] = "; ".join(deduped)
    snapshot["rules"] = rules


def apply_recovery(before: dict[str, Any], payloads: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    after = deepcopy(before)
    as_of = date.fromisoformat(str(before["snapshot_date_kst"]))
    candidates = target_codes(before)
    details: dict[str, Any] = {}
    m01_codes: list[str] = []
    m02_codes: list[str] = []

    for code in candidates:
        old = before["records"][code]
        row = after["records"][code]
        payload = payloads.get(code)
        detail: dict[str, Any] = {"name": row.get("name"), "market": row.get("market")}

        if row["m01"].get("raw") is None:
            r1 = resolve_m01(payload, as_of)
            detail["m01_resolver"] = r1
            if r1 and r1.get("value") is not None:
                row["m01"].update(
                    raw=r1["value"],
                    reason=None,
                    basis=f"{r1['key']}_NAVER_QUARTER_ACTUAL_YOY_STANDARD_RECOVERY",
                )
                m01_codes.append(code)

        if row["m02"].get("raw") is None:
            r2 = resolve_m02(payload, as_of)
            detail["m02_resolver"] = r2
            if r2 and r2.get("value") is not None:
                row["m02"].update(
                    raw=r2["value"],
                    reason=None,
                    basis=f"{r2['key']}_NAVER_QUARTER_ACTUAL_OPM_{r2['source']}_STANDARD_RECOVERY",
                )
                m02_codes.append(code)

        missing_after = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        detail["missing_after"] = [m.upper() for m in missing_after]
        detail["result"] = "PROMOTED_COMPLETE" if not missing_after else "PARTIAL"
        details[code] = detail

        # Per-row immutable-value gate.
        for mid in ("m01", "m02", "m03", "m04"):
            if old[mid].get("raw") is not None:
                assert row[mid].get("raw") == old[mid].get("raw"), (code, mid, "raw overwrite")
                assert row[mid].get("basis") == old[mid].get("basis"), (code, mid, "basis overwrite")
        if old["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
            assert row["m02"].get("raw") is None
            assert row["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED"

    before_coverage = dict(before["coverage"])
    after_coverage = recent.recompute_scores(after["records"])
    after["coverage"] = after_coverage
    _rebuild_metadata(after)
    recent.validate_snapshot(after)

    promoted = sorted(code for code in candidates if after["records"][code].get("composite") is not None)
    recovery = {
        "policy": "remaining-gap M01/M02 only; completed Naver actual periods; direct OPM preferred; computed fallback; abs OPM<=10000; existing numbers preserved",
        "candidate_count": len(candidates),
        "candidate_codes": candidates,
        "m01_recovered": len(m01_codes),
        "m02_recovered": len(m02_codes),
        "m01_codes": m01_codes,
        "m02_codes": m02_codes,
        "promoted_complete_count": len(promoted),
        "promoted_complete_codes": promoted,
        "coverage_before": before_coverage,
        "coverage_after": after_coverage,
        "details": details,
    }
    after["standard_actual_recovery"] = recovery

    assert set(after["records"]) == set(before["records"])
    assert after["universe_count"] == before["universe_count"] == len(after["records"])
    assert after_coverage["m03_available"] == before_coverage["m03_available"]
    assert after_coverage["m04_available"] == before_coverage["m04_available"]
    assert after_coverage["m01_available"] >= before_coverage["m01_available"]
    assert after_coverage["m02_available"] >= before_coverage["m02_available"]
    assert after_coverage["complete_count"] >= before_coverage["complete_count"]
    assert after_coverage["ranked_count"] == after_coverage["complete_count"]
    return after, recovery


def self_test() -> None:
    as_of = date(2026, 9, 15)
    payload = {"financeInfo": {
        "trTitleList": [
            {"key": "202503", "isConsensus": "N"},
            {"key": "202603", "isConsensus": "N"},
            {"key": "202606", "isConsensus": "Y"},
            {"key": "202612", "isConsensus": "N"},
        ],
        "rowList": [
            {"title": "매출액", "columns": {"202503": {"value": "100"}, "202603": {"value": "125"}, "202606": {"value": "999"}}},
            {"title": "영업이익", "columns": {"202603": {"value": "10"}}},
            {"title": "영업이익률", "columns": {"202603": {"value": "8.0"}, "202606": {"value": "99"}}},
        ],
    }}
    m1 = resolve_m01(payload, as_of)
    m2 = resolve_m02(payload, as_of)
    assert m1 and m1["key"] == "202603" and m1["value"] == 25.0, m1
    assert m2 and m2["key"] == "202603" and m2["value"] == 8.0, m2

    outlier = {"financeInfo": {
        "trTitleList": [{"key": "202603", "isConsensus": "N"}, {"key": "202606", "isConsensus": "N"}],
        "rowList": [{"title": "영업이익률", "columns": {"202603": {"value": "-250"}, "202606": {"value": "-12000"}}}],
    }}
    m2b = resolve_m02(outlier, as_of)
    assert m2b and m2b["key"] == "202603" and m2b["value"] == -250.0, m2b
    print("STANDARD_ACTUAL_RECOVERY_SELF_TEST_PASS")


def main() -> None:
    args = parse_args()
    if args.self_test:
        self_test()
        return
    quant_path = Path(args.quant)
    out_path = Path(args.out) if args.out else quant_path
    before = json.loads(quant_path.read_text(encoding="utf-8"))
    candidates = target_codes(before)

    payloads: dict[str, Any] = {}
    errors: dict[str, str] = {}
    for code in candidates:
        try:
            payloads[code] = v2.get_json(f"{base.NAVER_BASE}/{code}/finance/quarter")
        except Exception as exc:
            errors[code] = f"{type(exc).__name__}:{exc}"
    if errors:
        raise RuntimeError(f"STANDARD_ACTUAL_SOURCE_ERRORS:{json.dumps(errors, ensure_ascii=False)}")

    after, recovery = apply_recovery(before, payloads)
    recovery["source_error_count"] = 0
    recovery["source_errors"] = {}
    after["standard_actual_recovery"] = recovery
    # Validate once more after metadata insertion, before touching output.
    recent.validate_snapshot(after)
    out_path.write_text(json.dumps(after, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("STANDARD_ACTUAL_RECOVERY_PASS", json.dumps({
        "candidate_count": recovery["candidate_count"],
        "m01_recovered": recovery["m01_recovered"],
        "m02_recovered": recovery["m02_recovered"],
        "promoted_complete_count": recovery["promoted_complete_count"],
        "coverage_before": recovery["coverage_before"],
        "coverage_after": recovery["coverage_after"],
    }, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
