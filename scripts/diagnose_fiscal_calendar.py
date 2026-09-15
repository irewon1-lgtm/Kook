#!/usr/bin/env python3
from __future__ import annotations

import io
import json
import re
from collections import Counter
from pathlib import Path
from urllib.request import Request, urlopen

import pandas as pd
import requests

import collect_real_quant as base

ROOT = Path(__file__).resolve().parents[1]
KIND_URLS = {
    "KOSPI": "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=stockMkt",
    "KOSDAQ": "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=kosdaqMkt",
}


def clean_month(value: object) -> int | None:
    s = str(value).strip()
    m = re.search(r"(\d{1,2})", s)
    if not m:
        return None
    month = int(m.group(1))
    return month if 1 <= month <= 12 else None


def load_kind() -> dict[str, dict[str, object]]:
    out: dict[str, dict[str, object]] = {}
    for market, url in KIND_URLS.items():
        req = Request(url, headers={"User-Agent": "Mozilla/5.0 KR4-FiscalCalendar/1.0"})
        with urlopen(req, timeout=45) as resp:
            raw = resp.read()
        tables = pd.read_html(io.BytesIO(raw), header=0)
        if not tables:
            raise RuntimeError(f"KIND {market}: no table")
        df = tables[0].copy()
        df.columns = [str(c).strip() for c in df.columns]
        print("KIND_COLUMNS", market, json.dumps(df.columns.tolist(), ensure_ascii=False))
        required = {"회사명", "종목코드", "결산월"}
        missing = required - set(df.columns)
        if missing:
            raise RuntimeError(f"KIND {market}: missing {sorted(missing)}")
        months = Counter()
        non12 = []
        for _, row in df.iterrows():
            raw_code = str(row["종목코드"]).strip().upper()
            if re.fullmatch(r"\d+\.0", raw_code):
                raw_code = raw_code[:-2]
            raw_code = re.sub(r"\s+", "", raw_code)
            if raw_code.isdigit():
                raw_code = raw_code.zfill(6)
            if not re.fullmatch(r"[0-9A-Z]{6}", raw_code):
                continue
            month = clean_month(row["결산월"])
            name = str(row["회사명"]).strip()
            out[raw_code] = {"name": name, "market": market, "fiscal_month": month}
            months[month] += 1
            if month not in (None, 12):
                non12.append((raw_code, name, month))
        print("KIND_FISCAL_MONTH_COUNTS", market, json.dumps(dict(sorted(months.items(), key=lambda x: (x[0] is None, x[0] or 99))), ensure_ascii=False))
        print("KIND_NON12_SAMPLE", market, json.dumps(non12[:40], ensure_ascii=False))
    return out


def dart_entries() -> list[tuple[str, str, str, str]]:
    s = requests.Session()
    s.headers.update(base.DART_HEADERS)
    s.get(base.DART_BASE, timeout=30).raise_for_status()
    r = s.post(base.DART_LIST, timeout=60)
    r.raise_for_status()
    rows = base.DART_ENTRY_RE.findall(r.text)
    pl = sorted([x for x in rows if x[2] == "PL" and x[0] in {"2025", "2026"}])
    print("DART_PL_2025_2026", json.dumps(pl, ensure_ascii=False))
    return pl


def main() -> None:
    kind = load_kind()
    dart_entries()
    quant = json.load(open(ROOT / "evidence/real_quant_snapshot.json", encoding="utf-8"))
    hist = json.load(open(ROOT / "evidence/quarterly_history.json", encoding="utf-8"))
    print("HISTORY_TARGET_PERIODS", json.dumps(hist.get("target_periods"), ensure_ascii=False))
    print("HISTORY_SUPPORT_PERIODS", json.dumps(hist.get("support_periods"), ensure_ascii=False))
    missing = []
    for code, row in quant["records"].items():
        m1 = row["m01"]
        m2 = row["m02"]
        if m1.get("raw") is not None and m2.get("raw") is not None:
            continue
        fm = kind.get(code, {}).get("fiscal_month")
        if fm in (None, 12):
            continue
        pts = (hist.get("records", {}).get(code) or {}).get("points") or []
        compact = []
        for p in pts:
            compact.append({
                "period": p.get("period"),
                "revenue_yoy": p.get("revenue_yoy"),
                "operating_margin": p.get("operating_margin"),
                "basis": p.get("basis"),
                "scope": p.get("scope"),
                "reason": p.get("reason"),
            })
        missing.append({
            "code": code,
            "name": row.get("name"),
            "fiscal_month": fm,
            "m01_reason": m1.get("reason"),
            "m02_reason": m2.get("reason"),
            "points": compact,
        })
    print("NON12_MISSING_COUNT", len(missing))
    for item in missing[:100]:
        print("NON12_MISSING", json.dumps(item, ensure_ascii=False))


if __name__ == "__main__":
    main()
