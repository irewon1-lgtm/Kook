"""Controlled account API responses; no credential or real API call is used.

Bulk inputs and filing identity below are actual Dongwha observations from the
collected source. API account rows are controlled fixtures, not a claim that
those source amounts have already been reconciled against the live API.
"""
from __future__ import annotations

import copy
import json
import sys
from pathlib import Path
from unittest.mock import patch

import pytest
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
try:
    import reconcile_financial_corrections as rec
except ImportError:
    rec = None

SECRET = 'correction-test-secret-never-persist'
RAW = {'issuer_code': '000020', 'issuer_name': '동화약품', 'sector': '의약품 제조업',
       'scope': 'CFS', 'currency': 'KRW', 'unit': 'KRW', 'report_period': '2026-06-30',
       'report_kind': '반기보고서', 'source_file': '2026_2Q_BS_20261105030346.zip',
       'validation_issues': [], 'assets': 653674878926, 'liabilities': 238208331459,
       'equity': 415466547467, 'current_assets': 236729687805, 'current_liabilities': 193552459361}
FILING = {'expected_code': '000020', 'expected_name': '동화약품', 'corp_code': '00119195',
          'receipt_no': '20261002000407', 'filing_date': '2026-10-02', 'report_period': '2026-06-30',
          'correction_status': 'LATEST_CORRECTION', 'verified': True, 'error': None,
          'source_url': 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261002000407'}
FIELDS = ['assets', 'liabilities', 'equity', 'current_assets', 'current_liabilities']
ACCOUNTS = [('ifrs-full_Assets', '자산총계'), ('ifrs-full_Liabilities', '부채총계'),
            ('ifrs-full_Equity', '자본총계'), ('ifrs-full_CurrentAssets', '유동자산'),
            ('ifrs-full_CurrentLiabilities', '유동부채')]


def module():
    assert rec is not None, 'latest-correction amount reconciler is missing'
    return rec


def rows_for(raw=None):
    raw = raw or RAW
    return [{'rcept_no': '20261002000407', 'reprt_code': '11012', 'bsns_year': '2026',
             'corp_code': '00119195', 'sj_div': 'BS', 'sj_nm': '재무상태표',
             'account_id': account, 'account_nm': name, 'account_detail': '-',
             'thstrm_nm': '제98기 반기말', 'thstrm_amount': str(raw[field]),
             'frmtrm_nm': '제97기말', 'frmtrm_amount': '', 'bfefrmtrm_nm': '',
             'bfefrmtrm_amount': '', 'ord': str(i + 1), 'currency': 'KRW'}
            for i, (field, (account, name)) in enumerate(zip(FIELDS, ACCOUNTS))]


def run(tmp_path, *, records=None, raw=None, filing=None, payload=None, unavailable=False):
    raw_map = {'000020': copy.deepcopy(raw or RAW)}
    filing_map = {'000020': copy.deepcopy(filing or FILING)}
    before_raw, before_filing = copy.deepcopy(raw_map), copy.deepcopy(filing_map)
    records = rows_for(raw) if records is None else records
    def request(method, url, **kwargs):
        assert method == 'GET'
        assert url == 'https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json'
        assert kwargs['params'] == {'corp_code': '00119195', 'bsns_year': '2026',
                                   'reprt_code': '11012', 'fs_div': 'CFS', 'crtfc_key': SECRET}
        if unavailable:
            raise requests.exceptions.SSLError('failure ?crtfc_key=' + SECRET)
        response = requests.Response()
        response.status_code = 200
        response.url = url + '?crtfc_key=' + SECRET
        response._content = json.dumps(payload or {'status': '000', 'message': '정상', 'list': records}, ensure_ascii=False).encode()
        return response
    with patch.object(module().api, '_request', side_effect=request), patch.object(module().time, 'sleep'):
        result = module().reconcile_corrections(raw_map, filing_map, tmp_path, SECRET)
    assert raw_map == before_raw, 'reconciliation must never change any raw amount or source fact'
    assert filing_map == before_filing, 'caller filing metadata must also remain unchanged'
    return result['000020']


def test_exact_latest_receipt_and_all_five_amounts_produce_bound_correction_proof(tmp_path):
    row = run(tmp_path)
    assert row['values_reconciled'] is True
    assert row['verified'] is True
    proof = row['correction_reconciliation']
    assert proof['receipt_no'] == '20261002000407'
    assert proof['corp_code'] == '00119195'
    assert proof['report_period'] == '2026-06-30'
    assert proof['scope'] == 'CFS'
    assert proof['currency'] == 'KRW'
    assert proof['amounts'] == {'assets': 653674878926, 'liabilities': 238208331459,
                                'equity': 415466547467, 'current_assets': 236729687805,
                                'current_liabilities': 193552459361}
    assert proof['source_api_url'] == 'https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json'
    assert len(proof['source_api_sha256']) == 64
    assert row.get('reconciliation_error') is None


def test_changed_amount_is_held_and_retains_both_sources(tmp_path):
    rows = rows_for()
    rows[0]['thstrm_amount'] = '653674878927'
    row = run(tmp_path, records=rows)
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == 'BULK_AMOUNT_MISMATCH'
    proof = row['correction_reconciliation']
    assert proof['bulk_amounts']['assets'] == 653674878926
    assert proof['amounts']['assets'] == 653674878927
    assert row['verified'] is True  # The receipt identity itself remains independently verified.


@pytest.mark.parametrize('changed,error', [
    ({'rcept_no': '20260814000000'}, 'RECEIPT_MISMATCH'),
    ({'corp_code': '99999999'}, 'CORPORATION_MISMATCH'),
    ({'reprt_code': '11013'}, 'REPORT_CODE_MISMATCH'),
    ({'bsns_year': '2025'}, 'BUSINESS_YEAR_MISMATCH'),
    ({'currency': 'USD'}, 'MIXED_CURRENCY'),
    ({'fs_div': 'OFS'}, 'SCOPE_MISMATCH'),
    ({'thstrm_dt': '2026.03.31'}, 'REPORT_PERIOD_MISMATCH'),
])
def test_wrong_receipt_identity_period_scope_or_currency_never_reconciles(tmp_path, changed, error):
    rows = rows_for()
    rows[0].update(changed)
    row = run(tmp_path, records=rows)
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == error


def test_single_wrong_currency_is_not_fx_converted(tmp_path):
    rows = [{**r, 'currency': 'USD'} for r in rows_for()]
    row = run(tmp_path, records=rows)
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == 'CURRENCY_MISMATCH'


def test_missing_balance_sheet_account_is_not_replaced_with_income_statement_account(tmp_path):
    rows = rows_for()
    rows[0]['sj_div'] = 'IS'
    row = run(tmp_path, records=rows)
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == 'MISSING_ACCOUNT_ASSETS'


@pytest.mark.parametrize('value', ['', '-', '0.0', '1e3', '9,007,199,254,740,993.0'])
def test_missing_or_inexact_account_amounts_stay_unreconciled(tmp_path, value):
    rows = rows_for()
    rows[0]['thstrm_amount'] = value
    row = run(tmp_path, records=rows)
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] in {'MISSING_ACCOUNT_ASSETS', 'INVALID_AMOUNT_ASSETS'}


def test_zero_is_an_actual_integer_and_not_missing(tmp_path):
    raw = {**RAW, 'current_liabilities': 0}
    row = run(tmp_path, raw=raw)
    assert row['values_reconciled'] is True
    assert row['correction_reconciliation']['amounts']['current_liabilities'] == 0


def test_amounts_above_binary_float_precision_remain_exact_integers(tmp_path):
    raw = {**RAW, 'assets': 9007199254740993}
    row = run(tmp_path, raw=raw)
    assert row['values_reconciled'] is True
    assert row['correction_reconciliation']['amounts']['assets'] == 9007199254740993
    assert type(row['correction_reconciliation']['amounts']['assets']) is int


def test_conflicting_top_priority_duplicate_is_held(tmp_path):
    rows = rows_for()
    rows.append({**rows[0], 'thstrm_amount': '1'})
    row = run(tmp_path, records=rows)
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == 'CONFLICTING_ACCOUNT_ASSETS'
    assert set(row['correction_reconciliation']['account_candidates']['assets']) == {1, 653674878926}


def test_identical_duplicate_and_lower_priority_name_alias_do_not_override_canonical_amount(tmp_path):
    rows = rows_for()
    rows.extend([copy.deepcopy(rows[0]), {**rows[0], 'account_id': 'custom_asset_alias', 'thstrm_amount': '1'}])
    assert run(tmp_path, records=rows)['values_reconciled'] is True


def test_missing_bulk_values_are_skipped_before_network(tmp_path):
    raw = {**RAW, 'assets': None}
    row = run(tmp_path, raw=raw, unavailable=True)
    assert row['verified'] is True
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == 'MISSING_BULK_ACCOUNT'


def test_invalid_bulk_source_is_skipped_before_network(tmp_path):
    raw = {**RAW, 'validation_issues': ['MIXED_PERIOD']}
    row = run(tmp_path, raw=raw, unavailable=True)
    assert row['verified'] is True
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == 'RAW_VALIDATION_ISSUES'


def test_original_and_unverified_filings_are_unchanged_without_network(tmp_path):
    for filing in [{**FILING, 'correction_status': 'ORIGINAL'}, {**FILING, 'verified': False}]:
        assert run(tmp_path, filing=filing, unavailable=True) == filing


def test_transport_failure_invalidates_receipt_for_existing_source_fatal_gate(tmp_path):
    row = run(tmp_path, unavailable=True)
    assert row['verified'] is False
    assert row['error'] == 'SOURCE_UNAVAILABLE'
    assert row['values_reconciled'] is False
    assert SECRET not in json.dumps(row)
    assert all(SECRET.encode() not in path.read_bytes() for path in tmp_path.rglob('*') if path.is_file())


def test_known_no_data_is_a_hold_without_claiming_source_outage(tmp_path):
    row = run(tmp_path, payload={'status': '013', 'message': '조회된 데이타가 없습니다.'})
    assert row['verified'] is True
    assert row['values_reconciled'] is False
    assert row['reconciliation_error'] == 'NO_ACCOUNT_DATA'
    assert row['error'] is None


def test_authentication_api_error_is_not_a_normal_amount_hold(tmp_path):
    row = run(tmp_path, payload={'status': '010', 'message': '등록되지 않은 인증키'})
    assert row['verified'] is False
    assert row['error'] == 'SOURCE_UNAVAILABLE'


def test_credential_reflection_never_enters_proof_or_cached_response(tmp_path):
    row = run(tmp_path, payload={'status': '000', 'message': SECRET, 'list': rows_for()})
    assert row['verified'] is False
    assert row['error'] == 'COLLECTION_ERROR'
    assert SECRET not in json.dumps(row)
    assert all(SECRET.encode() not in path.read_bytes() for path in tmp_path.rglob('*') if path.is_file())
