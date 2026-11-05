#!/usr/bin/env python3
"""Authenticated official filing provenance without persisting credentials.

The authentication value exists only in memory and outbound request params.
Cached bodies, cache identities, metadata, errors and per-issuer outputs are
public facts. Reception dates come from rcept_dt, never an identifier prefix.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
import re
import time
import xml.etree.ElementTree as ET
import zipfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests
import dart_filing_provenance as public

API = 'https://opendart.fss.or.kr/api'
_request = public._request


class _SafeFailure(Exception):
    def __init__(self, code: str, detail: str):
        super().__init__(detail)
        self.code, self.detail = code, detail


def api_date_windows(start: str, end: str):
    """Yield contiguous inclusive windows containing at most 89 days."""
    cursor, cutoff = date.fromisoformat(start), date.fromisoformat(end)
    while cursor <= cutoff:
        stop = min(cutoff, cursor + timedelta(days=88))
        yield cursor.strftime('%Y%m%d'), stop.strftime('%Y%m%d')
        cursor = stop + timedelta(days=1)


def _xml_bytes(raw: bytes) -> bytes:
    if not zipfile.is_zipfile(io.BytesIO(raw)):
        return raw
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = [item for item in archive.infolist() if not item.is_dir()]
        if len(entries) != 1 or entries[0].filename.upper() != 'CORPCODE.XML' or entries[0].file_size > 128 * 1024 * 1024:
            raise _SafeFailure('COLLECTION_ERROR', 'INVALID_CORPORATION_ARCHIVE')
        return archive.read(entries[0])


def _reject_credentials(raw: bytes, secret: str):
    bodies = [raw]
    if zipfile.is_zipfile(io.BytesIO(raw)):
        bodies.append(_xml_bytes(raw))
    if any(secret.encode() in body or b'crtfc_key' in body.lower() for body in bodies):
        raise _SafeFailure('COLLECTION_ERROR', 'UNSAFE_RESPONSE_REJECTED')


def _check_status(raw: bytes, archive: bool):
    status = ET.fromstring(_xml_bytes(raw)).findtext('status') if archive else json.loads(raw).get('status')
    if archive and status is None:
        return
    if status not in {'000', '013'}:
        detail = 'API_STATUS_' + status if isinstance(status, str) and re.fullmatch(r'\d{3}', status) else 'INVALID_API_STATUS'
        raise _SafeFailure('SOURCE_UNAVAILABLE', detail)


class _Client:
    def __init__(self, directory: Path, secret: str):
        self.directory, self.secret = Path(directory), secret
        self.directory.mkdir(parents=True, exist_ok=True)
        self.gate = public._HostGate()

    def fetch(self, endpoint: str, params: dict[str, str], *, archive=False):
        # The public request alone determines the cache identity. Never use
        # Response.url or an exception string: either may contain credentials.
        request = {'method': 'GET', 'url': API + '/' + endpoint, 'params': dict(params)}
        key = hashlib.sha256(json.dumps(request, sort_keys=True).encode()).hexdigest()
        body_path = self.directory / (key + ('.zip' if archive else '.response.json'))
        meta_path = self.directory / (key + '.metadata.json')
        cached = body_path.exists() and meta_path.exists()
        if cached:
            raw, meta = body_path.read_bytes(), json.loads(meta_path.read_text())
            if any(meta.get(k) != value for k, value in request.items()) or meta.get('sha256') != hashlib.sha256(raw).hexdigest():
                raise _SafeFailure('COLLECTION_ERROR', 'CACHE_INTEGRITY_FAILURE')
            age = (datetime.now(timezone.utc) - datetime.fromisoformat(meta['retrieved_at_utc'])).total_seconds()
            cached = 0 <= age <= 3600
            _reject_credentials(raw, self.secret)
            try:
                _check_status(raw, archive)
            except _SafeFailure:
                cached = False
        if not cached:
            for attempt in range(3):
                try:
                    self.gate.before_request()
                except public._SourceUnavailable:
                    raise _SafeFailure('SOURCE_UNAVAILABLE', 'REQUEST_CIRCUIT_OPEN') from None
                started, response = time.monotonic(), None
                try:
                    response = _request('GET', request['url'], params={**params, 'crtfc_key': self.secret},
                                        headers=public.HEADERS, timeout=30)
                    response.raise_for_status()
                    raw = response.content
                    _reject_credentials(raw, self.secret)
                    _check_status(raw, archive)
                    self.gate.success()
                    break
                except requests.RequestException as exc:
                    failure = {**request, 'status_code': response.status_code if response is not None else None,
                               'retrieved_at_utc': datetime.now(timezone.utc).isoformat(), 'attempt': attempt + 1,
                               'elapsed_seconds': round(time.monotonic() - started, 6), 'error_type': type(exc).__name__}
                    errors = self.directory / 'errors'
                    errors.mkdir(exist_ok=True)
                    error_path = errors / (key + '-' + str(time.time_ns()) + '.json')
                    error_path.write_text(json.dumps(failure, ensure_ascii=False, indent=2) + '\n')
                    self.gate.failure(RuntimeError(type(exc).__name__), failure)
                    if attempt == 2:
                        raise _SafeFailure('SOURCE_UNAVAILABLE', type(exc).__name__) from None
            meta = {**request, 'status_code': response.status_code,
                    'retrieved_at_utc': datetime.now(timezone.utc).isoformat(),
                    'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw), 'authenticated': True}
            body_path.write_bytes(raw)
            meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
        evidence = {**meta, 'raw_path': str(body_path.resolve()), 'metadata_path': str(meta_path.resolve())}
        return raw, evidence


def _corporations(raw: bytes):
    root = ET.fromstring(_xml_bytes(raw))
    status = root.findtext('status')
    if status and status != '000':
        raise _SafeFailure('SOURCE_UNAVAILABLE', 'API_STATUS_' + status if re.fullmatch(r'\d{3}', status) else 'INVALID_API_STATUS')
    mapping = {}
    for item in root.findall('list'):
        code, corp, name = (str(item.findtext(key) or '').strip() for key in ('stock_code', 'corp_code', 'corp_name'))
        if not re.fullmatch(r'[0-9A-Z]{6}', code):
            continue
        identity = {'stock_code': code, 'corp_code': corp, 'name': name}
        if code in mapping and mapping[code] != identity:
            mapping[code] = {'ambiguous': True}
        else:
            mapping[code] = identity
    if not root.findall('list'):
        raise _SafeFailure('COLLECTION_ERROR', 'EMPTY_CORPORATION_MAPPING')
    return mapping


def _pages(client: _Client, query: dict[str, str]):
    records, evidence, seen, expected_meta = [], [], set(), None
    page_number, total_pages = 1, 1
    while page_number <= total_pages:
        raw, item = client.fetch('list.json', {**query, 'page_no': str(page_number), 'page_count': '100'})
        evidence.append(item)
        payload = json.loads(raw)
        status = payload.get('status')
        if status == '013' and page_number == 1:
            return [], evidence
        if status != '000':
            detail = 'API_STATUS_' + status if isinstance(status, str) and re.fullmatch(r'\d{3}', status) else 'INVALID_API_STATUS'
            raise _SafeFailure('SOURCE_UNAVAILABLE', detail)
        total, count, pages = (int(payload[key]) for key in ('total_count', 'page_count', 'total_page'))
        if int(payload['page_no']) != page_number or not 1 <= count <= 100 or total < 1 or pages != math.ceil(total / count) or pages > 1000:
            raise _SafeFailure('COLLECTION_ERROR', 'INVALID_API_PAGINATION')
        if expected_meta is not None and expected_meta != (total, count, pages):
            raise _SafeFailure('COLLECTION_ERROR', 'CHANGED_API_PAGINATION')
        expected_meta, total_pages = (total, count, pages), pages
        page_records = payload['list']
        expected_length = min(count, total - (page_number - 1) * count)
        if not isinstance(page_records, list) or len(page_records) != expected_length:
            raise _SafeFailure('COLLECTION_ERROR', 'INCOMPLETE_API_PAGE')
        for record in page_records:
            if not isinstance(record, dict) or not re.fullmatch(r'\d{14}', str(record.get('rcept_no', ''))):
                raise _SafeFailure('COLLECTION_ERROR', 'MALFORMED_API_RECORD')
            if record['rcept_no'] in seen:
                raise _SafeFailure('COLLECTION_ERROR', 'DUPLICATE_API_RECEIPT')
            seen.add(record['rcept_no'])
            records.append(record)
        page_number += 1
    if len(records) != expected_meta[0]:
        raise _SafeFailure('COLLECTION_ERROR', 'INCOMPLETE_API_PAGINATION')
    return records, evidence


def _resolve(code, spec, records, identity, cutoff):
    matching = [record for record in records if record.get('corp_code') == identity['corp_code']]
    out = public._empty(code, spec, cutoff)
    out.update(corp_code=identity['corp_code'], profile_stock_code=identity['stock_code'], profile_company_name=identity['name'])
    if not matching:
        out['error'] = 'FILING_NOT_FOUND'
        return out
    if any(record.get('stock_code') != code or public._name(str(record.get('corp_name', ''))) != public._name(spec['name']) for record in matching):
        out['error'] = 'IDENTITY_MISMATCH'
        return out
    rows = []
    for record in matching:
        received = str(record.get('rcept_dt') or '')
        try:
            if not re.fullmatch(r'\d{8}', received):
                raise ValueError
            received_date = datetime.strptime(received, '%Y%m%d').date()
        except ValueError:
            out['error'] = 'INVALID_FILING_DATE'
            return out
        rows.append({'corp_code': record['corp_code'], 'company_name': record['corp_name'],
                     'receipt_no': record['rcept_no'], 'report_name': record.get('report_nm', ''),
                     'filing_date_text': received_date.isoformat()})
    out = public.resolve_filing_rows(code, spec, rows, identity, as_of=cutoff)
    if out['verified']:
        out['correction_check'] = 'OPENDART_API: last_reprt_at=Y; explicit rcept_dt; exact stock/name/type/period; bulk amendment incorporation unverified'
        record = next(record for record in matching if record['rcept_no'] == out['receipt_no'])
        remarks = record.get('rm')
        if not isinstance(remarks, str):
            out.update(verified=False, error='UNRECOGNIZED_FILING_REMARK')
        elif '철' in remarks:
            out.update(verified=False, error='WITHDRAWN_FILING')
        elif '정' in remarks:
            out.update(verified=False, error='SUPERSEDED_FILING')
        elif set(re.sub(r'\s+', '', remarks)) - set('유코채넥공연'):
            out.update(verified=False, error='UNRECOGNIZED_FILING_REMARK')
        if not out['verified']:
            out['correction_check'] += '; API remark prevents final-version verification'
    return out


def collect_authenticated_filing_provenance(expected: dict[str, dict[str, Any]], cache_dir: Path,
                                             api_key: str, as_of: str) -> dict[str, dict[str, Any]]:
    """Return official authenticated metadata for each expected stock, fail closed."""
    cutoff = date.fromisoformat(as_of)
    directory = Path(cache_dir)
    directory.mkdir(parents=True, exist_ok=True)
    progress = directory / 'progress'
    progress.mkdir(exist_ok=True)
    result = {code: {**public._empty(code, spec, cutoff), 'source': 'OPENDART_AUTHENTICATED_API'}
              for code, spec in expected.items()}
    if not isinstance(api_key, str) or not api_key:
        for row in result.values():
            row['error'] = 'AUTHENTICATION_REQUIRED'
        return result
    client = _Client(directory, api_key)
    try:
        raw, corp_evidence = client.fetch('corpCode.xml', {}, archive=True)
        mapping = _corporations(raw)
    except Exception as exc:
        code = exc.code if isinstance(exc, _SafeFailure) else 'COLLECTION_ERROR'
        detail = exc.detail if isinstance(exc, _SafeFailure) else type(exc).__name__
        for row in result.values():
            row.update(error=code, error_detail=detail)
        return result
    groups = {}
    for code, spec in expected.items():
        row, identity = result[code], mapping.get(code)
        row['evidence'] = [corp_evidence]
        invalid = public._validate_expected(code, spec, cutoff)
        if invalid:
            row['error'] = invalid
        elif not identity:
            row['error'] = 'OFFICIAL_IDENTITY_NOT_FOUND'
        elif identity.get('ambiguous'):
            row['error'] = 'AMBIGUOUS_IDENTITY'
        elif not re.fullmatch(r'\d{8}', identity['corp_code']) or public._name(identity['name']) != public._name(spec['name']):
            row['error'] = 'IDENTITY_MISMATCH'
        else:
            row.update(corp_code=identity['corp_code'], profile_stock_code=code, profile_company_name=identity['name'])
            groups.setdefault((public.REPORT_TYPES[spec['report_kind']], int(spec['period_end'][:4])), []).append(code)
    for (report_type, year), codes in sorted(groups.items()):
        scopes = [[code] for code in sorted(codes)] if len(codes) <= 10 else [sorted(codes)]
        for scope in scopes:
            base = {'last_reprt_at': 'Y', 'pblntf_detail_ty': report_type, 'sort': 'date', 'sort_mth': 'desc'}
            if len(scope) == 1:
                queries = [{**base, 'corp_code': mapping[scope[0]]['corp_code'], 'bgn_de': f'{year}0101', 'end_de': cutoff.strftime('%Y%m%d')}]
            else:
                queries = [{**base, 'bgn_de': start, 'end_de': end} for start, end in api_date_windows(f'{year}-01-01', cutoff.isoformat())]
            records, evidence, receipt_records = [], [], {}
            try:
                for query in queries:
                    entries, pages = _pages(client, query)
                    evidence.extend(pages)
                    for record in entries:
                        key = record['rcept_no']
                        if key in receipt_records and receipt_records[key] != record:
                            raise _SafeFailure('COLLECTION_ERROR', 'CONFLICTING_API_RECEIPT')
                        if key not in receipt_records:
                            receipt_records[key] = record
                            records.append(record)
                index_raw = (json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()
                index_hash = hashlib.sha256(index_raw).hexdigest()
                index_path = directory / (index_hash + '.index.json')
                index_path.write_bytes(index_raw)
                receipt_sources = {}
                for page in evidence:
                    for record in json.loads(Path(page['raw_path']).read_bytes()).get('list', []):
                        receipt_sources[record['rcept_no']] = page
                for code in scope:
                    row = _resolve(code, expected[code], records, mapping[code], cutoff)
                    row['source'] = 'OPENDART_AUTHENTICATED_API'
                    receipt = row.get('receipt_no') or next((record['rcept_no'] for record in records
                        if record.get('corp_code') == mapping[code]['corp_code']), None)
                    selected = receipt_sources.get(receipt) or (evidence[0] if evidence else None)
                    row['evidence'] = [corp_evidence] + ([selected] if selected else [])
                    row['search_completeness'] = {'pages': len(evidence), 'rows': len(records), 'queries': len(queries),
                                                  'index_path': str(index_path.resolve()), 'index_sha256': index_hash}
                    if evidence:
                        row['correction_checked_at_utc'] = evidence[-1]['retrieved_at_utc']
                    result[code] = row
            except Exception as exc:
                error = exc.code if isinstance(exc, _SafeFailure) else 'COLLECTION_ERROR'
                detail = exc.detail if isinstance(exc, _SafeFailure) else type(exc).__name__
                for code in scope:
                    result[code].update(error=error, error_detail=detail)
            for code in scope:
                (progress / (code + '.json')).write_text(json.dumps(result[code], ensure_ascii=False, indent=2) + '\n')
    return {code: result[code] for code in sorted(result)}
