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

M04 six-month price-return policy:
1) Never divide the raw six-month start/end closes directly.
2) Compound each trading day's KRX/Naver reference-price return instead. KRX
   resets the reference/base price for stock splits, reverse splits, bonus
   issues and other corporate actions, so those unit changes do not become
   fake investment returns.
3) If a daily reference return is missing, raw close-to-close is accepted only
   when it is inside the normal KRX daily price-limit band. A split-like raw
   discontinuity without adjustment metadata fails closed instead of guessing.
"""
from __future__ import annotations

import math
import time
from datetime import date
from typing import Any

import collect_real_quant as base


# KRX's ordinary daily price limit is +/-30%. The small buffer absorbs tick and
# percentage rounding while still rejecting split/reverse-split discontinuities.
_DAILY_FACTOR_MIN = 0.69
_DAILY_FACTOR_MAX = 1.31


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


def _valid_daily_factor(value: float | None) -> bool:
    return value is not None and math.isfinite(value) and _DAILY_FACTOR_MIN <= value <= _DAILY_FACTOR_MAX


def _daily_reference_factor(bar: dict[str, Any], close: float) -> float | None:
    """Return a corporate-action-aware one-day price factor.

    Naver exposes both the day's price change and percentage change relative to
    the exchange reference/base price. KRX adjusts that base price around stock
    splits/reverse splits/bonus issues. The integer change-derived factor is
    preferred because it avoids compounding rounded percentages; the percentage
    field is the fallback and also rescues corporate-action days if the textual
    change field is not usable.
    """
    change = base.parse_number(bar.get("compareToPreviousClosePrice"))
    ratio = base.parse_number(bar.get("fluctuationsRatio"))

    change_factor = None
    if change is not None:
        reference_price = close - change
        if reference_price > 0:
            candidate = close / reference_price
            if _valid_daily_factor(candidate):
                change_factor = candidate

    ratio_factor = None
    if ratio is not None:
        candidate = 1.0 + ratio / 100.0
        if _valid_daily_factor(candidate):
            ratio_factor = candidate

    if change_factor is not None and ratio_factor is not None:
        # Percentage is normally rounded to two decimals. If both fields disagree
        # materially, prefer the exchange-style percentage factor rather than
        # allowing a suspicious absolute-change field to contaminate M04.
        if abs(change_factor - ratio_factor) > 0.02:
            return ratio_factor
        return change_factor
    return change_factor if change_factor is not None else ratio_factor


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
    corporate_action_days = 0
    raw_fallback_days = 0
    prev_date = dates[0]
    prev_close = by_date[prev_date]

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

        # A raw jump outside the legal ordinary daily band while the reference
        # factor is normal is the signature of an exchange reference-price reset
        # such as a split/reverse split. Count it for provenance/regression.
        if not _valid_daily_factor(raw_factor):
            corporate_action_days += 1

        growth *= adjusted_factor
        if not math.isfinite(growth) or growth <= 0 or growth > 1_000_000:
            return None, "NAVER_ADJUSTED_RETURN_INVALID", corporate_action_days, raw_fallback_days
        prev_date = d
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

    # Only pay for the second endpoint when integration did not provide actual EPS.
    # This targets the small missing-EPS tail without multiplying all 2,649 calls.
    if eps is None:
        fallback_eps, fallback_period, eps_fallback_error = _fallback_actual_annual_eps(code)
        if fallback_eps is not None:
            eps = fallback_eps
            eps_desc = f"ANNUAL_ACTUAL_{fallback_period}" if fallback_period else "ANNUAL_ACTUAL"
            eps_source = "NAVER_FINANCE_ANNUAL_EPS"

    by_date, by_factor, price_error = _parse_price_bars(code, cutoff, six_month_target)
    end_dates = [d for d in by_date if d <= cutoff]
    start_dates = [d for d in by_date if d <= six_month_target]
    end_date = max(end_dates) if end_dates else None
    end_close = by_date[end_date] if end_date else None

    # M03: positive provider PER stays first priority. When it is unavailable,
    # calculate from actual EPS. Negative EPS is intentionally retained as a
    # negative raw PER; only exactly zero EPS is mathematically undefined.
    m03_raw = None
    m03_reason = None
    m03_basis = ""
    if _valid_positive_per(provider_per):
        m03_raw = provider_per
        m03_basis = f"{cutoff.isoformat()}_NAVER_REPORTED_TRAILING_PER"
    else:
        per_close = end_close
        per_close_date = end_date
        if per_close is None and integration_last_close is not None and integration_last_close > 0:
            per_close = integration_last_close
            per_close_date = cutoff

        if eps is not None and eps != 0 and per_close is not None and per_close > 0:
            calculated = per_close / eps
            if not _valid_calculated_per(calculated):
                m03_reason = "NAVER_PER_OUTLIER_GUARD"
            else:
                m03_raw = calculated
                eps_basis = eps_desc if eps_desc else "ACTUAL_EPS"
                source_tag = "ANNUAL" if eps_source == "NAVER_FINANCE_ANNUAL_EPS" else "INTEGRATION"
                m03_basis = (
                    f"{per_close_date.isoformat() if per_close_date else cutoff.isoformat()}_"
                    f"CLOSE/NAVER_{source_tag}_EPS_{eps_basis}"
                )
        elif eps is not None and eps == 0:
            m03_reason = "ZERO_EPS"
        elif integration_error and eps_fallback_error:
            m03_reason = "NAVER_INTEGRATION_ERROR"
        elif per_close is None:
            m03_reason = "NAVER_NO_PRICE_AT_CUTOFF"
        else:
            m03_reason = "NAVER_EPS_MISSING"

    # M04: compound KRX-adjusted one-day reference returns. This is deliberately
    # separate from M03, which continues to use the real cutoff close unchanged.
    m04_raw = None
    m04_reason = None
    m04_basis = ""
    m04_adjustment_days = 0
    m04_factor_fallback_days = 0
    if not end_dates:
        m04_reason = "NAVER_NO_PRICE_AT_CUTOFF" if not price_error else "NAVER_PRICE_ERROR"
    elif not start_dates:
        m04_reason = "PRICE_HISTORY_SHORTER_THAN_6M"
    else:
        start_date = max(start_dates)
        if end_date is None or (end_date - start_date).days < 150:
            m04_reason = "PRICE_HISTORY_SHORTER_THAN_6M"
        else:
            m04_raw, m04_reason, m04_adjustment_days, m04_factor_fallback_days = _corporate_action_adjusted_return(
                by_date,
                by_factor,
                start_date,
                end_date,
            )
            if m04_raw is not None:
                m04_basis = (
                    f"{start_date.isoformat()}->{end_date.isoformat()}_"
                    f"KRX_ADJ_DAILY_CA{m04_adjustment_days}_FB{m04_factor_fallback_days}"
                )

    return code, {
        "m03_raw": round(m03_raw, 6) if m03_raw is not None else None,
        "m03_reason": m03_reason,
        "m03_basis": m03_basis,
        "m04_raw": round(m04_raw, 6) if m04_raw is not None else None,
        "m04_reason": m04_reason,
        "m04_basis": m04_basis,
        "naver_provider_per": provider_per,
        "naver_eps": eps,
        "naver_eps_source": eps_source,
        "naver_end_close": end_close,
        "naver_m04_adjustment_days": m04_adjustment_days,
        "naver_m04_factor_fallback_days": m04_factor_fallback_days,
        "integration_error": integration_error,
        "eps_fallback_error": eps_fallback_error,
        "price_error": price_error,
    }


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
