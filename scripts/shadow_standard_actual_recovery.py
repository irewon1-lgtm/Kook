#!/usr/bin/env python3
"""Shadow recovery for KR4 issuers that can become four-metric complete now.

Scope is deliberately narrow:
- Start only from the promoted 2,649-stock snapshot.
- Consider issuers whose *entire* missing set is M01/M02 (no structural M02,
  no new-listing M04, no EPS gap).
- Use Naver quarterly finance strictly as an actual-period recovery source.
- Reject consensus/forecast and future periods through the already-validated
  fiscal recovery parser.
- Never overwrite existing numeric values.
- Never promote a financial-sector M02 exclusion.
- This script writes shadow evidence only; it never replaces production data.
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
    p.add_argument("--out", default="evidence/standard_actual_recovery_shadow.json")
    return p.parse_args()


def target_codes(quant: dict[str, Any]) -> list[str]:
    out: list[str] = []
    for code, row in quant["records"].items():
        missing = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        if not missing or any(m not in {"m01", "m02"} for m in missing):
            continue
        # A financial M02 exclusion is structural and must never be recovered.
        if row["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
            continue
        valid = True
        for m in missing:
            reason = row[m].get("reason")
            if m == "m01" and reason not in M01_RECOVERABLE:
                valid = False
            if m == "m02" and reason not in M02_RECOVERABLE:
                valid = False
        if valid:
            out.append(code)
    return sorted(out)


def apply_shadow(quant: dict[str, Any], payloads: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    out = deepcopy(quant)
    as_of = date.fromisoformat(str(out["snapshot_date_kst"]))
    candidates = target_codes(out)
    before = dict(out.get("coverage") or {})
    details: dict[str, Any] = {}
    m01_codes: list[str] = []
    m02_codes: list[str] = []

    for code in candidates:
        row = out["records"][code]
        parsed = fiscal.extract_actual_quarter(payloads.get(code), as_of)
        detail: dict[str, Any] = {
            "name": row.get("name"),
            "market": row.get("market"),
            "missing_before": [m.upper() for m in ("m01", "m02") if row[m].get("raw") is None],
        }
        if not parsed:
            detail["result"] = "NO_SAFE_ACTUAL_QUARTER"
            details[code] = detail
            continue

        detail["latest_actual_period"] = parsed["key"]
        detail["prior_year_period"] = parsed["prior_key"]

        m1 = row["m01"]
        if m1.get("raw") is None and m1.get("reason") in M01_RECOVERABLE:
            yoy = fiscal.safe_revenue_yoy(parsed.get("revenue"), parsed.get("prior_revenue"))
            if yoy is not None:
                m1["raw"] = yoy
                m1["reason"] = None
                m1["basis"] = f"{parsed['key']}_NAVER_QUARTER_ACTUAL_YOY_SHADOW"
                m01_codes.append(code)
                detail["m01"] = yoy

        m2 = row["m02"]
        if m2.get("raw") is None and m2.get("reason") in M02_RECOVERABLE:
            margin = recent._finite(parsed.get("operating_margin"))
            if margin is not None and math.isfinite(margin) and abs(margin) <= 10000:
                m2["raw"] = round(margin, 6)
                m2["reason"] = None
                m2["basis"] = f"{parsed['key']}_NAVER_QUARTER_ACTUAL_OPM_SHADOW"
                m02_codes.append(code)
                detail["m02"] = round(margin, 6)

        missing_after = [m for m in ("m01", "m02", "m03", "m04") if row[m].get("raw") is None]
        detail["missing_after"] = [m.upper() for m in missing_after]
        detail["result"] = "PROMOTED_COMPLETE" if not missing_after else "PARTIAL"
        details[code] = detail

    after = recent.recompute_scores(out["records"])
    out["coverage"] = after
    promoted = sorted(code for code in candidates if out["records"][code].get("composite") is not None)

    # Non-overwrite and structural guards.
    for code, old in quant["records"].items():
        new = out["records"][code]
        for mid in ("m01", "m02", "m03", "m04"):
            if old[mid].get("raw") is not None:
                assert new[mid].get("raw") == old[mid].get("raw"), (code, mid, "raw overwrite")
                assert new[mid].get("basis") == old[mid].get("basis"), (code, mid, "basis overwrite")
        if old["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
            assert new["m02"].get("raw") is None
            assert new["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED"

    recent.validate_snapshot(out)
    assert int(after["complete_count"]) >= int(before["complete_count"])
    assert int(after["ranked_count"]) == int(after["complete_count"])

    summary = {
        "policy": "completion-candidate M01/M02 only; Naver actual quarters; forecast/future rejected; shadow only",
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
    return out, summary


def main() -> None:
    args = parse_args()
    quant = json.loads(Path(args.quant).read_text(encoding="utf-8"))
    candidates = target_codes(quant)
    payloads: dict[str, Any] = {}
    errors: dict[str, str] = {}
    for code in candidates:
        try:
            payloads[code] = v2.get_json(f"{base.NAVER_BASE}/{code}/finance/quarter")
        except Exception as exc:
            errors[code] = f"{type(exc).__name__}:{exc}"

    _shadow, summary = apply_shadow(quant, payloads)
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
