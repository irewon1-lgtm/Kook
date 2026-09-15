#!/usr/bin/env python3
"""Classify the current KR4 incomplete set after durable recovery stages."""
from __future__ import annotations

import argparse
import calendar
import json
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

METRICS=("m01","m02","m03","m04")


def subtract_months(d: date, months: int) -> date:
    idx=d.year*12+d.month-1-months
    y,m0=divmod(idx,12); m=m0+1
    day=min(d.day,calendar.monthrange(y,m)[1])
    return date(y,m,day)


def klass(metric:str, reason:str|None, listing_date:str|None, snap:date)->str:
    reason=reason or ""
    if metric=="m02" and reason=="FINANCIAL_SECTOR_EXCLUDED": return "STRUCTURAL_NA_FINANCIAL_M02"
    if metric=="m03" and reason=="ZERO_EPS": return "STRUCTURAL_NA_ZERO_EPS"
    if metric=="m04" and reason=="PRICE_HISTORY_SHORTER_THAN_6M":
        if listing_date:
            try:
                if date.fromisoformat(listing_date)>subtract_months(snap,6):
                    return "STRUCTURAL_NA_NEW_LISTING_M04"
            except ValueError: pass
        return "RECOVERABLE_PRICE_HISTORY"
    if metric=="m03" and reason=="NAVER_EPS_MISSING": return "RECOVERABLE_EPS_SOURCE"
    if metric=="m01" and reason in {"DART_NO_REVENUE","DART_NO_COMPARABLE_PRIOR_REVENUE"}:
        return "RECOVERABLE_ACCOUNTING_SOURCE"
    if metric=="m02" and reason in {"DART_NO_OPERATING_INCOME","DART_NO_COMPARABLE_OPERATING_MARGIN","DART_OPERATING_MARGIN_OUTLIER_GUARD"}:
        return "RECOVERABLE_ACCOUNTING_SOURCE"
    return f"INVESTIGATE_{metric.upper()}_{reason or 'UNKNOWN'}"


def classify(d:dict[str,Any])->dict[str,Any]:
    snap=date.fromisoformat(d["snapshot_date_kst"])
    available=Counter(); missing=Counter(); reasons={m:Counter() for m in METRICS}
    combos=Counter(); classes=Counter(); buckets=Counter(); rows=[]
    for code,row in sorted(d["records"].items()):
        ms=[]; cs={}; rs={}
        for m in METRICS:
            obj=row[m]
            if obj.get("raw") is None:
                missing[m]+=1; ms.append(m); r=obj.get("reason"); reasons[m][r or "UNKNOWN"]+=1
                c=klass(m,r,row.get("listing_date"),snap); cs[m]=c; classes[c]+=1; rs[m]=r
            else: available[m]+=1
        if not ms: continue
        combos["+".join(x.upper() for x in ms)]+=1
        cv=list(cs.values())
        structural=[x.startswith("STRUCTURAL_NA_") for x in cv]
        recoverable=[x.startswith("RECOVERABLE_") for x in cv]
        if cv and all(structural):
            bucket="STRUCTURAL_ONLY"
        elif cv and all(recoverable):
            bucket="RECOVERABLE_ONLY"
        elif any(structural) and any(recoverable):
            bucket="MIXED_STRUCTURAL_RECOVERABLE"
        else:
            bucket="INVESTIGATE"
        buckets[bucket]+=1
        rows.append({"code":code,"name":row.get("name"),"market":row.get("market"),"sector":row.get("sector"),"listing_date":row.get("listing_date"),"missing_metrics":[m.upper() for m in ms],"reasons":{m.upper():rs[m] for m in ms},"classes":{m.upper():cs[m] for m in ms},"issuer_bucket":bucket})
    result={
        "snapshot_date_kst":d["snapshot_date_kst"],"universe_count":len(d["records"]),
        "complete_count":len(d["records"])-len(rows),"incomplete_count":len(rows),
        "available_counts":{m.upper():available[m] for m in METRICS},
        "missing_counts":{m.upper():missing[m] for m in METRICS},
        "issuer_bucket_counts":dict(buckets.most_common()),
        "missing_combination_counts":dict(combos.most_common()),
        "metric_class_counts":dict(classes.most_common()),
        "missing_reason_counts":{m.upper():dict(reasons[m].most_common()) for m in METRICS},
        "records":rows,
    }
    expected=d["coverage"]
    assert result["complete_count"]==expected["complete_count"]
    assert result["available_counts"]=={"M01":expected["m01_available"],"M02":expected["m02_available"],"M03":expected["m03_available"],"M04":expected["m04_available"]}
    assert sum(result["issuer_bucket_counts"].values())==result["incomplete_count"]
    return result


def main()->None:
    p=argparse.ArgumentParser(); p.add_argument("--quant",default="evidence/real_quant_snapshot.json"); p.add_argument("--out",default="evidence/remaining_after_recovery.json"); a=p.parse_args()
    d=json.loads(Path(a.quant).read_text(encoding="utf-8")); r=classify(d)
    Path(a.out).write_text(json.dumps(r,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:r[k] for k in ("universe_count","complete_count","incomplete_count","available_counts","missing_counts","issuer_bucket_counts","missing_combination_counts","metric_class_counts")},ensure_ascii=False,indent=2))

if __name__=="__main__": main()
