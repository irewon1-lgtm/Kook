#!/usr/bin/env python3
from __future__ import annotations
import json
import requests

CODES = ["021820","030960","032800","050860","093240","097870","169330","189690","334970","950210","092440","099750","067010","060310","033200","020180"]
BASE = "https://m.stock.naver.com/api/stock"
HEADERS = {"User-Agent":"Mozilla/5.0 KR4-FiscalQuarter/1.0","Referer":"https://m.stock.naver.com/","Accept":"application/json, text/plain, */*"}


def slim(value):
    if isinstance(value, dict):
        out={}
        for k,v in value.items():
            if k in {"financeInfo","rowList","trTitleList","title","key","isConsensus","columns","value","valueDesc","code","unit"}:
                out[k]=slim(v)
        return out
    if isinstance(value, list):
        return [slim(x) for x in value[:12]]
    return value


def main():
    s=requests.Session(); s.headers.update(HEADERS)
    for code in CODES:
        url=f"{BASE}/{code}/finance/quarter"
        try:
            r=s.get(url,timeout=20); r.raise_for_status(); d=r.json()
            print("NAVER_QUARTER",code,json.dumps(slim(d),ensure_ascii=False))
        except Exception as e:
            print("NAVER_QUARTER_ERROR",code,type(e).__name__,str(e))

if __name__=="__main__":
    main()
