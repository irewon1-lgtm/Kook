"""Real price contract: strict Android-compatible parsing and truthful checkpoints."""
import hashlib
import json
from datetime import date
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import collect_pc_prices as pc

CUTOFF = date(2026, 11, 5)


def row(day='2026-11-05', **changes):
    value = dict(localTradedAt=day, openPrice='1,000', highPrice='1,100',
                 lowPrice='900', closePrice='1,050', accumulatedTradingVolume='1,234')
    value.update(changes)
    return value


def test_required_calendar_boundary_includes_full_week_and_month():
    assert pc.required_from(CUTOFF) == date(2025, 10, 27)
    assert pc.required_from(date(2024, 2, 29)) == date(2023, 1, 30)


def test_strict_actual_ohlcv_and_completed_cutoff():
    bars = pc.parse_bars(json.dumps([row(), row('2026-11-06'), row('bad'),
        row('2026-11-04', openPrice=None), row('2026-11-03', lowPrice='1100'),
        row('2026-11-02', highPrice='NaN'), row('2026-11-01', closePrice=0),
        {'localTradedAt':'2026-10-30', 'closePrice':1000}]), CUTOFF)
    assert bars == [dict(date='2026-11-05', open=1000, high=1100, low=900,
                         close=1050, volume=1234)]


@pytest.mark.parametrize('volume, expected', [(None,None), ('',None), ('1.5',None),
    ('-1',None), ('NaN',None), ('9223372036854775808',None), ('1.0',1), (0,0)])
def test_volume_is_nullable_exact_nonnegative_long(volume, expected):
    assert pc.parse_bars([row(accumulatedTradingVolume=volume)], CUTOFF)[0]['volume'] == expected


def test_conflicting_duplicates_fail_within_and_across_pages():
    with pytest.raises(ValueError, match='Conflicting'):
        pc.parse_bars([row(), row(closePrice=1001)], CUTOFF)
    calls = []
    def request(page, size):
        calls.append((page,size))
        return [row()] * 60 if page == 1 else [row(closePrice=1001)]
    with pytest.raises(ValueError, match='Conflicting'):
        pc.fetch_bars(CUTOFF, request)
    assert calls == [(1,60),(2,60)]


def test_pagination_stops_at_boundary_and_never_requests_200():
    calls = []
    def request(page,size):
        calls.append((page,size))
        return [row()] * 60 if page == 1 else [row('2025-10-27')] * 60
    bars = pc.fetch_bars(CUTOFF, request)
    assert calls == [(1,60),(2,60)]
    assert [x['date'] for x in bars] == ['2025-10-27','2026-11-05']


def test_pagination_is_bounded_and_empty_source_fails():
    calls=[]
    pc.fetch_bars(CUTOFF, lambda page,size: calls.append(page) or [row()] * 60)
    assert calls == list(range(1,9))
    with pytest.raises(ValueError, match='No completed'):
        pc.fetch_bars(CUTOFF, lambda page,size: [row('2026-11-06')])


def test_incremental_cache_needs_boundary_and_real_overlap():
    cached = pc.parse_bars([row('2025-10-27'),row('2026-11-04')], CUTOFF)
    calls=[]
    result = pc.fetch_bars(CUTOFF, lambda page,size: calls.append(page) or [row(),row('2026-11-04')], cached)
    assert calls == [1]
    assert result[0]['date'] == '2025-10-27'
    calls=[]
    result = pc.fetch_bars(CUTOFF, lambda page,size: calls.append(page) or [row()], cached)
    assert result == pc.parse_bars([row()], CUTOFF)  # no overlap: do not silently bridge gap


def response(rows, status=200):
    return status, json.dumps(rows).encode(), {'content-type':'application/json'}


def test_checkpoint_raw_hashes_and_failure_preserve_last_success(tmp_path):
    good = pc.collect_code('005930', CUTOFF, tmp_path, lambda url: response([row()]), now='2026-11-06T00:00:00Z')
    assert good['source'] == 'CACHE' and good['adjustment'] == 'UNVERIFIED'
    assert good['lastTradingDate'] == '2026-11-05'
    assert good['requestedCutoffDate'] == '2026-11-05'
    assert good['pages'][0]['status'] == 200
    raw = next((tmp_path/'raw').rglob('*.json'))
    assert hashlib.sha256(raw.read_bytes()).hexdigest() == good['pages'][0]['sha256']
    failed = pc.collect_code('005930', CUTOFF, tmp_path, lambda url: response([],503), now='2026-11-06T01:00:00Z')
    assert failed['source'] == 'CACHE' and '503' in failed['error']
    assert failed['bars'] == good['bars'] and failed['pages'] == good['pages']
    assert failed['collectedAt'] == good['collectedAt']
    assert failed['lastAttemptAt'] == '2026-11-06T01:00:00Z'
    assert failed['lastTradingDate'] == good['lastTradingDate']


def test_failed_or_corrupt_cache_never_fabricates_data(tmp_path):
    pc.collect_code('005930', CUTOFF, tmp_path, lambda url: response([row()]))
    target=tmp_path/'prices'/'005930.json'
    data=json.loads(target.read_text()); data['bars'][0]['close']=1060
    target.write_text(json.dumps(data))
    missing=pc.collect_code('005930',CUTOFF,tmp_path,lambda url: response([],403))
    assert missing['source'] == 'MISSING' and missing['bars'] == []
    assert missing['collectedAt'] is None and missing['lastTradingDate'] is None


def test_universe_and_index_include_missing_and_payload_integrity(tmp_path):
    universe=tmp_path/'quant.json'
    universe.write_text(json.dumps({'stocks':[{'issuerId':'005930'},{'issuerId':'0001A0'}]}))
    assert pc.load_codes(universe) == ['0001A0','005930']
    good=pc.collect_code('005930',CUTOFF,tmp_path,lambda url: response([row()]))
    bad=pc.collect_code('0001A0',CUTOFF,tmp_path,lambda url: response([],400))
    index=pc.write_index(tmp_path,['0001A0','005930'],CUTOFF)
    assert index['coverage'] == {'universe':2,'withBars':1,'missing':1,'errors':1,'throughCutoff':1,'historyBoundaryReached':0}
    assert index['prices']['005930']['sha256'] == hashlib.sha256((tmp_path/'prices'/'005930.json').read_bytes()).hexdigest()
    assert index['prices']['0001A0']['error'] == bad['error']


def test_actual_quant_snapshot_records_are_keyed_by_issue_code(tmp_path):
    universe=tmp_path/'quant.json'
    universe.write_text(json.dumps({'records':{'005930':{'name':'Samsung'},'0001A0':{'name':'IPO'}}}))
    assert pc.load_codes(universe) == ['0001A0','005930']


def test_seed_is_deterministic_and_excludes_private_raw(tmp_path):
    import tarfile
    root=tmp_path/'cache'
    pc.collect_code('005930',CUTOFF,root,lambda url: response([row()]))
    pc.write_index(root,['005930'],CUTOFF)
    first=pc.seed_archive(root,tmp_path/'first.tar.gz')
    second=pc.seed_archive(root,tmp_path/'second.tar.gz')
    assert first == second
    with tarfile.open(tmp_path/'first.tar.gz') as archive:
        assert archive.getnames() == ['index.json','prices/005930.json']
        assert json.load(archive.extractfile('prices/005930.json'))['bars'][0]['close'] == 1050


def test_successful_incremental_refresh_keeps_original_raw_provenance(tmp_path):
    old=pc.collect_code('005930',CUTOFF,tmp_path,
        lambda url: response([row('2026-11-04'),row('2025-10-27')]),now='2026-11-05T00:00:00Z')
    latest=pc.collect_code('005930',CUTOFF,tmp_path,
        lambda url: response([row(),row('2026-11-04')]),now='2026-11-06T00:00:00Z')
    assert latest['error'] is None and latest['collectedAt'] != old['collectedAt']
    assert [bar['date'] for bar in latest['bars']] == ['2025-10-27','2026-11-04','2026-11-05']
    assert old['pages'][0] in latest['pages'] and len(latest['pages']) == 2
    for page in latest['pages']:
        raw=tmp_path/'raw'/'005930'/f"{page['sha256']}.json"
        assert hashlib.sha256(raw.read_bytes()).hexdigest() == page['sha256']


def test_missing_result_keeps_actual_empty_source_page_provenance(tmp_path):
    missing=pc.collect_code('005930',CUTOFF,tmp_path,lambda url: response([]))
    assert missing['source']=='MISSING'
    assert len(missing['pages'])==1
    assert missing['pages'][0]['status']==200
    assert missing['pages'][0]['bytes']==2
    assert missing['pages'][0]['sha256']==hashlib.sha256(b'[]').hexdigest()


def test_failed_seed_refresh_preserves_previous_complete_artifact(tmp_path):
    root=tmp_path/'cache'
    pc.collect_code('005930',CUTOFF,root,lambda url: response([row()]))
    pc.write_index(root,['005930'],CUTOFF)
    target=tmp_path/'seed.tar.gz'
    pc.seed_archive(root,target)
    original=target.read_bytes()
    (root/'index.json').unlink()
    with pytest.raises(FileNotFoundError):
        pc.seed_archive(root,target)
    assert target.read_bytes()==original


def test_nonoverlapping_short_source_does_not_claim_unused_old_raw_pages(tmp_path):
    previous=pc.collect_code('005930',CUTOFF,tmp_path,
        lambda url: response([row('2026-11-04'),row('2025-10-27')]))
    def request(url):
        return response([row()]*60 if url.endswith('page=1') else [])
    current=pc.collect_code('005930',CUTOFF,tmp_path,request)
    assert current['error'] is None
    assert [bar['date'] for bar in current['bars']]==['2026-11-05']
    assert previous['pages'][0] not in current['pages']
