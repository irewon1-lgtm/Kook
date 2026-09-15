#!/usr/bin/env python3
from __future__ import annotations

import calendar
import json
from collections import Counter
from datetime import date
from pathlib import Path

SNAP=Path('evidence/real_quant_snapshot.json')
OUT=Path('evidence/remaining_gap_diagnostic.json')
METRICS=('m01','m02','m03','m04')


def minus_months(d:date,n:int)->date:
    idx=d.year*12+d.month-1-n
    y,m0=divmod(idx,12); m=m0+1
    return date(y,m,min(d.day,calendar.monthrange(y,m)[1]))


def classify(mid:str, reason:str|None, listing:str|None, snap:date)->str:
    r=reason or 'UNKNOWN'
    if mid=='m02' and r=='FINANCIAL_SECTOR_EXCLUDED': return 'STRUCTURAL_FINANCIAL_M02'
    if mid=='m03' and r=='ZERO_EPS': return 'STRUCTURAL_ZERO_EPS_M03'
    if mid=='m04' and r=='PRICE_HISTORY_SHORTER_THAN_6M':
        if listing:
            try:
                if date.fromisoformat(listing)>minus_months(snap,6):
                    return 'STRUCTURAL_NEW_LISTING_M04'
            except ValueError: pass
        return 'RECOVERABLE_M04_PRICE_HISTORY'
    if mid=='m04' and r=='NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING':
        return 'RECOVERABLE_M04_CORPORATE_ACTION'
    if mid=='m03' and r=='NAVER_EPS_MISSING': return 'RECOVERABLE_M03_EPS_SOURCE'
    if mid=='m01' and r in {'DART_NO_REVENUE','DART_NO_COMPARABLE_PRIOR_REVENUE'}:
        return 'RECOVERABLE_M01_ACCOUNTING_SOURCE'
    if mid=='m02' and r in {'DART_NO_OPERATING_INCOME','DART_NO_COMPARABLE_OPERATING_MARGIN','DART_OPERATING_MARGIN_OUTLIER_GUARD'}:
        return 'RECOVERABLE_M02_ACCOUNTING_SOURCE'
    return f'INVESTIGATE_{mid.upper()}_{r}'


def main()->None:
    d=json.loads(SNAP.read_text(encoding='utf-8'))
    snap=date.fromisoformat(d['snapshot_date_kst'])
    classes=Counter(); buckets=Counter(); combos=Counter(); rows=[]
    for code,row in sorted(d['records'].items()):
        missing=[]; cs={}; rs={}
        for mid in METRICS:
            if row[mid].get('raw') is None:
                missing.append(mid); rs[mid]=row[mid].get('reason')
                c=classify(mid,rs[mid],row.get('listing_date'),snap); cs[mid]=c; classes[c]+=1
        if not missing: continue
        combos['+'.join(m.upper() for m in missing)]+=1
        vals=list(cs.values())
        structural=[v.startswith('STRUCTURAL_') for v in vals]
        recoverable=[v.startswith('RECOVERABLE_') for v in vals]
        if all(structural): bucket='STRUCTURAL_ONLY'
        elif all(recoverable): bucket='RECOVERABLE_ONLY'
        elif any(structural) and any(recoverable): bucket='MIXED_STRUCTURAL_RECOVERABLE'
        else: bucket='INVESTIGATE'
        buckets[bucket]+=1
        rows.append({
            'code':code,'name':row.get('name'),'market':row.get('market'),'sector':row.get('sector'),
            'listing_date':row.get('listing_date'),'missing_metrics':[m.upper() for m in missing],
            'reasons':{m.upper():rs[m] for m in missing},'classes':{m.upper():cs[m] for m in missing},
            'bucket':bucket,
        })
    ca=[r for r in rows if 'M04' in r['classes'] and r['classes']['M04']=='RECOVERABLE_M04_CORPORATE_ACTION']
    ro=[r for r in rows if r['bucket']=='RECOVERABLE_ONLY']
    result={
        'snapshot_date_kst':d['snapshot_date_kst'],'coverage':d['coverage'],'universe_count':len(d['records']),
        'complete_count':d['coverage']['complete_count'],'incomplete_count':len(rows),
        'bucket_counts':dict(buckets.most_common()),'class_counts':dict(classes.most_common()),
        'missing_combination_counts':dict(combos.most_common()),
        'corporate_action_m04_count':len(ca),'corporate_action_m04_records':ca,
        'recoverable_only_count':len(ro),'recoverable_only_records':ro,
        'records':rows,
    }
    assert result['universe_count']==2649
    assert result['complete_count']+result['incomplete_count']==2649
    assert result['corporate_action_m04_count']==4
    OUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('coverage','incomplete_count','bucket_counts','class_counts','corporate_action_m04_count','recoverable_only_count')},ensure_ascii=False,indent=2))

if __name__=='__main__': main()
