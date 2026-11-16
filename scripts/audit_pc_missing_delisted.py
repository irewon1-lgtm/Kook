#!/usr/bin/env python3
"""Read-only: distinguish reported delisted issuers from price-feed failures.

This audit DOES NOT create prices, remove stocks, change rankings, or claim
authenticated KRX issuer-status verification.
"""
from __future__ import annotations
import argparse
from datetime import date
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REGISTER = ROOT / 'evidence' / 'pc_missing_delisted_13_20261116.json'
EXPECTED_DATE = '2026-11-13'


def registry(path: Path) -> dict:
    info = json.loads(path.read_text(encoding='utf-8'))
    if info.get('schema') != 'KR4_MISSING_PRICE_DELISTING_REVIEW_V1':
        raise ValueError('Unknown audit registry schema')
    if info.get('snapshot_cutoff') != EXPECTED_DATE:
        raise ValueError('Unexpected snapshot cutoff')
    if info.get('classification') != 'DELISTING_REPORTED_SECONDARY_SOURCE_PRIMARY_PER_ISSUER_CONFIRMATION_PENDING':
        raise ValueError('Registry cannot assert primary confirmation')
    if not info.get('source_url', '').startswith('https://'):
        raise ValueError('Missing review provenance')
    rows = info.get('rows')
    if not isinstance(rows, list) or len(rows) != 13:
        raise ValueError('Expected 13 independently listed delisting candidates')
    codes = []
    for r in rows:
        code = r['issuer_id']
        if not re.fullmatch(r'[0-9A-Z]{6}', code) or not r['name'] or r['market'] not in ('KOSPI', 'KOSDAQ'):
            raise ValueError('Invalid issuer reference')
        when = date.fromisoformat(r['reported_delisting_date'])
        if when.isoformat() != r['reported_delisting_date'] or when > date.fromisoformat(EXPECTED_DATE):
            raise ValueError('Delisting after observed price cutoff')
        codes.append(code)
    if codes != sorted(set(codes)):
        raise ValueError('Duplicate or unsorted issuer identifiers')
    return info


def parse_collection(path: Path, expected_universe: int=2649) -> dict:
    rows = [json.loads(x) for x in path.read_text(encoding='utf-8').splitlines() if x.strip()]
    starts = [r for r in rows if r.get('event') == 'start']
    finishes = [r for r in rows if r.get('event') == 'complete']
    collected = [r for r in rows if r.get('event') == 'collected']
    if len(starts) != 1 or len(finishes) != 1 or starts[0]['universe'] != expected_universe:
        raise ValueError('Collection run not complete or expected universe changed')
    if starts[0]['cutoff'] != EXPECTED_DATE:
        raise ValueError('Collection cutoff mismatch')
    ids = [x['code'] for x in collected]
    if len(ids) != expected_universe or len(set(ids)) != len(ids):
        raise ValueError('Collection not all issuer codes exactly once')
    cover = finishes[0]['coverage']
    missing = [x for x in collected if x['source'] == 'MISSING' and x['bars'] == 0]
    if len(missing) != cover['missing'] or len(missing) + cover['withBars'] != expected_universe:
        raise ValueError('Collection coverage not reconciled')
    return {x['code']: x for x in collected}


def evaluate(ref: dict, collection: dict | None=None) -> dict:
    rows = []
    expected_codes = {x['issuer_id'] for x in ref['rows']}
    actual_missing = {code for code, r in collection.items() if r['source']=='MISSING' and r['bars']==0} if collection else set()
    if collection is not None and actual_missing != expected_codes:
        raise ValueError('Unexpected missing issuer(s); investigate independently')
    for original in ref['rows']:
        code = original['issuer_id']
        record = collection.get(code) if collection else None
        if record is not None:
            if record['error'] != 'ValueError: No completed daily prices':
                raise ValueError(f'Unexpected missing reason for {code}')
        rows.append({**original,
            'missing_reason':record['error'] if record else None,
            'classification':'DELISTED_REPORTED_PRIMARY_CONFIRMATION_PENDING',
            'current_quote':'NOT_AVAILABLE_IN_ORIGINAL_FEED',
            'ranking_action':'REVIEW_UNIVERSE_ELIGIBILITY_MANUALLY',
            'decision':'HOLD',
        })
    return {'schema':'KR4_MISSING_PRICE_AUDIT_RESULT_V1',
            'snapshot_cutoff':EXPECTED_DATE, 'registry_count':len(rows),
            'observed_missing_count':len(actual_missing) if collection else None,
            'current_universe_changed':False, 'rankings_changed':False,
            'source_grade':'INDEPENDENT_DELISTING_DIRECTORY_NOT_AUTHENTICATED_KRX',
            'decision':'HOLD', 'rows':rows}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--registry',type=Path,default=DEFAULT_REGISTER)
    p.add_argument('--collection',type=Path,help='Actual GitHub price-collection.jsonl from a completed PR execution')
    p.add_argument('--output',type=Path,default=ROOT/'.pc-work'/'pc-missing-delisted-audit.json')
    a=p.parse_args()
    result=evaluate(registry(a.registry),parse_collection(a.collection) if a.collection else None)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('registry_count','observed_missing_count','source_grade','decision','rankings_changed')},ensure_ascii=False))

if __name__=='__main__':
    main()
