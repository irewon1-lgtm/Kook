#!/usr/bin/env python3
"""Temporary live diagnostic for M04 exceptional trading gaps."""
from __future__ import annotations

import json
from datetime import date

import requests

BASE = "https://m.stock.naver.com/api/stock"
TARGETS = {
    "006380": "카프로",
    "008290": "원풍물산",
    "046070": "코다코",
    "082660": "코스나인",
}
START = date(2026, 3, 13)
END = date(2026, 9, 15)


def num(v):
    if v is None:
        return None
    try:
        return float(str(v).replace(",", "").replace("%", "").strip())
    except Exception:
        return None


def main():
    s = requests.Session()
    s.headers.update({"User-Agent": "Mozilla/5.0", "Referer": "https://m.stock.naver.com/"})
    for code, name in TARGETS.items():
        rows = {}
        for page in range(1, 6):
            r = s.get(f"{BASE}/{code}/price?pageSize=60&page={page}", timeout=15)
            r.raise_for_status()
            payload = r.json()
            if not isinstance(payload, list) or not payload:
                break
            for bar in payload:
                d_raw = str(bar.get("localTradedAt") or "")[:10]
                try:
                    d = date.fromisoformat(d_raw)
                except Exception:
                    continue
                if START <= d <= END:
                    rows[d] = bar
            if min(rows, default=END) <= START or len(payload) < 60:
                break
        dates = sorted(rows)
        print(f"TARGET {code} {name} bars={len(dates)} first={dates[0] if dates else None} last={dates[-1] if dates else None}")
        prev_d = None
        prev_close = None
        for d in dates:
            bar = rows[d]
            close = num(bar.get("closePrice"))
            if close is None or close <= 0:
                continue
            raw_factor = close / prev_close if prev_close else None
            ratio = num(bar.get("fluctuationsRatio"))
            compare = num(bar.get("compareToPreviousClosePrice"))
            ratio_factor = (1 + ratio / 100.0) if ratio is not None else None
            gap_days = (d - prev_d).days if prev_d else None
            exceptional = (
                prev_close is not None and (
                    raw_factor < 0.69 or raw_factor > 1.31 or
                    ratio_factor is None or ratio_factor < 0.69 or ratio_factor > 1.31 or
                    (gap_days is not None and gap_days > 10)
                )
            )
            if exceptional:
                keep = {
                    k: bar.get(k)
                    for k in (
                        "localTradedAt", "closePrice", "compareToPreviousClosePrice",
                        "fluctuationsRatio", "openPrice", "highPrice", "lowPrice",
                        "accumulatedTradingVolume"
                    )
                }
                print("GAP", json.dumps({
                    "code": code,
                    "name": name,
                    "prev_date": str(prev_d),
                    "date": str(d),
                    "gap_days": gap_days,
                    "prev_close": prev_close,
                    "raw_factor": raw_factor,
                    "ratio_factor": ratio_factor,
                    "compare": compare,
                    "bar": keep,
                }, ensure_ascii=False, sort_keys=True))
            prev_d = d
            prev_close = close


if __name__ == "__main__":
    main()
