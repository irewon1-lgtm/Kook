"""Read-only KRX date/issuer/close verification regression tests."""
import hashlib
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
# Support this review file outside the Git checkout as well as inside tests/.
sys.path.insert(0,str(Path(__file__).resolve().parent))
import audit_pc_krx_closes as audit


def registry(tmp_path):
    source=tmp_path/'registry.csv'
    source.write_text('issuer_id,trade_date,archived_close_krw,github_blob_sha1,status\n' + ''.join(
        f'{i:06d},2026-11-06,1000,{i:040x},HOLD_KRX_UNVERIFIED\n'
        for i in range(1,68)),encoding='utf-8')
    return source


def data(*rows):
    return json.dumps({'OutBlock_1':list(rows)},ensure_ascii=False).encode()


def krx(code,price,day='20261106'):
    return {'BAS_DD':day,'ISU_CD':code,'ISU_NM':'증거종목','TDD_CLSPRC':price}


def test_registry_requires_exact_67_and_original_price_evidence(tmp_path):
    f=registry(tmp_path)
    records=audit.read_registry(f)
    assert len(records)==67 and records[0]['archived_close_krw']==1000
    assert records[0]['github_blob_sha1']=='0'*39+'1'
    f.write_text(f.read_text().replace('000067,','000066,'),encoding='utf-8')
    with pytest.raises(ValueError,match='exactly 67'):
        audit.read_registry(f)


def test_official_krx_response_requires_exact_date_unique_code_and_positive_close():
    good=audit.read_official(data(krx('000001','1,010'),krx('000002','2,300')),'KOSPI')
    assert good['000001']['close']==1010
    assert good['000002']['close']==2300
    with pytest.raises(ValueError,match='trade date'):
        audit.read_official(data(krx('000001','1,010','20261105')),'KOSPI')
    with pytest.raises(ValueError,match='Duplicate'):
        audit.read_official(data(krx('000001','1000'),krx('000001','2000')),'KOSPI')
    with pytest.raises(ValueError,match='positive'):
        audit.read_official(data(krx('000001','0')),'KOSPI')
    with pytest.raises(ValueError,match='OutBlock_1'):
        audit.read_official(b'{"rows":[]}', 'KOSPI')
    assert not audit.read_official(data(krx('KR7000000000','1234')),'KOSPI')


def test_no_keys_or_missing_market_files_never_claim_official_verification(tmp_path):
    f=registry(tmp_path)
    report=audit.execute(f,status_only=True)
    assert report['registry_count']==67 and report['hold_count']==67
    assert report['krx_exact_matches']==0 and report['promotion_authorized'] is False
    assert report['by_classification']=={'BLOCKED_KRX_NOT_VERIFIED':67}
    with pytest.raises(RuntimeError,match='both KRX market JSON'):
        audit.execute(f, files={'KOSPI':tmp_path/'anything'})
    with pytest.raises(RuntimeError,match='approved KRX_AUTH_KEY'):
        audit.execute(f,fetch=True,key=None)


def test_snapshot_comparison_with_naver_is_hold_only_even_when_official_matches(tmp_path):
    f=registry(tmp_path)
    kospi=tmp_path/'KOSPI.json';kosdaq=tmp_path/'KOSDAQ.json'
    kospi.write_bytes(data(krx('000001','1000'),krx('000002','1010'),krx('000003','1020')))
    kosdaq.write_bytes(data(krx('000004','1050')))
    naver=tmp_path/'naver.json'
    observations={
      'trade_date':'20261106',
      'records':[
        {'issuer_id':c,'close':p,'raw_sha256':'a'*64}
        for c,p in [('000001',1010),('000002',1010),('000003',1010),('000004',1000)]
      ]
    }
    naver.write_text(json.dumps(observations),encoding='utf-8')
    rep=audit.execute(f,files={'KOSPI':kospi,'KOSDAQ':kosdaq},candidates_path=naver)
    got={x['issuer_id']:x for x in rep['records']}
    assert got['000001']['classification']=='KRX_SUPPORTS_ARCHIVED_CLOSE'
    assert got['000002']['classification']=='KRX_SUPPORTS_REVISED_CLOSE'
    assert got['000003']['classification']=='HOLD_THREE_WAY_DISAGREEMENT'
    assert got['000004']['classification']=='HOLD_THREE_WAY_DISAGREEMENT'
    assert got['000005']['classification']=='HOLD_NO_KRX_ROW'
    assert rep['hold_count']==67 and rep['promotion_authorized'] is False
    assert all(x['decision']=='HOLD' for x in rep['records'])
    assert rep['official_source']['KOSPI']['sha256']==hashlib.sha256(kospi.read_bytes()).hexdigest()


def test_official_matches_without_naver_remains_hold_and_inputs_unchanged(tmp_path):
    f=registry(tmp_path)
    kospi=tmp_path/'stk.json';kosdaq=tmp_path/'ksq.json'
    kospi.write_bytes(data(krx('000001','1000')));kosdaq.write_bytes(data(krx('000002','1050')))
    before=[f.read_bytes(),kospi.read_bytes(),kosdaq.read_bytes()]
    result=audit.execute(f,files={'KOSPI':kospi,'KOSDAQ':kosdaq})
    assert result['records'][0]['classification']=='HOLD_NAVER_NOT_RECHECKED'
    assert result['records'][1]['classification']=='HOLD_NAVER_NOT_RECHECKED'
    assert [f.read_bytes(),kospi.read_bytes(),kosdaq.read_bytes()]==before


def test_fake_candidate_duplicate_and_wrong_trade_date_rejected(tmp_path):
    f=tmp_path/'cand.json'
    f.write_text(json.dumps({'trade_date':'20261105','records':[]}),encoding='utf-8')
    with pytest.raises(ValueError,match='date'):
        audit.read_candidates(f)
    f.write_text(json.dumps({'trade_date':'20261106','records':[
      {'issuer_id':'000001','close':1000,'raw_sha256':'b'*64},
      {'issuer_id':'000001','close':1001,'raw_sha256':'c'*64}]}),encoding='utf-8')
    with pytest.raises(ValueError,match='Duplicate'):
        audit.read_candidates(f)
