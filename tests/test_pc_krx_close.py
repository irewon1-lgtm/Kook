"""KRX independent closing-price review never modifies market data or unlocks HOLD."""
import json
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_krx_close as module

MANIFEST = ROOT / 'evidence' / 'kr4_krx_close_hold_20261106.json'


def test_original_67_hold_records_are_intact():
    manifest = module.load_hold_manifest(MANIFEST)
    assert manifest['hold_count'] == len(manifest['records']) == 67
    assert all(row['decision'].startswith('HOLD_') for row in manifest['records'])


def test_empty_official_input_keeps_every_price_on_hold():
    result = module.reconcile(module.load_hold_manifest(MANIFEST), {}, [], None)
    assert result['remaining_hold'] == 67
    assert result['automatic_price_changes'] == 0
    assert result['counts'] == {'MISSING_KRX_RECORD': 67}


def test_submitted_korean_krx_format_only_compares(tmp_path):
    inp = tmp_path / 'official.csv'
    inp.write_text('일자,종목코드,종가\n2026-11-06,001540,"9,180"\n2026-11-06,005720,"6,060"\n', encoding='utf-8-sig')
    quotes, sources = module.parse_krx_files([inp], '20261106')
    result = module.reconcile(module.load_hold_manifest(MANIFEST), quotes, sources, 'https://data.krx.co.kr/')
    assert result['counts']['MATCH_ARCHIVED_CLOSE'] == 1
    assert result['counts']['DIFFERS_FROM_ARCHIVED_CLOSE'] == 1
    assert result['counts']['MISSING_KRX_RECORD'] == 65
    assert result['remaining_hold'] == 67 and result['automatic_price_changes'] == 0


def test_original_api_json_and_duplicate_guard(tmp_path):
    inp = tmp_path/'official.json'
    inp.write_text(json.dumps({'OutBlock_1': [{'BAS_DD': '20261106','ISU_CD': '001540','TDD_CLSPRC': '9,180'}]}))
    quotes, sources = module.parse_krx_files([inp], '20261106')
    assert quotes == {'001540': 9180}
    assert sources[0]['sha256']
    with pytest.raises(ValueError, match='Duplicate KRX'):
        module.parse_krx_files([inp, inp], '20261106')


def test_wrong_date_invalid_format_and_unofficial_metadata_fail_closed(tmp_path):
    wrong = tmp_path/'wrong.csv'
    wrong.write_text('BAS_DD,ISU_CD,TDD_CLSPRC\n20261113,001540,9180\n')
    with pytest.raises(ValueError, match='differs'):
        module.parse_krx_files([wrong], '20261106')
    thirdparty = tmp_path/'thirdparty.csv'
    thirdparty.write_text('basDt,종목코드,clpr\n20261106,001540,9180\n')
    with pytest.raises(ValueError, match='Missing KRX date'):
        module.parse_krx_files([thirdparty], '20261106')
    with pytest.raises(ValueError, match='Official KRX URL'):
        module.reconcile(module.load_hold_manifest(MANIFEST), {'001540': 9180}, [],
                         'https://data.krx.co.kr.evil.example/')


def test_invalid_price_and_date_without_explicit_record_provenance_fail(tmp_path):
    inp = tmp_path / 'bad.csv'
    inp.write_text('일자,종목코드,종가\n20261106,001540,0\n')
    with pytest.raises(ValueError, match='positive'):
        module.parse_krx_files([inp], '20261106')
    inp.write_text('종목코드,종가\n001540,9180\n')
    with pytest.raises(ValueError, match='Missing KRX date'):
        module.parse_krx_files([inp], '20261106')
