#!/usr/bin/env python3
"""Broaden KR4 standard recovery to every safe residual M01/M02 cell.

This wrapper reuses the validated standard recovery core but removes the old
"immediately four-metric-completable only" restriction. It still preserves all
structural N/A rules and all existing numeric values.

Key safety differences from the old entrypoint:
- a row is eligible when either M01 or M02 has an explicitly recoverable reason,
  even if M03/M04 or the other metric remain structurally unavailable;
- M01 is resolved only when its own reason is in M01_RECOVERABLE;
- M02 is resolved only when its own reason is in M02_RECOVERABLE;
- FINANCIAL_SECTOR_EXCLUDED is therefore never touched while finance-sector M01
  can still be recovered;
- M03/M04 stay immutable.
"""
from __future__ import annotations

from copy import deepcopy
from datetime import date
from typing import Any

import apply_standard_actual_recovery as core


def target_codes(quant: dict[str, Any]) -> list[str]:
    result: list[str] = []
    for code, row in quant["records"].items():
        m01 = row["m01"]
        m02 = row["m02"]
        need_m01 = m01.get("raw") is None and m01.get("reason") in core.M01_RECOVERABLE
        need_m02 = m02.get("raw") is None and m02.get("reason") in core.M02_RECOVERABLE
        if need_m01 or need_m02:
            result.append(code)
    return sorted(result)


def apply_recovery(before: dict[str, Any], payloads: dict[str, Any]):
    after = deepcopy(before)
    as_of = date.fromisoformat(str(before["snapshot_date_kst"]))
    candidates = target_codes(before)
    upgrade_candidates = core.standard_m02_upgrade_codes(before)
    upgrade_candidate_set = set(upgrade_candidates)
    details: dict[str, Any] = {}
    m01_codes: list[str] = []
    m02_codes: list[str] = []
    m02_upgraded_codes: list[str] = []

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
        if m01.get("raw") is None and m01.get("reason") in core.M01_RECOVERABLE:
            r1 = core.resolve_m01(payload, as_of)
            detail["m01_resolver"] = r1
            if r1 and r1.get("value") is not None:
                m01.update(
                    raw=r1["value"],
                    reason=None,
                    basis=f"{r1['key']}_NAVER_QUARTER_ACTUAL_YOY_STANDARD_RECOVERY",
                )
                m01_codes.append(code)

        m02 = row["m02"]
        if m02.get("raw") is None and m02.get("reason") in core.M02_RECOVERABLE:
            r2 = core.resolve_m02(payload, as_of)
            detail["m02_resolver"] = r2
            if r2 and r2.get("value") is not None:
                m02.update(
                    raw=r2["value"],
                    reason=None,
                    basis=core._m02_basis(r2),
                )
                m02_codes.append(code)

        missing_after = [
            m for m in ("m01", "m02", "m03", "m04")
            if row[m].get("raw") is None
        ]
        detail["missing_after"] = [m.upper() for m in missing_after]
        detail["result"] = "PROMOTED_COMPLETE" if not missing_after else "PARTIAL"
        details[code] = detail

    for code in upgrade_candidates:
        old_m02 = before["records"][code]["m02"]
        new_m02 = after["records"][code]["m02"]
        r2 = core.resolve_m02(payloads.get(code), as_of)
        detail = details.setdefault(code, {
            "name": after["records"][code].get("name"),
            "market": after["records"][code].get("market"),
            "sector": after["records"][code].get("sector"),
        })
        detail["m02_upgrade_resolver"] = r2
        if not r2 or r2.get("value") is None:
            detail["m02_upgrade_result"] = "RETAINED_PRIOR_STANDARD_RECOVERY"
            continue
        new_basis = core._m02_basis(r2)
        new_value = r2["value"]
        if new_m02.get("raw") != new_value or new_m02.get("basis") != new_basis:
            new_m02.update(raw=new_value, reason=None, basis=new_basis)
            m02_upgraded_codes.append(code)
            detail["m02_upgrade_result"] = "UPGRADED"
            detail["m02_before"] = {
                "raw": old_m02.get("raw"),
                "basis": old_m02.get("basis"),
            }
            detail["m02_after"] = {"raw": new_value, "basis": new_basis}
        else:
            detail["m02_upgrade_result"] = "ALREADY_CURRENT"

    for code, old in before["records"].items():
        new = after["records"][code]
        for mid in ("m01", "m02", "m03", "m04"):
            if old[mid].get("raw") is None:
                continue
            if mid == "m02" and code in upgrade_candidate_set:
                continue
            assert new[mid].get("raw") == old[mid].get("raw"), (code, mid, "raw overwrite")
            assert new[mid].get("basis") == old[mid].get("basis"), (code, mid, "basis overwrite")
        if old["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
            assert new["m02"].get("raw") is None
            assert new["m02"].get("reason") == "FINANCIAL_SECTOR_EXCLUDED"

    before_coverage = dict(before["coverage"])
    after_coverage = core.recent.recompute_scores(after["records"])
    after["coverage"] = after_coverage
    core._rebuild_metadata(after)
    core.recent.validate_snapshot(after)

    promoted = sorted(
        code for code in candidates
        if before["records"][code].get("composite") is None
        and after["records"][code].get("composite") is not None
    )
    recovery = {
        "policy": (
            "all safe residual M01/M02 cells; completed Naver actual periods; "
            "per-metric reason gates; finance M02 structural exclusion preserved; "
            "finite direct actual OPM authoritative; computed fallback abs<=10000; "
            "M03/M04 immutable"
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


def install() -> None:
    core.target_codes = target_codes
    core.apply_recovery = apply_recovery


def main() -> None:
    install()
    core.main()


if __name__ == "__main__":
    main()
