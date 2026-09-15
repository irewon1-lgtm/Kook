#!/usr/bin/env python3
"""Recover KR4 M01/M02 for non-December fiscal-year issuers.

Safety policy:
- KRX KIND `결산월` identifies only non-December fiscal issuers.
- Naver quarterly finance is used only as an actual-quarter recovery source.
- Any title marked consensus/forecast is rejected.
- A period ending after the snapshot date is rejected even if provider metadata is wrong.
- M01 requires latest actual revenue AND prior-year same-calendar-month actual revenue.
- M02 uses latest actual operating margin, or actual operating-income/revenue fallback.
- Existing numeric M01/M02 values are never overwritten.
- FINANCIAL_SECTOR_EXCLUDED is never overridden.
- After recovery, all percentiles/composite/ranks are recomputed with the same loss-safe PER policy.
"""
from __future__ import annotations

import argparse
import calendar
import io
import json
import math
import re
import time
from copy import deepcopy
from datetime import date
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

import pandas as pd

import collect_real_quant as base
import collect_real_quant_v2 as v2
import apply_recent_dart_fallback as recent

KIND_URLS = {
    "KOSPI": "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=stockMkt",
    "KOSDAQ": "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=kosdaqMkt",
}
M01_RECOVERABLE = {"DART_NO_REVENUE", "DART_NO_COMPARABLE_PRIOR_REVENUE"}
M02_RECOVERABLE = {"DART_NO_OPERATING_INCOME", "DART_NO_COMPARABLE_OPERATING_MARGIN"}
TARGET_ROW_NAMES = {"매출액", "영업이익", "영업이익률"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--out", default=None)
    p.add_argument("--self-test", action="store_true")
    p.add_argument("--kind-retries", type=int, default=3)
    return p.parse_args()


def _issue_code(value: object) -> str | None:
    raw = str(value).strip().upper()
    if re.fullmatch(r"\d+\.0", raw):
        raw = raw[:-2]
    raw = re.sub(r"\s+", "", raw)
    if raw.isdigit():
        raw = raw.zfill(6)
    return raw if re.fullmatch(r"[0-9A-Z]{6}", raw) else None


def _month(value: object) -> int | None:
    m = re.search(r"(\d{1,2})", str(value or ""))
    if not m:
        return None
    n = int(m.group(1))
    return n if 1 <= n <= 12 else None


def _fetch_kind_table(url: str, retries: int = 3) -> pd.DataFrame:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            req = Request(url, headers={"User-Agent": "Mozilla/5.0 KR4-FiscalRecovery/1.0"})
            with urlopen(req, timeout=45) as resp:
                raw = resp.read()
            tables = pd.read_html(io.BytesIO(raw), header=0)
            if not tables:
                raise RuntimeError("KIND_NO_TABLE")
            df = tables[0].copy()
            df.columns = [str(c).strip() for c in df.columns]
            missing = {"회사명", "종목코드", "결산월"} - set(df.columns)
            if missing:
                raise RuntimeError(f"KIND_SCHEMA_MISSING:{sorted(missing)}")
            return df
        except Exception as exc:
            last = exc
            if attempt + 1 < retries:
                time.sleep(0.7 * (2**attempt))
    raise RuntimeError(f"KIND_FETCH_FAILED:{type(last).__name__}:{last}")


def fetch_fiscal_months(retries: int = 3) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for market, url in KIND_URLS.items():
        df = _fetch_kind_table(url, retries)
        for _, row in df.iterrows():
            code = _issue_code(row["종목코드"])
            if not code:
                continue
            month = _month(row["결산월"])
            name = str(row["회사명"] or "").strip()
            prev = result.get(code)
            if prev and prev.get("name") and prev["name"] != name:
                raise RuntimeError(f"KIND_CODE_NAME_CONFLICT:{code}:{prev['name']}:{name}")
            result[code] = {"fiscal_month": month, "name": name, "market": market}
    return result


def _is_actual_title(item: dict[str, Any]) -> bool:
    raw = item.get("isConsensus")
    if isinstance(raw, bool):
        return not raw
    return str(raw or "N").strip().upper() not in {"Y", "YES", "TRUE", "1"}


def _period_end(key: str) -> date | None:
    if not re.fullmatch(r"\d{6}", key):
        return None
    y, m = int(key[:4]), int(key[4:])
    if not 1 <= m <= 12:
        return None
    return date(y, m, calendar.monthrange(y, m)[1])


def _cell_value(columns: Any, key: str) -> float | None:
    value: Any = None
    if isinstance(columns, dict):
        cell = columns.get(key)
        value = cell.get("value") if isinstance(cell, dict) else cell
    elif isinstance(columns, list):
        for cell in columns:
            if not isinstance(cell, dict):
                continue
            if str(cell.get("key") or cell.get("columnKey") or "") == key:
                value = cell.get("value")
                break
    parsed = base.parse_number(value)
    return parsed if parsed is not None and math.isfinite(parsed) else None


def extract_actual_quarter(payload: Any, as_of: date) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    fi = payload.get("financeInfo")
    if not isinstance(fi, dict):
        return None
    actual: dict[str, dict[str, Any]] = {}
    for item in fi.get("trTitleList") or []:
        if not isinstance(item, dict) or not _is_actual_title(item):
            continue
        key = str(item.get("key") or "").strip()
        end = _period_end(key)
        if end is None or end > as_of:
            continue
        actual[key] = item
    if not actual:
        return None

    rows: dict[str, Any] = {}
    for row in fi.get("rowList") or []:
        if not isinstance(row, dict):
            continue
        title = str(row.get("title") or row.get("name") or "").replace(" ", "").strip()
        if title in {x.replace(" ", "") for x in TARGET_ROW_NAMES}:
            rows[title] = row.get("columns")

    revenue_cols = rows.get("매출액")
    margin_cols = rows.get("영업이익률")
    op_cols = rows.get("영업이익")
    candidates: list[dict[str, Any]] = []
    for key in sorted(actual, reverse=True):
        revenue = _cell_value(revenue_cols, key)
        margin = _cell_value(margin_cols, key)
        op_income = _cell_value(op_cols, key)
        if margin is None and revenue not in (None, 0.0) and op_income is not None:
            v = op_income / revenue * 100.0
            if math.isfinite(v):
                margin = v
        candidates.append({
            "key": key,
            "title": str(actual[key].get("title") or key),
            "revenue": revenue,
            "operating_income": op_income,
            "operating_margin": margin,
        })

    latest = next((x for x in candidates if x["revenue"] is not None or x["operating_margin"] is not None), None)
    if latest is None:
        return None
    prior_key = f"{int(latest['key'][:4]) - 1:04d}{latest['key'][4:]}"
    prior_revenue = None
    if prior_key in actual:
        prior_revenue = _cell_value(revenue_cols, prior_key)
    latest["prior_key"] = prior_key
    latest["prior_revenue"] = prior_revenue
    latest["actual_keys"] = sorted(actual)
    return latest


def safe_revenue_yoy(current: float | None, prior: float | None) -> float | None:
    if current is None or prior is None or prior <= 0:
        return None
    value = (current / prior - 1.0) * 100.0
    if not math.isfinite(value) or abs(value) > 100000:
        return None
    return round(value, 6)


def apply_recovery(
    quant: dict[str, Any],
    fiscal: dict[str, dict[str, Any]],
    payloads: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    out = deepcopy(quant)
    as_of = date.fromisoformat(str(out["snapshot_date_kst"]))
    records = out["records"]
    before = dict(out.get("coverage") or {})
    m01_codes: list[str] = []
    m02_codes: list[str] = []
    details: dict[str, Any] = {}

    for code, row in records.items():
        meta = fiscal.get(code) or {}
        fiscal_month = meta.get("fiscal_month")
        if fiscal_month in (None, 12):
            continue
        m1 = row["m01"]
        m2 = row["m02"]
        need_m1 = m1.get("raw") is None and m1.get("reason") in M01_RECOVERABLE
        need_m2 = m2.get("raw") is None and m2.get("reason") in M02_RECOVERABLE
        if not need_m1 and not need_m2:
            continue
        parsed = extract_actual_quarter(payloads.get(code), as_of)
        if not parsed:
            continue
        rec: dict[str, Any] = {
            "fiscal_month": fiscal_month,
            "latest_actual_period": parsed["key"],
            "prior_year_period": parsed["prior_key"],
        }
        if need_m1:
            yoy = safe_revenue_yoy(parsed.get("revenue"), parsed.get("prior_revenue"))
            if yoy is not None:
                m1["raw"] = yoy
                m1["reason"] = None
                m1["basis"] = f"{parsed['key']}_NAVER_QUARTER_ACTUAL_YOY_FISCAL{int(fiscal_month):02d}"
                m01_codes.append(code)
                rec["m01"] = yoy
        if need_m2:
            margin = recent._finite(parsed.get("operating_margin"))
            if margin is not None and abs(margin) <= 10000:
                m2["raw"] = round(margin, 6)
                m2["reason"] = None
                m2["basis"] = f"{parsed['key']}_NAVER_QUARTER_ACTUAL_OPM_FISCAL{int(fiscal_month):02d}"
                m02_codes.append(code)
                rec["m02"] = round(margin, 6)
        if len(rec) > 3:
            details[code] = rec

    after = recent.recompute_scores(records)
    out["coverage"] = after
    recovery = {
        "policy": "non-December fiscal issuers only; latest completed Naver actual quarter; consensus rejected; existing numeric metrics preserved",
        "m01_recovered": len(m01_codes),
        "m02_recovered": len(m02_codes),
        "m01_codes": m01_codes,
        "m02_codes": m02_codes,
        "details": details,
        "coverage_before": before,
        "coverage_after": after,
    }
    out["fiscal_calendar_recovery"] = recovery
    return out, recovery


def validate_recovery(before: dict[str, Any], after: dict[str, Any], fiscal: dict[str, dict[str, Any]]) -> None:
    assert before["universe_count"] == after["universe_count"] == 2649
    assert set(before["records"]) == set(after["records"])
    rec = after.get("fiscal_calendar_recovery") or {}
    recovered = set(rec.get("m01_codes") or []) | set(rec.get("m02_codes") or [])
    for code in recovered:
        assert fiscal[code]["fiscal_month"] not in (None, 12), code
        row = after["records"][code]
        for mid in ("m01", "m02"):
            basis = str(row[mid].get("basis") or "")
            if "NAVER_QUARTER_ACTUAL" in basis:
                assert "FISCAL12" not in basis, (code, mid, basis)
    # Existing numeric metrics must be byte-for-byte preserved at raw/basis level.
    for code, old in before["records"].items():
        new = after["records"][code]
        for mid in ("m01", "m02"):
            if old[mid].get("raw") is not None:
                assert new[mid].get("raw") == old[mid].get("raw"), (code, mid, "raw overwrite")
                assert new[mid].get("basis") == old[mid].get("basis"), (code, mid, "basis overwrite")
            if old[mid].get("reason") == "FINANCIAL_SECTOR_EXCLUDED":
                assert new[mid].get("raw") is None
                assert new[mid].get("reason") == "FINANCIAL_SECTOR_EXCLUDED"
    recent.validate_snapshot(after)
    b = before["coverage"]; a = after["coverage"]
    for key in ("m01_available","m02_available","m03_available","m04_available","complete_count","ranked_count"):
        assert int(a[key]) >= int(b[key]), (key, b[key], a[key])


def self_test() -> None:
    as_of = date(2026, 9, 15)
    payload = {"financeInfo": {
        "trTitleList": [
            {"key":"202503","title":"2025.03.","isConsensus":"N"},
            {"key":"202603","title":"2026.03.","isConsensus":"N"},
            {"key":"202609","title":"2026.09.","isConsensus":"N"},
            {"key":"202612","title":"2026.12.","isConsensus":"Y"},
        ],
        "rowList": [
            {"title":"매출액","columns":{"202503":{"value":"100"},"202603":{"value":"125"},"202609":{"value":"999"},"202612":{"value":"9999"}}},
            {"title":"영업이익","columns":{"202603":{"value":"10"}}},
            {"title":"영업이익률","columns":{"202603":{"value":"8.0"},"202609":{"value":"99"}}},
        ],
    }}
    p = extract_actual_quarter(payload, as_of)
    assert p and p["key"] == "202603", p
    assert p["prior_key"] == "202503" and p["prior_revenue"] == 100.0
    assert safe_revenue_yoy(p["revenue"], p["prior_revenue"]) == 25.0
    assert p["operating_margin"] == 8.0
    print("FISCAL_CALENDAR_RECOVERY_SELF_TEST_PASS")


def main() -> None:
    args = parse_args()
    if args.self_test:
        self_test()
        return
    quant_path = Path(args.quant)
    out_path = Path(args.out) if args.out else quant_path
    before = json.loads(quant_path.read_text(encoding="utf-8"))
    fiscal = fetch_fiscal_months(args.kind_retries)
    candidates = []
    for code, row in before["records"].items():
        fm = (fiscal.get(code) or {}).get("fiscal_month")
        if fm in (None, 12):
            continue
        need_m1 = row["m01"].get("raw") is None and row["m01"].get("reason") in M01_RECOVERABLE
        need_m2 = row["m02"].get("raw") is None and row["m02"].get("reason") in M02_RECOVERABLE
        if need_m1 or need_m2:
            candidates.append(code)

    payloads: dict[str, Any] = {}
    errors: dict[str, str] = {}
    for code in candidates:
        try:
            payloads[code] = v2.get_json(f"{base.NAVER_BASE}/{code}/finance/quarter")
        except Exception as exc:
            errors[code] = f"{type(exc).__name__}:{exc}"

    after, recovery = apply_recovery(before, fiscal, payloads)
    recovery["candidate_count"] = len(candidates)
    recovery["source_errors"] = errors
    after["fiscal_calendar_recovery"] = recovery
    validate_recovery(before, after, fiscal)
    out_path.write_text(json.dumps(after, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("FISCAL_CALENDAR_RECOVERY_PASS", json.dumps(recovery, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
