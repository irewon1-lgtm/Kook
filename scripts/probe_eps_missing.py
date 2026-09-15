#!/usr/bin/env python3
"""Probe the nine KR4 NAVER_EPS_MISSING cases without mutating production data.

The goal is to discover whether a safe actual EPS representation exists in
Naver integration/annual/quarter payloads before inventing a new PER fallback.
Only EPS/PER-related fields and actual-period EPS rows are persisted.
"""
from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path
from typing import Any

import apply_fiscal_calendar_recovery as fiscal
import collect_real_quant as base
import collect_real_quant_v2 as v2


def parse_args() -> argparse.Namespace:
    p=argparse.ArgumentParser()
    p.add_argument('--quant', default='evidence/real_quant_snapshot.json')
    p.add_argument('--out', default='evidence/eps_missing_probe.json')
    return p.parse_args()


def recursive_hits(obj: Any, path: str='') -> list[dict[str, Any]]:
    hits=[]
    if isinstance(obj, dict):
        for k,v in obj.items():
            kp=f'{path}.{k}' if path else str(k)
            kl=str(k).lower()
            if 'eps' in kl or 'per' in kl or '주당' in str(k):
                if isinstance(v, (str,int,float,bool)) or v is None:
                    hits.append({'path':kp,'value':v})
            hits.extend(recursive_hits(v,kp))
    elif isinstance(obj,list):
        for i,v in enumerate(obj):
            hits.extend(recursive_hits(v,f'{path}[{i}]'))
    return hits


def finance_eps(payload: Any, as_of: date) -> dict[str, Any]:
    fi=payload.get('financeInfo') if isinstance(payload,dict) else None
    if not isinstance(fi,dict):
        return {'actual_keys':[],'eps_rows':[]}
    actual=[]
    for item in fi.get('trTitleList') or []:
        if not isinstance(item,dict) or not fiscal._is_actual_title(item):
            continue
        key=str(item.get('key') or '').strip()
        end=fiscal._period_end(key)
        if end is not None and end<=as_of:
            actual.append(key)
    actual=sorted(set(actual))
    rows=[]
    for row in fi.get('rowList') or []:
        if not isinstance(row,dict):
            continue
        title=str(row.get('title') or row.get('name') or '').strip()
        low=title.lower()
        if 'eps' not in low and '주당' not in title:
            continue
        values={}
        cols=row.get('columns')
        for key in actual:
            val=fiscal._cell_value(cols,key)
            if val is not None:
                values[key]=val
        rows.append({'title':title,'actual_values':values})
    return {'actual_keys':actual,'eps_rows':rows}


def main() -> None:
    args=parse_args()
    q=json.loads(Path(args.quant).read_text(encoding='utf-8'))
    as_of=date.fromisoformat(q['snapshot_date_kst'])
    candidates=[]
    for code,row in sorted(q['records'].items()):
        if row['m03'].get('raw') is None and row['m03'].get('reason')=='NAVER_EPS_MISSING':
            candidates.append((code,row))
    if len(candidates)!=9:
        raise SystemExit(f'EPS missing gate failed: {len(candidates)} != 9')
    out={'candidate_count':len(candidates),'records':{},'errors':{}}
    for code,row in candidates:
        rec={
            'name':row.get('name'),'market':row.get('market'),'sector':row.get('sector'),
            'listing_date':row.get('listing_date'),
            'other_missing':[m.upper() for m in ('m01','m02','m04') if row[m].get('raw') is None],
            'other_reasons':{m.upper():row[m].get('reason') for m in ('m01','m02','m04') if row[m].get('raw') is None},
        }
        for label,endpoint in (
            ('integration',f'{base.NAVER_BASE}/{code}/integration'),
            ('annual',f'{base.NAVER_BASE}/{code}/finance/annual'),
            ('quarter',f'{base.NAVER_BASE}/{code}/finance/quarter'),
        ):
            try:
                payload=v2.get_json(endpoint)
                if label=='integration':
                    rec['integration_eps_per_hits']=recursive_hits(payload)
                else:
                    rec[f'{label}_eps']=finance_eps(payload,as_of)
            except Exception as exc:
                out['errors'][f'{code}:{label}']=f'{type(exc).__name__}:{exc}'
        out['records'][code]=rec
    out['error_count']=len(out['errors'])
    Path(args.out).write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'candidate_count':out['candidate_count'],'error_count':out['error_count'],
                      'codes':list(out['records'])},ensure_ascii=False,indent=2))

if __name__=='__main__':
    main()
