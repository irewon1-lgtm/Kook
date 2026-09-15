#!/usr/bin/env python3
"""Durable KR4 recovery for immediately-completable standard-fiscal issuers.

This stage runs *after* the primary DART collector, one-quarter DART fallback,
and non-December fiscal-calendar recovery.

Safety contract
---------------
- Only issuers whose remaining missing metrics are M01/M02 are new candidates.
- Existing numeric values are never overwritten except an M02 value previously
  produced by this exact STANDARD_RECOVERY stage; those values may be upgraded
  when a newer authoritative actual-period direct OPM is now available.
- Financial-sector M02 exclusion is never overridden.
- Naver finance periods must be completed, actual (non-consensus) periods.
- M01 requires revenue plus prior-year same-calendar-month actual revenue.
- M02 prefers Naver's published actual operating-margin row. A finite direct
  actual OPM is authoritative regardless of magnitude. Only if direct OPM is
  absent is actual operating-income/revenue used.
- The +/-10,000% outlier guard applies only to the computed fallback because
  the displayed revenue/operating-income cells are rounded.
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
STANDARD_RECOVERY_MARKER = "STANDARD_RECOVERY"
COMPUTED_OPM_ABS_LIMIT = 10000.0


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--out", default=None)
    p.add_argument("--self-test", action="store_true")
    return p.parse_args()


def target_codes(quant: dict[str, Any]) -> list[str]:
    """Missing-only candidates that can become complete in this stage."""
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


def standard_m02_upgrade_codes(quant: dict[str, Any]) -> list[str]:
    """Only M02 values already created by this stage may be rewritten."""
    result: list[str] = []
    for code, row in quant["records"].items():
        m02 = row.get("m02") or {}
        basis = str(m02.get("basis") or "")
        if m02.get("raw") is not None and STANDARD_RECOVERY_MARKER in basis:
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

        # Provider-published actual OPM is authoritative. Do not impose an
        # arbitrary magnitude cap on a directly published finite loss margin.
        if direct is not None:
            value = float(direct)
            if math.isfinite(value):
                return {
                    "key": key,
                    "value": round(value, 6),
                    "source": "DIRECT",
                    "direct": direct,
                    "computed_from_rounded_cells": None,
                    "extreme_direct_actual": abs(value) > COMPUTED_OPM_ABS_LIMIT,
                    "rejected_newer_periods": rejected,
                }
            rejected.append({
                "key": key,
                "value": direct,
                "source": "DIRECT",
                "reason": "NONFINITE_DIRECT_MARGIN",
            })
            continue

        # Only computed fallback values receive an outlier guard because the
        # displayed revenue and operating-income cells can be heavily rounded.
        if revenue not in (None, 0.0) and op_income is not None:
            computed = float(op_income) / float(revenue) * 100.0
            if not math.isfinite(computed):
                rejected.append({
                    "key": key,
                    "value": computed,
                    "source": "COMPUTED",
                    "reason": "NONFINITE_COMPUTED_MARGIN",
                })
                continue
            if abs(computed) > COMPUTED_OPM_ABS_LIMIT:
                rejected.append({
                    "key": key,
                    "value": computed,
                    "source": "COMPUTED",
                    "reason": "COMPUTED_MARGIN_OUTLIER_GUARD",
                })
                continue
            return {
                "key": key,
                "value": round(computed, 6),
                "source": "COMPUTED",
                "direct": None,
                "computed_from_rounded_cells": computed,
                "extreme_direct_actual": False,
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

    samples = snapshot.get("samples")
    if isinstance(samples, dict):
        for code in list(samples):
            if code in records:
                samples[code] = deepcopy(records[code])

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


def _m02_basis(result: dict[str, Any]) -> str:
    return f"{result['key']}_NAVER_QUARTER_ACTUAL_OPM_{result['source']}_STANDARD_RECOVERY"


def apply_recovery(before: dict[str, Any], payloads: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    after = deepcopy(before)
    as_of = date.fromisoformat(str(before["snapshot_date_kst"]))
    candidates = target_codes(before)
    upgrade_candidates = standard_m02_upgrade_codes(before)
    upgrade_candidate_set = set(upgrade_candidates)
    details: dict[str, Any] = {}
    m01_codes: list[str] = []
    m02_codes: list[str] = []
    m02_upgraded_codes: list[str] = []

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
                row["m02"].update(raw=r2["value"], reason=None, basis=_m02_basis(r2))
                m02_codes.append(code)

        missing_after = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        detail["missing_after"] = [m.upper() for m in missing_after]
        detail["result"] = "PROMOTED_COMPLETE" if not missing_after else "PARTIAL"
        details[code] = detail

    # Only prior STANDARD_RECOVERY M02 values are eligible for policy upgrades.
    for code in upgrade_candidates:
        old_m02 = before["records"][code]["m02"]
        new_m02 = after["records"][code]["m02"]
        r2 = resolve_m02(payloads.get(code), as_of)
        d = details.setdefault(code, {
            "name": after["records"][code].get("name"),
            "market": after["records"][code].get("market"),
        })
        d["m02_upgrade_resolver"] = r2
        if not r2 or r2.get("value") is None:
            d["m02_upgrade_result"] = "RETAINED_PRIOR_STANDARD_RECOVERY"
            continue
        new_basis = _m02_basis(r2)
        new_value = r2["value"]
        if new_m02.get("raw") != new_value or new_m02.get("basis") != new_basis:
            new_m02.update(raw=new_value, reason=None, basis=new_basis)
            m02_upgraded_codes.append(code)
            d["m02_upgrade_result"] = "UPGRADED"
            d["m02_before"] = {"raw": old_m02.get("raw"), "basis": old_m02.get("basis")}
            d["m02_after"] = {"raw": new_value, "basis": new_basis}
        else:
            d["m02_upgrade_result"] = "ALREADY_CURRENT"

    for code, old in before["records"].items():
        new = after["records"][code]
        for mid in ("m01", "m02", "m03", "m04"):
            if old[mid].get("raw") is None:
                continue
            allow_standard_m02_upgrade = mid == "m02" and code in upgrade_candidate_set
            if allow_standard_m02_upgrade:
                continue
            assert new[mid].get("raw") == old[mid].get("raw"), (code, mid, "raw overwrite")
            assert new[mid].get("basis") == old[mid].get("basis"), (code, mid, "basis overwrite")
        if old["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
            assert new["m02"].get("raw") is None
            assert new["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED"

    before_coverage = dict(before["coverage"])
    after_coverage = recent.recompute_scores(after["records"])
    after["coverage"] = after_coverage
    _rebuild_metadata(after)
    recent.validate_snapshot(after)

    promoted = sorted(
        code for code in candidates
        if before["records"][code].get("composite") is None
        and after["records"][code].get("composite") is not None
    )
    recovery = {
        "policy": (
            "remaining-gap M01/M02 only; completed Naver actual periods; finite direct actual OPM authoritative "
            "regardless of magnitude; computed fallback only uses abs<=10000 guard; existing numbers preserved "
            "except prior STANDARD_RECOVERY M02 policy upgrades"
        ),
        "candidate_count": len(candidates),
        "candidate_codes": candidates,
        "upgrade_candidate_count": len(upgrade_candidates),
        "upgrade_candidate_codes": upgrade_candidates,
        "m01_recovered": len(m01_codes),
        "m02_recovered": len(m02_codes),
        "m01_codes": m01_codes,
        "m02_codes": m02_codes,
        "m02_upgraded_count": len(m02_upgraded_codes),
        "m02_upgraded_codes": sorted(m02_upgraded_codes),
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

    direct_extreme = {"financeInfo": {
        "trTitleList": [{"key": "202603", "isConsensus": "N"}, {"key": "202606", "isConsensus": "N"}],
        "rowList": [{"title": "영업이익률", "columns": {"202603": {"value": "-250"}, "202606": {"value": "-12000"}}}],
    }}
    m2b = resolve_m02(direct_extreme, as_of)
    assert m2b and m2b["key"] == "202606" and m2b["value"] == -12000.0, m2b
    assert m2b["source"] == "DIRECT" and m2b["extreme_direct_actual"] is True

    computed_extreme = {"financeInfo": {
        "trTitleList": [{"key": "202603", "isConsensus": "N"}, {"key": "202606", "isConsensus": "N"}],
        "rowList": [
            {"title": "매출액", "columns": {"202603": {"value": "100"}, "202606": {"value": "1"}}},
            {"title": "영업이익", "columns": {"202603": {"value": "-250"}, "202606": {"value": "-120"}}},
        ],
    }}
    m2c = resolve_m02(computed_extreme, as_of)
    assert m2c and m2c["key"] == "202603" and m2c["value"] == -250.0, m2c
    assert m2c["source"] == "COMPUTED"
    assert m2c["rejected_newer_periods"][0]["reason"] == "COMPUTED_MARGIN_OUTLIER_GUARD"
    print("STANDARD_ACTUAL_RECOVERY_SELF_TEST_PASS")


def main() -> None:
    args = parse_args()
    if args.self_test:
        self_test()
        return
    quant_path = Path(args.quant)
    out_path = Path(args.out) if args.out else quant_path
    before = json.loads(quant_path.read_text(encoding="utf-8"))
    fetch_codes = sorted(set(target_codes(before)) | set(standard_m02_upgrade_codes(before)))

    payloads: dict[str, Any] = {}
    errors: dict[str, str] = {}
    for code in fetch_codes:
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
    recent.validate_snapshot(after)
    out_path.write_text(json.dumps(after, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("STANDARD_ACTUAL_RECOVERY_PASS", json.dumps({
        "candidate_count": recovery["candidate_count"],
        "upgrade_candidate_count": recovery["upgrade_candidate_count"],
        "m01_recovered": recovery["m01_recovered"],
        "m02_recovered": recovery["m02_recovered"],
        "m02_upgraded_count": recovery["m02_upgraded_count"],
        "promoted_complete_count": recovery["promoted_complete_count"],
        "coverage_before": recovery["coverage_before"],
        "coverage_after": recovery["coverage_after"],
    }, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
