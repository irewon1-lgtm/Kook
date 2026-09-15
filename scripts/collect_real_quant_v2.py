#!/usr/bin/env python3
"""KR4 real-data collector v2.

Overrides the Naver leg of collect_real_quant.py. Naver's stock price history
endpoint caps pageSize at ~60, so six-month history is paged in 60-row chunks.

M03 actual-earnings PER policy:
1) Prefer Naver integration's reported positive trailing PER when present.
2) Otherwise divide the last completed-session close by actual/non-consensus EPS.
3) If integration EPS is missing, retry with the latest non-consensus EPS from
   Naver's annual finance payload. Consensus/forward EPS is never used.
4) Negative actual EPS produces a negative raw PER instead of a missing metric.
   Negative PER is a loss-state marker, not a cheap-valuation signal: its M03
   percentile is fixed at 0. Positive PER keeps the normal lower-is-better rank.
5) Exactly zero EPS remains unavailable because price / 0 is undefined.
6) If a reference-price reset is detected in the six-month window, M03 must also
   pass a second Naver polling-EPS cross-check. A mismatch or missing cross-check
   fails closed rather than combining a post-action price with stale EPS.

M04 / corporate-action policy:
1) M04 is computed from Naver legacy adjusted historical closes.
2) Mobile raw close history is used only to compare raw/adjusted price scale.
3) A stable scale-regime change identifies split/merge/bonus/reduction effects;
   mobile fluctuationsRatio is never treated as the corporate-action baseline.
4) Corporate-action stocks must pass the M03 polling-EPS/PER cross-check.
5) Legacy adjusted-price failure leaves M04 unavailable rather than guessing.
"""
from __future__ import annotations

import ast
import math
import re
import statistics
import time
from datetime import date, timedelta
from typing import Any
from urllib.parse import urlencode

import collect_real_quant as base


# KRX's ordinary daily price limit is +/-30%. The small buffer absorbs tick and
# percentage rounding while still rejecting split/reverse-split discontinuities.
_DAILY_FACTOR_MIN = 0.69
_DAILY_FACTOR_MAX = 1.31
# A normal session's raw close factor and KRX-reference factor should be the
# same. A >=1.5% reset is far beyond rounding noise and is treated as a material
# corporate-action/reference-price event even if the raw move remains inside
# the +/-30% daily band.
_REFERENCE_RESET_REL_TOL = 0.015
_M03_XCHECK_EPS_REL_TOL = 0.02
_M03_XCHECK_PER_REL_TOL = 0.08
_NOTICE_BASE = "https://stock.naver.com/api/domestic/detail/notice"
_POLLING_BASE = "https://polling.finance.naver.com/api/realtime"
_LEGACY_PRICE_BASE = "https://api.finance.naver.com/siseJson.naver"
_SCALE_RESET_REL_TOL = 0.015
_SCALE_STRONG_RATIO = 1.08
_SCALE_STABILITY_REL_TOL = 0.006
_SCALE_WINDOW = 3


def get_json(url: str, retries: int = 3) -> Any:
    sess = base.naver_session()
    last: Exception | None = None
    for attempt in range(retries):
        try:
            r = sess.get(url, timeout=12)
            if r.status_code == 200:
                return r.json()
            if r.status_code in (400, 404, 409):
                raise LookupError(f"HTTP_{r.status_code}")
            if r.status_code == 429 or r.status_code >= 500:
                retry_after = r.headers.get("Retry-After")
                delay = float(retry_after) if retry_after and retry_after.isdigit() else 0.35 * (2**attempt)
                time.sleep(min(delay, 3.0))
                continue
            r.raise_for_status()
        except LookupError:
            raise
        except Exception as exc:
            last = exc
            if attempt + 1 < retries:
                time.sleep(0.25 * (2**attempt))
    raise RuntimeError(type(last).__name__ if last else "NAVER_RETRY_EXHAUSTED")



def get_text(url: str, retries: int = 3) -> str:
    sess = base.naver_session()
    last: Exception | None = None
    for attempt in range(retries):
        try:
            r = sess.get(url, timeout=15, headers={"Referer": "https://finance.naver.com/"})
            if r.status_code == 200:
                return r.text
            if r.status_code in (400, 404, 409):
                raise LookupError(f"HTTP_{r.status_code}")
            if r.status_code == 429 or r.status_code >= 500:
                time.sleep(min(0.35 * (2**attempt), 3.0))
                continue
            r.raise_for_status()
        except LookupError:
            raise
        except Exception as exc:
            last = exc
            if attempt + 1 < retries:
                time.sleep(0.25 * (2**attempt))
    raise RuntimeError(type(last).__name__ if last else "NAVER_TEXT_RETRY_EXHAUSTED")


def _fetch_legacy_adjusted_closes(
    code: str,
    start: date,
    end: date,
) -> tuple[dict[date, float], str | None]:
    params = urlencode({
        "symbol": code,
        "requestType": 1,
        "startTime": start.strftime("%Y%m%d"),
        "endTime": end.strftime("%Y%m%d"),
        "timeframe": "day",
    })
    try:
        raw = get_text(f"{_LEGACY_PRICE_BASE}?{params}")
        cleaned = "\n".join(line.strip() for line in raw.splitlines() if line.strip())
        rows = ast.literal_eval(cleaned)
        if not isinstance(rows, list) or len(rows) < 2:
            return {}, "LEGACY_PRICE_EMPTY"
        header = [str(x).strip() for x in rows[0]]
        try:
            date_idx = header.index("날짜")
            close_idx = header.index("종가")
        except ValueError:
            return {}, "LEGACY_PRICE_HEADER_MISSING"
        out: dict[date, float] = {}
        for row in rows[1:]:
            if not isinstance(row, (list, tuple)) or len(row) <= max(date_idx, close_idx):
                continue
            digits = re.sub(r"\D", "", str(row[date_idx]))[:8]
            if len(digits) != 8:
                continue
            try:
                d = date(int(digits[:4]), int(digits[4:6]), int(digits[6:8]))
            except ValueError:
                continue
            close = base.parse_number(row[close_idx])
            if close is not None and math.isfinite(close) and close > 0:
                out[d] = float(close)
        return out, None if out else "LEGACY_PRICE_EMPTY"
    except LookupError as exc:
        return {}, str(exc)
    except Exception as exc:
        return {}, type(exc).__name__


def _stable_scale(values: list[float], center: float) -> bool:
    return bool(values) and all(_relative_diff(v, center) <= _SCALE_STABILITY_REL_TOL for v in values)


def _detect_scale_transition_events(
    raw_closes: dict[date, float],
    adjusted_closes: dict[date, float],
    start_date: date,
    end_date: date,
) -> list[dict[str, Any]]:
    shared = []
    for d in sorted(set(raw_closes) & set(adjusted_closes)):
        if d < start_date or d > end_date:
            continue
        raw = raw_closes[d]
        adj = adjusted_closes[d]
        if raw <= 0 or adj <= 0:
            continue
        scale = raw / adj
        if math.isfinite(scale) and scale > 0:
            shared.append((d, scale))
    if len(shared) < 2:
        return []

    events: list[dict[str, Any]] = []
    last_event_index = -10
    for i in range(1, len(shared)):
        # Require the scale itself to jump at this exact boundary.
        # Median windows validate regimes; they must not invent
        # an event one day before or after a real transition.
        immediate_reset = shared[i][1] / shared[i - 1][1]
        if _relative_diff(immediate_reset, 1.0) < _SCALE_RESET_REL_TOL:
            continue
        left = [x[1] for x in shared[max(0, i - _SCALE_WINDOW):i]]
        right = [x[1] for x in shared[i:min(len(shared), i + _SCALE_WINDOW)]]
        if not left or not right:
            continue
        before = float(statistics.median(left))
        after = float(statistics.median(right))
        if before <= 0 or after <= 0:
            continue
        reset_ratio = after / before
        if _relative_diff(reset_ratio, 1.0) < _SCALE_RESET_REL_TOL:
            continue

        before_stable = _stable_scale(left, before)
        after_stable = _stable_scale(right, after)
        strong = max(reset_ratio, 1.0 / reset_ratio) >= _SCALE_STRONG_RATIO
        stable_regimes = len(left) >= 2 and len(right) >= 2 and before_stable and after_stable
        if not strong and not stable_regimes:
            continue
        # Suppress repeated detections from the same transition window.
        if i - last_event_index <= 1:
            continue

        family = "SPLIT_OR_BONUS_ISSUE" if reset_ratio < 1.0 else "REVERSE_SPLIT_OR_CAPITAL_REDUCTION"
        events.append({
            "date": shared[i][0],
            "previous_date": shared[i - 1][0],
            "reset_ratio": reset_ratio,
            "scale_before": before,
            "scale_after": after,
            "scale_before_count": len(left),
            "scale_after_count": len(right),
            "scale_before_stable": before_stable,
            "scale_after_stable": after_stable,
            "scale_strong": strong,
            "scale_stable_regimes": stable_regimes,
            "type": family,
        })
        last_event_index = i
    return events


def _legacy_adjusted_return(
    adjusted_closes: dict[date, float],
    six_month_target: date,
    cutoff: date,
) -> tuple[float | None, str | None, date | None, date | None]:
    end_dates = [d for d in adjusted_closes if d <= cutoff]
    start_dates = [d for d in adjusted_closes if d <= six_month_target]
    if not end_dates:
        return None, "NAVER_LEGACY_NO_PRICE_AT_CUTOFF", None, None
    if not start_dates:
        return None, "PRICE_HISTORY_SHORTER_THAN_6M", None, max(end_dates)
    end_date = max(end_dates)
    start_date = max(start_dates)
    if (end_date - start_date).days < 150:
        return None, "PRICE_HISTORY_SHORTER_THAN_6M", start_date, end_date
    start_close = adjusted_closes[start_date]
    end_close = adjusted_closes[end_date]
    if start_close <= 0 or end_close <= 0:
        return None, "NAVER_LEGACY_ADJUSTED_RETURN_INVALID", start_date, end_date
    value = (end_close / start_close - 1.0) * 100.0
    if not math.isfinite(value) or abs(value) > 100000:
        return None, "NAVER_RETURN_OUTLIER_GUARD", start_date, end_date
    return value, None, start_date, end_date

def _valid_daily_factor(value: float | None) -> bool:
    return value is not None and math.isfinite(value) and _DAILY_FACTOR_MIN <= value <= _DAILY_FACTOR_MAX


def _relative_diff(a: float, b: float) -> float:
    scale = max(abs(a), abs(b), 1e-12)
    return abs(a - b) / scale


def _daily_reference_factor(bar: dict[str, Any], close: float) -> float | None:
    """Return the KRX-reference one-day factor without direction ambiguity.

    ``fluctuationsRatio`` is signed and already measured from the exchange
    reference/base price, so it is authoritative. When Naver also supplies
    ``compareToPreviousPrice.name`` we normalize the absolute KRW change to
    that direction and use the more precise change-derived factor only when
    it agrees with the signed ratio. A positive change magnitude without
    direction is never assumed to mean a rise.
    """
    ratio = base.parse_number(bar.get("fluctuationsRatio"))
    ratio_factor = None
    if ratio is not None:
        candidate = 1.0 + ratio / 100.0
        if _valid_daily_factor(candidate):
            ratio_factor = candidate

    change = base.parse_number(bar.get("compareToPreviousClosePrice"))
    direction_raw = bar.get("compareToPreviousPrice")
    if isinstance(direction_raw, dict):
        direction = str(direction_raw.get("name") or "").strip().upper()
    else:
        direction = str(direction_raw or "").strip().upper()

    change_factor = None
    if change is not None and direction:
        if direction in {"FALLING", "LOWER", "DOWN"}:
            change = -abs(change)
        elif direction in {"RISING", "HIGHER", "UP"}:
            change = abs(change)
        elif direction in {"UNCHANGED", "SAME", "FLAT"}:
            change = 0.0
        reference_price = close - change
        if reference_price > 0:
            candidate = close / reference_price
            if _valid_daily_factor(candidate):
                change_factor = candidate

    if change_factor is not None and ratio_factor is not None:
        # fluctuationsRatio is rounded while KRW change is exact. Use the
        # precise factor only after direction normalization and agreement.
        if abs(change_factor - ratio_factor) <= 0.005:
            return change_factor
        return ratio_factor
    if ratio_factor is not None:
        return ratio_factor
    return change_factor

def _parse_price_bars(
    code: str,
    cutoff: date,
    six_month_target: date,
) -> tuple[dict[date, float], dict[date, float | None], str | None]:
    by_date: dict[date, float] = {}
    by_factor: dict[date, float | None] = {}
    last_error: str | None = None
    for page in range(1, 5):
        try:
            bars = get_json(f"{base.NAVER_BASE}/{code}/price?pageSize=60&page={page}")
        except LookupError as exc:
            last_error = str(exc)
            break
        except Exception as exc:
            last_error = type(exc).__name__
            break
        if not isinstance(bars, list):
            last_error = "PRICE_NOT_LIST"
            break
        if not bars:
            break
        for bar in bars:
            if not isinstance(bar, dict):
                continue
            d = base.parse_date(str(bar.get("localTradedAt") or ""))
            p = base.parse_number(bar.get("closePrice"))
            if d and p is not None and p > 0:
                by_date[d] = p
                by_factor[d] = _daily_reference_factor(bar, p)
        if by_date and min(by_date) <= six_month_target:
            break
        if len(bars) < 60:
            break
    return by_date, by_factor, last_error


def _detect_reference_reset_events(
    by_date: dict[date, float],
    by_factor: dict[date, float | None],
    start_date: date,
    end_date: date,
) -> list[dict[str, Any]]:
    dates = sorted(d for d in by_date if start_date <= d <= end_date)
    events: list[dict[str, Any]] = []
    if len(dates) < 2:
        return events
    prev_close = by_date[dates[0]]
    for d in dates[1:]:
        close = by_date[d]
        raw_factor = close / prev_close if prev_close > 0 else None
        adjusted_factor = by_factor.get(d)
        if raw_factor is None or not math.isfinite(raw_factor) or raw_factor <= 0:
            prev_close = close
            continue
        if adjusted_factor is None:
            if not _valid_daily_factor(raw_factor):
                events.append({
                    "date": d,
                    "reset_ratio": None,
                    "raw_factor": raw_factor,
                    "adjusted_factor": None,
                    "type": "UNRESOLVED_REFERENCE_RESET",
                })
            prev_close = close
            continue
        if not _valid_daily_factor(adjusted_factor):
            prev_close = close
            continue
        reset_ratio = raw_factor / adjusted_factor
        if math.isfinite(reset_ratio) and reset_ratio > 0 and abs(reset_ratio - 1.0) >= _REFERENCE_RESET_REL_TOL:
            family = "SPLIT_OR_BONUS_ISSUE" if reset_ratio < 1.0 else "REVERSE_SPLIT_OR_CAPITAL_REDUCTION"
            events.append({
                "date": d,
                "reset_ratio": reset_ratio,
                "raw_factor": raw_factor,
                "adjusted_factor": adjusted_factor,
                "type": family,
            })
        prev_close = close
    return events


def _notice_dates(text: str) -> list[date]:
    out: list[date] = []
    for m in re.finditer(r"(20\d{2})[-./년\s]?(\d{1,2})[-./월\s]?(\d{1,2})", text):
        try:
            out.append(date(int(m.group(1)), int(m.group(2)), int(m.group(3))))
        except ValueError:
            pass
    return out


def _iter_dict_nodes(value: Any):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _iter_dict_nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from _iter_dict_nodes(child)


def _notice_action_label(text: str) -> str | None:
    compact = re.sub(r"\s+", "", text)
    if "무상증자" in compact:
        return "BONUS_ISSUE"
    if "주식분할" in compact or "액면분할" in compact:
        return "STOCK_SPLIT"
    if "주식병합" in compact or "액면병합" in compact:
        return "REVERSE_SPLIT"
    if "감자" in compact or "자본감소" in compact:
        return "CAPITAL_REDUCTION"
    return None


def _direction_compatible(label: str, reset_ratio: float | None) -> bool:
    if reset_ratio is None:
        return True
    if label in {"STOCK_SPLIT", "BONUS_ISSUE"}:
        return reset_ratio < 1.0
    if label in {"REVERSE_SPLIT", "CAPITAL_REDUCTION"}:
        return reset_ratio > 1.0
    return True


def _annotate_action_types(code: str, events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not events:
        return events
    try:
        payload = get_json(f"{_NOTICE_BASE}?itemCode={code}&startIdx=0&pageSize=100")
    except Exception:
        return events

    nodes: list[tuple[str, list[date]]] = []
    for node in _iter_dict_nodes(payload):
        scalars = [
            f"{k}={v}"
            for k, v in node.items()
            if isinstance(v, (str, int, float, bool)) or v is None
        ]
        text = " ".join(scalars)
        label = _notice_action_label(text)
        dates = _notice_dates(text)
        if label and dates:
            nodes.append((label, dates))

    for event in events:
        event_date = event["date"]
        reset_ratio = event.get("reset_ratio")
        best: tuple[int, str] | None = None
        for label, dates in nodes:
            if not _direction_compatible(label, reset_ratio):
                continue
            # Corporate-action decisions can precede the effective date by
            # weeks. Keep a bounded window but never accept undated/stale
            # notices as automatic evidence.
            distance = min(abs((dt - event_date).days) for dt in dates)
            if distance > 120:
                continue
            if best is None or distance < best[0]:
                best = (distance, label)
        if best is not None:
            event["type"] = best[1]
            event["notice_distance_days"] = best[0]
    return events

def _format_action_event_tokens(events: list[dict[str, Any]]) -> str:
    parts = []
    for event in events:
        dt = event["date"].strftime("%Y%m%d")
        label = str(event.get("type") or "REFERENCE_RESET")
        rr = event.get("reset_ratio")
        ratio = "NA" if rr is None else f"{float(rr):.6f}"
        parts.append(f"_EVT{dt}:{label}:{ratio}")
    return "".join(parts)


def _corporate_action_adjusted_return(
    by_date: dict[date, float],
    by_factor: dict[date, float | None],
    start_date: date,
    end_date: date,
) -> tuple[float | None, str | None, int, int]:
    """Compound daily adjusted-reference returns from start(exclusive) to end.

    Returns (percentage, reason, corporate_action_days, raw_fallback_days).
    A raw factor is used only when it is itself inside the normal daily band.
    Therefore an unannotated 10:1 split (raw factor ~0.1) or 1:5 reverse split
    (raw factor ~5) fails closed instead of becoming a bogus M04 return.
    """
    dates = sorted(d for d in by_date if start_date <= d <= end_date)
    if not dates or dates[0] != start_date or dates[-1] != end_date:
        return None, "NAVER_ADJUSTED_RETURN_UNAVAILABLE", 0, 0
    if len(dates) < 2:
        return None, "PRICE_HISTORY_SHORTER_THAN_6M", 0, 0

    growth = 1.0
    corporate_action_days = len(_detect_reference_reset_events(by_date, by_factor, start_date, end_date))
    raw_fallback_days = 0
    prev_close = by_date[dates[0]]

    for d in dates[1:]:
        close = by_date[d]
        raw_factor = close / prev_close if prev_close > 0 else None
        adjusted_factor = by_factor.get(d)

        if adjusted_factor is None:
            # Safe fallback only for a normal-size raw move. This preserves
            # coverage if Naver omits one daily metadata field, while refusing
            # to guess across a split/reverse-split/bonus-issue discontinuity.
            if not _valid_daily_factor(raw_factor):
                return None, "NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING", corporate_action_days, raw_fallback_days
            adjusted_factor = raw_factor
            raw_fallback_days += 1
        elif not _valid_daily_factor(adjusted_factor):
            return None, "NAVER_ADJUSTED_RETURN_INVALID", corporate_action_days, raw_fallback_days

        growth *= adjusted_factor
        if not math.isfinite(growth) or growth <= 0 or growth > 1_000_000:
            return None, "NAVER_ADJUSTED_RETURN_INVALID", corporate_action_days, raw_fallback_days
        prev_close = close

    value = (growth - 1.0) * 100.0
    if not math.isfinite(value) or abs(value) > 100000:
        return None, "NAVER_RETURN_OUTLIER_GUARD", corporate_action_days, raw_fallback_days
    return value, None, corporate_action_days, raw_fallback_days


def _valid_positive_per(value: float | None) -> bool:
    return value is not None and math.isfinite(value) and value > 0 and value <= 100000


def _valid_calculated_per(value: float | None) -> bool:
    return value is not None and math.isfinite(value) and value != 0 and abs(value) <= 100000


def _actual_title(row: dict[str, Any]) -> bool:
    raw = row.get("isConsensus")
    if isinstance(raw, bool):
        return not raw
    return str(raw or "N").strip().upper() not in {"Y", "YES", "TRUE", "1"}


def _extract_latest_actual_annual_eps(payload: Any) -> tuple[float | None, str]:
    """Read the newest non-consensus EPS from Naver's annual-finance payload.

    The mobile JSON shape has changed slightly over time, so both dictionary and
    scalar cells are accepted. Forecast columns are explicitly rejected.
    """
    if not isinstance(payload, dict):
        return None, ""
    finance = payload.get("financeInfo")
    if not isinstance(finance, dict):
        return None, ""
    title_rows = [x for x in (finance.get("trTitleList") or []) if isinstance(x, dict)]
    row_list = [x for x in (finance.get("rowList") or []) if isinstance(x, dict)]
    eps_rows = []
    for row in row_list:
        title = str(row.get("title") or row.get("name") or "").upper().replace(" ", "")
        if "EPS" in title:
            eps_rows.append(row)
    if not eps_rows:
        return None, ""

    actual_periods: list[tuple[str, str]] = []
    for item in title_rows:
        if not _actual_title(item):
            continue
        key = str(item.get("key") or "").strip()
        title = str(item.get("title") or key).strip()
        if key:
            actual_periods.append((key, title))
    actual_periods.sort(key=lambda x: x[0], reverse=True)

    for key, title in actual_periods:
        for row in eps_rows:
            columns = row.get("columns")
            value: Any = None
            if isinstance(columns, dict):
                cell = columns.get(key)
                if isinstance(cell, dict):
                    value = cell.get("value")
                else:
                    value = cell
            elif isinstance(columns, list):
                for cell in columns:
                    if not isinstance(cell, dict):
                        continue
                    if str(cell.get("key") or cell.get("columnKey") or "") == key:
                        value = cell.get("value")
                        break
            parsed = base.parse_number(value)
            if parsed is not None and math.isfinite(parsed):
                return parsed, title or key
    return None, ""


def _fallback_actual_annual_eps(code: str) -> tuple[float | None, str, str | None]:
    try:
        payload = get_json(f"{base.NAVER_BASE}/{code}/finance/annual")
        eps, period = _extract_latest_actual_annual_eps(payload)
        return eps, period, None
    except LookupError as exc:
        return None, "", str(exc)
    except Exception as exc:
        return None, "", type(exc).__name__


def _extract_polling_item(payload: Any) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    result = payload.get("result")
    if not isinstance(result, dict):
        return None
    areas = result.get("areas")
    if not isinstance(areas, list):
        return None
    for area in areas:
        if not isinstance(area, dict):
            continue
        datas = area.get("datas")
        if isinstance(datas, list):
            for item in datas:
                if isinstance(item, dict):
                    return item
    return None


def _m03_corporate_action_crosscheck(
    code: str,
    row: dict[str, Any],
    end_close: float | None,
) -> dict[str, Any]:
    if row.get("m03_raw") is None:
        return row
    if end_close is None or end_close <= 0:
        row["m03_raw"] = None
        row["m03_reason"] = "CORPORATE_ACTION_PER_XCHECK_NO_PRICE"
        row["m03_basis"] = ""
        return row

    try:
        payload = get_json(f"{_POLLING_BASE}?query=SERVICE_ITEM:{code}")
        item = _extract_polling_item(payload)
    except Exception:
        item = None
    if not item:
        row["m03_raw"] = None
        row["m03_reason"] = "CORPORATE_ACTION_PER_XCHECK_UNAVAILABLE"
        row["m03_basis"] = ""
        return row

    poll_eps = base.parse_number(item.get("eps"))
    original_eps = row.get("naver_eps")
    if poll_eps is None or not math.isfinite(poll_eps) or poll_eps == 0:
        row["m03_raw"] = None
        row["m03_reason"] = "CORPORATE_ACTION_PER_XCHECK_EPS_MISSING"
        row["m03_basis"] = ""
        return row
    if original_eps is None or not math.isfinite(float(original_eps)) or float(original_eps) == 0:
        row["m03_raw"] = None
        row["m03_reason"] = "CORPORATE_ACTION_PER_XCHECK_SOURCE_EPS_MISSING"
        row["m03_basis"] = ""
        return row
    original_eps = float(original_eps)
    if (poll_eps > 0) != (original_eps > 0) or _relative_diff(poll_eps, original_eps) > _M03_XCHECK_EPS_REL_TOL:
        row["m03_raw"] = None
        row["m03_reason"] = "CORPORATE_ACTION_PER_XCHECK_EPS_MISMATCH"
        row["m03_basis"] = ""
        return row

    poll_per = end_close / poll_eps
    raw = row.get("m03_raw")
    if raw is None or not math.isfinite(float(raw)) or not math.isfinite(poll_per):
        row["m03_raw"] = None
        row["m03_reason"] = "CORPORATE_ACTION_PER_XCHECK_INVALID"
        row["m03_basis"] = ""
        return row
    raw = float(raw)
    if (poll_per > 0) != (raw > 0) or _relative_diff(poll_per, raw) > _M03_XCHECK_PER_REL_TOL:
        row["m03_raw"] = None
        row["m03_reason"] = "CORPORATE_ACTION_PER_XCHECK_PER_MISMATCH"
        row["m03_basis"] = ""
        return row

    row["m03_basis"] = (row.get("m03_basis") or "") + "_CA_XCHECK_PASS_POLLING_EPS"
    row["naver_polling_eps"] = poll_eps
    row["naver_polling_per"] = poll_per
    row["naver_polling_listed_shares"] = base.parse_number(item.get("countOfListedStock"))
    return row


def naver_metric_worker(issuer: base.Issuer, cutoff: date, six_month_target: date) -> tuple[str, dict[str, Any]]:
    code = issuer.code
    eps = None
    eps_desc = ""
    eps_source = ""
    eps_fallback_error = None
    provider_per = None
    integration_last_close = None
    integration_error = None

    try:
        integ = get_json(f"{base.NAVER_BASE}/{code}/integration")
        rows = integ.get("totalInfos") or [] if isinstance(integ, dict) else []
        info_rows = {
            str(x.get("code")): x
            for x in rows
            if isinstance(x, dict) and x.get("code")
        }
        eps_row = info_rows.get("eps") or {}
        per_row = info_rows.get("per") or {}
        close_row = info_rows.get("lastClosePrice") or {}
        eps = base.parse_number(eps_row.get("value"))
        eps_desc = str(eps_row.get("valueDesc") or "").strip()
        if eps is not None:
            eps_source = "NAVER_INTEGRATION_EPS"
        provider_per = base.parse_number(per_row.get("value"))
        integration_last_close = base.parse_number(close_row.get("value"))
    except LookupError as exc:
        integration_error = str(exc)
    except Exception as exc:
        integration_error = type(exc).__name__

    if eps is None:
        fallback_eps, fallback_period, eps_fallback_error = _fallback_actual_annual_eps(code)
        if fallback_eps is not None:
            eps = fallback_eps
            eps_desc = f"ANNUAL_ACTUAL_{fallback_period}" if fallback_period else "ANNUAL_ACTUAL"
            eps_source = "NAVER_FINANCE_ANNUAL_EPS"

    # Mobile raw history is now used ONLY to detect raw/adjusted scale
    # regimes. Its daily fluctuationsRatio is not used by M04.
    raw_by_date, _unused_daily_factor, price_error = _parse_price_bars(code, cutoff, six_month_target)
    lookup_start = six_month_target - timedelta(days=14)
    adjusted_by_date, legacy_error = _fetch_legacy_adjusted_closes(code, lookup_start, cutoff)

    m04_raw, m04_reason, start_date, end_date = _legacy_adjusted_return(
        adjusted_by_date, six_month_target, cutoff
    )
    adjusted_end_close = adjusted_by_date.get(end_date) if end_date else None
    raw_end_dates = [d for d in raw_by_date if d <= cutoff]
    raw_end_date = max(raw_end_dates) if raw_end_dates else None
    raw_end_close = raw_by_date.get(raw_end_date) if raw_end_date else None

    scan_start = start_date or (min(adjusted_by_date) if adjusted_by_date else six_month_target)
    scan_end = end_date or cutoff
    scale_candidates = _detect_scale_transition_events(
        raw_by_date, adjusted_by_date, scan_start, scan_end
    )
    annotated_candidates = _annotate_action_types(code, [dict(e) for e in scale_candidates])
    # A one-day scale spike is not enough. Confirm only stable
    # multi-day regimes or an independently dated CA notice.
    action_events = [
        e for e in annotated_candidates
        if bool(e.get('scale_stable_regimes'))
        or e.get('notice_distance_days') is not None
    ]
    action_tokens = _format_action_event_tokens(action_events)

    # Use the adjusted/regular historical close for calculation and CA
    # cross-check where available. Fall back to mobile/integration only
    # when legacy history is missing, without changing M04 fail-closed.
    per_close = adjusted_end_close or raw_end_close
    per_close_date = end_date or raw_end_date
    if per_close is None and integration_last_close is not None and integration_last_close > 0:
        per_close = integration_last_close
        per_close_date = cutoff

    m03_raw = None
    m03_reason = None
    m03_basis = ""
    if _valid_positive_per(provider_per):
        m03_raw = provider_per
        m03_basis = f"{cutoff.isoformat()}_NAVER_REPORTED_TRAILING_PER"
    else:
        if eps is not None and eps != 0 and per_close is not None and per_close > 0:
            calculated = per_close / eps
            if not _valid_calculated_per(calculated):
                m03_reason = "NAVER_PER_OUTLIER_GUARD"
            else:
                m03_raw = calculated
                eps_basis = eps_desc if eps_desc else "ACTUAL_EPS"
                source_tag = "ANNUAL" if eps_source == "NAVER_FINANCE_ANNUAL_EPS" else "INTEGRATION"
                price_tag = "LEGACY_ADJ_CLOSE" if adjusted_end_close is not None else "CLOSE"
                m03_basis = (
                    f"{per_close_date.isoformat() if per_close_date else cutoff.isoformat()}_"
                    f"{price_tag}/NAVER_{source_tag}_EPS_{eps_basis}"
                )
        elif eps is not None and eps == 0:
            m03_reason = "ZERO_EPS"
        elif integration_error and eps_fallback_error:
            m03_reason = "NAVER_INTEGRATION_ERROR"
        elif per_close is None:
            m03_reason = "NAVER_NO_PRICE_AT_CUTOFF"
        else:
            m03_reason = "NAVER_EPS_MISSING"

    m04_basis = ""
    if m04_raw is not None and start_date is not None and end_date is not None:
        m04_basis = (
            f"{start_date.isoformat()}->{end_date.isoformat()}_"
            f"NAVER_LEGACY_ADJUSTED_CLOSE_CA{len(action_events)}{action_tokens}"
        )
    elif action_events:
        m04_basis = f"{scan_start.isoformat()}->{scan_end.isoformat()}_CA_SCALE_SCAN{action_tokens}"
    if m04_raw is None and legacy_error and m04_reason in {None, "NAVER_LEGACY_NO_PRICE_AT_CUTOFF"}:
        m04_reason = "NAVER_LEGACY_PRICE_ERROR"

    row = {
        "m03_raw": round(m03_raw, 6) if m03_raw is not None else None,
        "m03_reason": m03_reason,
        "m03_basis": m03_basis,
        "m04_raw": round(m04_raw, 6) if m04_raw is not None else None,
        "m04_reason": m04_reason,
        "m04_basis": m04_basis,
        "naver_provider_per": provider_per,
        "naver_eps": eps,
        "naver_eps_source": eps_source,
        "naver_end_close": per_close,
        "naver_mobile_end_close": raw_end_close,
        "naver_legacy_adjusted_end_close": adjusted_end_close,
        "naver_m04_adjustment_days": len(action_events),
        "naver_m04_factor_fallback_days": 0,
        "naver_corporate_action_events": action_events,
        "naver_scale_transition_candidates": scale_candidates,
        "integration_error": integration_error,
        "eps_fallback_error": eps_fallback_error,
        "price_error": price_error,
        "legacy_price_error": legacy_error,
    }
    if action_events:
        row = _m03_corporate_action_crosscheck(code, row, per_close)
    return code, row

def _apply_loss_safe_per_percentiles(records: dict[str, dict[str, Any]]) -> int:
    """Score positive PER normally while preventing negative PER rank inversion.

    Positive PER receives 1..100 (lower positive PER is better). Any negative PER
    is a valid/available metric but receives exactly 0 valuation points. This
    keeps loss-making stocks in 4-metric completeness without calling a negative
    multiple 'cheaper' than a profitable company.
    """
    positives = [(code, row.get("m03_raw")) for code, row in records.items() if (row.get("m03_raw") or 0) > 0]
    negatives = [(code, row.get("m03_raw")) for code, row in records.items() if row.get("m03_raw") is not None and row.get("m03_raw") < 0]
    positives.sort(key=lambda x: (x[1], x[0]))
    n = len(positives)
    i = 0
    while i < n:
        j = i + 1
        while j < n and positives[j][1] == positives[i][1]:
            j += 1
        avg_index = (i + (j - 1)) / 2.0
        normal = 50.0 if n == 1 else 100.0 - avg_index / (n - 1) * 100.0
        score = 1.0 + normal * 0.99
        for k in range(i, j):
            records[positives[k][0]]["m03_pct"] = round(score, 6)
        i = j
    for code, _ in negatives:
        records[code]["m03_pct"] = 0.0
    return n + len(negatives)


def compute_scores_with_loss_per(records: dict[str, dict[str, Any]]) -> None:
    base.apply_percentiles(records, "m01_raw", "m01_pct", True)
    base.apply_percentiles(records, "m02_raw", "m02_pct", True)
    _apply_loss_safe_per_percentiles(records)
    base.apply_percentiles(records, "m04_raw", "m04_pct", True)

    complete: list[tuple[str, float]] = []
    for code, row in records.items():
        scores = [row.get(f"m{i:02d}_pct") for i in range(1, 5)]
        if all(v is not None for v in scores):
            comp = sum(float(v) for v in scores) / 4.0
            row["composite"] = round(comp, 6)
            complete.append((code, comp))
        else:
            row["composite"] = None
        row["rank"] = None
    complete.sort(key=lambda x: (-x[1], x[0]))
    for rank, (code, _) in enumerate(complete, 1):
        records[code]["rank"] = rank


base.get_json = get_json
base.naver_metric_worker = naver_metric_worker
base.compute_scores = compute_scores_with_loss_per

if __name__ == "__main__":
    base.main()
