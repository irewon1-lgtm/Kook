#!/usr/bin/env python3
"""Bind existing bulk amounts to an exact corrected filing without changing them."""
from __future__ import annotations

import copy
import json
import re
import time
from datetime import date
from pathlib import Path
from typing import Any

import collect_financial_safety as policy
import collect_financial_safety_verified as bulk
import opendart_filing_provenance as api

FIELDS = tuple(policy.ACCOUNT_SPECS)
REPORT_CODES = {'사업보고서': ('11011', '4'), '반기보고서': ('11012', '2'),
                '1분기보고서': ('11013', '1'), '3분기보고서': ('11014', '3')}
ENDPOINT = 'fnlttSinglAcntAll.json'


def _precheck(code, raw, filing):
    if not raw:
        return 'MISSING_RAW_RECORD'
    if raw.get('validation_issues'):
        return 'RAW_VALIDATION_ISSUES'
    if any(raw.get(field) is None for field in FIELDS):
        return 'MISSING_BULK_ACCOUNT'
    if any(type(raw[field]) is not int or not -(2**63) <= raw[field] < 2**63 for field in FIELDS):
        return 'INVALID_BULK_AMOUNT'
    if not re.fullmatch(r'[0-9A-Z]{6}', code) or raw.get('issuer_code') != code:
        return 'ISSUER_CODE_MISMATCH'
    if not re.fullmatch(r'\d{8}', str(filing.get('corp_code', ''))) or not re.fullmatch(r'\d{14}', str(filing.get('receipt_no', ''))):
        return 'INVALID_FILING_IDENTITY'
    if filing.get('expected_code', code) != code:
        return 'ISSUER_CODE_MISMATCH'
    if filing.get('expected_name') and bulk.normalized_name(filing['expected_name']) != bulk.normalized_name(raw.get('issuer_name', '')):
        return 'ISSUER_NAME_MISMATCH'
    try:
        date.fromisoformat(raw.get('report_period', ''))
    except (TypeError, ValueError):
        return 'INVALID_REPORT_PERIOD'
    if raw['report_period'] != filing.get('report_period'):
        return 'REPORT_PERIOD_MISMATCH'
    if raw.get('scope') not in {'CFS', 'OFS'}:
        return 'INVALID_RAW_SCOPE'
    if raw.get('currency') not in bulk.CURRENCIES or raw.get('unit') != raw['currency']:
        return 'INVALID_RAW_CURRENCY_UNIT'
    source = re.match(r'^(\d{4})_([1-4])Q_BS_', str(raw.get('source_file', '')))
    if not source:
        return 'INVALID_SOURCE_FILE'
    report = REPORT_CODES.get(raw.get('report_kind'))
    if not report or source.group(2) != report[1]:
        return 'SOURCE_REPORT_KIND_MISMATCH'
    return None


def _amounts(rows):
    amounts, candidates, observations, issues = {}, {}, {}, []
    for field in FIELDS:
        scored = [(policy.account_score(field, {'항목코드': row.get('account_id'), '항목명': row.get('account_nm')}), row) for row in rows]
        scored = [(score, row) for score, row in scored if score >= 0]
        selected = [row for score, row in scored if score == max(s for s, _ in scored)] if scored else []
        values, evidence, invalid = [], [], False
        for row in selected:
            value = row.get('thstrm_amount')
            try:
                if not isinstance(value, (str, int)) or isinstance(value, bool):
                    raise ValueError
                parsed = bulk.exact_amount(str(value))
            except ValueError:
                parsed, invalid = None, True
            values.append(parsed)
            evidence.append({'account_id': row.get('account_id'), 'account_name': row.get('account_nm'),
                             'raw_amount': value, 'amount': parsed})
        unique = list(dict.fromkeys(values))
        amounts[field] = unique[0] if len(unique) == 1 and not invalid else None
        candidates[field], observations[field] = unique, evidence
        if invalid:
            issues.append('INVALID_AMOUNT_' + field.upper())
        elif not selected or None in unique:
            issues.append('MISSING_ACCOUNT_' + field.upper())
        elif len(unique) != 1:
            issues.append('CONFLICTING_ACCOUNT_' + field.upper())
    return amounts, candidates, observations, issues


def _row_contract(rows, raw, filing, params):
    if not rows:
        return 'NO_BALANCE_SHEET_DATA'
    for row in rows:
        for key, expected, issue in (
            ('corp_code', filing['corp_code'], 'CORPORATION_MISMATCH'),
            ('rcept_no', filing['receipt_no'], 'RECEIPT_MISMATCH'),
            ('bsns_year', params['bsns_year'], 'BUSINESS_YEAR_MISMATCH'),
            ('reprt_code', params['reprt_code'], 'REPORT_CODE_MISMATCH'),
        ):
            if str(row.get(key, '')) != expected:
                return issue
        if 'fs_div' in row and row['fs_div'] != raw['scope']:
            return 'SCOPE_MISMATCH'
        if row.get('thstrm_dt'):
            dates = re.findall(r'(\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})', str(row['thstrm_dt']))
            try:
                observed = date(*map(int, dates[-1])).isoformat() if dates else None
            except ValueError:
                observed = None
            if observed != raw['report_period']:
                return 'REPORT_PERIOD_MISMATCH'
    currencies = {str(row.get('currency') or '').strip() for row in rows}
    if len(currencies) != 1:
        return 'MIXED_CURRENCY'
    if currencies != {raw['currency']}:
        return 'CURRENCY_MISMATCH'
    return None


def reconcile_corrections(raw: dict, filings: dict, cache_dir: Path, api_key: str) -> dict:
    """Compare all five unchanged bulk values to the exact latest API receipt.

    Missing or contradictory observations remain an explicit unreconciled HOLD.
    Transport/protocol/authentication failures invalidate filing verification so
    the caller's source-failure gate remains effective. Credentials and API I/O
    use the existing authenticated client's sanitized cache and request policy.
    """
    result = copy.deepcopy(filings)
    directory = Path(cache_dir)
    client = None
    for code, filing in result.items():
        if not filing.get('verified') or filing.get('correction_status') != 'LATEST_CORRECTION':
            continue
        filing['values_reconciled'] = False
        filing.pop('correction_reconciliation', None)
        filing.pop('reconciliation_error', None)
        source = raw.get(code)
        error = _precheck(code, source, filing)
        if error:
            filing['reconciliation_error'] = error
            continue
        if not isinstance(api_key, str) or not api_key:
            filing.update(verified=False, error='SOURCE_UNAVAILABLE', reconciliation_error='AUTHENTICATION_REQUIRED')
            continue
        if client is None:
            client = api._Client(directory, api_key)
        year = re.match(r'^(\d{4})_', source['source_file']).group(1)
        params = {'corp_code': filing['corp_code'], 'bsns_year': year,
                  'reprt_code': REPORT_CODES[source['report_kind']][0], 'fs_div': source['scope']}
        try:
            body, evidence = client.fetch(ENDPOINT, params)
            payload = json.loads(body)
            if payload.get('status') == '013':
                filing['reconciliation_error'] = 'NO_ACCOUNT_DATA'
                continue
            account_rows = payload.get('list')
            if not isinstance(account_rows, list) or any(not isinstance(row, dict) for row in account_rows):
                raise api._SafeFailure('COLLECTION_ERROR', 'MALFORMED_ACCOUNT_RESPONSE')
            bs_rows = [row for row in account_rows if row.get('sj_div') == 'BS']
            amounts, candidates, observations, account_issues = _amounts(bs_rows)
            proof = {'receipt_no': filing['receipt_no'], 'corp_code': filing['corp_code'],
                     'report_period': source['report_period'], 'scope': source['scope'], 'currency': source['currency'],
                     'bsns_year': year, 'reprt_code': params['reprt_code'], 'amounts': amounts,
                     'bulk_amounts': {field: source[field] for field in FIELDS},
                     'account_candidates': candidates, 'account_evidence': observations,
                     'observed_receipt_nos': sorted({str(row.get('rcept_no', '')) for row in bs_rows}),
                     'observed_corp_codes': sorted({str(row.get('corp_code', '')) for row in bs_rows}),
                     'observed_currencies': sorted({str(row.get('currency', '')) for row in bs_rows}),
                     'source_api_url': evidence['url'], 'source_api_sha256': evidence['sha256'],
                     'source_api_params': evidence['params'], 'source_api_raw_path': evidence['raw_path'],
                     'source_api_metadata_path': evidence['metadata_path'],
                     'reconciled_at_utc': evidence['retrieved_at_utc']}
            filing['correction_reconciliation'] = proof
            error = _row_contract(bs_rows, source, filing, params)
            if not error and account_issues:
                error = account_issues[0]
            mismatches = {field: {'bulk': source[field], 'api': amounts[field]} for field in FIELDS
                          if amounts[field] is not None and amounts[field] != source[field]}
            proof['mismatches'] = mismatches
            if not error and mismatches:
                error = 'BULK_AMOUNT_MISMATCH'
            if error:
                filing['reconciliation_error'] = error
                proof['validation_error'] = error
            else:
                filing['values_reconciled'] = True
                filing['correction_check'] = 'OPENDART_API: latest final receipt; exact corporation/period/scope/currency and all five unchanged bulk amounts match fnlttSinglAcntAll'
        except Exception as exc:
            error = exc.code if isinstance(exc, api._SafeFailure) else 'COLLECTION_ERROR'
            detail = exc.detail if isinstance(exc, api._SafeFailure) else type(exc).__name__
            filing.update(verified=False, error=error, reconciliation_error=detail)
        progress = directory / 'progress'
        progress.mkdir(parents=True, exist_ok=True)
        (progress / (code + '.json')).write_text(json.dumps(filing, ensure_ascii=False, indent=2) + '\n')
    return result
