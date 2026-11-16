#!/usr/bin/env python3
"""Read-only, provenance-preserving KRX 2026-11-06 close reconciliation.

No market data or published cache is ever modified. The official KRX API needs a
separately approved AUTH_KEY; without it, no comparison is claimed to be made.

Usage (local):
  python scripts/audit_pc_krx_closes.py --status-only
  python scripts/audit_pc_krx_closes.py --krx-stk stk.json --krx-ksq ksq.json \
    --naver-candidates candidates.json
  KRX_AUTH_KEY in your private environment (never in Git):
  python scripts/audit_pc_krx_closes.py --fetch-krx

All verdicts are HOLD. This tool has no promotion/commit/publish operation.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REGISTRY = ROOT / 'evidence' / 'pc_krx_close_hold_20261106.csv'
BASE = 'https://data-dbg.krx.co.kr/svc/apis/sto/'
DAY = '20261106'
CODE_RE = re.compile(r'^[0-9A-Z]{6}$')
SHA_RE = re.compile(r'^[0-9a-f]{40}$')


def read_registry(path: Path) -> list[dict]:
    with path.open(newline='', encoding='utf-8-sig') as stream:
        reader = csv.DictReader(stream)
        expected = ['issuer_id', 'trade_date', 'archived_close_krw', 'github_blob_sha1', 'status']
        if reader.fieldnames != expected:
            raise ValueError('Registry header differs from pinned schema')
        result = []
        for row in reader:
            code = row['issuer_id']
            if not CODE_RE.fullmatch(code) or not SHA_RE.fullmatch(row['github_blob_sha1']):
                raise ValueError('Invalid issuer identifier or evidence SHA')
            if row['trade_date'] != '2026-11-06' or row['status'] != 'HOLD_KRX_UNVERIFIED':
                raise ValueError('Invalid pinned date/status')
            if not re.fullmatch(r'[1-9][0-9]*', row['archived_close_krw']):
                raise ValueError('Invalid archived close')
            result.append({**row, 'archived_close_krw': int(row['archived_close_krw'])})
    ids = [r['issuer_id'] for r in result]
    if len(ids) != 67 or ids != sorted(set(ids)):
        raise ValueError('Registry must contain exactly 67 unique sorted issuer IDs')
    return result


def _positive_price(value: object) -> int:
    if not isinstance(value, str) or not re.fullmatch(r'[0-9][0-9,]*', value):
        raise ValueError('Invalid KRX price representation')
    n = int(value.replace(',', ''))
    if n <= 0:
        raise ValueError('KRX close must be positive')
    return n


def read_official(payload: bytes, market: str, date: str=DAY, target_codes: set[str]|None=None) -> dict[str, dict]:
    """Returns only exact six-character short codes; never guesses ISIN mappings."""
    obj = json.loads(payload)
    rows = obj.get('OutBlock_1') if isinstance(obj, dict) else None
    if not isinstance(rows, list):
        raise ValueError('Official KRX OutBlock_1 missing or invalid: ' + market)
    result: dict[str, dict] = {}
    for row in rows:
        if not isinstance(row, dict) or row.get('BAS_DD') != date:
            raise ValueError('Unexpected KRX trade date/row: ' + market)
        code = row.get('ISU_SRT_CD') or row.get('ISU_CD')
        if not isinstance(code, str) or not CODE_RE.fullmatch(code):
            # Any ISIN must be separately mapped using approved KRX base-info;
            # deriving six digits by slicing would permit a wrong match.
            continue
        if target_codes is not None and code not in target_codes:
            # Unrelated suspended/zero-priced securities cannot block the 67.
            continue
        if code in result:
            raise ValueError('Duplicate official KRX issuer ' + code)
        result[code] = {'market': market, 'close': _positive_price(row.get('TDD_CLSPRC')),
                        'name': row.get('ISU_NM', '')}
    return result


def read_candidates(path: Path, date: str=DAY) -> dict[str, dict]:
    """Optional separate Naver provider observations, never official KRX evidence."""
    obj = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(obj, dict) or obj.get('trade_date') != date:
        raise ValueError('Naver candidate trade date mismatch')
    records = obj.get('records')
    if not isinstance(records, list):
        raise ValueError('Invalid Naver candidate records')
    out = {}
    for row in records:
        if not isinstance(row, dict) or not CODE_RE.fullmatch(str(row.get('issuer_id',''))):
            raise ValueError('Bad Naver issuer code')
        if row['issuer_id'] in out:
            raise ValueError('Duplicate Naver candidate code')
        sha = row.get('raw_sha256')
        close = row.get('close')
        if not isinstance(close, int) or type(close) is bool or close <= 0 or not isinstance(sha, str) or not re.fullmatch('[a-f0-9]{64}',sha):
            raise ValueError('Candidate must have positive integral close and raw SHA256')
        out[row['issuer_id']] = {'close':close,'raw_sha256':sha}
    return out


def evaluate(registry: list[dict], official: dict[str, dict]|None=None,
             candidates: dict[str, dict]|None=None, official_sha256: dict|None=None) -> dict:
    official = official or {}
    candidates = candidates or {}
    seen = set()
    rows = []
    for old in registry:
        code = old['issuer_id']
        seen.add(code)
        krx = official.get(code)
        candidate = candidates.get(code)
        old_price = old['archived_close_krw']
        krx_price = krx['close'] if krx else None
        naver_price = candidate['close'] if candidate else None
        if not official_sha256:
            classification = 'BLOCKED_KRX_NOT_VERIFIED'
        elif krx is None:
            classification = 'HOLD_NO_KRX_ROW'
        elif candidate is None:
            classification = 'HOLD_NAVER_NOT_RECHECKED'
        elif krx_price == old_price and krx_price != naver_price:
            classification = 'KRX_SUPPORTS_ARCHIVED_CLOSE'
        elif krx_price == naver_price and krx_price != old_price:
            classification = 'KRX_SUPPORTS_REVISED_CLOSE'
        elif krx_price == naver_price == old_price:
            classification = 'KRX_AND_NAVER_MATCH_ARCHIVE'
        else:
            classification = 'HOLD_THREE_WAY_DISAGREEMENT'
        rows.append({
            'issuer_id':code, 'trade_date':'2026-11-06',
            'archived_close_krw':old_price, 'krx_close_krw':krx_price,
            'naver_close_krw':naver_price,
            'classification':classification, 'decision':'HOLD',
            'github_blob_sha1':old['github_blob_sha1'],
            'krx_market':krx.get('market') if krx else None,
            'krx_name':krx.get('name') if krx else None,
            'naver_raw_sha256':candidate.get('raw_sha256') if candidate else None,
        })
    if set(candidates) - seen:
        raise ValueError('Naver candidate includes off-registry issuer')
    return {'schema':'KR4_KRX_CLOSE_AUDIT_V1', 'trade_date':'2026-11-06',
            'official_source':official_sha256 or None, 'promotion_authorized':False,
            'registry_count':len(registry), 'krx_exact_matches':sum(r['krx_close_krw'] is not None for r in rows),
            'by_classification':{s:sum(x['classification']==s for x in rows) for s in sorted(set(x['classification'] for x in rows))},
            'hold_count':len(rows), 'records':rows}


def fetch_krx(market: str, key: str, date: str=DAY) -> bytes:
    api = {'KOSPI':'stk_bydd_trd','KOSDAQ':'ksq_bydd_trd'}[market]
    address = BASE + api + '?' + urlencode({'basDd':date})
    request = Request(address, headers={'AUTH_KEY':key, 'Accept':'application/json'})
    try:
        with urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise RuntimeError('Official KRX non-200 response for ' + market)
            data = response.read(16_000_001)
    except (HTTPError,URLError) as exc:
        # Never emit key, URL query secrets, authentication headers, or raw response.
        raise RuntimeError('KRX endpoint unavailable/unauthorized for '+market) from None
    if len(data) > 16_000_000 or not data:
        raise ValueError('Official KRX response missing/too large')
    return data


def execute(registry_path: Path, *, status_only: bool=False, files: dict[str,Path]|None=None,
            fetch: bool=False, key: str|None=None, candidates_path: Path|None=None) -> dict:
    registry = read_registry(registry_path)
    files = files or {}
    if status_only:
        return evaluate(registry)
    if fetch and files:
        raise ValueError('Do not mix KRX online and supplied-file modes')
    if fetch and not key:
        raise RuntimeError('BLOCKED_RESOURCE: approved KRX_AUTH_KEY is not available')
    if not fetch and set(files) != {'KOSPI','KOSDAQ'}:
        raise RuntimeError('BLOCKED_RESOURCE: both KRX market JSON snapshots required')
    raw = {m:(fetch_krx(m,key) if fetch else files[m].read_bytes()) for m in ('KOSPI','KOSDAQ')}
    source = {m:{'sha256':hashlib.sha256(raw[m]).hexdigest(), 'origin':'KRX_OPEN_API', 'api_id': 'stk_bydd_trd' if m=='KOSPI' else 'ksq_bydd_trd'} for m in raw}
    official = {}
    for m in ('KOSPI','KOSDAQ'):
        data = read_official(raw[m],m,target_codes={x['issuer_id'] for x in registry})
        if set(official).intersection(data):
            raise ValueError('One KRX issuer appears in both KOSPI and KOSDAQ')
        official.update(data)
    candidates = read_candidates(candidates_path) if candidates_path else None
    return evaluate(registry, official, candidates, source)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--registry',type=Path,default=DEFAULT_REGISTRY)
    parser.add_argument('--status-only',action='store_true',help='Show 67 HOLD rows without claiming official verification')
    parser.add_argument('--krx-stk',type=Path,help='Unmodified approved KRX KOSPI response JSON')
    parser.add_argument('--krx-ksq',type=Path,help='Unmodified approved KRX KOSDAQ response JSON')
    parser.add_argument('--fetch-krx',action='store_true',help='Official KRX API read-only fetch, requires KRX_AUTH_KEY in environment')
    parser.add_argument('--naver-candidates',type=Path,help='Optional separately captured Naver candidate closes with raw SHA256')
    parser.add_argument('--output',type=Path,default=ROOT/'.pc-work'/'krx-close-audit-20261106.json')
    args=parser.parse_args()
    if args.status_only and (args.krx_stk or args.krx_ksq or args.fetch_krx or args.naver_candidates):
        parser.error('--status-only cannot be combined with data verification')
    files={}
    if args.krx_stk:files['KOSPI']=args.krx_stk
    if args.krx_ksq:files['KOSDAQ']=args.krx_ksq
    report=execute(args.registry,status_only=args.status_only,files=files,
                   fetch=args.fetch_krx,key=os.getenv('KRX_AUTH_KEY'),candidates_path=args.naver_candidates)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    print(json.dumps({'schema':report['schema'],'registry_count':report['registry_count'],
                      'krx_exact_matches':report['krx_exact_matches'],
                      'by_classification':report['by_classification'],
                      'hold_count':report['hold_count'],'promotion_authorized':False,
                      'output':str(args.output)},ensure_ascii=False))


if __name__=='__main__':
    main()
