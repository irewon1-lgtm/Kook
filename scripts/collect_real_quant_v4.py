#!/usr/bin/env python3
"""KR4 automatic collector v4: strict current-quarter financial fallback.

OpenDART remains the primary source for M01 revenue growth and M02 operating
margin.  When OpenDART cannot produce one of those metrics, this layer makes a
small read-only request to Naver Finance's quarterly statement endpoint and
uses only non-consensus (actual) periods.

Safety rules:
- Never replace a valid OpenDART value.
- Never use consensus/forecast quarterly columns.
- Use only the newest actual quarter that is not stale at the snapshot date.
- M01 requires an exact same-month prior-year actual quarter.
- M02 requires revenue and operating profit from the same actual quarter.
- Financial-sector M02 remains excluded; a fallback must not change the metric's
  economic meaning just to increase coverage.
- Any malformed, ambiguous, stale, or outlier response fails closed and leaves
  the original OpenDART reason code intact.
"""
from __future__ import annotations

import calendar
import json
import math
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date
from pathlib import Path
from typing import Any

import collect_real_quant as base
import collect_real_quant_v2 as v2
import collect_real_quant_v3 as v3

MAX_QUARTER_AGE_DAYS = 190
QUARTER_FALLBACK_WORKERS = 8


def _normal_title(value: Any) -> str:
    text = re.sub(r"\s+", "", str(value or ""))
    text = text.replace("（", "(").replace("）", ")")
    text = re.sub(r"\((?:억원|백만원|원)\)$", "", text)
    return text


def _is_revenue_title(value: Any) -> bool:
    title = _normal_title(value)
    if title in {"매출액", "수익(매출액)", "영업수익", "영업수익(매출액)", "순매출액", "매출"}:
        return True
    return title.startswith("매출액") and all(x not in title for x in ("원가", "총이익", "증가"))


def _is_operating_title(value: Any) -> bool:
    title = _normal_title(value)
    return title in {"영업이익", "영업손실", "영업이익(손실)", "영업손익", "영업손익(손실)"}


def _cell_value(row: dict[str, Any] | None, key: str) -> float | None:
    if not isinstance(row, dict):
        return None
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
            if str(cell.get("key") or cell.get("columnKey") or "").strip() == key:
                value = cell.get("value")
                break
    parsed = base.parse_number(value)
    return parsed if parsed is not None and math.isfinite(parsed) else None


def _period_end_from_key(key: str) -> date | None:
    if not re.fullmatch(r"\d{6}", key):
        return None
    year, month = int(key[:4]), int(key[4:6])
    if not 1 <= month <= 12:
        return None
    return date(year, month, calendar.monthrange(year, month)[1])


def _extract_actual_quarter_metrics(payload: Any, as_of: date) -> dict[str, Any]:
    """Extract strict latest-actual M01/M02 candidates from a Naver quarter table."""
    result = {
        "m01_raw": None,
        "m01_basis": "",
        "m02_raw": None,
        "m02_basis": "",
        "period_key": "",
        "reason": "NAVER_QUARTER_UNUSABLE",
    }
    if not isinstance(payload, dict):
        return result
    finance = payload.get("financeInfo")
    if not isinstance(finance, dict):
        return result

    title_rows = [x for x in (finance.get("trTitleList") or []) if isinstance(x, dict)]
    rows = [x for x in (finance.get("rowList") or []) if isinstance(x, dict)]
    revenue_rows = [x for x in rows if _is_revenue_title(x.get("title") or x.get("name"))]
    operating_rows = [x for x in rows if _is_operating_title(x.get("title") or x.get("name"))]
    if len(revenue_rows) != 1:
        result["reason"] = "NAVER_QUARTER_REVENUE_AMBIGUOUS"
        return result
    revenue_row = revenue_rows[0]
    operating_row = operating_rows[0] if len(operating_rows) == 1 else None

    actual_periods: list[tuple[date, str]] = []
    actual_keys: set[str] = set()
    for item in title_rows:
        if not v2._actual_title(item):
            continue
        key = str(item.get("key") or "").strip()
        period_end = _period_end_from_key(key)
        if period_end is None or period_end > as_of:
            continue
        actual_periods.append((period_end, key))
        actual_keys.add(key)
    actual_periods.sort(reverse=True)

    # Select the newest actual quarter that actually has revenue. Do not walk
    # backwards merely to manufacture a value once a newer actual revenue row is
    # present but incomplete in another field.
    current_key = ""
    current_end: date | None = None
    current_revenue: float | None = None
    for period_end, key in actual_periods:
        value = _cell_value(revenue_row, key)
        if value is not None:
            current_key, current_end, current_revenue = key, period_end, value
            break
    if not current_key or current_end is None or current_revenue is None:
        result["reason"] = "NAVER_QUARTER_NO_ACTUAL_REVENUE"
        return result
    if (as_of - current_end).days > MAX_QUARTER_AGE_DAYS:
        result["reason"] = "NAVER_QUARTER_STALE"
        return result

    result["period_key"] = current_key
    basis = f"NAVER_QUARTER_ACTUAL_{current_key}_3M"

    prior_key = f"{int(current_key[:4]) - 1:04d}{current_key[4:]}"
    if prior_key in actual_keys:
        prior_revenue = _cell_value(revenue_row, prior_key)
        if prior_revenue is not None and prior_revenue > 0:
            m01 = (current_revenue / prior_revenue - 1.0) * 100.0
            if math.isfinite(m01) and abs(m01) <= 100000:
                result["m01_raw"] = round(m01, 6)
                result["m01_basis"] = basis + f"_YOY_{prior_key}"

    if current_revenue != 0 and operating_row is not None:
        operating = _cell_value(operating_row, current_key)
        if operating is not None:
            m02 = operating / current_revenue * 100.0
            if math.isfinite(m02) and abs(m02) <= 10000:
                result["m02_raw"] = round(m02, 6)
                result["m02_basis"] = basis

    if result["m01_raw"] is not None or result["m02_raw"] is not None:
        result["reason"] = None
    elif prior_key not in actual_keys:
        result["reason"] = "NAVER_QUARTER_NO_PRIOR_YEAR_MATCH"
    else:
        result["reason"] = "NAVER_QUARTER_METRICS_MISSING"
    return result


def _quarter_fallback_worker(issuer: base.Issuer, as_of: date) -> tuple[str, dict[str, Any], str | None]:
    try:
        payload = v2.get_json(f"{base.NAVER_BASE}/{issuer.code}/finance/quarter")
        return issuer.code, _extract_actual_quarter_metrics(payload, as_of), None
    except LookupError as exc:
        return issuer.code, {}, str(exc)
    except Exception as exc:
        return issuer.code, {}, type(exc).__name__


def parse_financials_with_actual_quarter_fallback(
    issuers: list[base.Issuer], zip_bytes: bytes
) -> dict[str, dict[str, Any]]:
    out = v3.parse_dart_metrics_dynamic(issuers, zip_bytes)
    as_of = date.fromisoformat(v3.parse_args_auto_cached().as_of)

    candidates = [
        issuer
        for issuer in issuers
        if out[issuer.code].get("m01_raw") is None
        or (not base.is_financial(issuer) and out[issuer.code].get("m02_raw") is None)
    ]
    recovered_m01 = 0
    recovered_m02 = 0
    errors = 0
    reason_counts: dict[str, int] = {}

    if candidates:
        workers = min(QUARTER_FALLBACK_WORKERS, len(candidates))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(_quarter_fallback_worker, issuer, as_of): issuer for issuer in candidates}
            for future in as_completed(futures):
                issuer = futures[future]
                try:
                    code, fallback, error = future.result()
                except Exception as exc:
                    code, fallback, error = issuer.code, {}, type(exc).__name__
                if error:
                    errors += 1
                    reason_counts[error] = reason_counts.get(error, 0) + 1
                    continue
                reason = str(fallback.get("reason") or "RECOVERED")
                reason_counts[reason] = reason_counts.get(reason, 0) + 1
                row = out[code]
                if row.get("m01_raw") is None and fallback.get("m01_raw") is not None:
                    row["m01_raw"] = fallback["m01_raw"]
                    row["m01_reason"] = None
                    row["m01_basis"] = fallback["m01_basis"]
                    recovered_m01 += 1
                if (
                    not base.is_financial(issuer)
                    and row.get("m02_raw") is None
                    and fallback.get("m02_raw") is not None
                ):
                    row["m02_raw"] = fallback["m02_raw"]
                    row["m02_reason"] = None
                    row["m02_basis"] = fallback["m02_basis"]
                    recovered_m02 += 1

    print(
        "NAVER_QUARTER_FINANCIAL_FALLBACK",
        json.dumps(
            {
                "candidates": len(candidates),
                "m01_recovered": recovered_m01,
                "m02_recovered": recovered_m02,
                "errors": errors,
                "outcomes": dict(sorted(reason_counts.items())),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    return out


def _postprocess_evidence_v4(path: Path) -> None:
    v3._postprocess_evidence(path)
    d = json.loads(path.read_text(encoding="utf-8"))
    d["auto_update"]["collector"] = "collect_real_quant_v4.py"
    d["auto_update"]["financial_fallback_policy"] = (
        "OpenDART primary; for missing M01/M02 only, newest non-consensus Naver quarterly actuals may fill gaps; "
        "same-month prior-year actual required for M01; financial-sector M02 remains excluded; stale/ambiguous responses fail closed"
    )
    d["sources"]["M01_M02"] = (
        "Financial Supervisory Service OpenDART public bulk financials (primary); "
        "Naver Finance non-consensus quarterly actuals (strict missing-only fallback)"
    )
    d["sources"]["naver_quarter_pattern"] = base.NAVER_BASE + "/{code}/finance/quarter"
    d["rules"]["M01"] = (
        "latest OpenDART PL period primary; 3-month YoY preferred, YTD/annual comparable fallback; CFS preferred to OFS; "
        "if still missing, newest fresh Naver non-consensus quarterly revenue with exact same-month prior-year actual may fill the gap"
    )
    d["rules"]["M02"] = (
        "same latest OpenDART PL period primary; operating income/revenue on matched period; financial/insurance sectors excluded; "
        "if still missing for non-financial issuers, newest fresh Naver non-consensus quarterly revenue and operating profit from the same period may fill the gap"
    )
    path.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    args = v3.parse_args_auto_cached()
    base.parse_args = v3.parse_args_auto_cached
    base.get_json = v2.get_json
    base.naver_metric_worker = v2.naver_metric_worker
    base.compute_scores = v2.compute_scores_with_loss_per
    base.download_dart_halfyear_pl = v3.download_latest_dart_pl
    base.parse_dart_metrics = parse_financials_with_actual_quarter_fallback
    base.write_kotlin = v3.write_kotlin_runtime_capable
    base.main()
    evidence_path = base.ROOT / args.evidence_out
    _postprocess_evidence_v4(evidence_path)
    print(
        "AUTO_COLLECTOR_V4_PASS",
        json.dumps(
            {
                "as_of": args.as_of,
                "price_cutoff": args.price_cutoff,
                "dart_year": v3._SELECTED_DART_YEAR,
                "dart_period": v3._SELECTED_DART_PERIOD,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
