#!/usr/bin/env python3
"""Second durable KR4 recovery pass for all remaining safe M01/M02 cells.

This stage runs after apply_standard_actual_recovery.py. The standard stage is
completion-oriented; this pass also fills safe individual M01/M02 gaps when an
issuer remains incomplete for an unrelated structural reason (for example a
financial-sector M02 exclusion or a new-listing M04 gap).

Safety contract
---------------
- Existing numeric values are never overwritten.
- FINANCIAL_SECTOR_EXCLUDED M02 is never touched.
- Only explicit DART-recoverable M01/M02 reason codes are eligible.
- Naver periods must be completed, actual and non-consensus.
- M01 requires current revenue and prior-year same-calendar-month actual revenue.
- M02 uses the validated resolver from apply_standard_actual_recovery.py:
  published actual OPM first, computed operating-income/revenue fallback second.
- M03 and M04 are immutable.
- Any live source error fails closed before output is written.
- Percentiles, composite, ranks, reason-count metadata and samples are rebuilt.
"""
from __future__ import annotations

import argparse
import json
from copy import deepcopy
from datetime import date
from pathlib import Path
from typing import Any

import apply_recent_dart_fallback as recent
import apply_standard_actual_recovery as standard
import collect_real_quant as base
import collect_real_quant_v2 as v2

ALL_SAFE_MARKER = "ALL_SAFE_RECOVERY"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--out", default=None)
    p.add_argument("--self-test", action="store_true")
    return p.parse_args()


def target_codes(snapshot: dict[str, Any]) -> list[str]:
    """Every issuer with at least one safely recoverable missing M01/M02 cell."""
    result: list[str] = []
    for code, row in snapshot["records"].items():
        m01 = row["m01"]
        m02 = row["m02"]
        need_m01 = m01.get("raw") is None and m01.get("reason") in standard.M01_RECOVERABLE
        need_m02 = (
            m02.get("raw") is None
            and m02.get("reason") in standard.M02_RECOVERABLE
            and m02.get("reason") != "FINANCIAL_SECTOR_EXCLUDED"
        )
        if need_m01 or need_m02:
            result.append(code)
    return sorted(result)


def _rebuild_metadata(snapshot: dict[str, Any]) -> None:
    # Reuse the production-tested metadata rebuild from the standard stage.
    standard._rebuild_metadata(snapshot)


def apply_recovery(
    before: dict[str, Any],
    payloads: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
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
        detail: dict[str, Any] = {
            "name": row.get("name"),
            "market": row.get("market"),
            "sector": row.get("sector"),
        }

        m01 = row["m01"]
        if m01.get("raw") is None and m01.get("reason") in standard.M01_RECOVERABLE:
            r1 = standard.resolve_m01(payload, as_of)
            detail["m01_resolver"] = r1
            if r1 and r1.get("value") is not None:
                m01.update(
                    raw=round(float(r1["value"]), 6),
                    reason=None,
                    basis=f"{r1['key']}_NAVER_QUARTER_ACTUAL_YOY_{ALL_SAFE_MARKER}",
                )
                m01_codes.append(code)

        m02 = row["m02"]
        if (
            m02.get("raw") is None
            and m02.get("reason") in standard.M02_RECOVERABLE
            and m02.get("reason") != "FINANCIAL_SECTOR_EXCLUDED"
        ):
            r2 = standard.resolve_m02(payload, as_of)
            detail["m02_resolver"] = r2
            if r2 and r2.get("value") is not None:
                m02.update(
                    raw=round(float(r2["value"]), 6),
                    reason=None,
                    basis=f"{r2['key']}_NAVER_QUARTER_ACTUAL_OPM_{r2['source']}_{ALL_SAFE_MARKER}",
                )
                m02_codes.append(code)

        detail["remaining_missing"] = [
            mid.upper()
            for mid in ("m01", "m02", "m03", "m04")
            if row[mid].get("raw") is None
        ]
        details[code] = detail

        # Existing numeric data is immutable in this second-stage recovery.
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

    # M03/M04 are a strict immutable boundary for this stage.
    assert after_coverage["m03_available"] == before_coverage["m03_available"]
    assert after_coverage["m04_available"] == before_coverage["m04_available"]
    assert after_coverage["m01_available"] >= before_coverage["m01_available"]
    assert after_coverage["m02_available"] >= before_coverage["m02_available"]
    assert after_coverage["complete_count"] >= before_coverage["complete_count"]
    assert after_coverage["ranked_count"] == after_coverage["complete_count"]

    promoted = sorted(
        code for code in candidates
        if before["records"][code].get("composite") is None
        and after["records"][code].get("composite") is not None
    )
    recovery = {
        "policy": (
            "all remaining explicit DART-recoverable M01/M02 cells; completed non-consensus Naver actual periods; "
            "same-month prior-year revenue required for M01; direct actual OPM preferred for M02; existing values immutable"
        ),
        "candidate_count": len(candidates),
        "candidate_codes": candidates,
        "m01_recovered": len(m01_codes),
        "m01_codes": m01_codes,
        "m02_recovered": len(m02_codes),
        "m02_codes": m02_codes,
        "promoted_complete_count": len(promoted),
        "promoted_complete_codes": promoted,
        "coverage_before": before_coverage,
        "coverage_after": after_coverage,
        "details": details,
    }
    after["all_safe_m01_m02_recovery"] = recovery
    return after, recovery


def _synthetic_metric(raw: float | None, reason: str | None, basis: str = "") -> dict[str, Any]:
    return {"raw": raw, "percentile": None, "reason": reason, "basis": basis}


def self_test() -> None:
    # Two records: one remains incomplete because financial M02 is structural,
    # but its M01 must still be recovered; the other recovers M02 only.
    before = {
        "snapshot_date_kst": "2026-09-15",
        "universe_count": 2,
        "coverage": {
            "m01_available": 1,
            "m02_available": 0,
            "m03_available": 2,
            "m04_available": 2,
            "complete_count": 0,
            "ranked_count": 0,
        },
        "reason_counts": {"m01": {}, "m02": {}, "m03": {}, "m04": {}},
        "rules": {"M01": "x", "M02": "y"},
        "records": {
            "000001": {
                "name": "Financial A", "market": "KOSPI", "sector": "보험업", "listing_date": "2000-01-01",
                "m01": _synthetic_metric(None, "DART_NO_REVENUE"),
                "m02": _synthetic_metric(None, "FINANCIAL_SECTOR_EXCLUDED"),
                "m03": _synthetic_metric(10.0, None, "PER"),
                "m04": _synthetic_metric(5.0, None, "PRICE"),
                "composite": None, "rank": None,
            },
            "000002": {
                "name": "Industrial B", "market": "KOSDAQ", "sector": "제조업", "listing_date": "2000-01-01",
                "m01": _synthetic_metric(10.0, None, "DART"),
                "m02": _synthetic_metric(None, "DART_NO_OPERATING_INCOME"),
                "m03": _synthetic_metric(8.0, None, "PER"),
                "m04": _synthetic_metric(3.0, None, "PRICE"),
                "composite": None, "rank": None,
            },
        },
    }
    payload = {
        "financeInfo": {
            "trTitleList": [
                {"key": "202506", "isConsensus": "N"},
                {"key": "202606", "isConsensus": "N"},
            ],
            "rowList": [
                {"title": "매출액", "columns": {"202506": {"value": "100"}, "202606": {"value": "120"}}},
                {"title": "영업이익률", "columns": {"202606": {"value": "7.5"}}},
            ],
        }
    }
    out, rec = apply_recovery(before, {"000001": payload, "000002": payload})
    assert rec["m01_recovered"] == 1
    assert rec["m02_recovered"] == 1
    assert out["records"]["000001"]["m01"]["raw"] == 20.0
    assert out["records"]["000001"]["m02"]["reason"] == "FINANCIAL_SECTOR_EXCLUDED"
    assert out["records"]["000002"]["m02"]["raw"] == 7.5
    assert out["records"]["000001"]["m03"]["raw"] == 10.0
    assert out["records"]["000001"]["m04"]["raw"] == 5.0
    print("ALL_SAFE_M01_M02_RECOVERY_SELF_TEST_PASS")


def main() -> None:
    args = parse_args()
    if args.self_test:
        self_test()
        return

    quant_path = Path(args.quant)
    out_path = Path(args.out) if args.out else quant_path
    before = json.loads(quant_path.read_text(encoding="utf-8"))
    codes = target_codes(before)
    payloads: dict[str, Any] = {}
    errors: dict[str, str] = {}
    for code in codes:
        try:
            payloads[code] = v2.get_json(f"{base.NAVER_BASE}/{code}/finance/quarter")
        except Exception as exc:
            errors[code] = f"{type(exc).__name__}:{exc}"

    if errors:
        raise SystemExit(f"ALL_SAFE_RECOVERY_SOURCE_ERRORS:{json.dumps(errors, ensure_ascii=False)}")

    after, recovery = apply_recovery(before, payloads)
    recovery["source_errors"] = errors
    recovery["source_error_count"] = 0
    after["all_safe_m01_m02_recovery"] = recovery
    out_path.write_text(json.dumps(after, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("ALL_SAFE_M01_M02_RECOVERY_PASS", json.dumps({
        "candidate_count": recovery["candidate_count"],
        "m01_recovered": recovery["m01_recovered"],
        "m02_recovered": recovery["m02_recovered"],
        "promoted_complete_count": recovery["promoted_complete_count"],
        "coverage_before": recovery["coverage_before"],
        "coverage_after": recovery["coverage_after"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
