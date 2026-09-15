#!/usr/bin/env python3
"""Second-pass shadow recovery with metric-specific actual-period resolution.

The first shadow used one shared "latest" period for revenue and margin. That is
safe but unnecessarily strict when the newest actual period exposes revenue but
not operating margin (or vice versa). V2 resolves each metric independently:
- M01: newest completed actual period that has revenue, then requires actual
  prior-year same-calendar-month revenue.
- M02: newest completed actual period that has a direct operating margin or a
  computable actual operating-income/revenue margin.
- If direct and computed margin both exist, they must agree within a rounding
  tolerance; otherwise the candidate is rejected.
- Consensus/forecast and future periods are rejected. Existing values are never
  overwritten. Financial-sector M02 remains structural N/A.

Shadow only: does not modify evidence/real_quant_snapshot.json.
"""
from __future__ import annotations

import argparse
import calendar
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
    p.add_argument("--out", default="evidence/standard_actual_recovery_v2_shadow.json")
    return p.parse_args()


def target_codes(quant: dict[str, Any]) -> list[str]:
    result = []
    for code, row in quant["records"].items():
        missing = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        if not missing or any(m not in {"m01", "m02"} for m in missing):
            continue
        if row["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
            continue
        ok = all(
            (m == "m01" and row[m].get("reason") in M01_RECOVERABLE)
            or (m == "m02" and row[m].get("reason") in M02_RECOVERABLE)
            for m in missing
        )
        if ok:
            result.append(code)
    return sorted(result)


def _actual_keys(payload: Any, as_of: date) -> list[str]:
    if not isinstance(payload, dict):
        return []
    fi = payload.get("financeInfo")
    if not isinstance(fi, dict):
        return []
    keys = []
    for item in fi.get("trTitleList") or []:
        if not isinstance(item, dict) or not fiscal._is_actual_title(item):
            continue
        key = str(item.get("key") or "").strip()
        end = fiscal._period_end(key)
        if end is None or end > as_of:
            continue
        keys.append(key)
    return sorted(set(keys), reverse=True)


def _rows(payload: Any) -> dict[str, Any]:
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
    keys = _actual_keys(payload, as_of)
    rows = _rows(payload)
    revenue_cols = rows.get("매출액")
    latest_key = next((k for k in keys if fiscal._cell_value(revenue_cols, k) is not None), None)
    if not latest_key:
        return None
    cur = fiscal._cell_value(revenue_cols, latest_key)
    prior_key = f"{int(latest_key[:4]) - 1:04d}{latest_key[4:]}"
    if prior_key not in keys:
        return {"key": latest_key, "prior_key": prior_key, "reason": "PRIOR_PERIOD_NOT_ACTUAL"}
    prior = fiscal._cell_value(revenue_cols, prior_key)
    yoy = fiscal.safe_revenue_yoy(cur, prior)
    if yoy is None:
        return {"key": latest_key, "prior_key": prior_key, "reason": "NO_SAFE_PRIOR_REVENUE"}
    return {"key": latest_key, "prior_key": prior_key, "value": yoy, "current_revenue": cur, "prior_revenue": prior}


def _margin_for_key(rows: dict[str, Any], key: str) -> dict[str, Any] | None:
    direct = fiscal._cell_value(rows.get("영업이익률"), key)
    revenue = fiscal._cell_value(rows.get("매출액"), key)
    op = fiscal._cell_value(rows.get("영업이익"), key)
    computed = None
    if revenue not in (None, 0.0) and op is not None:
        v = op / revenue * 100.0
        if math.isfinite(v):
            computed = v

    if direct is None and computed is None:
        return None
    if direct is not None and computed is not None:
        tolerance = max(0.25, abs(direct) * 0.02)
        if abs(direct - computed) > tolerance:
            return {
                "rejected": True,
                "reason": "DIRECT_COMPUTED_MARGIN_MISMATCH",
                "direct": direct,
                "computed": computed,
                "tolerance": tolerance,
            }
        value = direct
        source = "DIRECT_CROSSCHECKED"
    elif direct is not None:
        value = direct
        source = "DIRECT"
    else:
        value = computed
        source = "COMPUTED"
    if value is None or not math.isfinite(value) or abs(value) > 10000:
        return {"rejected": True, "reason": "MARGIN_OUTLIER_GUARD", "value": value}
    return {"value": round(value, 6), "source": source, "direct": direct, "computed": computed}


def resolve_m02(payload: Any, as_of: date) -> dict[str, Any] | None:
    keys = _actual_keys(payload, as_of)
    rows = _rows(payload)
    rejected = []
    for key in keys:
        margin = _margin_for_key(rows, key)
        if not margin:
            continue
        if margin.get("rejected"):
            rejected.append({"key": key, **margin})
            continue
        return {"key": key, **margin, "rejected_periods": rejected}
    if rejected:
        return {"reason": "ALL_AVAILABLE_MARGINS_REJECTED", "rejected_periods": rejected}
    return None


def run_shadow(quant: dict[str, Any], payloads: dict[str, Any]) -> dict[str, Any]:
    out = deepcopy(quant)
    as_of = date.fromisoformat(str(quant["snapshot_date_kst"]))
    candidates = target_codes(quant)
    before = dict(quant["coverage"])
    details = {}
    m01_codes = []
    m02_codes = []

    for code in candidates:
        old = quant["records"][code]
        row = out["records"][code]
        payload = payloads.get(code)
        detail = {"name": row.get("name"), "market": row.get("market")}

        if row["m01"].get("raw") is None:
            r1 = resolve_m01(payload, as_of)
            detail["m01_resolver"] = r1
            if r1 and r1.get("value") is not None:
                row["m01"]["raw"] = round(float(r1["value"]), 6)
                row["m01"]["reason"] = None
                row["m01"]["basis"] = f"{r1['key']}_NAVER_QUARTER_ACTUAL_YOY_METRIC_RESOLVED_SHADOW"
                m01_codes.append(code)

        if row["m02"].get("raw") is None:
            r2 = resolve_m02(payload, as_of)
            detail["m02_resolver"] = r2
            if r2 and r2.get("value") is not None:
                row["m02"]["raw"] = round(float(r2["value"]), 6)
                row["m02"]["reason"] = None
                row["m02"]["basis"] = f"{r2['key']}_NAVER_QUARTER_ACTUAL_OPM_{r2['source']}_SHADOW"
                m02_codes.append(code)

        missing_after = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        detail["missing_after"] = [m.upper() for m in missing_after]
        detail["result"] = "PROMOTED_COMPLETE" if not missing_after else "PARTIAL"
        details[code] = detail

        # Per-row non-overwrite guard.
        for mid in ("m01", "m02", "m03", "m04"):
            if old[mid].get("raw") is not None:
                assert row[mid].get("raw") == old[mid].get("raw")
                assert row[mid].get("basis") == old[mid].get("basis")

    after = recent.recompute_scores(out["records"])
    out["coverage"] = after
    recent.validate_snapshot(out)
    promoted = [c for c in candidates if out["records"][c].get("composite") is not None]
    assert int(after["m03_available"]) == int(before["m03_available"])
    assert int(after["m04_available"]) == int(before["m04_available"])
    assert int(after["complete_count"]) >= int(before["complete_count"])

    return {
        "policy": "metric-specific latest completed actual period; consensus/future rejected; direct/computed OPM cross-check; shadow only",
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
    candidates = target_codes(quant)
    payloads = {}
    errors = {}
    for code in candidates:
        try:
            payloads[code] = v2.get_json(f"{base.NAVER_BASE}/{code}/finance/quarter")
        except Exception as exc:
            errors[code] = f"{type(exc).__name__}:{exc}"
    summary = run_shadow(quant, payloads)
    summary["source_errors"] = errors
    summary["source_error_count"] = len(errors)
    Path(args.out).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "candidate_count": summary["candidate_count"],
        "m01_recovered": summary["m01_recovered"],
        "m02_recovered": summary["m02_recovered"],
        "promoted_complete_count": summary["promoted_complete_count"],
        "source_error_count": summary["source_error_count"],
        "coverage_before": summary["coverage_before"],
        "coverage_after_shadow": summary["coverage_after_shadow"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
