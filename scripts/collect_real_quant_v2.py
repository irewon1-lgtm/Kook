#!/usr/bin/env python3
"""KR4 real-data collector v2.

Overrides only the Naver leg of collect_real_quant.py.  Naver's stock price
history endpoint caps pageSize at ~60, so six-month history is paged in 60-row
chunks.  The official OpenDART bulk-financial parser/scoring/output logic remains
identical to v1.
"""
from __future__ import annotations

import math
import time
from datetime import date
from typing import Any

import collect_real_quant as base


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


def _parse_price_bars(code: str, cutoff: date, six_month_target: date) -> tuple[dict[date, float], str | None]:
    by_date: dict[date, float] = {}
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
        if by_date and min(by_date) <= six_month_target:
            break
        if len(bars) < 60:
            break
    return by_date, last_error


def naver_metric_worker(issuer: base.Issuer, cutoff: date, six_month_target: date) -> tuple[str, dict[str, Any]]:
    code = issuer.code
    eps = None
    eps_desc = ""
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
        provider_per = base.parse_number(per_row.get("value"))
        integration_last_close = base.parse_number(close_row.get("value"))
    except LookupError as exc:
        integration_error = str(exc)
    except Exception as exc:
        integration_error = type(exc).__name__

    by_date, price_error = _parse_price_bars(code, cutoff, six_month_target)
    end_dates = [d for d in by_date if d <= cutoff]
    start_dates = [d for d in by_date if d <= six_month_target]
    end_date = max(end_dates) if end_dates else None
    end_close = by_date[end_date] if end_date else None

    # M03: actual-earnings PER. Use the last completed-session close + Naver's
    # non-consensus EPS. Integration's 'lastClosePrice' is a fallback only when
    # the historical endpoint lacks the cutoff session.
    m03_raw = None
    m03_reason = None
    m03_basis = ""
    per_close = end_close
    per_close_date = end_date
    if per_close is None and integration_last_close is not None and integration_last_close > 0:
        per_close = integration_last_close
        per_close_date = cutoff
    if eps is not None and eps > 0 and per_close is not None and per_close > 0:
        m03_raw = per_close / eps
        if not math.isfinite(m03_raw) or m03_raw <= 0 or m03_raw > 100000:
            m03_raw = None
            m03_reason = "NAVER_PER_OUTLIER_GUARD"
        else:
            eps_basis = eps_desc if eps_desc else "ACTUAL_EPS"
            m03_basis = f"{per_close_date.isoformat() if per_close_date else cutoff.isoformat()}_CLOSE/NAVER_EPS_{eps_basis}"
    elif eps is not None and eps <= 0:
        m03_reason = "NONPOSITIVE_EPS"
    elif integration_error:
        m03_reason = "NAVER_INTEGRATION_ERROR"
    elif per_close is None:
        m03_reason = "NAVER_NO_PRICE_AT_CUTOFF"
    else:
        m03_reason = "NAVER_EPS_MISSING"

    # M04: last trading close on/before the six-month target through the cutoff.
    m04_raw = None
    m04_reason = None
    m04_basis = ""
    if not end_dates:
        m04_reason = "NAVER_NO_PRICE_AT_CUTOFF" if not price_error else "NAVER_PRICE_ERROR"
    elif not start_dates:
        m04_reason = "PRICE_HISTORY_SHORTER_THAN_6M"
    else:
        start_date = max(start_dates)
        start_close = by_date[start_date]
        if end_date is None or (end_date - start_date).days < 150:
            m04_reason = "PRICE_HISTORY_SHORTER_THAN_6M"
        elif start_close <= 0 or end_close is None or end_close <= 0:
            m04_reason = "NAVER_PRICE_MISSING"
        else:
            m04_raw = (end_close / start_close - 1.0) * 100.0
            if not math.isfinite(m04_raw) or abs(m04_raw) > 100000:
                m04_raw = None
                m04_reason = "NAVER_RETURN_OUTLIER_GUARD"
            else:
                m04_basis = f"{start_date.isoformat()}->{end_date.isoformat()}"

    return code, {
        "m03_raw": round(m03_raw, 6) if m03_raw is not None else None,
        "m03_reason": m03_reason,
        "m03_basis": m03_basis,
        "m04_raw": round(m04_raw, 6) if m04_raw is not None else None,
        "m04_reason": m04_reason,
        "m04_basis": m04_basis,
        "naver_provider_per": provider_per,
        "naver_eps": eps,
        "naver_end_close": end_close,
        "integration_error": integration_error,
        "price_error": price_error,
    }


base.get_json = get_json
base.naver_metric_worker = naver_metric_worker

if __name__ == "__main__":
    base.main()
