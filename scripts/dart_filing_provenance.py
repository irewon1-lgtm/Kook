#!/usr/bin/env python3
"""Collect public DART filing identity, independently of bulk financial values.

No API credential is used. Verification means the official final-report search
and company profile agree with the expected issuer, report type and period. It
does NOT prove that previously downloaded bulk values incorporate amendments.
Raw response bytes and request metadata remain available for audit and replay.
"""
from __future__ import annotations

import calendar
import hashlib
import html
import json
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests

DART = 'https://dart.fss.or.kr'
REPORT_TYPES = {'사업보고서': 'A001', '반기보고서': 'A002', '분기보고서': 'A003'}
REPORT_TYPES.update({'1분기보고서': 'A003', '3분기보고서': 'A003'})
HEADERS = {'User-Agent': 'Mozilla/5.0 KR4 public filing provenance',
           'Referer': DART + '/dsab001/main.do'}
_HTTP_SESSIONS = threading.local()


def _request(method: str, url: str, **kwargs) -> requests.Response:
    if not hasattr(_HTTP_SESSIONS, 'session'):
        _HTTP_SESSIONS.session = requests.Session()
    return _HTTP_SESSIONS.session.request(method, url, **kwargs)


class _SourceUnavailable(Exception):
    def __init__(self, detail: str, evidence: list[dict[str, Any]]):
        super().__init__(detail)
        self.evidence = evidence


class _HostGate:
    """One shared request/second, exponential pauses and a bounded circuit."""
    def __init__(self):
        self.lock = threading.Lock()
        self.next_request = 0.0
        self.failures = 0
        self.circuit_open = False
        self.last_error = ''
        self.error_evidence: list[dict[str, Any]] = []

    def before_request(self):
        with self.lock:
            if self.circuit_open:
                raise _SourceUnavailable(self.last_error, list(self.error_evidence))
            delay = self.next_request - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            self.next_request = time.monotonic() + 1.0

    def success(self):
        with self.lock:
            if not self.circuit_open:
                self.failures = 0

    def failure(self, exc: Exception, evidence: dict[str, Any] | None):
        with self.lock:
            self.failures += 1
            if self.failures >= 3:
                self.circuit_open = True
            self.last_error = f'{type(exc).__name__}: {exc}'
            if evidence:
                self.error_evidence.append(evidence)
            self.next_request = max(self.next_request, time.monotonic() + min(20.0, 5.0 * 2 ** (self.failures - 1)))


def _text(markup: str) -> str:
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]*>', ' ', markup))).strip()


def _name(value: str) -> str:
    value = re.sub(r'\s+', '', html.unescape(value))
    return value.replace('주식회사', '').replace('(주)', '').replace('㈜', '')


def _rows(markup: str) -> list[list[str]]:
    return [re.findall(r'(?is)<t[dh]\b[^>]*>(.*?)</t[dh]\s*>', row)
            for row in re.findall(r'(?is)<tr\b[^>]*>(.*?)</tr\s*>', markup)]


def parse_search_rows(markup: str) -> list[dict[str, str]]:
    """Read each receipt from its own table row, never nearby unrelated links."""
    result = []
    for cells in _rows(markup):
        if len(cells) != 6:
            continue
        corp = re.search(r"openCorpInfo(?:New)?\(\s*['\"](\d{8})['\"]", cells[1])
        company = re.search(r'(?is)<a\b[^>]*>(.*?)</a\s*>', cells[1])
        report = re.search(r'''(?is)<a\b[^>]*href\s*=\s*['"](?:https://dart\.fss\.or\.kr)?/dsaf001/main\.do\?rcpNo=(\d{14})['"][^>]*>(.*?)</a\s*>''', cells[2])
        if corp and company and report:
            result.append({'corp_code': corp.group(1), 'company_name': _text(company.group(1)),
                           'receipt_no': report.group(1), 'report_name': _text(report.group(2)),
                           'filing_date_text': _text(cells[4])})
    return result


def _as_of(value: date | str | None) -> date:
    if value is None:
        return datetime.now(ZoneInfo('Asia/Seoul')).date()
    return date.fromisoformat(value) if isinstance(value, str) else value


def _empty(code: str, expected: dict[str, Any], as_of: date) -> dict[str, Any]:
    return {'expected_code': code, 'expected_name': expected.get('name'),
            'expected_period': expected.get('period_end'),
            'expected_report_kind': expected.get('report_kind'), 'corp_code': None,
            'receipt_no': None, 'filing_date': None, 'report_period': None,
            'report_name': None, 'source_url': None, 'correction_status': 'UNKNOWN',
            'correction_check': 'NOT_CHECKED', 'bulk_values_match_latest_filing': None,
            'verified': False, 'error': None, 'as_of_date': as_of.isoformat(), 'evidence': []}


def _validate_expected(code: str, expected: dict[str, Any], as_of: date) -> str | None:
    if not re.fullmatch(r'[0-9A-Z]{6}', code) or not _name(str(expected.get('name') or '')):
        return 'INVALID_EXPECTED_IDENTITY'
    try:
        period = date.fromisoformat(expected.get('period_end') or '')
        if period > as_of or period.day != calendar.monthrange(period.year, period.month)[1]:
            return 'INVALID_EXPECTED_PERIOD'
    except (ValueError, TypeError):
        return 'INVALID_EXPECTED_PERIOD'
    if expected.get('report_kind') not in REPORT_TYPES:
        return 'INVALID_EXPECTED_REPORT_KIND'
    return None


def resolve_filing_provenance(code: str, expected: dict[str, Any], search_html: str,
                              profile_html: str, *, as_of: date | str | None = None) -> dict[str, Any]:
    """Resolve one complete finalReport=recent search and its company profile.

    Callers providing their own HTML must enforce the final-report query. The
    collector below always emits it and stores its exact parameters.
    """
    cutoff = _as_of(as_of)
    out = _empty(code, expected, cutoff)
    def fail(reason: str) -> dict[str, Any]:
        out['error'] = reason
        return out
    invalid = _validate_expected(code, expected, cutoff)
    if invalid:
        return fail(invalid)
    if not re.search(r'''(?is)<table\b[^>]*class\s*=\s*['"][^'"]*\btbList\b''', search_html):
        return fail('INVALID_SEARCH_RESPONSE')
    if '조회 결과가 없습니다' in _text(search_html):
        return fail('FILING_NOT_FOUND')
    page = re.search(r'\[(\d+)\s*/\s*(\d+)\]\s*\[총\s*([\d,]+)건\]', _text(search_html))
    if not page:
        return fail('INVALID_SEARCH_RESPONSE')
    if page.group(1) != '1' or page.group(2) != '1':
        return fail('INCOMPLETE_SEARCH_RESULTS')
    rows = parse_search_rows(search_html)
    if len(rows) != int(page.group(3).replace(',', '')):
        return fail('INVALID_SEARCH_RESPONSE')
    corp_codes = {row['corp_code'] for row in rows}
    if len(corp_codes) != 1:
        return fail('AMBIGUOUS_COMPANY_SEARCH')
    profile = {_text(cells[0]): _text(cells[1]) for cells in _rows(profile_html) if len(cells) == 2}
    out['profile_stock_code'] = profile.get('종목코드')
    out['profile_company_name'] = profile.get('공시회사명')
    if (profile.get('종목코드') != code or
            _name(profile.get('공시회사명', '')) != _name(expected['name']) or
            any(_name(row['company_name']) != _name(expected['name']) for row in rows)):
        return fail('IDENTITY_MISMATCH')
    out['corp_code'] = next(iter(corp_codes))
    identity = {'stock_code': profile.get('종목코드'), 'name': profile.get('공시회사명'),
                'corp_code': next(iter(corp_codes))}
    return resolve_filing_rows(code, expected, rows, identity, as_of=cutoff)


def parse_public_corp_mapping(markup: str) -> dict[str, dict[str, str]]:
    """Parse OpenDART's official public company selector stock-code column."""
    result = {}
    for cells in _rows(markup):
        if len(cells) != 5:
            continue
        code = _text(cells[3])
        corp = re.search(r"name=['\"]hiddenCikCD1['\"]\s+value=['\"](\d{8})['\"]", cells[1])
        name = re.search(r"name=['\"]hiddenCikNM1['\"]\s+value=['\"]([^'\"]+)['\"]", cells[1])
        if re.fullmatch(r'[0-9A-Z]{6}', code) and corp and name:
            item = {'stock_code': code, 'corp_code': corp.group(1), 'name': html.unescape(name.group(1))}
            if code in result and result[code] != item:
                raise ValueError('ambiguous official company mapping: ' + code)
            result[code] = item
    return result


def resolve_filing_rows(code: str, expected: dict[str, Any], rows: list[dict[str, str]],
                        identity: dict[str, str], *, as_of: date | str | None = None) -> dict[str, Any]:
    """Resolve completely collected final-report rows using official identity."""
    cutoff = _as_of(as_of)
    out = _empty(code, expected, cutoff)
    def fail(reason: str) -> dict[str, Any]:
        out['error'] = reason
        return out
    invalid = _validate_expected(code, expected, cutoff)
    if invalid:
        return fail(invalid)
    out['profile_stock_code'] = identity.get('stock_code')
    out['profile_company_name'] = identity.get('name')
    if (identity.get('stock_code') != code or _name(identity.get('name', '')) != _name(expected['name'])
            or not re.fullmatch(r'\d{8}', identity.get('corp_code', ''))):
        return fail('IDENTITY_MISMATCH')
    rows = [row for row in rows if row['corp_code'] == identity['corp_code']]
    if any(_name(row['company_name']) != _name(expected['name']) for row in rows):
        return fail('IDENTITY_MISMATCH')
    out['corp_code'] = identity['corp_code']
    period = date.fromisoformat(expected['period_end'])
    candidates = []
    for row in rows:
        report = re.fullmatch(r'(?:\[[^\]]+\]\s*)*(사업보고서|반기보고서|분기보고서)\s*\((\d{4})\.(\d{2})\)', row['report_name'])
        if report and (report.group(1), report.group(2), report.group(3)) == (
                '분기보고서' if REPORT_TYPES[expected['report_kind']] == 'A003' else expected['report_kind'],
                f'{period.year:04}', f'{period.month:02}'):
            candidates.append(row)
    if not candidates:
        return fail('REPORT_PERIOD_NOT_FOUND')
    if len(candidates) != 1:
        return fail('AMBIGUOUS_FINAL_FILING')
    row = candidates[0]
    prefixes = re.findall(r'\[([^\]]+)\]', row['report_name'])
    if any(prefix not in {'기재정정', '첨부정정', '첨부추가', '기재추가'} for prefix in prefixes):
        return fail('UNRECOGNIZED_REPORT_STATUS')
    try:
        filing_date = date.fromisoformat(row['filing_date_text'].replace('.', '-'))
        receipt_date = date.fromisoformat(f"{row['receipt_no'][:4]}-{row['receipt_no'][4:6]}-{row['receipt_no'][6:8]}")
        if filing_date != receipt_date or not period < filing_date <= cutoff:
            return fail('INVALID_FILING_DATE')
    except ValueError:
        return fail('INVALID_FILING_DATE')
    out.update(receipt_no=row['receipt_no'], filing_date=filing_date.isoformat(),
               report_period=period.isoformat(), report_name=row['report_name'],
               source_url=DART + '/dsaf001/main.do?rcpNo=' + row['receipt_no'],
               correction_status='LATEST_CORRECTION' if prefixes else 'ORIGINAL',
               correction_check='PUBLIC_DART_FINAL_REPORT_SEARCH: finalReport=recent; '
                                'identity/type/period validated; bulk amendment incorporation unverified',
               verified=True)
    return out


def _cached_request(method: str, url: str, cache_dir: Path,
                    data: dict[str, str] | None = None,
                    gate: _HostGate | None = None,
                    cache_only: bool = False,
                    max_cache_age_seconds: float = 3600) -> tuple[str, dict[str, Any]]:
    request_data = {'method': method, 'url': url, 'data': data}
    key = hashlib.sha256(json.dumps(request_data, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    raw_path, meta_path = cache_dir / (key + '.html'), cache_dir / (key + '.json')
    cached_available = raw_path.exists() and meta_path.exists()
    stale = False
    if cached_available:
        raw = raw_path.read_bytes()
        meta = json.loads(meta_path.read_text())
        if meta.get('sha256') != hashlib.sha256(raw).hexdigest() or any(meta.get(k) != v for k, v in request_data.items()):
            raise ValueError('cached response hash/request mismatch')
        current_day_search = bool(data and data.get('endDate') == _as_of(None).strftime('%Y%m%d'))
        age = (datetime.now(timezone.utc) - datetime.fromisoformat(meta['retrieved_at_utc'])).total_seconds()
        stale = current_day_search and (age < 0 or age > max_cache_age_seconds)
        if stale and not cache_only:
            cached_available = False
    if not cached_available:
        if cache_only:
            raise _SourceUnavailable('CACHE_ONLY: no cached official response; live source unavailable', [])
        for attempt in range(3):
            response = None
            try:
                if gate:
                    gate.before_request()
                request_started_at = datetime.now(timezone.utc).isoformat()
                attempt_started = time.monotonic()
                response = _request(method, url, data=data, headers=HEADERS, timeout=30)
                response.raise_for_status()
                if gate:
                    gate.success()
                break
            except requests.RequestException as exc:
                failure_dir = cache_dir / 'errors'
                failure_dir.mkdir(exist_ok=True)
                failure_stem = key + '-' + str(time.time_ns())
                failure_meta = {**request_data, 'status_code': None,
                                'request_started_at_utc': request_started_at,
                                'retrieved_at_utc': datetime.now(timezone.utc).isoformat(),
                                'attempt': attempt + 1,
                                'elapsed_seconds': round(time.monotonic() - attempt_started, 6),
                                'response_received': response is not None,
                                'error_type': type(exc).__name__, 'error_detail': str(exc)}
                if response is not None:
                    failure_path = failure_dir / (failure_stem + '.html')
                    failure_meta_path = failure_path.with_suffix('.json')
                    failure_meta.update({'status_code': response.status_code,
                                    'sha256': hashlib.sha256(response.content).hexdigest(),
                                    'bytes': len(response.content)})
                    failure_path.write_bytes(response.content)
                    failure_raw_path = str(failure_path.resolve())
                else:
                    # A TLS/connection failure has no HTTP response body to
                    # hash or save. Preserve the real request and timing only.
                    failure_meta_path = failure_dir / (failure_stem + '.transport.json')
                    failure_raw_path = None
                failure_meta_path.write_text(json.dumps(failure_meta, ensure_ascii=False, indent=2) + '\n')
                failure_evidence = {**failure_meta, 'raw_path': failure_raw_path,
                                    'metadata_path': str(failure_meta_path.resolve())}
                if gate:
                    gate.failure(exc, failure_evidence)
                if attempt == 2:
                    if gate:
                        raise _SourceUnavailable(gate.last_error, list(gate.error_evidence)) from exc
                    raise
                if not gate:
                    time.sleep(0.5 * (2 ** attempt))
        raw = response.content
        meta = {**request_data, 'status_code': response.status_code,
                'retrieved_at_utc': datetime.now(timezone.utc).isoformat(),
                'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
        raw_path.write_bytes(raw)
        meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
        stale = False
    evidence = {**meta, 'raw_path': str(raw_path.resolve()), 'metadata_path': str(meta_path.resolve()),
                'cache_stale': stale}
    return raw.decode('utf-8-sig'), evidence


def collect_public_corp_mapping(cache_dir: Path, *, cache_only: bool = False,
                                expected_codes: list[str] | None = None) -> dict[str, dict[str, Any]]:
    """Collect listed company identifiers via the official OpenDART selector.

    P/A and the initial-character indices are the values exposed by that
    selector's public HTML. Each page and row must be fully accounted for.
    """
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    gate = _HostGate()
    result: dict[str, dict[str, Any]] = {}
    for market in ('P', 'A'):
        for initial in list(range(14)) + [15, 16]:
            page_number, page_count, expected_count, seen_count = 1, 1, None, 0
            while page_number <= page_count:
                data = {'textCrpNm': '', 'corporationType': market, 'searchIndex': str(initial),
                        'pageIndex': str(page_number), 'recordCountPerPage': '1000', 'pageSize': '10'}
                markup, evidence = _cached_request('POST', 'https://opendart.fss.or.kr/cmm/searchCorpL.do',
                                                   cache_dir, data, gate, cache_only)
                page = re.search(r'\[(\d+)\s*/\s*(\d+)\]\s*\[총\s*([\d,]+)건\]', _text(markup))
                if '조회 결과가 없습니다' in _text(markup):
                    break
                if not page or int(page.group(1)) != page_number:
                    raise ValueError('invalid official company mapping page')
                page_count = int(page.group(2))
                count = int(page.group(3).replace(',', ''))
                if expected_count is not None and count != expected_count:
                    raise ValueError('official company mapping changed during pagination')
                expected_count = count
                parsed = parse_public_corp_mapping(markup)
                seen_count += len(parsed)
                for code, item in parsed.items():
                    if code in result and any(result[code][key] != value for key, value in item.items()):
                        raise ValueError('ambiguous official company mapping: ' + code)
                    if code not in result:
                        result[code] = {**item, 'evidence': [evidence]}
                page_number += 1
            if expected_count is not None and seen_count != expected_count:
                raise ValueError('incomplete official company mapping rows')
    for code in sorted(set(expected_codes or []) - result.keys()):
        data = {'textCrpNm': code, 'corporationType': '', 'searchIndex': '',
                'pageIndex': '1', 'recordCountPerPage': '1000', 'pageSize': '10'}
        markup, evidence = _cached_request('POST', 'https://opendart.fss.or.kr/cmm/searchCorpL.do',
                                           cache_dir, data, gate, cache_only)
        page = re.search(r'\[(\d+)\s*/\s*(\d+)\]\s*\[총\s*([\d,]+)건\]', _text(markup))
        if page and page.group(1) == '1' and page.group(2) == '1':
            parsed = parse_public_corp_mapping(markup)
            if code in parsed:
                result[code] = {**parsed[code], 'evidence': [evidence]}
    return result


def collect_public_filing_rows(cache_dir: Path, public_type: str, year: int, *,
                               as_of: date | str | None = None, cache_only: bool = False,
                               gate: _HostGate | None = None, text_crp_nm: str = '',
                               max_cache_age_seconds: float = 3600) -> tuple[list[dict[str, str]], list[dict[str, Any]]]:
    """Collect every page of an official final-report search, or fail closed."""
    cutoff = _as_of(as_of)
    gate = gate or _HostGate()
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    all_rows, evidence, total, page_count, page_number = [], [], None, 1, 1
    while page_number <= page_count:
        data = {'textCrpNm': text_crp_nm, 'currentPage': str(page_number), 'maxResults': '100',
                'maxLinks': '10', 'sort': 'date', 'series': 'desc', 'finalReport': 'recent',
                'publicType': public_type, 'startDate': f'{year}0101', 'endDate': cutoff.strftime('%Y%m%d')}
        markup, item = _cached_request('POST', DART + '/dsab001/search.ax', cache_dir,
                                      data, gate, cache_only, max_cache_age_seconds)
        evidence.append(item)
        if not re.search(r'''(?is)<table\b[^>]*class\s*=\s*['"][^'"]*\btbList\b''', markup):
            raise ValueError('invalid global DART search response')
        if '조회 결과가 없습니다' in _text(markup) and page_number == 1:
            return [], evidence
        page = re.search(r'\[(\d+)\s*/\s*(\d+)\]\s*\[총\s*([\d,]+)건\]', _text(markup))
        if not page or int(page.group(1)) != page_number:
            raise ValueError('invalid global DART search page')
        count, pages = int(page.group(3).replace(',', '')), int(page.group(2))
        if pages > 200 or (total is not None and (count != total or pages != page_count)):
            raise ValueError('global DART search changed or exceeded bounded pagination')
        total, page_count = count, pages
        rows = parse_search_rows(markup)
        if not rows:
            raise ValueError('empty global DART page with nonempty result count')
        all_rows.extend(rows)
        page_number += 1
    if len(all_rows) != total or len({r['receipt_no'] for r in all_rows}) != total:
        raise ValueError('incomplete or duplicate global DART search rows')
    return all_rows, evidence


def parse_opendart_reference_rows(markup: str) -> list[dict[str, str]]:
    """Read the official biz table's explicit settlement date and receipt.

    These references intentionally contain no filing date: that field is not
    present in this public table and must never be guessed from an identifier.
    """
    result = []
    for cells in _rows(markup):
        if len(cells) < 3:
            continue
        corp = re.search(r"openCorpInfo\(\s*['\"](\d{8})['\"]", cells[0])
        name = re.search(r'(?is)<span\b[^>]*openCorpInfo\([^>]*>(.*?)</span>', cells[0])
        period = _text(cells[1])
        receipts = set(re.findall(r'''id\s*=\s*['"]r_(\d{14})['"]''', ''.join(cells)))
        if not corp or not name or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', period):
            continue
        if len(receipts) != 1:
            raise ValueError('ambiguous OpenDART reference receipt within one table row')
        date.fromisoformat(period)
        result.append({'corp_code': corp.group(1), 'company_name': _text(name.group(1)),
                       'receipt_no': next(iter(receipts)), 'report_period': period})
    return result


def collect_opendart_reference_rows(cache_dir: Path, report_code: str, year: int, *,
                                    cache_only: bool = False, gate: _HostGate | None = None,
                                    text_crp_cik: str = '', gubun: str = 'TA') -> tuple[list[dict[str, str]], list[dict[str, Any]]]:
    """Collect public final-report references, keeping unknown date/correction."""
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    gate = gate or _HostGate()
    all_rows, evidence, total, page_count, page_number, raw_count = [], [], None, 1, 1, 0
    while page_number <= page_count:
        data = {'textCrpCik': text_crp_cik, 'textCrpNm': '', 'selectYear': str(year),
                'reportCode': report_code, 'gubun': gubun, 'corpType': ['P', 'A'],
                'pageIndex': str(page_number), 'recordCountPerPage': '100',
                'pageSize': '10', 'sortStdr': 'crp', 'sortOrdr': 'asc'}
        if text_crp_cik:
            data.update(corpType=['P', 'A', 'N', 'E'], corpTypeAll='1')
        markup, item = _cached_request('POST', 'https://opendart.fss.or.kr/disclosureinfo/biz/list.do',
                                       cache_dir, data, gate, cache_only)
        evidence.append(item)
        page = re.search(r'\[(\d+)\s*/\s*(\d+)\]\s*\[총\s*([\d,]+)건\]', _text(markup))
        if '조회 결과가 없습니다' in _text(markup) and page_number == 1:
            return [], evidence
        if not page or int(page.group(1)) != page_number:
            raise ValueError('invalid OpenDART reference page')
        count, pages = int(page.group(3).replace(',', '')), int(page.group(2))
        if pages > 200 or (total is not None and (count != total or pages != page_count)):
            raise ValueError('OpenDART references changed or exceeded bounded pagination')
        total, page_count = count, pages
        raw_count += sum(bool(cells and 'openCorpInfo(' in cells[0]) for cells in _rows(markup))
        all_rows.extend(parse_opendart_reference_rows(markup))
        page_number += 1
    if raw_count != total:
        raise ValueError('incomplete OpenDART reference rows')
    return all_rows, evidence


def _supplement_opendart_references(expected: dict[str, dict[str, Any]], result: dict[str, dict[str, Any]],
                                     mapping: dict[str, dict[str, Any]], cache_dir: Path,
                                     cutoff: date, cache_only: bool):
    """Recover actual identifiers while leaving unavailable filing facts unknown."""
    report_codes = {'사업보고서': '11011', '반기보고서': '11012',
                    '1분기보고서': '11013', '3분기보고서': '11014'}
    groups: dict[tuple[str, int], list[str]] = {}
    for code, spec in expected.items():
        if _validate_expected(code, spec, cutoff) is None and spec['report_kind'] in report_codes:
            groups.setdefault((report_codes[spec['report_kind']], int(spec['period_end'][:4])), []).append(code)
    gate = _HostGate()
    for (report_code, year), codes in sorted(groups.items()):
        if not any(result[code]['error'] == 'SOURCE_UNAVAILABLE' for code in codes):
            continue
        batches = [[]] if len(codes) > 100 else [codes[i:i + 10] for i in range(0, len(codes), 10)]
        for batch in batches:
            try:
                references, evidence = collect_opendart_reference_rows(cache_dir / 'report-references',
                    report_code, year, cache_only=cache_only, gate=gate,
                    text_crp_cik=' '.join(mapping[code]['corp_code'] for code in batch if code in mapping))
            except (_SourceUnavailable, requests.RequestException, ValueError, OSError) as exc:
                for code in batch or codes:
                    result[code]['reference_collection_error'] = f'{type(exc).__name__}: {exc}'
                continue
            available = {(reference['corp_code'], reference['report_period']) for reference in references}
            missing_codes = [code for code in batch or codes
                             if result[code]['error'] == 'SOURCE_UNAVAILABLE' and not result[code]['receipt_no']
                             and code in mapping and _name(mapping[code]['name']) == _name(expected[code]['name'])
                             and (mapping[code]['corp_code'], expected[code]['period_end']) not in available]
            # The directors table can be omitted from quarterly reports. The
            # mandatory share table provides the same explicit report reference.
            for offset in range(0, len(missing_codes), 10):
                missing_batch = missing_codes[offset:offset + 10]
                try:
                    extra_rows, extra_evidence = collect_opendart_reference_rows(cache_dir / 'report-references',
                        report_code, year, cache_only=cache_only, gate=gate, gubun='LA',
                        text_crp_cik=' '.join(mapping[code]['corp_code'] for code in missing_batch))
                    references.extend(extra_rows)
                    evidence.extend(extra_evidence)
                except (_SourceUnavailable, requests.RequestException, ValueError, OSError) as exc:
                    for code in missing_batch:
                        result[code]['reference_collection_error'] = f'{type(exc).__name__}: {exc}'
            receipt_evidence = {}
            for page in evidence:
                for reference in parse_opendart_reference_rows(Path(page['raw_path']).read_text()):
                    receipt_evidence[reference['receipt_no']] = page
            for code in batch or codes:
                row, spec, identity = result[code], expected[code], mapping.get(code)
                if not identity or _name(identity['name']) != _name(spec['name']):
                    continue
                candidates = {r['receipt_no']: r for r in references
                              if r['corp_code'] == identity['corp_code'] and r['report_period'] == spec['period_end']
                              and _name(r['company_name']) == _name(spec['name'])}
                if len(candidates) != 1:
                    row['reference_match_error'] = 'AMBIGUOUS_REFERENCE' if candidates else 'REFERENCE_PERIOD_NOT_FOUND'
                    continue
                receipt, reference = next(iter(candidates.items()))
                if receipt[:8] > cutoff.strftime('%Y%m%d'):
                    row['reference_match_error'] = 'REFERENCE_IDENTIFIER_AFTER_AS_OF'
                    continue
                source = receipt_evidence[receipt]
                row['reference_checked_at_utc'] = source['retrieved_at_utc']
                row['reference_source'] = 'OPENDART_PUBLIC_BIZ_TABLE'
                row['evidence'].append(source)
                if row['verified']:
                    if row['receipt_no'] != receipt:
                        row.update(verified=False, error='FINAL_RECEIPT_MISMATCH', alternate_receipt_no=receipt)
                elif row['error'] == 'SOURCE_UNAVAILABLE':
                    row.update(receipt_no=receipt, report_period=reference['report_period'],
                               source_url=DART + '/dsaf001/main.do?rcpNo=' + receipt,
                               correction_check='OPENDART_REFERENCE_ONLY: exact identity and explicit period; '
                                                'reception date and correction label unavailable')
                (cache_dir / 'progress' / (code + '.json')).write_text(json.dumps(row, ensure_ascii=False, indent=2) + '\n')


def _collect_global(expected: dict[str, dict[str, Any]], cache_dir: Path, workers: int,
                    cutoff: date, cache_only: bool, max_cache_age_seconds: float) -> dict[str, dict[str, Any]]:
    # Previously captured individual searches remain useful during a live host outage.
    result = collect_filing_provenance(expected, cache_dir, workers, as_of=cutoff,
                                       cache_only=True, global_search=False,
                                       max_cache_age_seconds=max_cache_age_seconds)
    try:
        mapping = collect_public_corp_mapping(cache_dir / 'corp-map', cache_only=cache_only,
                                              expected_codes=list(expected))
    except (_SourceUnavailable, requests.RequestException, ValueError, OSError) as exc:
        for code, row in result.items():
            row['live_refresh_error'] = f'Official identity source: {type(exc).__name__}: {exc}'
            if isinstance(exc, _SourceUnavailable):
                row['source_error_evidence'] = exc.evidence
            (cache_dir / 'progress' / (code + '.json')).write_text(json.dumps(row, ensure_ascii=False, indent=2) + '\n')
        return result
    groups: dict[tuple[str, int], list[str]] = {}
    for code, spec in expected.items():
        identity = mapping.get(code)
        if identity:
            result[code].update(corp_code=identity['corp_code'], profile_stock_code=identity['stock_code'],
                                profile_company_name=identity['name'])
            result[code]['evidence'].extend(identity['evidence'])
            if _name(identity['name']) != _name(spec.get('name', '')):
                result[code].update(verified=False, error='IDENTITY_MISMATCH')
        if _validate_expected(code, spec, cutoff) is None:
            groups.setdefault((REPORT_TYPES[spec['report_kind']], int(spec['period_end'][:4])), []).append(code)
    gate = _HostGate()
    index_dir = cache_dir / 'search-index'
    index_dir.mkdir(exist_ok=True)
    # DART's public multiselect accepts at most ten company identifiers. A
    # completed issuer batch remains independently verifiable if a later
    # batch suffers a transport failure. Deterministic scopes also replay
    # directly from the raw-response cache on a bounded retry run.
    batches = []
    for (public_type, year), group_codes in sorted(groups.items()):
        ordered_codes = sorted(group_codes)
        for offset in range(0, len(ordered_codes), 10):
            batches.append((public_type, year, ordered_codes[offset:offset + 10]))
    for public_type, year, codes in batches:
        try:
            rows, pages = collect_public_filing_rows(cache_dir, public_type, year, as_of=cutoff,
                cache_only=cache_only, gate=gate,
                text_crp_nm='/'.join(codes),
                max_cache_age_seconds=max_cache_age_seconds)
        except (_SourceUnavailable, requests.RequestException, ValueError, OSError) as exc:
            for code in codes:
                result[code]['live_refresh_error'] = f'{type(exc).__name__}: {exc}'
                if isinstance(exc, _SourceUnavailable):
                    result[code]['source_error_evidence'] = exc.evidence
                    if result[code]['error'] == 'SOURCE_UNAVAILABLE':
                        result[code]['error_detail'] = str(exc)
                (cache_dir / 'progress' / (code + '.json')).write_text(json.dumps(result[code], ensure_ascii=False, indent=2) + '\n')
            continue
        index_raw = (json.dumps(pages, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()
        index_hash = hashlib.sha256(index_raw).hexdigest()
        index_path = index_dir / (index_hash + '.json')
        index_path.write_bytes(index_raw)
        page_receipts = []
        for page in pages:
            page_receipts.append({r['receipt_no'] for r in parse_search_rows(Path(page['raw_path']).read_text())})
        for code in codes:
            identity = mapping.get(code)
            if identity is None:
                row = _empty(code, expected[code], cutoff)
                row['error'] = 'OFFICIAL_IDENTITY_NOT_FOUND'
                result[code] = row
                continue
            row = resolve_filing_rows(code, expected[code], rows, identity, as_of=cutoff)
            relevant_pages = [page for page, receipts in zip(pages, page_receipts)
                              if row.get('receipt_no') in receipts]
            if not relevant_pages:
                relevant_pages = pages[:1]
            row['evidence'] = identity['evidence'] + relevant_pages
            row['search_completeness'] = {'pages': len(pages), 'rows': len(rows), 'stock_codes': codes,
                                          'index_path': str(index_path.resolve()), 'index_sha256': index_hash}
            row['correction_checked_at_utc'] = relevant_pages[0]['retrieved_at_utc']
            if row['verified']:
                row['correction_check'] += '; observed_at_utc=' + row['correction_checked_at_utc']
                if any(page['cache_stale'] for page in pages):
                    row.update(verified=False, error='STALE_CORRECTION_CHECK')
            result[code] = row
            (cache_dir / 'progress' / (code + '.json')).write_text(json.dumps(row, ensure_ascii=False, indent=2) + '\n')
    _supplement_opendart_references(expected, result, mapping, cache_dir, cutoff, cache_only)
    return {code: result[code] for code in sorted(result)}


def collect_filing_provenance(expected: dict[str, dict[str, Any]], cache_dir: Path,
                               workers: int = 6, *, as_of: date | str | None = None,
                               cache_only: bool = False,
                               max_cache_age_seconds: float = 3600,
                               global_search: bool | None = None) -> dict[str, dict[str, Any]]:
    """Collect official public filing references for each exact expected issuer.

    Missing, invalid, ambiguous, HTTP and cache-integrity failures stay explicit
    unverified records. Search dates end at the injected date or the current date in Asia/Seoul.
    """
    cutoff = _as_of(as_of)
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    if global_search is True or (global_search is None and len(expected) > 100 and not cache_only):
        return _collect_global(expected, cache_dir, workers, cutoff, cache_only, max_cache_age_seconds)
    progress_dir = cache_dir / 'progress'
    progress_dir.mkdir(exist_ok=True)
    gate = _HostGate()
    def collect(code: str, spec: dict[str, Any]) -> tuple[str, dict[str, Any]]:
        out = _empty(code, spec, cutoff)
        invalid = _validate_expected(code, spec, cutoff)
        if invalid:
            out['error'] = invalid
            return code, out
        evidence = []
        try:
            data = {'textCrpNm': code, 'currentPage': '1', 'maxResults': '100',
                    'maxLinks': '10', 'sort': 'date', 'series': 'desc',
                    'finalReport': 'recent', 'publicType': REPORT_TYPES[spec['report_kind']],
                    'startDate': spec['period_end'][:4] + '0101', 'endDate': cutoff.strftime('%Y%m%d')}
            search, item = _cached_request('POST', DART + '/dsab001/search.ax', cache_dir, data, gate, cache_only, max_cache_age_seconds)
            evidence.append(item)
            codes = {row['corp_code'] for row in parse_search_rows(search)}
            profile = ''
            if len(codes) == 1:
                profile, item = _cached_request('GET', DART + '/dsae001/selectPopup.ax?selectKey=' + next(iter(codes)), cache_dir, gate=gate, cache_only=cache_only)
                evidence.append(item)
            out = resolve_filing_provenance(code, spec, search, profile, as_of=cutoff)
            out['correction_checked_at_utc'] = evidence[0]['retrieved_at_utc']
            if out['verified']:
                out['correction_check'] += '; observed_at_utc=' + evidence[0]['retrieved_at_utc']
                if evidence[0]['cache_stale']:
                    out.update(verified=False, error='STALE_CORRECTION_CHECK')
        except _SourceUnavailable as exc:
            out['error'] = 'SOURCE_UNAVAILABLE'
            out['error_detail'] = str(exc)
            evidence.extend(exc.evidence)
        except (requests.RequestException, OSError, ValueError, UnicodeError) as exc:
            out['error'] = 'COLLECTION_ERROR'
            out['error_detail'] = f'{type(exc).__name__}: {exc}'
        out['evidence'] = evidence
        return code, out
    result = {}
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = [pool.submit(collect, code, spec) for code, spec in expected.items()]
        for future in as_completed(futures):
            code, row = future.result()
            result[code] = row
            (progress_dir / (code + '.json')).write_text(json.dumps(row, ensure_ascii=False, indent=2) + '\n')
    return {code: result[code] for code in sorted(result)}
