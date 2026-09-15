#!/usr/bin/env python3
"""KR4 standard actual recovery V3: safe residual M01/M02 + liquidation-trading M04 recovery.

This entrypoint reuses the validated standard-actual core resolver, broadens M01/M02
recovery to every per-metric-safe residual cell, and repairs one specific M04 failure
mode: price-limit-free liquidation trading that was previously mistaken for an
unadjusted split/capital-reduction discontinuity.

Safety contract:
- M01 is resolved only when its own reason is in M01_RECOVERABLE;
- M02 is resolved only when its own reason is in M02_RECOVERABLE;
- FINANCIAL_SECTOR_EXCLUDED is never touched, while finance-sector M01 may recover;
- existing numeric values remain immutable except prior STANDARD_RECOVERY M02 upgrades;
- M03 is immutable;
- M04 changes only from NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING and only when:
  (a) a Naver notice explicitly contains liquidation-trading language,
  (b) the unresolved reference-reset date is on/after a liquidation notice date and
      no more than 45 days later, and
  (c) the event has no computed corporate-action reset ratio;
- real split/reverse-split/bonus-issue/capital-reduction adjustments stay authoritative;
- all source failures remain fail-closed.
"""
from __future__ import annotations

import math
import re
import sys
from copy import deepcopy
from datetime import date
from typing import Any

import collect_real_quant_v2 as v2
import standard_actual_core as core


M04_LIQUIDATION_REASON = "NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING"
_LIQUIDATION_NOTICE_MAX_AGE_DAYS = 45
_WINDOW_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})->(\d{4}-\d{2}-\d{2})_")


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


def _parse_m04_window(row: dict[str, Any]) -> tuple[date, date] | None:
    basis = str(row.get("m04", {}).get("basis") or "")
    match = _WINDOW_RE.match(basis)
    if not match:
        return None
    try:
        return date.fromisoformat(match.group(1)), date.fromisoformat(match.group(2))
    except ValueError:
        return None


def _scalar_node_text(node: dict[str, Any]) -> str:
    parts: list[str] = []
    for key, value in node.items():
        if isinstance(value, (str, int, float, bool)) or value is None:
            parts.append(f"{key}={value}")
    return " ".join(parts)


def _liquidation_notice_dates(code: str) -> list[date]:
    """Return dated Naver notice nodes that explicitly describe liquidation trading.

    Fail closed: undated notices are not enough to authorize unlimited raw moves.
    """
    payload = v2.get_json(f"{v2._NOTICE_BASE}?itemCode={code}&startIdx=0&pageSize=100")
    dates: set[date] = set()
    for node in v2._iter_dict_nodes(payload):
        text = _scalar_node_text(node)
        compact = re.sub(r"\s+", "", text)
        if "정리매매" not in compact:
            continue
        if "상장폐지" not in compact and "매매거래" not in compact and "거래정지" not in compact:
            continue
        for dt in v2._notice_dates(text):
            dates.add(dt)
    return sorted(dates)


def _is_liquidation_event(event_date: date, notice_dates: list[date]) -> bool:
    return any(
        0 <= (event_date - notice_date).days <= _LIQUIDATION_NOTICE_MAX_AGE_DAYS
        for notice_date in notice_dates
    )


def _liquidation_adjusted_return(
    by_date: dict[date, float],
    by_factor: dict[date, float | None],
    start_date: date,
    end_date: date,
    notice_dates: list[date],
) -> tuple[float | None, str | None, int, int, int, list[dict[str, Any]]]:
    """Compound M04 while allowing raw out-of-band moves only during verified liquidation trading.

    A genuine corporate action with a usable adjusted reference factor keeps that factor.
    Only unresolved events (reset_ratio is None) can be reclassified as liquidation trading.
    """
    dates = sorted(d for d in by_date if start_date <= d <= end_date)
    if not dates or dates[0] != start_date or dates[-1] != end_date:
        return None, "NAVER_ADJUSTED_RETURN_UNAVAILABLE", 0, 0, 0, []
    if len(dates) < 2:
        return None, "PRICE_HISTORY_SHORTER_THAN_6M", 0, 0, 0, []

    events = v2._detect_reference_reset_events(by_date, by_factor, start_date, end_date)
    liquidation_dates: set[date] = set()
    for event in events:
        event_date = event["date"]
        if event.get("reset_ratio") is None and _is_liquidation_event(event_date, notice_dates):
            event["type"] = "LIQUIDATION_TRADING"
            liquidation_dates.add(event_date)

    if not liquidation_dates:
        return None, M04_LIQUIDATION_REASON, len(events), 0, 0, events

    growth = 1.0
    raw_fallback_days = 0
    liquidation_fallback_days = 0
    prev_close = by_date[dates[0]]

    for dt in dates[1:]:
        close = by_date[dt]
        raw_factor = close / prev_close if prev_close > 0 else None
        adjusted_factor = by_factor.get(dt)

        if adjusted_factor is None:
            raw_ok = raw_factor is not None and math.isfinite(raw_factor) and raw_factor > 0
            if dt in liquidation_dates:
                if not raw_ok:
                    return None, "LIQUIDATION_RAW_RETURN_INVALID", len(events), raw_fallback_days, liquidation_fallback_days, events
                adjusted_factor = raw_factor
                raw_fallback_days += 1
                liquidation_fallback_days += 1
            else:
                if not v2._valid_daily_factor(raw_factor):
                    return None, M04_LIQUIDATION_REASON, len(events), raw_fallback_days, liquidation_fallback_days, events
                adjusted_factor = raw_factor
                raw_fallback_days += 1
        elif not v2._valid_daily_factor(adjusted_factor):
            return None, "NAVER_ADJUSTED_RETURN_INVALID", len(events), raw_fallback_days, liquidation_fallback_days, events

        growth *= adjusted_factor
        if not math.isfinite(growth) or growth <= 0 or growth > 1_000_000:
            return None, "NAVER_ADJUSTED_RETURN_INVALID", len(events), raw_fallback_days, liquidation_fallback_days, events
        prev_close = close

    value = (growth - 1.0) * 100.0
    if not math.isfinite(value) or abs(value) > 100000:
        return None, "NAVER_RETURN_OUTLIER_GUARD", len(events), raw_fallback_days, liquidation_fallback_days, events
    return value, None, len(events), raw_fallback_days, liquidation_fallback_days, events


def _recover_m04_liquidations(before: dict[str, Any], after: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    recovered: list[str] = []
    details: dict[str, Any] = {}

    for code, old_row in before["records"].items():
        old_m04 = old_row["m04"]
        if old_m04.get("raw") is not None or old_m04.get("reason") != M04_LIQUIDATION_REASON:
            continue

        detail: dict[str, Any] = {
            "name": old_row.get("name"),
            "reason_before": old_m04.get("reason"),
            "basis_before": old_m04.get("basis"),
        }
        window = _parse_m04_window(old_row)
        if window is None:
            detail["result"] = "RETAINED_WINDOW_UNPARSEABLE"
            details[code] = detail
            continue
        start_date, end_date = window

        try:
            notice_dates = _liquidation_notice_dates(code)
        except Exception as exc:
            detail["result"] = f"RETAINED_NOTICE_ERROR:{type(exc).__name__}"
            details[code] = detail
            continue

        detail["liquidation_notice_dates"] = [d.isoformat() for d in notice_dates]
        if not notice_dates:
            detail["result"] = "RETAINED_NO_DATED_LIQUIDATION_NOTICE"
            details[code] = detail
            continue

        by_date, by_factor, price_error = v2._parse_price_bars(code, end_date, start_date)
        detail["price_error"] = price_error
        value, reason, action_days, fallback_days, liquidation_days, events = _liquidation_adjusted_return(
            by_date,
            by_factor,
            start_date,
            end_date,
            notice_dates,
        )
        detail["action_days"] = action_days
        detail["raw_fallback_days"] = fallback_days
        detail["liquidation_fallback_days"] = liquidation_days
        detail["events"] = [
            {
                "date": event["date"].isoformat(),
                "type": event.get("type"),
                "reset_ratio": event.get("reset_ratio"),
            }
            for event in events
        ]

        if value is None:
            detail["result"] = f"RETAINED_{reason or 'UNRESOLVED'}"
            details[code] = detail
            continue

        tokens = v2._format_action_event_tokens(events)
        new_m04 = after["records"][code]["m04"]
        new_m04.update(
            raw=round(value, 6),
            reason=None,
            basis=(
                f"{start_date.isoformat()}->{end_date.isoformat()}_"
                f"KRX_ADJ_DAILY_CA{action_days}_FB{fallback_days}_LIQ{liquidation_days}{tokens}"
            ),
        )
        recovered.append(code)
        detail["result"] = "RECOVERED_LIQUIDATION_TRADING"
        detail["raw_after"] = new_m04["raw"]
        detail["basis_after"] = new_m04["basis"]
        details[code] = detail

    return sorted(recovered), details


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
                m02.update(raw=r2["value"], reason=None, basis=core._m02_basis(r2))
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

    m04_liquidation_codes, m04_liquidation_details = _recover_m04_liquidations(before, after)

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
        if old["m03"].get("raw") is None:
            assert new["m03"].get("raw") is None, (code, "m03 changed")

    before_coverage = dict(before["coverage"])
    after_coverage = core.recent.recompute_scores(after["records"])
    after["coverage"] = after_coverage
    core._rebuild_metadata(after)
    core.recent.validate_snapshot(after)

    promoted = sorted(
        code for code in set(candidates) | set(m04_liquidation_codes)
        if before["records"][code].get("composite") is None
        and after["records"][code].get("composite") is not None
    )
    recovery = {
        "policy": (
            "all safe residual M01/M02 cells plus verified liquidation-trading M04 recovery; "
            "completed Naver actual periods; per-metric reason gates; finance M02 structural exclusion preserved; "
            "finite direct actual OPM authoritative; computed fallback abs<=10000; "
            "M03 immutable; M04 raw out-of-band moves allowed only for dated liquidation notices and unresolved resets"
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
        "m04_liquidation_recovered": len(m04_liquidation_codes),
        "m04_liquidation_codes": m04_liquidation_codes,
        "m04_liquidation_details": m04_liquidation_details,
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
    assert after_coverage["m04_available"] >= before_coverage["m04_available"]
    assert after_coverage["m01_available"] >= before_coverage["m01_available"]
    assert after_coverage["m02_available"] >= before_coverage["m02_available"]
    assert after_coverage["complete_count"] >= before_coverage["complete_count"]
    assert after_coverage["ranked_count"] == after_coverage["complete_count"]
    return after, recovery


def install() -> None:
    core.target_codes = target_codes
    core.apply_recovery = apply_recovery


def wrapper_self_test() -> None:
    core.self_test()
    q = {
        "records": {
            "FIN001": {
                "m01": {"raw": None, "reason": "DART_NO_REVENUE"},
                "m02": {"raw": None, "reason": "FINANCIAL_SECTOR_EXCLUDED"},
                "m03": {"raw": 1.0, "reason": None},
                "m04": {"raw": None, "reason": "PRICE_HISTORY_SHORTER_THAN_6M"},
            },
            "M02001": {
                "m01": {"raw": 1.0, "reason": None},
                "m02": {"raw": None, "reason": "DART_NO_OPERATING_INCOME"},
                "m03": {"raw": None, "reason": "NAVER_EPS_MISSING"},
                "m04": {"raw": None, "reason": "PRICE_HISTORY_SHORTER_THAN_6M"},
            },
            "NA0001": {
                "m01": {"raw": 1.0, "reason": None},
                "m02": {"raw": None, "reason": "FINANCIAL_SECTOR_EXCLUDED"},
                "m03": {"raw": 1.0, "reason": None},
                "m04": {"raw": 1.0, "reason": None},
            },
        }
    }
    assert target_codes(q) == ["FIN001", "M02001"], target_codes(q)

    d1 = date(2026, 3, 13)
    d2 = date(2026, 9, 4)
    d3 = date(2026, 9, 10)
    d4 = date(2026, 9, 15)
    by_date = {d1: 100.0, d2: 800.0, d3: 8.0, d4: 4.0}
    by_factor = {d1: 1.0, d2: 1.0, d3: None, d4: None}
    value, reason, _, _, liq_days, events = _liquidation_adjusted_return(
        by_date, by_factor, d1, d4, [date(2026, 9, 8)]
    )
    assert reason is None and value is not None
    assert liq_days == 2
    assert any(e.get("type") == "LIQUIDATION_TRADING" for e in events)

    value2, reason2, *_ = _liquidation_adjusted_return(
        {d1: 100.0, d2: 800.0},
        {d1: 1.0, d2: None},
        d1,
        d2,
        [date(2026, 9, 8)],
    )
    assert value2 is None and reason2 == M04_LIQUIDATION_REASON

    print("STANDARD_ACTUAL_RECOVERY_V3_WRAPPER_SELF_TEST_PASS")


def main() -> None:
    install()
    if "--self-test" in sys.argv:
        wrapper_self_test()
        return
    core.main()


if __name__ == "__main__":
    main()
