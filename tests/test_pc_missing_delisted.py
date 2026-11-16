"""Fail-closed tests for historical delisting/missing-price triage."""
import json
from pathlib import Path
import sys
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import audit_pc_missing_delisted as m


def base(tmp_path):
    rows=[{'issuer_id':f'{i:06d}','name':f'Issuer{i}','market':'KOSDAQ','reported_delisting_date':'2026-11-02'} for i in range(1,14)]
    a={'schema':'KR4_MISSING_PRICE_DELISTING_REVIEW_V1','snapshot_cutoff':'2026-11-13',
       'classification':'DELISTING_REPORTED_SECONDARY_SOURCE_PRIMARY_PER_ISSUER_CONFIRMATION_PENDING',
       'source_url':'https://example.org/test','rows':rows}
    target=tmp_path/'registry.json';target.write_text(json.dumps(a),encoding='utf-8')
    return target


def collect(tmp_path):
    rec=[{'event':'start','universe':13,'cutoff':'2026-11-13'}]
    rec += [{'event':'collected','code':f'{i:06d}','source':'MISSING','bars':0,'error':'ValueError: No completed daily prices'} for i in range(1,14)]
    rec.append({'event':'complete','coverage':{'missing':13,'withBars':0}})
    f=tmp_path/'price.jsonl';f.write_text('\n'.join(json.dumps(x) for x in rec),encoding='utf-8')
    return f


def test_all_13_remain_hold_without_krx_proof(tmp_path):
    r=m.evaluate(m.registry(base(tmp_path)))
    assert r['decision']=='HOLD' and len(r['rows'])==13
    assert not r['rankings_changed'] and not r['current_universe_changed']
    assert all(x['ranking_action']=='REVIEW_UNIVERSE_ELIGIBILITY_MANUALLY' for x in r['rows'])


def test_reconcile_completed_realistic_missing_results(tmp_path):
    x=m.parse_collection(collect(tmp_path),expected_universe=13)
    r=m.evaluate(m.registry(base(tmp_path)),x)
    assert r['observed_missing_count']==13
    assert all(x['decision']=='HOLD' for x in r['rows'])


def test_unexpected_missing_reason_not_silently_classified(tmp_path):
    q=m.parse_collection(collect(tmp_path),expected_universe=13)
    q['000001']['error']='HTTP 503'
    with pytest.raises(ValueError,match='Unexpected missing reason'):m.evaluate(m.registry(base(tmp_path)),q)


def test_surprise_missing_stock_never_misclassified(tmp_path):
    q=m.parse_collection(collect(tmp_path),expected_universe=13)
    q['000999']={'source':'MISSING','bars':0}
    with pytest.raises(ValueError,match='Unexpected missing issuer'):m.evaluate(m.registry(base(tmp_path)),q)


def test_registry_does_not_accept_future_delisting(tmp_path):
    f=base(tmp_path);d=json.loads(f.read_text())
    d['rows'][0]['reported_delisting_date']='2026-11-20'
    f.write_text(json.dumps(d))
    with pytest.raises(ValueError,match='Delisting after'):m.registry(f)


def test_duplicate_issuer_rejected(tmp_path):
    f=base(tmp_path);d=json.loads(f.read_text())
    d['rows'][1]['issuer_id']=d['rows'][0]['issuer_id']
    f.write_text(json.dumps(d))
    with pytest.raises(ValueError,match='Duplicate'):m.registry(f)


def test_incomplete_or_malformed_collection_rejected(tmp_path):
    f=collect(tmp_path);q=f.read_text().splitlines();f.write_text('\n'.join(q[:-1]))
    with pytest.raises(ValueError,match='not complete'):m.parse_collection(f,13)
