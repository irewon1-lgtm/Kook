#!/usr/bin/env python3
from __future__ import annotations
import json
import requests

CODES = ["021820","030960","032800","050860","093240","097870","169330","189690","334970","950210","092440","099750","067010","060310","033200","020180"]
BASE = "https://m.stock.naver.com/api/stock"
HEADERS = {"User-Agent":"Mozilla/5.0 KR4-FiscalQuarter/1.0","Referer":"https://m.stock.naver.com/","Accept":"application/json, text/plain, */*"}
TARGET_ROWS = {"매출액", "영업이익", "영업이익률"}


def main():
    s=requests.Session(); s.headers.update(HEADERS)
    for code in CODES:
        try:
            r=s.get(f"{BASE}/{code}/finance/quarter",timeout=20); r.raise_for_status(); d=r.json()
            fi=d.get("financeInfo") or {}
            titles=fi.get("trTitleList") or []
            actual=[x for x in titles if str(x.get("isConsensus") or "N").upper() != "Y"]
            actual_keys=[str(x.get("key") or "") for x in actual if x.get("key")]
            rows={}
            for row in fi.get("rowList") or []:
                title=str(row.get("title") or "").strip()
                if title not in TARGET_ROWS:
                    continue
                cols=row.get("columns") or {}
                rows[title]={k: cols.get(k) for k in actual_keys if k in cols}
            print("NAVER_QUARTER_VALUES",code,json.dumps({"actual_titles":actual,"rows":rows},ensure_ascii=False))
        except Exception as e:
            print("NAVER_QUARTER_ERROR",code,type(e).__name__,str(e))

if __name__=="__main__":
    main()
