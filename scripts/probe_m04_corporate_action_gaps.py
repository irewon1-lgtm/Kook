#!/usr/bin/env python3
from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Any

import collect_real_quant as base
import collect_real_quant_v2 as v2

CODES=("006380","008290","046070","082660")
OUT=Path("evidence/m04_corporate_action_gap_probe.json")


def compact_bar(bar: dict[str, Any]) -> dict[str, Any]:
    # Preserve every scalar key because the probe's purpose is to discover
    # whether Naver exposes another reference/adjustment field on the gap day.
    return {str(k): v for k,v in bar.items() if not isinstance(v,(dict,list))}


def get_mobile_bars(code:str)->list[dict[str,Any]]:
    out=[]
    for page in range(1,5):
        bars=v2.get_json(f"{base.NAVER_BASE}/{code}/price?pageSize=60&page={page}")
        if not isinstance(bars,list):
            raise RuntimeError(f"{code}:price not list")
        out.extend(x for x in bars if isinstance(x,dict))
        if len(bars)<60: break
    return out


def get_chart(code:str,start:str,end:str)->Any:
    # Alternate Naver chart API. We do not assume it is adjusted; the probe only
    # records what it returns around the suspicious corporate-action dates.
    url=(f"https://api.stock.naver.com/chart/domestic/item/{code}"
         f"?periodType=dayCandle&startDateTime={start}&endDateTime={end}")
    # use a plain requests session because v2.get_json uses m.stock headers;
    # Naver accepts these headers on this host as well.
    return v2.get_json(url)


def main()->None:
    snap=json.load(open('evidence/real_quant_snapshot.json',encoding='utf-8'))
    cutoff=date.fromisoformat(snap['price_cutoff_date_kst'])
    target=base.subtract_six_months(cutoff)
    result={'cutoff':str(cutoff),'target':str(target),'records':{},'errors':{}}
    for code in CODES:
        row=snap['records'][code]
        rec={'name':row['name'],'snapshot_m04':row['m04'],'suspicious':[],'all_scalar_keys':[]}
        try:
            raw=get_mobile_bars(code)
            keys=set()
            parsed=[]
            for b in raw:
                keys.update(str(k) for k,v in b.items() if not isinstance(v,(dict,list)))
                d=base.parse_date(str(b.get('localTradedAt') or ''))
                c=base.parse_number(b.get('closePrice'))
                if d and c and d<=cutoff:
                    parsed.append((d,c,b,v2._daily_reference_factor(b,c)))
            parsed.sort(key=lambda x:x[0])
            rec['all_scalar_keys']=sorted(keys)
            for i in range(1,len(parsed)):
                pd,pc,pb,pf=parsed[i-1]
                d,c,b,f=parsed[i]
                if d<target: continue
                raw_factor=c/pc if pc else None
                if f is None and not v2._valid_daily_factor(raw_factor):
                    rec['suspicious'].append({
                        'previous_date':str(pd),'previous_close':pc,'date':str(d),'close':c,
                        'raw_factor':raw_factor,'reference_factor':f,
                        'previous_bar':compact_bar(pb),'bar':compact_bar(b),
                    })
            if rec['suspicious']:
                first=rec['suspicious'][0]['previous_date'].replace('-','')
                last=rec['suspicious'][-1]['date'].replace('-','')
                try:
                    rec['alternate_chart_payload']=get_chart(code,first,last)
                except Exception as exc:
                    rec['alternate_chart_error']=f"{type(exc).__name__}:{exc}"
        except Exception as exc:
            result['errors'][code]=f"{type(exc).__name__}:{exc}"
        result['records'][code]=rec
    result['error_count']=len(result['errors'])
    OUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({
        'error_count':result['error_count'],
        'summary':{code:{'name':r['name'],'suspicious_count':len(r['suspicious']),
                         'keys':r['all_scalar_keys'],'chart_error':r.get('alternate_chart_error')}
                   for code,r in result['records'].items()}
    },ensure_ascii=False,indent=2))

if __name__=='__main__': main()
