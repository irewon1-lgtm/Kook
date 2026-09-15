#!/usr/bin/env python3
"""KR4 collector v5: gap-aware M04 corporate-action engine.

This wrapper keeps the validated v3 DART/M01/M02/M03 plumbing while replacing
only the Naver daily-price semantics used by M04 corporate-action detection.

Production guarantees:
- Explicit Naver direction metadata normalizes change/rate signs.
- A KOSPI trading-session calendar is loaded once for the six-month window.
- A raw-close/reference-return mismatch is considered a corporate-action reset
  only when the two stock bars are consecutive KRX sessions.
- Missing intermediate stock bars are never misclassified as corporate actions.
- M04 uses six-month endpoint closes, adjusted only by verified reset ratios;
  it no longer compounds every daily metadata row, so one missing intermediate
  bar cannot silently delete a day's investment return.
- Unresolved split-sized reset metadata fails closed.
- Notice labels are accepted only when an actual date is within 7 calendar days
  of the detected reset; stale/no-date notices cannot relabel an event.
- M03 corporate-action EPS/PER cross-check remains the v2 fail-closed policy and
  is invoked only for the resulting gap-safe action events.
"""
from __future__ import annotations

import bisect
import json
import math
from datetime import date
from pathlib import Path
from typing import Any

import collect_real_quant as base
import collect_real_quant_v2 as v2
import collect_real_quant_v3 as v3

_INDEX_PRICE_URL = "https://m.stock.naver.com/api/index/KOSPI/price"
_MARKET_DATES: list[date] = []
_MARKET_DATE_SET: set[date] = set()

_UP_CODES = {"1", "2"}
_FLAT_CODES = {"3"}
_DOWN_CODES = {"4", "5"}
_UP_WORDS = {"RISING", "HIGHER", "UP", "UPPER_LIMIT", "LIMIT_UP", "상승", "상한"}
_FLAT_WORDS = {"UNCHANGED", "SAME", "FLAT", "보합"}
_DOWN_WORDS = {"FALLING", "LOWER", "DOWN", "LOWER_LIMIT", "LIMIT_DOWN", "하락", "하한"}


def _direction_sign(bar: dict[str, Any]) -> int | None:
    raw = bar.get("compareToPreviousPrice")
    code = ""
    tokens: set[str] = set()
    if isinstance(raw, dict):
        code = str(raw.get("code") or "").strip()
        for key in ("name", "text"):
            value = str(raw.get(key) or "").strip()
            if value:
                tokens.add(value.upper())
                tokens.add(value)
    else:
        token = str(raw or "").strip()
        if token.isdigit():
            code = token
        elif token:
            tokens.add(token.upper())
            tokens.add(token)

    if code in _DOWN_CODES or tokens & _DOWN_WORDS:
        return -1
    if code in _UP_CODES or tokens & _UP_WORDS:
        return 1
    if code in _FLAT_CODES or tokens & _FLAT_WORDS:
        return 0
    return None


def _daily_reference_factor(bar: dict[str, Any], close: float) -> float | None:
    """One-session Naver reference factor with ambiguity-safe direction."""
    sign = _direction_sign(bar)
    ratio = base.parse_number(bar.get("fluctuationsRatio"))
    if ratio is not None and sign is not None:
        ratio = 0.0 if sign == 0 else abs(ratio) * sign
    ratio_factor = None
    if ratio is not None:
        candidate = 1.0 + ratio / 100.0
        if v2._valid_daily_factor(candidate):
            ratio_factor = candidate

    change = base.parse_number(bar.get("compareToPreviousClosePrice"))
    if change is not None and sign is not None:
        change = 0.0 if sign == 0 else abs(change) * sign
    elif sign is None:
        # This field can be a magnitude. Without explicit direction do not infer
        # its sign; the already-signed percentage remains the safer source.
        change = None

    change_factor = None
    if change is not None:
        reference_price = close - change
        if reference_price > 0:
            candidate = close / reference_price
            if v2._valid_daily_factor(candidate):
                change_factor = candidate

    if change_factor is not None and ratio_factor is not None:
        return change_factor if abs(change_factor - ratio_factor) <= 0.005 else ratio_factor
    return ratio_factor if ratio_factor is not None else change_factor


def _set_market_calendar(days: set[date] | list[date]) -> None:
    global _MARKET_DATES, _MARKET_DATE_SET
    _MARKET_DATES = sorted(set(days))
    _MARKET_DATE_SET = set(_MARKET_DATES)


def _load_market_calendar(cutoff: date, six_month_target: date) -> None:
    """Load official KOSPI session dates once; fail closed if coverage is weak."""
    days: set[date] = set()
    for page in range(1, 6):
        bars = v2.get_json(f"{_INDEX_PRICE_URL}?pageSize=60&page={page}")
        if not isinstance(bars, list):
            raise RuntimeError("KRX_CALENDAR_NOT_LIST")
        if not bars:
            break
        for bar in bars:
            if not isinstance(bar, dict):
                continue
            d = base.parse_date(str(bar.get("localTradedAt") or ""))
            p = base.parse_number(bar.get("closePrice"))
            if d and p is not None and p > 0 and d <= cutoff:
                days.add(d)
        if days and min(days) <= six_month_target:
            break
        if len(bars) < 60:
            break
    ordered = sorted(days)
    if not ordered or ordered[-1] != cutoff or ordered[0] > six_month_target:
        raise RuntimeError(
            f"KRX_CALENDAR_COVERAGE_INVALID:{ordered[0] if ordered else None}:{ordered[-1] if ordered else None}"
        )
    _set_market_calendar(days)


def _is_consecutive_market_session(previous: date, current: date) -> bool:
    if not _MARKET_DATES:
        # Deterministic unit tests that do not install a market calendar keep
        # legacy adjacency semantics. Production v5 always loads the calendar.
        return True
    if current not in _MARKET_DATE_SET or previous not in _MARKET_DATE_SET:
        return False
    pos = bisect.bisect_left(_MARKET_DATES, current)
    return pos > 0 and _MARKET_DATES[pos - 1] == previous


def _detect_reference_reset_events(
    by_date: dict[date, float],
    by_factor: dict[date, float | None],
    start_date: date,
    end_date: date,
) -> list[dict[str, Any]]:
    """Detect reference resets only across consecutive KRX sessions."""
    dates = sorted(d for d in by_date if start_date <= d <= end_date)
    events: list[dict[str, Any]] = []
    if len(dates) < 2:
        return events

    previous = dates[0]
    for current in dates[1:]:
        prev_close = by_date[previous]
        close = by_date[current]
        raw_factor = close / prev_close if prev_close > 0 else None
        adjusted_factor = by_factor.get(current)

        # Critical guard: a one-session Naver ratio cannot be compared with a
        # multi-session raw close ratio. This was the source of the 2026-09-15
        # 546-stock false-positive cluster when an intermediate bar was absent.
        if not _is_consecutive_market_session(previous, current):
            previous = current
            continue

        if raw_factor is None or not math.isfinite(raw_factor) or raw_factor <= 0:
            previous = current
            continue
        if adjusted_factor is None:
            if not v2._valid_daily_factor(raw_factor):
                events.append({
                    "date": current,
                    "previous_date": previous,
                    "reset_ratio": None,
                    "raw_factor": raw_factor,
                    "adjusted_factor": None,
                    "type": "UNRESOLVED_REFERENCE_RESET",
                })
            previous = current
            continue
        if not v2._valid_daily_factor(adjusted_factor):
            previous = current
            continue

        reset_ratio = raw_factor / adjusted_factor
        if math.isfinite(reset_ratio) and reset_ratio > 0 and abs(reset_ratio - 1.0) >= v2._REFERENCE_RESET_REL_TOL:
            family = "SPLIT_OR_BONUS_ISSUE" if reset_ratio < 1.0 else "REVERSE_SPLIT_OR_CAPITAL_REDUCTION"
            events.append({
                "date": current,
                "previous_date": previous,
                "reset_ratio": reset_ratio,
                "raw_factor": raw_factor,
                "adjusted_factor": adjusted_factor,
                "type": family,
            })
        previous = current
    return events


def _annotate_action_types(code: str, events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Tightly label a reset; stale/no-date notices are never accepted."""
    if not events:
        return events
    try:
        payload = v2.get_json(f"{v2._NOTICE_BASE}?itemCode={code}&startIdx=0&pageSize=100")
    except Exception:
        return events

    nodes: list[tuple[str, list[date]]] = []
    for node in v2._iter_dict_nodes(payload):
        scalars = [
            f"{k}={value}"
            for k, value in node.items()
            if isinstance(value, (str, int, float, bool)) or value is None
        ]
        text = " ".join(scalars)
        label = v2._notice_action_label(text)
        dates = v2._notice_dates(text)
        if label and dates:
            nodes.append((label, dates))

    for event in events:
        event_date = event["date"]
        reset_ratio = event.get("reset_ratio")
        best: tuple[int, str] | None = None
        for label, notice_dates in nodes:
            if not v2._direction_compatible(label, reset_ratio):
                continue
            distance = min(abs((d - event_date).days) for d in notice_dates)
            if distance > 7:
                continue
            if best is None or distance < best[0]:
                best = (distance, label)
        if best is not None:
            event["type"] = best[1]
    return events


def _corporate_action_adjusted_return(
    by_date: dict[date, float],
    by_factor: dict[date, float | None],
    start_date: date,
    end_date: date,
) -> tuple[float | None, str | None, int, int]:
    """Endpoint close return adjusted only by verified reference resets.

    Missing intermediate bars no longer erase their economic returns. A reset
    ratio is raw one-session factor / Naver reference-return factor, therefore
    dividing endpoint raw growth by each reset ratio removes split/merge unit
    changes while preserving actual investment performance.
    """
    if start_date not in by_date or end_date not in by_date:
        return None, "NAVER_ADJUSTED_RETURN_UNAVAILABLE", 0, 0
    start_close = by_date[start_date]
    end_close = by_date[end_date]
    if start_close <= 0 or end_close <= 0:
        return None, "NAVER_ADJUSTED_RETURN_INVALID", 0, 0

    events = _detect_reference_reset_events(by_date, by_factor, start_date, end_date)
    growth = end_close / start_close
    if not math.isfinite(growth) or growth <= 0:
        return None, "NAVER_ADJUSTED_RETURN_INVALID", len(events), 0

    for event in events:
        reset_ratio = event.get("reset_ratio")
        if reset_ratio is None or not math.isfinite(float(reset_ratio)) or float(reset_ratio) <= 0:
            return None, "NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING", len(events), 0
        growth /= float(reset_ratio)
        if not math.isfinite(growth) or growth <= 0 or growth > 1_000_000:
            return None, "NAVER_ADJUSTED_RETURN_INVALID", len(events), 0

    value = (growth - 1.0) * 100.0
    if not math.isfinite(value) or abs(value) > 100000:
        return None, "NAVER_RETURN_OUTLIER_GUARD", len(events), 0
    return value, None, len(events), 0


def _install_patches() -> None:
    v2._daily_reference_factor = _daily_reference_factor
    v2._detect_reference_reset_events = _detect_reference_reset_events
    v2._annotate_action_types = _annotate_action_types
    v2._corporate_action_adjusted_return = _corporate_action_adjusted_return


def main() -> None:
    # Resolve the same auto dates v3 will use, then install a six-month KRX
    # calendar before the 2,649 worker pool starts.
    args = v3.parse_args_auto_cached()
    cutoff = date.fromisoformat(str(args.price_cutoff))
    six_month_target = base.subtract_six_months(cutoff)
    _install_patches()
    _load_market_calendar(cutoff, six_month_target)
    v3.main()

    # Extra provenance is ignored by old APKs but makes the remote engine audit
    # explicit without changing the app's required collector=v3 compatibility.
    evidence = Path(base.ROOT) / args.evidence_out
    payload = json.loads(evidence.read_text(encoding="utf-8"))
    payload.setdefault("auto_update", {})["m04_engine"] = "collect_real_quant_v5_gap_aware_endpoint_close"
    payload["auto_update"]["m04_calendar"] = "KOSPI completed-session calendar"
    evidence.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("M04_GAP_AWARE_V5_PASS", json.dumps({"market_sessions": len(_MARKET_DATES)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
