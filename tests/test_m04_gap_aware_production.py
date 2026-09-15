#!/usr/bin/env python3
"""Deterministic regressions for the permanently patched collect_real_quant_v2."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import collect_real_quant_v2 as v2


def test_exact_001230_missing_session_false_positive_is_rejected() -> None:
    d11=date(2026,9,11); d14=date(2026,9,14); d15=date(2026,9,15)
    v2._set_market_calendar([d11,d14,d15])
    by_date={d11:2075.0,d15:2110.0}
    by_factor={d11:1.0,d15:2110.0/2135.0}
    old_reset=(2110.0/2075.0)/(2110.0/2135.0)
    assert abs(old_reset-1.0289156626506024)<1e-12
    assert v2._detect_reference_reset_events(by_date,by_factor,d11,d15)==[]


def test_nonconsecutive_extreme_split_survives() -> None:
    d0=date(2026,4,10); missing=date(2026,4,16); d1=date(2026,4,17)
    v2._set_market_calendar([d0,missing,d1])
    by_date={d0:100000.0,d1:10000.0}
    by_factor={d0:1.0,d1:1.0}
    events=v2._detect_reference_reset_events(by_date,by_factor,d0,d1)
    assert len(events)==1 and abs(events[0]['reset_ratio']-0.1)<1e-12,events
    value,reason,count,fb=v2._corporate_action_adjusted_return(by_date,by_factor,d0,d1)
    assert reason is None and count==1 and fb==0 and abs(value)<1e-9,(value,reason,count,fb)


def test_nonconsecutive_ambiguous_large_mismatch_fails_closed() -> None:
    d0=date(2026,6,1); missing=date(2026,6,2); d1=date(2026,6,3)
    v2._set_market_calendar([d0,missing,d1])
    by_date={d0:10000.0,d1:11200.0}
    by_factor={d0:1.0,d1:1.01}
    events=v2._detect_reference_reset_events(by_date,by_factor,d0,d1)
    assert len(events)==1 and events[0]['reset_ratio'] is None,events
    value,reason,count,fb=v2._corporate_action_adjusted_return(by_date,by_factor,d0,d1)
    assert value is None and reason=='NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING'
    assert count==1 and fb==0


def test_missing_one_session_small_move_preserves_endpoint_return() -> None:
    # Same failure shape as production: Friday bar, Monday omitted, Tuesday bar.
    # Tuesday's daily factor is +1%, while the two-session endpoint return is +2%.
    # The +~1% gap mismatch must NOT become a corporate action, and M04 must
    # preserve the full +2% endpoint return rather than only Tuesday's +1%.
    d11=date(2026,9,11); d14=date(2026,9,14); d15=date(2026,9,15)
    v2._set_market_calendar([d11,d14,d15])
    by_date={d11:10000.0,d15:10200.0}
    by_factor={d11:1.0,d15:1.01}
    events=v2._detect_reference_reset_events(by_date,by_factor,d11,d15)
    assert events==[],events
    value,reason,count,fb=v2._corporate_action_adjusted_return(by_date,by_factor,d11,d15)
    assert reason is None and count==0 and fb==0 and abs(value-2.0)<1e-9,value


def test_unresolved_split_sized_gap_without_adjustment_fails_closed() -> None:
    d0=date(2026,4,16); d1=date(2026,4,17)
    v2._set_market_calendar([d0,d1])
    by_date={d0:100000.0,d1:10000.0}
    by_factor={d0:1.0,d1:None}
    value,reason,count,fb=v2._corporate_action_adjusted_return(by_date,by_factor,d0,d1)
    assert value is None and reason=='NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING'
    assert count==1 and fb==0


def test_direction_code_5_unsigned_fields_become_falling() -> None:
    bar={
        'compareToPreviousClosePrice':'100',
        'compareToPreviousPrice':{'code':'5','text':'하락','name':'FALLING'},
        'fluctuationsRatio':'0.99',
    }
    factor=v2._daily_reference_factor(bar,10000.0)
    assert factor is not None and abs(factor-(10000.0/10100.0))<1e-12,factor


def test_stale_notice_does_not_relabel_event() -> None:
    event={
        'date':date(2026,9,15),
        'previous_date':date(2026,9,14),
        'reset_ratio':0.5,
        'raw_factor':0.5,
        'adjusted_factor':1.0,
        'type':'SPLIT_OR_BONUS_ISSUE',
    }
    original=v2.get_json
    try:
        v2.get_json=lambda _url:{'content':[{'title':'무상증자 결정','date':'2026-06-01'}]}
        got=v2._annotate_action_types('111111',[event.copy()])
        assert got[0]['type']=='SPLIT_OR_BONUS_ISSUE',got
    finally:
        v2.get_json=original


def main() -> None:
    tests=[v for k,v in globals().items() if k.startswith('test_') and callable(v)]
    failures=[]
    for fn in sorted(tests,key=lambda f:f.__name__):
        try:
            fn(); print('PASS',fn.__name__)
        except Exception as exc:
            failures.append((fn.__name__,repr(exc))); print('FAIL',fn.__name__,repr(exc))
    assert not failures,failures
    print('M04_GAP_AWARE_PRODUCTION_CLEANPASS',{'status':'PASS','tests':len(tests)})


if __name__=='__main__':
    main()
