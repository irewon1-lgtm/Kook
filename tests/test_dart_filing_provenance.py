"""Independent DART HTML examples, reduced from public responses on 2026-11-05.

The real Samsung receipt/date/name are hand-read from dsab001/search.ax,
publicType=A002, finalReport=recent, textCrpNm=005930. Variants below test
failure boundaries; variant receipts are test inputs, never collected facts.
"""
import hashlib
import json
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
try:
    import dart_filing_provenance as dart
except ImportError:
    dart = None

ROW = '''<tr><td>1</td><td class="tL"><span class="innerWrap">
<span class="tagCom_kospi" title="유가증권시장">유</span>
<a onclick="openCorpInfoNew('00126380', 'winCorpInfo', '/dsae001/selectPopup.ax');" title="삼성전자 기업개황 새창">삼성전자</a>
</span></td><td class="tL"><a href="/dsaf001/main.do?rcpNo=20260814003699"
onclick="openReportViewer('20260814003699',''); return false;" id="r_20260814003699" title="반기보고서 공시뷰어 새창">반기보고서\n (2026.06)</a>
<img onclick="openXbrlViewerNew('https://opendart.fss.or.kr','20260814003699','Y','Y');"></td>
<td class="tL ellipsis" title="삼성전자">삼성전자</td><td>2026.08.14</td><td></td></tr>'''
SEARCH = '<table class="tbList"><tbody id="tbody">' + ROW + '</tbody></table><div class="pageInfo">[1/1] [총 1건]</div>'
PROFILE = '''<table class="popTbView"><tbody>
<tr><th><label>회사이름</label></th><td>삼성전자(주)<button>정보 더보기</button></td></tr>
<tr><th><label>공시회사명</label></th><td>삼성전자</td></tr>
<tr><th><label>종목코드</label></th><td>005930</td></tr>
</tbody></table>'''
EXPECTED = {'name': '삼성전자', 'period_end': '2026-06-30', 'report_kind': '반기보고서'}


def module():
    assert dart is not None, 'public DART provenance module is missing'
    return dart


def resolve(search=SEARCH, profile=PROFILE, code='005930', expected=None):
    return module().resolve_filing_provenance(code, expected or EXPECTED, search, profile, as_of='2026-11-05')


def test_real_search_row_uses_receipt_link_and_reception_date():
    row = resolve()
    assert row['verified'] is True
    assert row['corp_code'] == '00126380'
    assert row['receipt_no'] == '20260814003699'
    assert row['filing_date'] == '2026-08-14'
    assert row['report_period'] == '2026-06-30'
    assert row['source_url'] == 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814003699'
    assert row['correction_status'] == 'ORIGINAL'
    assert row['expected_code'] == '005930'
    assert row['expected_name'] == '삼성전자'
    assert row['expected_period'] == '2026-06-30'


def test_correction_label_is_retained_and_not_claimed_to_match_bulk():
    row = resolve(SEARCH.replace('반기보고서\n', '[기재정정]반기보고서\n'))
    assert row['verified'] is True
    assert row['correction_status'] == 'LATEST_CORRECTION'
    assert row['bulk_values_match_latest_filing'] is None
    assert 'finalReport=recent' in row['correction_check']


@pytest.mark.parametrize('profile,code,expected', [
    (PROFILE.replace('005930', '005935'), '005930', EXPECTED),
    (PROFILE, '005935', {'name': '삼성전자우', 'period_end': '2026-06-30', 'report_kind': '반기보고서'}),
    (PROFILE.replace('<td>삼성전자</td>', '<td>삼성전자서비스</td>'), '005930', EXPECTED),
])
def test_related_or_preferred_or_wrong_name_entity_is_rejected(profile, code, expected):
    row = resolve(profile=profile, code=code, expected=expected)
    assert row['verified'] is False
    assert row['error'] == 'IDENTITY_MISMATCH'


@pytest.mark.parametrize('search,error', [
    (SEARCH.replace('(2026.06)', '(2026.03)'), 'REPORT_PERIOD_NOT_FOUND'),
    (SEARCH.replace('2026.08.14</td>', '2026.11.06</td>'), 'INVALID_FILING_DATE'),
    (SEARCH.replace('2026.08.14</td>', '2026.08.15</td>'), 'INVALID_FILING_DATE'),
    (SEARCH.replace('[1/1]', '[1/2]'), 'INCOMPLETE_SEARCH_RESULTS'),
    ('<html>서비스 이용에 불편을 드려 죄송합니다.</html>', 'INVALID_SEARCH_RESPONSE'),
])
def test_invalid_or_ambiguous_evidence_never_verifies(search, error):
    row = resolve(search=search)
    assert row['verified'] is False
    assert row['error'] == error


def test_no_results_is_missing_rather_than_invalid():
    row = resolve('<table class="tbList"><tbody id="tbody"><tr><td class="no_data">조회 결과가 없습니다.</td></tr></tbody></table>')
    assert row['verified'] is False
    assert row['error'] == 'FILING_NOT_FOUND'
    assert row['receipt_no'] is None


def test_two_final_rows_for_same_period_fail_closed():
    duplicate = ROW.replace('20260814003699', '20260814003700')
    search = SEARCH.replace('</tbody>', duplicate + '</tbody>').replace('총 1건', '총 2건')
    row = resolve(search)
    assert row['verified'] is False
    assert row['error'] == 'AMBIGUOUS_FINAL_FILING'


def test_missing_expected_period_never_uses_most_recent_filing():
    row = resolve(expected={'name': '삼성전자', 'period_end': '', 'report_kind': '반기보고서'})
    assert row['verified'] is False
    assert row['error'] == 'INVALID_EXPECTED_PERIOD'


def test_collector_saves_original_raw_bytes_hash_and_request_provenance(tmp_path):
    class Response:
        status_code = 200
        def __init__(self, text):
            self.content = text.encode('utf-8')
        def raise_for_status(self):
            pass
    def request(method, url, **kwargs):
        if method == 'POST' and url.endswith('/dsab001/search.ax'):
            data = kwargs['data']
            assert data['publicType'] == 'A002'
            assert data['finalReport'] == 'recent'
            assert data['endDate'] == '20261105'
            return Response(SEARCH)
        assert method == 'GET' and url.endswith('selectKey=00126380')
        return Response(PROFILE)
    with patch.object(module().requests, 'request', side_effect=request):
        row = module().collect_filing_provenance({'005930': EXPECTED}, tmp_path, workers=1, as_of='2026-11-05')['005930']
    assert row['verified'] is True
    assert len(row['evidence']) == 2
    for evidence in row['evidence']:
        raw = Path(evidence['raw_path']).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == evidence['sha256']
        meta = json.loads(Path(evidence['metadata_path']).read_text())
        assert meta['url'].startswith('https://dart.fss.or.kr/')
        assert meta['status_code'] == 200
    # This second call must use the cached raw responses, not a network answer.
    with patch.object(module().requests, 'request', side_effect=AssertionError('unexpected network')):
        again = module().collect_filing_provenance({'005930': EXPECTED}, tmp_path, workers=1, as_of='2026-11-05')['005930']
    assert again['receipt_no'] == '20260814003699'


def test_unknown_report_prefix_does_not_claim_original_or_final_correction():
    row = resolve(SEARCH.replace('반기보고서\n', '[철회]반기보고서\n'))
    assert row['verified'] is False
    assert row['error'] == 'UNRECOGNIZED_REPORT_STATUS'


def test_corporations_sharing_a_search_response_cannot_use_first_identity():
    other = ROW.replace('00126380', '00126381').replace('삼성전자', '삼성전자서비스')
    search = SEARCH.replace('</tbody>', other + '</tbody>').replace('총 1건', '총 2건')
    row = resolve(search)
    assert row['verified'] is False
    assert row['error'] == 'AMBIGUOUS_COMPANY_SEARCH'


def test_annual_report_cannot_substitute_for_expected_half_year():
    row = resolve(SEARCH.replace('반기보고서', '사업보고서'))
    assert row['verified'] is False
    assert row['error'] == 'REPORT_PERIOD_NOT_FOUND'


def test_injected_as_of_date_rejects_a_filing_after_that_date():
    row = module().resolve_filing_provenance('005930', EXPECTED, SEARCH, PROFILE, as_of='2026-08-13')
    assert row['verified'] is False
    assert row['error'] == 'INVALID_FILING_DATE'
    assert row['as_of_date'] == '2026-08-13'


@pytest.mark.parametrize('kind', ['1분기보고서', '3분기보고서'])
def test_bulk_quarter_type_alias_keeps_exact_report_month_match(kind):
    spec = {'name': '삼성전자', 'period_end': '2026-06-30', 'report_kind': kind}
    row = resolve(SEARCH.replace('반기보고서', '분기보고서'), expected=spec)
    assert row['verified'] is True
    assert row['report_period'] == '2026-06-30'
    assert row['expected_report_kind'] == kind


def test_host_outage_opens_circuit_preserves_error_bytes_and_partial_records(tmp_path):
    calls = []
    def unavailable(method, url, **kwargs):
        calls.append(url)
        response = module().requests.Response()
        response.status_code = 503
        response._content = b'upstream connect error or disconnect/reset before headers'
        response.url = url
        return response
    expected = {'005930': EXPECTED, '000660': {'name': 'SK하이닉스', 'period_end': '2026-06-30', 'report_kind': '반기보고서'}}
    with patch.object(module().requests, 'request', side_effect=unavailable), patch.object(module().time, 'sleep'):
        rows = module().collect_filing_provenance(expected, tmp_path, workers=1, as_of='2026-11-05')
    assert len(calls) == 3, 'a host outage must stop new issuer network attempts'
    assert {r['error'] for r in rows.values()} == {'SOURCE_UNAVAILABLE'}
    assert rows['005930']['evidence'][0]['status_code'] == 503
    assert Path(rows['005930']['evidence'][0]['raw_path']).read_bytes().startswith(b'upstream connect error')
    partial = json.loads((tmp_path / 'progress' / '005930.json').read_text())
    assert partial['verified'] is False
    assert partial['error'] == 'SOURCE_UNAVAILABLE'


def test_cache_only_mode_preserves_unknown_instead_of_attempting_live_network(tmp_path):
    with patch.object(module().requests, 'request', side_effect=AssertionError('live network forbidden')):
        row = module().collect_filing_provenance({'005930': EXPECTED}, tmp_path, workers=1,
                                                as_of='2026-11-05', cache_only=True)['005930']
    assert row['verified'] is False
    assert row['error'] == 'SOURCE_UNAVAILABLE'
    assert row['receipt_no'] is None

CORP_MAPPING_HTML = '''<table class="tb"><tbody><tr><td><input type="checkbox" name="checkCorpSelect" value="삼성전자"></td>
<td class="ellipsis tl"><span class="tagCom_kospi">유</span>삼성전자
<input type='hidden' name='hiddenCikCD1' value='00126380'><input type='hidden' name='hiddenCikNM1' value='삼성전자'></td>
<td>전영현, 노태문</td><td>005930</td><td>통신 및 방송 장비 제조업</td></tr></tbody></table>
<div class="page_info">[1/1] [총 1건]</div>'''


def test_public_corporation_mapping_uses_stock_code_and_hidden_official_identity():
    assert module().parse_public_corp_mapping(CORP_MAPPING_HTML) == {
        '005930': {'stock_code': '005930', 'corp_code': '00126380', 'name': '삼성전자'}}


def test_bulk_search_rows_match_the_official_code_map_before_verifying():
    rows = [{'corp_code': '00126380', 'company_name': '삼성전자', 'receipt_no': '20260814003699',
             'report_name': '반기보고서 (2026.06)', 'filing_date_text': '2026.08.14'},
            {'corp_code': '00126381', 'company_name': '삼성전자서비스', 'receipt_no': '20260814003700',
             'report_name': '반기보고서 (2026.06)', 'filing_date_text': '2026.08.14'}]
    identity = {'stock_code': '005930', 'corp_code': '00126380', 'name': '삼성전자'}
    row = module().resolve_filing_rows('005930', EXPECTED, rows, identity, as_of='2026-11-05')
    assert row['verified'] is True
    assert row['receipt_no'] == '20260814003699'
    wrong = module().resolve_filing_rows('005930', EXPECTED, rows, {**identity, 'stock_code': '005935'}, as_of='2026-11-05')
    assert wrong['verified'] is False
    assert wrong['error'] == 'IDENTITY_MISMATCH'


def test_public_mapping_collection_keeps_raw_official_evidence(tmp_path):
    class Response:
        status_code = 200
        content = CORP_MAPPING_HTML.encode('utf-8')
        def raise_for_status(self):
            pass
    def request(method, url, **kwargs):
        assert method == 'POST' and url == 'https://opendart.fss.or.kr/cmm/searchCorpL.do'
        assert kwargs['data']['corporationType'] in {'P', 'A'}
        assert kwargs['data']['searchIndex'] in {str(i) for i in list(range(14)) + [15, 16]}
        return Response()
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        mapping = module().collect_public_corp_mapping(tmp_path)
    assert mapping['005930']['corp_code'] == '00126380'
    evidence = mapping['005930']['evidence'][0]
    assert Path(evidence['raw_path']).read_text() == CORP_MAPPING_HTML
    assert evidence['url'] == 'https://opendart.fss.or.kr/cmm/searchCorpL.do'


def test_stale_current_day_cache_does_not_claim_a_fresh_correction_check(tmp_path):
    from datetime import datetime, timezone
    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime(2026, 11, 5, 9, 0, tzinfo=timezone.utc).astimezone(tz)
    class Response:
        status_code = 200
        def __init__(self, text):
            self.content = text.encode()
        def raise_for_status(self):
            pass
    def request(method, url, **kwargs):
        return Response(SEARCH if method == 'POST' else PROFILE)
    with patch.object(module(), 'datetime', Clock), patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        module().collect_filing_provenance({'005930': EXPECTED}, tmp_path, as_of='2026-11-05')
        for path in tmp_path.glob('*.json'):
            meta = json.loads(path.read_text())
            meta['retrieved_at_utc'] = '2026-11-05T07:00:00+00:00'
            path.write_text(json.dumps(meta))
        row = module().collect_filing_provenance({'005930': EXPECTED}, tmp_path, as_of='2026-11-05', cache_only=True)['005930']
    assert row['verified'] is False
    assert row['error'] == 'STALE_CORRECTION_CHECK'
    assert row['receipt_no'] == '20260814003699'
    assert row['correction_checked_at_utc'] == '2026-11-05T07:00:00+00:00'


def test_late_inflight_success_cannot_reopen_an_outage_circuit():
    gate = module()._HostGate()
    for _ in range(3):
        gate.failure(ConnectionError('official source unavailable'), None)
    gate.success()  # A request already in flight may finish after the circuit opens.
    with pytest.raises(module()._SourceUnavailable):
        gate.before_request()


def test_issuer_absent_from_initial_index_map_gets_exact_official_code_lookup(tmp_path):
    class Response:
        status_code = 200
        def __init__(self, markup):
            self.content = markup.encode()
        def raise_for_status(self):
            pass
    def request(method, url, **kwargs):
        data = kwargs['data']
        if data['textCrpNm'] == '000660':
            return Response(CORP_MAPPING_HTML.replace('005930', '000660').replace('00126380', '00164779').replace('삼성전자', 'SK하이닉스'))
        return Response(CORP_MAPPING_HTML)
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        mapping = module().collect_public_corp_mapping(tmp_path, expected_codes=['005930', '000660'])
    assert mapping['000660']['stock_code'] == '000660'
    assert mapping['000660']['corp_code'] == '00164779'
    assert mapping['000660']['name'] == 'SK하이닉스'


def test_global_report_pages_are_all_collected_before_final_receipt_selection(tmp_path):
    class Response:
        status_code = 200
        def __init__(self, text):
            self.content = text.encode()
        def raise_for_status(self):
            pass
    def request(method, url, **kwargs):
        assert url == 'https://dart.fss.or.kr/dsab001/search.ax'
        page = kwargs['data']['currentPage']
        assert kwargs['data']['textCrpNm'] == ''
        text = SEARCH.replace('[1/1] [총 1건]', f'[{page}/2] [총 2건]')
        if page == '2':
            text = text.replace('20260814003699', '20260814003700')
        return Response(text)
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        rows, evidence = module().collect_public_filing_rows(tmp_path, 'A002', 2026, as_of='2026-11-05')
    assert [r['receipt_no'] for r in rows] == ['20260814003699', '20260814003700']
    assert len(evidence) == 2


def test_global_collector_uses_official_stock_map_without_per_company_profile_calls(tmp_path):
    class Response:
        status_code = 200
        def __init__(self, text):
            self.content = text.encode()
        def raise_for_status(self):
            pass
    def request(method, url, **kwargs):
        assert method == 'POST'
        if url == 'https://opendart.fss.or.kr/cmm/searchCorpL.do':
            return Response(CORP_MAPPING_HTML)
        assert url == 'https://dart.fss.or.kr/dsab001/search.ax'
        assert kwargs['data']['textCrpNm'] == ''
        return Response(SEARCH)
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        row = module().collect_filing_provenance({'005930': EXPECTED}, tmp_path,
                                                as_of='2026-11-05', global_search=True)['005930']
    assert row['verified'] is True
    assert row['receipt_no'] == '20260814003699'
    assert row['profile_stock_code'] == '005930'
    assert row['correction_checked_at_utc']


def test_official_identity_is_preserved_even_when_global_filing_host_is_down(tmp_path):
    def request(method, url, **kwargs):
        response = module().requests.Response()
        response.url = url
        response.status_code = 200 if url.startswith('https://opendart.') else 503
        response._content = CORP_MAPPING_HTML.encode() if response.status_code == 200 else b'upstream connection termination'
        return response
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        row = module().collect_filing_provenance({'005930': EXPECTED}, tmp_path,
                                                as_of='2026-11-05', global_search=True)['005930']
    assert row['corp_code'] == '00126380'
    assert row['profile_stock_code'] == '005930'
    assert row['verified'] is False
    assert row['error'] == 'SOURCE_UNAVAILABLE'
    assert row['receipt_no'] is None
    assert row['source_error_evidence'][0]['status_code'] == 503


def test_global_identity_source_outage_returns_explicit_unknown_records(tmp_path):
    def request(method, url, **kwargs):
        response = module().requests.Response()
        response.url = url
        response.status_code = 503
        response._content = b'official identity source unavailable'
        return response
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        rows = module().collect_filing_provenance({'005930': EXPECTED}, tmp_path,
                                                 as_of='2026-11-05', global_search=True)
    assert rows['005930']['verified'] is False
    assert rows['005930']['error'] == 'SOURCE_UNAVAILABLE'
    assert 'identity' in rows['005930']['live_refresh_error'].lower()

OPENDART_REFERENCE_HTML = '''<table class="tb_result"><thead><tr><th>회사명</th><th>결산기준일</th></tr></thead><tbody>
<tr><td><span class="tagCom_kospi">유</span><span onclick="openCorpInfo('00365387'); return false;" title="AJ네트웍스 기업개황 새창">AJ네트웍스</span></td>
<td title="2026-06-30"><a id="r_20260814004027" onclick="openReportViewerDetail('20260814004027','5','','TA'); return false;">2026-06-30</a></td>
<td><a id="r_20260814004027">6</a></td><td>2</td><td>2</td><td>-</td><td>-</td></tr>
</tbody></table><div class="page_info">[1/1] [총 1건]</div>'''


def test_opendart_reference_exposes_actual_period_and_receipt_without_inventing_filing_date():
    rows = module().parse_opendart_reference_rows(OPENDART_REFERENCE_HTML)
    assert rows == [{'corp_code': '00365387', 'company_name': 'AJ네트웍스',
                     'receipt_no': '20260814004027', 'report_period': '2026-06-30'}]
    assert 'filing_date' not in rows[0]


def test_opendart_reference_pagination_keeps_every_exact_period_row(tmp_path):
    class Response:
        status_code = 200
        content = OPENDART_REFERENCE_HTML.encode()
        def raise_for_status(self):
            pass
    with patch.object(module().requests, 'request', return_value=Response()):
        rows, evidence = module().collect_opendart_reference_rows(tmp_path, '11012', 2026)
    assert rows[0]['receipt_no'] == '20260814004027'
    assert evidence[0]['url'] == 'https://opendart.fss.or.kr/disclosureinfo/biz/list.do'


def test_opendart_receipt_fallback_keeps_missing_date_and_correction_unverified(tmp_path):
    mapping = CORP_MAPPING_HTML.replace('005930', '095570').replace('00126380', '00365387').replace('삼성전자', 'AJ네트웍스')
    def request(method, url, **kwargs):
        response = module().requests.Response()
        response.url = url
        response.status_code = 503 if url.startswith('https://dart.') else 200
        if '/cmm/searchCorpL.do' in url:
            response._content = mapping.encode()
        elif '/disclosureinfo/biz/list.do' in url:
            response._content = OPENDART_REFERENCE_HTML.encode()
        else:
            response._content = b'upstream connection termination'
        return response
    spec = {'095570': {'name': 'AJ네트웍스', 'period_end': '2026-06-30', 'report_kind': '반기보고서'}}
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        row = module().collect_filing_provenance(spec, tmp_path, as_of='2026-11-05', global_search=True)['095570']
    assert row['receipt_no'] == '20260814004027'
    assert row['report_period'] == '2026-06-30'
    assert row['filing_date'] is None
    assert row['correction_status'] == 'UNKNOWN'
    assert row['verified'] is False
    assert row['error'] == 'SOURCE_UNAVAILABLE'


def test_company_ir_badge_cannot_replace_the_official_company_name():
    markup = OPENDART_REFERENCE_HTML.replace('</span></td>', '</span><span><a href="https://company.example/ir"><span class="tagCom_ir">IR</span></a></span></td>')
    row = module().parse_opendart_reference_rows(markup)[0]
    assert row['company_name'] == 'AJ네트웍스'


def test_exact_reference_lookups_include_official_other_company_class_for_delisted_issuers(tmp_path):
    class Response:
        status_code = 200
        content = OPENDART_REFERENCE_HTML.encode()
        def raise_for_status(self):
            pass
    def request(method, url, **kwargs):
        assert kwargs['data']['corpType'] == ['P', 'A', 'N', 'E']
        assert kwargs['data']['corpTypeAll'] == '1'
        return Response()
    with patch.object(module().requests, 'request', side_effect=request):
        rows, _ = module().collect_opendart_reference_rows(tmp_path, '11012', 2026, text_crp_cik='00365387')
    assert rows[0]['corp_code'] == '00365387'


# Actual first AJ Networks common-share row, public OpenDART LA table.
OPENDART_LA_REFERENCE_HTML = '<table class="tb_result"><tbody><tr class="">\n\t\t\t\t\t\t\t<td class="tl">\n\t\t\t\t\t\t\t    \t<span class="tagCom_kospi" alt="유가증권시장">유</span><span class="com layerPop03 btnLayerPop"  onclick="openCorpInfo(\'00365387\'); return false;" title="AJ네트웍스 기업개황 새창">AJ네트웍스</span>\n\t\t\t\t\t\t\t\t\n\t\t\t\t\t\t\t</td>\n\t\t\t\t\t\t\t<td title="2026-06-30"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">2026-06-30</a></td>\n\t\t\t\t\t\t\t<td title="보통주"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">보통주</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="100,000,000"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">100,000,000</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="47,222,295"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">47,222,295</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="1,969,536"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">1,969,536</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="400,000"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">400,000</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="1,569,536"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">1,569,536</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="-"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">-</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="-"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">-</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="45,252,759"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">45,252,759</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="501,709"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">501,709</a></td>\n\t\t\t\t\t\t\t<td class="tr" title="44,751,050"><a id="r_20260814004027" onclick="openReportViewerDetail(\'20260814004027\',\'5\',\'\',\'LA\'); return false;" title="보고서바로가기">44,751,050</a></td>\n\t\t\t\t\t\t</tr></tbody></table><div class="page_info">[1/1] [총 1건]</div>'


def test_missing_optional_director_table_uses_actual_share_table_reference(tmp_path):
    mapping = CORP_MAPPING_HTML.replace('005930', '095570').replace('00126380', '00365387').replace('삼성전자', 'AJ네트웍스')
    def request(method, url, **kwargs):
        response = module().requests.Response()
        response.url = url
        response.status_code = 503 if url.startswith('https://dart.') else 200
        if '/cmm/searchCorpL.do' in url:
            response._content = mapping.encode()
        elif '/disclosureinfo/biz/list.do' in url:
            response._content = (OPENDART_LA_REFERENCE_HTML if kwargs['data']['gubun'] == 'LA'
                                 else '<table class="tb_result"><tr><td>조회 결과가 없습니다.</td></tr></table>').encode()
        else:
            response._content = b'upstream connection termination'
        return response
    spec = {'095570': {'name': 'AJ네트웍스', 'period_end': '2026-06-30', 'report_kind': '반기보고서'}}
    with patch.object(module().requests, 'request', side_effect=request), patch.object(module().time, 'sleep'):
        row = module().collect_filing_provenance(spec, tmp_path, as_of='2026-11-05', global_search=True)['095570']
    assert row['receipt_no'] == '20260814004027'
    assert row['filing_date'] is None
    assert row['verified'] is False
