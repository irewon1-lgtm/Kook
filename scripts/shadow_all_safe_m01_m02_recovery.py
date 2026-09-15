#!/usr/bin/env python3
"""Shadow all safe remaining M01/M02 recovery cells.

Unlike the production completion-oriented stage, this diagnostic does not
require an issuer to become four-metric complete. It attempts every currently
missing M01/M02 cell that has an explicitly recoverable reason while keeping
structural N/A untouched.

Safety:
- existing numeric values are immutable;
- FINANCIAL_SECTOR_EXCLUDED M02 is never touched;
- actual completed non-consensus Naver periods only;
- M01 requires same-calendar-month prior-year actual revenue;
- M02 uses direct actual OPM first, computed fallback second;
- M03/M04 never change;
- source errors are recorded and never converted into values.
"""
from __future__ import annotations

import argparse
import json
from copy import deepcopy
from datetime import date
from pathlib import Path
from typing import Any

import apply_recent_dart_fallback as recent
import apply_standard_actual_recovery as standard
import collect_real_quant as base
import collect_real_quant_v2 as v2


def parse_args() -> argparse.Namespace:
    p=argparse.ArgumentParser()
    p.add_argument('--quant',default='../evidence/real_quant_snapshot.json')
    p.add_argument('--out',default='../evidence/all_safe_m01_m02_shadow.json')
    return p.parse_args()


def candidates(snapshot: dict[str,Any]) -> list[str]:
    out=[]
    for code,row in snapshot['records'].items():
        m1=row['m01']; m2=row['m02']
        need1=m1.get('raw') is None and m1.get('reason') in standard.M01_RECOVERABLE
        need2=(m2.get('raw') is None and m2.get('reason') in standard.M02_RECOVERABLE
               and m2.get('reason')!='FINANCIAL_SECTOR_EXCLUDED')
        if need1 or need2:
            out.append(code)
    return sorted(out)


def apply_shadow(before:dict[str,Any], payloads:dict[str,Any]) -> tuple[dict[str,Any],dict[str,Any]]:
    after=deepcopy(before)
    as_of=date.fromisoformat(before['snapshot_date_kst'])
    m01_codes=[]; m02_codes=[]; details={}
    codes=candidates(before)
    for code in codes:
        old=before['records'][code]
        row=after['records'][code]
        payload=payloads.get(code)
        d={'name':row.get('name'),'market':row.get('market'),'sector':row.get('sector')}
        m1=row['m01']
        if m1.get('raw') is None and m1.get('reason') in standard.M01_RECOVERABLE:
            r1=standard.resolve_m01(payload,as_of)
            d['m01_resolver']=r1
            if r1 and r1.get('value') is not None:
                m1.update(raw=round(float(r1['value']),6),reason=None,
                          basis=f"{r1['key']}_NAVER_QUARTER_ACTUAL_YOY_ALL_SAFE_SHADOW")
                m01_codes.append(code)
        m2=row['m02']
        if (m2.get('raw') is None and m2.get('reason') in standard.M02_RECOVERABLE
                and m2.get('reason')!='FINANCIAL_SECTOR_EXCLUDED'):
            r2=standard.resolve_m02(payload,as_of)
            d['m02_resolver']=r2
            if r2 and r2.get('value') is not None:
                m2.update(raw=round(float(r2['value']),6),reason=None,
                          basis=f"{r2['key']}_NAVER_QUARTER_ACTUAL_OPM_{r2['source']}_ALL_SAFE_SHADOW")
                m02_codes.append(code)
        if 'm01_resolver' in d or 'm02_resolver' in d:
            d['remaining_missing']=[m.upper() for m in ('m01','m02','m03','m04') if row[m].get('raw') is None]
            details[code]=d
        for mid in ('m01','m02','m03','m04'):
            if old[mid].get('raw') is not None:
                assert row[mid].get('raw')==old[mid].get('raw'),(code,mid,'raw overwrite')
                assert row[mid].get('basis')==old[mid].get('basis'),(code,mid,'basis overwrite')
        if old['m02'].get('reason')=='FINANCIAL_SECTOR_EXCLUDED':
            assert row['m02'].get('raw') is None and row['m02'].get('reason')=='FINANCIAL_SECTOR_EXCLUDED'
    coverage=recent.recompute_scores(after['records'])
    after['coverage']=coverage
    recent.validate_snapshot(after)
    b=before['coverage']
    assert coverage['m01_available']>=b['m01_available']
    assert coverage['m02_available']>=b['m02_available']
    assert coverage['m03_available']==b['m03_available']
    assert coverage['m04_available']==b['m04_available']
    return after,{
        'candidate_issuer_count':len(codes),
        'm01_recovered':len(m01_codes),'m01_codes':m01_codes,
        'm02_recovered':len(m02_codes),'m02_codes':m02_codes,
        'coverage_before':b,'coverage_after_shadow':coverage,
        'details':details,
    }


def main()->None:
    args=parse_args()
    before=json.loads(Path(args.quant).read_text(encoding='utf-8'))
    payloads={}; errors={}
    for code in candidates(before):
        try:
            payloads[code]=v2.get_json(f"{base.NAVER_BASE}/{code}/finance/quarter")
        except Exception as exc:
            errors[code]=f'{type(exc).__name__}:{exc}'
    _after,summary=apply_shadow(before,payloads)
    summary['source_errors']=errors
    summary['source_error_count']=len(errors)
    Path(args.out).write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:summary[k] for k in ('candidate_issuer_count','m01_recovered','m02_recovered','coverage_before','coverage_after_shadow','source_error_count')},ensure_ascii=False,indent=2))

if __name__=='__main__':
    main()
