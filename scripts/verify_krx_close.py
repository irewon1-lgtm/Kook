#!/usr/bin/env python3
"""Read-only reconciliation of KR4 closing-price HOLDs against submitted KRX exports.

This tool cannot independently authenticate that a supplied file came from KRX.
Every case remains HOLD; no market data, rankings, or caches are modified.

Example:
 python scripts/verify_krx_close.py \
   --manifest evidence/kr4_krx_close_hold_20261106.json \
   --krx-file path/to/official_kospi.csv --krx-file path/to/official_kosdaq.csv \
   --source-url https://data.krx.co.kr/ \
   --output /tmp/kr4-krx-review.json
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
from pathlib import Path
from urllib.parse import urlparse

DATE_FIELDS = ('BAS_DD', '기준일자', '일자', '거래일', '조회일자')
CODE_FIELDS = ('ISU_SRT_CD', 'ISU_CD', '종목코드', '단축코드')
CLOSE_FIELDS = ('TDD_CLSPRC', '종가')
KRX_HOSTS = {'data.krx.co.kr', 'openapi.krx.co.kr', 'data-dbg.krx.co.kr'}
CODE_RE = re.compile(r'[0-9A-Z]{6}\Z')


def _pick(row: dict, choices: tuple[str, ...], label: str) -> str:
    for key in choices:
        if key in row and row[key] is not None and str(row[key]).strip():
            return str(row[key]).strip()
    raise ValueError(f'Missing KRX {label} field ({", ".join(choices)})')


def _price(raw: str) -> int:
    clean = str(raw).strip().replace(',', '')
    if not re.fullmatch(r'[0-9]+', clean):
        raise ValueError('Invalid official close: expected nonnegative integer')
    value = int(clean)
    if value <= 0:
        raise ValueError('Official close must be positive; halted/zero-price rows require review')
    return value


def _normalize_date(raw: str) -> str:
    value = str(raw).strip().replace('-', '')
    if not re.fullmatch(r'[0-9]{8}', value):
        raise ValueError('Invalid KRX trading date')
    from datetime import datetime
    datetime.strptime(value, '%Y%m%d')
    return value


def load_hold_manifest(path: Path) -> dict:
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    if data.get('schema_version') != 1 or data.get('trading_date') != '2026-11-06':
        raise ValueError('Unexpected hold manifest schema or trading date')
    rows = data.get('records')
    if data.get('hold_count') != 67 or not isinstance(rows, list) or len(rows) != 67:
        raise ValueError('Exactly 67 original HOLD rows required')
    codes = [row.get('issuer_code') for row in rows]
    if len(set(codes)) != 67 or any(not isinstance(c, str) or not CODE_RE.fullmatch(c) for c in codes):
        raise ValueError('Duplicate/malformed HOLD stock code')
    if any(row.get('decision') != 'HOLD_KRX_OFFICIAL_CLOSE_UNVERIFIED' or
           type(row.get('archived_close_krw')) is not int or row['archived_close_krw'] <= 0
           for row in rows):
        raise ValueError('Unsafe archive or unlocked HOLD')
    return data


def parse_krx_files(paths: list[Path], expected_date: str) -> tuple[dict, list[dict]]:
    observed = {}
    sources = []
    for path in paths:
        raw = path.read_bytes()
        if path.suffix.lower() == '.csv':
            content = raw.decode('utf-8-sig')
            rows = list(csv.DictReader(io.StringIO(content)))
        elif path.suffix.lower() == '.json':
            content = json.loads(raw.decode('utf-8-sig'))
            if not isinstance(content, dict) or not isinstance(content.get('OutBlock_1'), list):
                raise ValueError('Only original KRX OutBlock_1 JSON is accepted')
            rows = content['OutBlock_1']
        else:
            raise ValueError('KRX source must be CSV or API JSON')
        if not rows:
            raise ValueError('Empty official KRX input; HOLD cannot be released')
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError('Malformed KRX record')
            date = _normalize_date(_pick(row, DATE_FIELDS, 'date'))
            if date != expected_date:
                raise ValueError(f'KRX input date {date} differs from expected {expected_date}')
            code = _pick(row, CODE_FIELDS, 'stock code')
            if not CODE_RE.fullmatch(code):
                raise ValueError(f'Noncanonical KRX code: {code}')
            close = _price(_pick(row, CLOSE_FIELDS, 'closing price'))
            if code in observed:
                raise ValueError(f'Duplicate KRX code across submitted exports: {code}')
            observed[code] = close
        sources.append({'filename': path.name, 'sha256': hashlib.sha256(raw).hexdigest(),
                        'size_bytes': len(raw), 'records': len(rows)})
    return observed, sources


def reconcile(manifest: dict, official: dict, sources: list[dict], source_url: str | None) -> dict:
    if official:
        parsed = urlparse(source_url or '')
        if parsed.scheme != 'https' or parsed.hostname not in KRX_HOSTS:
            raise ValueError('Official KRX URL metadata is required for submitted exports')
    rows = []
    for entry in manifest['records']:
        code, old = entry['issuer_code'], entry['archived_close_krw']
        observed = official.get(code)
        label = ('MISSING_KRX_RECORD' if observed is None else
                 'MATCH_ARCHIVED_CLOSE' if observed == old else 'DIFFERS_FROM_ARCHIVED_CLOSE')
        rows.append({'issuer_code': code, 'archived_close_krw': old,
                     'submitted_krx_close_krw': observed, 'comparison': label,
                     'decision': 'HOLD_REQUIRES_SOURCE_AUTHENTICATION_AND_APPROVAL'})
    from collections import Counter
    counts = Counter(row['comparison'] for row in rows)
    return {'schema_version': 1, 'trading_date': manifest['trading_date'],
            'input_hold_run_id': manifest['source_run_id'],
            'input_hold_log_sha256': manifest['collector_log_sha256'],
            'krx_source_url_claim': source_url, 'krx_files': sources,
            'data_authenticity': 'NOT_INDEPENDENTLY_AUTHENTICATED',
            'automatic_price_changes': 0, 'remaining_hold': len(rows),
            'counts': dict(sorted(counts.items())), 'records': rows}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--krx-file', type=Path, action='append', default=[])
    parser.add_argument('--source-url', type=str)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    manifest = load_hold_manifest(options.manifest)
    submitted, sources = parse_krx_files(options.krx_file, '20261106') if options.krx_file else ({}, [])
    result = reconcile(manifest, submitted, sources, options.source_url)
    options.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = options.output.with_suffix(options.output.suffix + '.tmp')
    temporary.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    temporary.replace(options.output)
    print(json.dumps({'hold': result['remaining_hold'], 'comparison_counts': result['counts'],
                      'automatic_price_changes': 0, 'output': str(options.output)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
