#!/usr/bin/env python3
"""Final shadow policy for immediately promotable KR4 M01/M02 gaps.

V3 keeps metric-specific period resolution but treats Naver's published actual
operating-margin row as authoritative because displayed revenue/operating-income
cells are rounded and can create false ratio mismatches, especially for small
revenue biotech issuers.

Rules:
- M01: newest completed non-consensus actual period with revenue; require the
  prior-year same-calendar-month period to also be actual and have revenue.
- M02: newest completed non-consensus actual period with a direct published
  operating margin. If direct margin is absent, use operating-income/revenue.
- abs(M02) > 10,000% is rejected; continue to the next older actual period.
- Existing numeric values are never overwritten; financial-sector exclusion is
  never overridden; M03/M04 are untouched.
- Shadow only.
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
    p.add_argument("--out", default="evidence/standard_actual_recovery_v3_shadow.json")
    return p.parse_args()


def target_codes(quant: dict[str, Any]) -> list[str]:
    out = []
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
            out.append(code)
    return sorted(out)


def actual_keys(payload: Any, as_of: date) -> list[str]:
    fi = payload.get("financeInfo") if isinstance(payload, dict) else None
    if not isinstance(fi, dict):
        return []
    out = []
    for item in fi.get("trTitleList") or []:
        if not isinstance(item, dict) or not fiscal._is_actual_title(item):
            continue
        key = str(item.get("key") or "").strip()
        end = fiscal._period_end(key)
        if end is not None and end <= as_of:
            out.append(key)
    return sorted(set(out), reverse=True)


def finance_rows(payload: Any) -> dict[str, Any]:
    fi = payload.get("financeInfo") if isinstance(payload, dict) else None
    if not isinstance(fi, dict):
        return {}
    rows = {}
    for row in fi.get("rowList") or []:
        if not isinstance(row, dict):
            continue
        title = str(row.get("title") or row.get("name") or "").replace(" ", "").strip()
        if title in {"매출액", "영업이익", "영업이익률"}:
            rows[title] = row.get("columns")
    return rows


def resolve_m01(payload: Any, as_of: date) -> dict[str, Any] | None:
    keys = actual_keys(payload, as_of)
    rows = finance_rows(payload)
    rev = rows.get("매출액")
    key = next((k for k in keys if fiscal._cell_value(rev, k) is not None), None)
    if key is None:
        return None
    cur = fiscal._cell_value(rev, key)
    prior_key = f"{int(key[:4]) - 1:04d}{key[4:]}"
    if prior_key not in keys:
        return {"key": key, "prior_key": prior_key, "reason": "PRIOR_PERIOD_NOT_ACTUAL"}
    prior = fiscal._cell_value(rev, prior_key)
    yoy = fiscal.safe_revenue_yoy(cur, prior)
    if yoy is None:
        return {"key": key, "prior_key": prior_key, "reason": "NO_SAFE_PRIOR_REVENUE"}
    return {"key": key, "prior_key": prior_key, "value": yoy, "current_revenue": cur, "prior_revenue": prior}


def resolve_m02(payload: Any, as_of: date) -> dict[str, Any] | None:
    keys = actual_keys(payload, as_of)
    rows = finance_rows(payload)
    rejected = []
    for key in keys:
        direct = fiscal._cell_value(rows.get("영업이익률"), key)
        revenue = fiscal._cell_value(rows.get("매출액"), key)
        op = fiscal._cell_value(rows.get("영업이익"), key)
        computed = None
        if revenue not in (None, 0.0) and op is not None:
            candidate = op / revenue * 100.0
            if math.isfinite(candidate):
                computed = candidate

        if direct is not None:
            value, source = direct, "DIRECT"
        elif computed is not None:
            value, source = computed, "COMPUTED"
        else:
            continue

        if not math.isfinite(value) or abs(value) > 10000:
            rejected.append({"key": key, "reason": "MARGIN_OUTLIER_GUARD", "value": value, "source": source})
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


def run_shadow(quant: dict[str, Any], payloads: dict[str, Any]) -> dict[str, Any]:
    out = deepcopy(quant)
    as_of = date.fromisoformat(str(quant["snapshot_date_kst"]))
    before = dict(quant["coverage"])
    candidates = target_codes(quant)
    details = {}
    m01_codes, m02_codes = [], []

    for code in candidates:
        old = quant["records"][code]
        row = out["records"][code]
        payload = payloads.get(code)
        d = {"name": row.get("name"), "market": row.get("market")}
        if row["m01"].get("raw") is None:
            r1 = resolve_m01(payload, as_of)
            d["m01_resolver"] = r1
            if r1 and r1.get("value") is not None:
                row["m01"].update(raw=round(float(r1["value"]), 6), reason=None,
                                  basis=f"{r1['key']}_NAVER_QUARTER_ACTUAL_YOY_METRIC_RESOLVED_V3_SHADOW")
                m01_codes.append(code)
        if row["m02"].get("raw") is None:
            r2 = resolve_m02(payload, as_of)
            d["m02_resolver"] = r2
            if r2 and r2.get("value") is not None:
                row["m02"].update(raw=round(float(r2["value"]), 6), reason=None,
                                  basis=f"{r2['key']}_NAVER_QUARTER_ACTUAL_OPM_{r2['source']}_V3_SHADOW")
                m02_codes.append(code)
        missing_after = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        d["missing_after"] = [m.upper() for m in missing_after]
        d["result"] = "PROMOTED_COMPLETE" if not missing_after else "PARTIAL"
        details[code] = d
        for mid in ("m01", "m02", "m03", "m04"):
            if old[mid].get("raw") is not None:
                assert row[mid].get("raw") == old[mid].get("raw")
                assert row[mid].get("basis") == old[mid].get("basis")

    after = recent.recompute_scores(out["records"])
    out["coverage"] = after
    recent.validate_snapshot(out)
    promoted = [c for c in candidates if out["records"][c].get("composite") is not None]
    assert after["m03_available"] == before["m03_available"]
    assert after["m04_available"] == before["m04_available"]
    assert after["complete_count"] >= before["complete_count"]
    return {
        "policy": "V3 metric-specific actual periods; direct OPM authoritative; computed fallback only; abs OPM<=10000; shadow only",
        "candidate_count": len(candidates),
        "candidate_codes": candidates,
        "m01_recovered": len(m01_codes),
        "m02_recovered": len(m02_codes),
        "m01_codes": m01_codes,
        "m02_codes": m02_codes,
        "promoted_complete_count": len(promoted),
        "promoted_complete_codes": promoted,
        "coverage_before": before,
        "coverage_after_shadow": after,
        "details": details,
    }


def main() -> None:
    args = parse_args()
    quant = json.loads(Path(args.quant).read_text(encoding="utf-8"))
    payloads, errors = {}, {}
    for code in target_codes(quant):
        try:
            payloads[code] = v2.get_json(f"{base.NAVER_BASE}/{code}/finance/quarter")
        except Exception as exc:
            errors[code] = f"{type(exc).__name__}:{exc}"
    summary = run_shadow(quant, payloads)
    summary["source_errors"] = errors
    summary["source_error_count"] = len(errors)
    Path(args.out).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: summary[k] for k in (
        "candidate_count", "m01_recovered", "m02_recovered", "promoted_complete_count", "source_error_count",
        "coverage_before", "coverage_after_shadow")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
