#!/usr/bin/env python3
"""Probe the five post-V3 issuers that could otherwise become complete."""
from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Any

import apply_fiscal_calendar_recovery as fiscal
import collect_real_quant as base
import collect_real_quant_v2 as v2

CODES=("0120G0","365270","372320","388870","475830")


def actual_finance(payload:Any, as_of:date)->dict[str,Any]:
    fi=payload.get('financeInfo') if isinstance(payload,dict) else None
    if not isinstance(fi,dict): return {'actual_keys':[],'rows':{}}
    keys=[]
    for x in fi.get('trTitleList') or []:
        if not isinstance(x,dict) or not fiscal._is_actual_title(x): continue
        k=str(x.get('key') or '').strip(); e=fiscal._period_end(k)
        if e is not None and e<=as_of: keys.append(k)
    keys=sorted(set(keys))
    rows={}
    for r in fi.get('rowList') or []:
        if not isinstance(r,dict): continue
        t=str(r.get('title') or r.get('name') or '').replace(' ','').strip()
        if t not in {'매출액','영업이익','영업이익률'}: continue
        vals={}
        for k in keys:
            val=fiscal._cell_value(r.get('columns'),k)
            if val is not None: vals[k]=val
        rows[t]=vals
    return {'actual_keys':keys,'rows':rows}


def main()->None:
    q=json.load(open('evidence/real_quant_snapshot.json',encoding='utf-8'))
    h=json.load(open('evidence/quarterly_history.json',encoding='utf-8'))
    as_of=date.fromisoformat(q['snapshot_date_kst'])
    out={'codes':list(CODES),'records':{},'errors':{}}
    for code in CODES:
        row=q['records'][code]
        rec={
            'name':row['name'],'listing_date':row.get('listing_date'),'sector':row.get('sector'),
            'missing':{m.upper():row[m].get('reason') for m in ('m01','m02','m03','m04') if row[m].get('raw') is None},
            'dart_history_points':(h.get('records',{}).get(code) or {}).get('points',[]),
        }
        for label in ('annual','quarter'):
            try:
                rec[f'naver_{label}_actual']=actual_finance(v2.get_json(f'{base.NAVER_BASE}/{code}/finance/{label}'),as_of)
            except Exception as exc:
                out['errors'][f'{code}:{label}']=f'{type(exc).__name__}:{exc}'
        out['records'][code]=rec
    out['error_count']=len(out['errors'])
    Path('evidence/final_five_recovery_probe.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'error_count':out['error_count'],'codes':list(out['records'])},ensure_ascii=False))

if __name__=='__main__': main()
