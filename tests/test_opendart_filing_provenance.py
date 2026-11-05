"""Authenticated API contract tests; only Samsung's base record is real data.

That record was independently captured in the sanitized official response on
2026-11-05. Altered records and additional issuers are controlled test inputs.
No real authentication credential is present or read by this test suite.
"""
from __future__ import annotations

import io
import json
import sys
import zipfile
from pathlib import Path
from unittest.mock import patch

import pytest
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
try:
    import opendart_filing_provenance as api
except ImportError:
    api = None

SECRET = 'unit-test-secret-sentinel-not-a-real-api-key'
EXPECTED = {'name': '삼성전자', 'period_end': '2026-06-30', 'report_kind': '반기보고서'}
SAMSUNG = {'corp_code': '00126380', 'corp_name': '삼성전자', 'stock_code': '005930',
           'corp_cls': 'Y', 'report_nm': '반기보고서 (2026.06)', 'rcept_no': '20260814003699',
           'flr_nm': '삼성전자', 'rcept_dt': '20260814', 'rm': ''}
XML = '''<?xml version="1.0" encoding="UTF-8"?><result><list><corp_code>00126380</corp_code>
<corp_name>삼성전자</corp_name><corp_eng_name>SAMSUNG ELECTRONICS CO,.LTD</corp_eng_name>
<stock_code>005930</stock_code><modify_date>20261105</modify_date></list></result>'''


def module():
    assert api is not None, 'authenticated OpenDART provenance module is missing'
    return api


def zipped_xml(xml=XML):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('CORPCODE.xml', xml)
    return data.getvalue()


def response(body, status=200):
    result = requests.Response()
    result.status_code = status
    result._content = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
    # Real Requests responses carry the authenticated URL. It must never be
    # used as a public source URL, persisted metadata, or exception detail.
    result.url = 'https://opendart.fss.or.kr/api/list.json?crtfc_key=' + SECRET
    return result


def payload(rows=None, *, page=1, page_count=100, total=None, pages=None):
    rows = [SAMSUNG] if rows is None else rows
    total = len(rows) if total is None else total
    pages = max(1, (total + page_count - 1) // page_count) if pages is None else pages
    return {'status': '000', 'message': '정상', 'page_no': page, 'page_count': page_count,
            'total_count': total, 'total_page': pages, 'list': rows}


def collect(tmp_path, *, rows=None, xml=XML, api_response=None, expected=None):
    def request(method, url, **kwargs):
        assert method == 'GET'
        params = kwargs['params']
        assert params['crtfc_key'] == SECRET
        assert kwargs.get('verify', True) is True
        if url.endswith('/corpCode.xml'):
            return response(zipped_xml(xml))
        assert url == 'https://opendart.fss.or.kr/api/list.json'
        assert params['last_reprt_at'] == 'Y'
        assert params['pblntf_detail_ty'] == 'A002'
        assert params['end_de'] <= '20261105'
        return api_response(params) if api_response else response(payload(rows))
    with patch.object(module(), '_request', side_effect=request), patch.object(module().time, 'sleep'):
        return module().collect_authenticated_filing_provenance(expected or {'005930': EXPECTED}, tmp_path,
                                                               SECRET, '2026-11-05')


def assert_secret_absent(tmp_path, rows):
    public = json.dumps(rows, ensure_ascii=False)
    assert SECRET not in public
    assert 'crtfc_key' not in public
    for path in tmp_path.rglob('*'):
        if path.is_file():
            assert SECRET.encode() not in path.read_bytes()
            if path.suffix in {'.json', '.xml'}:
                assert b'crtfc_key' not in path.read_bytes()


def test_actual_samsung_api_record_supplies_explicit_reception_date(tmp_path):
    rows = collect(tmp_path)
    row = rows['005930']
    assert row['verified'] is True
    assert row['corp_code'] == '00126380'
    assert row['receipt_no'] == '20260814003699'
    assert row['filing_date'] == '2026-08-14'
    assert row['report_period'] == '2026-06-30'
    assert row['source_url'] == 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814003699'
    assert row['source'] == 'OPENDART_AUTHENTICATED_API'
    assert 'last_reprt_at=Y' in row['correction_check']
    assert row['correction_status'] == 'ORIGINAL'
    assert_secret_absent(tmp_path, rows)


@pytest.mark.parametrize('changed,error', [
    ({'rcept_dt': '20260815'}, 'INVALID_FILING_DATE'),
    ({'rcept_dt': '20260832'}, 'INVALID_FILING_DATE'),
    ({'rcept_dt': ''}, 'INVALID_FILING_DATE'),
    ({'rcept_dt': '20261106', 'rcept_no': '20261106000000'}, 'INVALID_FILING_DATE'),
    ({'stock_code': '005935'}, 'IDENTITY_MISMATCH'),
    ({'corp_name': '삼성전자서비스'}, 'IDENTITY_MISMATCH'),
    ({'report_nm': '반기보고서 (2026.03)'}, 'REPORT_PERIOD_NOT_FOUND'),
    ({'rm': '정'}, 'SUPERSEDED_FILING'),
    ({'rm': '철'}, 'WITHDRAWN_FILING'),
    ({'report_nm': '[철회]반기보고서 (2026.06)'}, 'UNRECOGNIZED_REPORT_STATUS'),
])
def test_bad_identity_dates_periods_and_superseded_filings_fail_closed(tmp_path, changed, error):
    row = collect(tmp_path, rows=[{**SAMSUNG, **changed}])['005930']
    assert row['verified'] is False
    assert row['error'] == error


def test_latest_explicit_correction_is_metadata_verified_without_claiming_bulk_match(tmp_path):
    row = collect(tmp_path, rows=[{**SAMSUNG, 'report_nm': '[기재정정]반기보고서 (2026.06)'}])['005930']
    assert row['verified'] is True
    assert row['correction_status'] == 'LATEST_CORRECTION'
    assert row['bulk_values_match_latest_filing'] is None


def test_empty_or_wrong_stock_mapping_cannot_guess_by_company_name(tmp_path):
    row = collect(tmp_path, xml=XML.replace('<stock_code>005930</stock_code>', '<stock_code></stock_code>'))['005930']
    assert row['verified'] is False
    assert row['error'] == 'OFFICIAL_IDENTITY_NOT_FOUND'


def test_official_corporation_name_must_match_expected_name(tmp_path):
    row = collect(tmp_path, xml=XML.replace('<corp_name>삼성전자</corp_name>', '<corp_name>삼성전자서비스</corp_name>'))['005930']
    assert row['verified'] is False
    assert row['error'] == 'IDENTITY_MISMATCH'


def test_all_api_pages_are_required_and_stable(tmp_path):
    prior_period = {**SAMSUNG, 'report_nm': '반기보고서 (2025.06)', 'rcept_no': '20260301000000', 'rcept_dt': '20260301'}
    def paged(params):
        page = int(params['page_no'])
        return response(payload([SAMSUNG if page == 1 else prior_period], page=page, page_count=1, total=2))
    row = collect(tmp_path, api_response=paged)['005930']
    assert row['verified'] is True
    assert row['receipt_no'] == '20260814003699'
    assert row['search_completeness']['pages'] == 2


@pytest.mark.parametrize('problem', ['count_drift', 'missing_page_rows', 'duplicate_receipt'])
def test_unstable_or_incomplete_pagination_never_verifies(tmp_path, problem):
    def paged(params):
        page = int(params['page_no'])
        if page == 1:
            return response(payload([SAMSUNG], page=1, page_count=1, total=2))
        if problem == 'count_drift':
            return response(payload([SAMSUNG], page=2, page_count=1, total=3))
        if problem == 'missing_page_rows':
            return response(payload([], page=2, page_count=1, total=2))
        return response(payload([SAMSUNG], page=2, page_count=1, total=2))
    row = collect(tmp_path, api_response=paged)['005930']
    assert row['verified'] is False
    assert row['error'] == 'COLLECTION_ERROR'


def test_global_date_windows_are_nonoverlapping_and_at_most_89_days():
    assert list(module().api_date_windows('2026-01-01', '2026-11-05')) == [
        ('20260101', '20260330'), ('20260331', '20260627'),
        ('20260628', '20260924'), ('20260925', '20261105')]


def test_api_status_013_is_explicit_no_filing_not_source_failure(tmp_path):
    row = collect(tmp_path, api_response=lambda _: response({'status': '013', 'message': '조회된 데이타가 없습니다.'}))['005930']
    assert row['verified'] is False
    assert row['error'] == 'FILING_NOT_FOUND'


def test_transport_url_with_credential_is_never_serialized(tmp_path):
    def unavailable(params):
        raise requests.exceptions.SSLError('failed https://opendart.fss.or.kr/api/list.json?crtfc_key=' + SECRET)
    rows = collect(tmp_path, api_response=unavailable)
    assert rows['005930']['error'] == 'SOURCE_UNAVAILABLE'
    assert_secret_absent(tmp_path, rows)


def test_api_reflecting_credential_is_rejected_before_response_is_cached(tmp_path):
    rows = collect(tmp_path, api_response=lambda _: response({'status': '010', 'message': SECRET}))
    assert rows['005930']['verified'] is False
    assert_secret_absent(tmp_path, rows)


def test_zip_member_reflecting_credential_is_never_cached(tmp_path):
    rows = collect(tmp_path, xml=XML.replace('삼성전자', SECRET))
    assert rows['005930']['verified'] is False
    assert_secret_absent(tmp_path, rows)


def test_public_cache_identity_is_independent_of_authentication_value(tmp_path):
    first = collect(tmp_path)
    with patch.object(module(), '_request', side_effect=AssertionError('cached public facts must replay without network')):
        second = module().collect_authenticated_filing_provenance({'005930': EXPECTED}, tmp_path,
                                                                  'a-different-test-key', '2026-11-05')
    assert first['005930']['receipt_no'] == second['005930']['receipt_no'] == '20260814003699'
    assert second['005930']['verified'] is True
    assert_secret_absent(tmp_path, second)


def global_inputs():
    # Synthetic additional issuers exercise the official global-query window
    # constraint without pretending to be collected market observations.
    expected = {'005930': EXPECTED}
    xml = XML
    for i in range(10):
        code, corp, name = f'{100000 + i:06}', f'{10000000 + i:08}', f'합성테스트{i}'
        expected[code] = {**EXPECTED, 'name': name}
        xml = xml.replace('</result>', f'<list><corp_code>{corp}</corp_code><corp_name>{name}</corp_name><stock_code>{code}</stock_code><modify_date>20261105</modify_date></list></result>')
    return expected, xml


def test_global_queries_use_bounded_nonoverlapping_windows_and_compact_evidence(tmp_path):
    expected, xml = global_inputs()
    windows = []
    def windowed(params):
        assert 'corp_code' not in params
        windows.append((params['bgn_de'], params['end_de']))
        if params['bgn_de'] <= '20260814' <= params['end_de']:
            return response(payload())
        return response({'status': '013', 'message': '조회된 데이타가 없습니다.'})
    rows = collect(tmp_path, expected=expected, xml=xml, api_response=windowed)
    assert windows == [('20260101', '20260330'), ('20260331', '20260627'),
                       ('20260628', '20260924'), ('20260925', '20261105')]
    assert rows['005930']['verified'] is True
    assert rows['005930']['search_completeness']['queries'] == 4
    assert len(rows['005930']['evidence']) <= 2
    assert_secret_absent(tmp_path, rows)


def test_original_and_later_correction_across_windows_cannot_promote_original(tmp_path):
    expected, xml = global_inputs()
    correction = {**SAMSUNG, 'report_nm': '[기재정정]반기보고서 (2026.06)',
                  'rcept_no': '20261008000000', 'rcept_dt': '20261008'}
    def windowed(params):
        entries = [r for r in [SAMSUNG, correction] if params['bgn_de'] <= r['rcept_dt'] <= params['end_de']]
        return response(payload(entries)) if entries else response({'status': '013', 'message': '조회된 데이타가 없습니다.'})
    row = collect(tmp_path, expected=expected, xml=xml, api_response=windowed)['005930']
    assert row['verified'] is False
    assert row['error'] == 'AMBIGUOUS_FINAL_FILING'
    assert row['correction_status'] != 'ORIGINAL'


def test_later_correction_remark_rejects_even_an_explicit_correction_prefix(tmp_path):
    row = collect(tmp_path, rows=[{**SAMSUNG, 'report_nm': '[기재정정]반기보고서 (2026.06)', 'rm': '정'}])['005930']
    assert row['verified'] is False
    assert row['error'] == 'SUPERSEDED_FILING'
    assert 'PUBLIC_DART' not in row['correction_check']
    assert 'OPENDART_API' in row['correction_check']


def test_authentication_api_error_is_a_source_failure_without_message_leak(tmp_path):
    rows = collect(tmp_path, api_response=lambda _: response({'status': '010', 'message': '등록되지 않은 인증키'}))
    assert rows['005930']['error'] == 'SOURCE_UNAVAILABLE'
    assert rows['005930']['error_detail'] == 'API_STATUS_010'
    assert_secret_absent(tmp_path, rows)


def test_http_error_with_authenticated_response_url_does_not_leak_credentials(tmp_path):
    rows = collect(tmp_path, api_response=lambda _: response({'message': SECRET}, status=403))
    assert rows['005930']['error'] == 'SOURCE_UNAVAILABLE'
    assert_secret_absent(tmp_path, rows)


def test_credential_parameter_reflection_is_not_a_cacheable_public_response(tmp_path):
    rows = collect(tmp_path, api_response=lambda _: response({'status': '010', 'crtfc_key': 'masked'}))
    assert rows['005930']['error'] == 'COLLECTION_ERROR'
    assert_secret_absent(tmp_path, rows)


def test_conflicting_stock_mapping_is_held_without_picking_first_corporation(tmp_path):
    other = '<list><corp_code>99999999</corp_code><corp_name>다른법인</corp_name><stock_code>005930</stock_code></list>'
    rows = collect(tmp_path, xml=XML.replace('</result>', other + '</result>'))
    assert rows['005930']['verified'] is False
    assert rows['005930']['error'] == 'AMBIGUOUS_IDENTITY'


def test_authentication_failure_does_not_poison_replay_after_credential_is_fixed(tmp_path):
    first = collect(tmp_path, api_response=lambda _: response({'status': '010', 'message': '등록되지 않은 인증키'}))
    assert first['005930']['error'] == 'SOURCE_UNAVAILABLE'
    second = collect(tmp_path)
    assert second['005930']['verified'] is True
    assert second['005930']['receipt_no'] == '20260814003699'
