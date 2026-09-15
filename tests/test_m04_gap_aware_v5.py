#!/usr/bin/env python3
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import collect_real_quant_v2 as v2
import collect_real_quant_v5 as v5


def test_001230_missing_20260914_reproduces_old_false_positive_but_v5_rejects_it() -> None:
    # Dongkuk Holdings public KRX history: 9/11=2,075, 9/14=2,135,
    # 9/15=2,110 (-1.17% vs 9/14). If 9/14 is absent from a stock-history
    # payload, the old detector compares 9/15 against 9/11 and fabricates a
    # reset of about 1.0289 -- the exact production failure shape.
    d11=date(2026,9,11); d14=date(2026,9,14); d15=date(2026,9,15)
    v5._set_market_calendar([d11,d14,d15])
    by_date={d11:2075.0,d15:2110.0}
    by_factor={d11:1.0,d15:2110.0/2135.0}
    old_reset=(2110.0/2075.0)/(2110.0/2135.0)
    assert abs(old_reset-1.0289156626506024)<1e-12
    assert v5._detect_reference_reset_events(by_date,by_factor,d11,d15)==[]


def test_normal_consecutive_day_is_not_action() -> None:
    d14=date(2026,9,14); d15=date(2026,9,15)
    v5._set_market_calendar([d14,d15])
    by_date={d14:10000.0,d15:10100.0}
    by_factor={d14:1.0,d15:1.01}
    assert v5._detect_reference_reset_events(by_date,by_factor,d14,d15)==[]


def test_split_on_consecutive_session_is_detected_and_adjusted() -> None:
    d0=date(2026,4,16); d1=date(2026,4,17); d2=date(2026,9,15)
    v5._set_market_calendar([d0,d1,d2])
    by_date={d0:100000.0,d1:10000.0,d2:10500.0}
    by_factor={d0:1.0,d1:1.0,d2:1.05}
    events=v5._detect_reference_reset_events(by_date,by_factor,d0,d2)
    assert len(events)==1 and abs(events[0]['reset_ratio']-0.1)<1e-12,events
    value,reason,count,fb=v5._corporate_action_adjusted_return(by_date,by_factor,d0,d2)
    assert reason is None and count==1 and fb==0
    assert abs(value-5.0)<1e-9,value


def test_reverse_split_on_consecutive_session_is_detected_and_adjusted() -> None:
    d0=date(2026,5,1); d1=date(2026,5,4); d2=date(2026,9,15)
    v5._set_market_calendar([d0,d1,d2])
    by_date={d0:1000.0,d1:5000.0,d2:4900.0}
    by_factor={d0:1.0,d1:1.0,d2:0.98}
    events=v5._detect_reference_reset_events(by_date,by_factor,d0,d2)
    assert len(events)==1 and abs(events[0]['reset_ratio']-5.0)<1e-12,events
    value,reason,count,fb=v5._corporate_action_adjusted_return(by_date,by_factor,d0,d2)
    assert reason is None and count==1 and fb==0
    assert abs(value+2.0)<1e-9,value


def test_missing_intermediate_bar_does_not_delete_return_from_m04() -> None:
    d0=date(2026,3,13); dm=date(2026,9,14); d1=date(2026,9,15)
    v5._set_market_calendar([d0,dm,d1])
    by_date={d0:10000.0,d1:12000.0}
    by_factor={d0:1.0,d1:1.01}
    value,reason,count,fb=v5._corporate_action_adjusted_return(by_date,by_factor,d0,d1)
    assert reason is None and count==0 and fb==0
    assert abs(value-20.0)<1e-9,value


def test_unresolved_split_sized_consecutive_gap_fails_closed() -> None:
    d0=date(2026,4,16); d1=date(2026,4,17)
    v5._set_market_calendar([d0,d1])
    by_date={d0:100000.0,d1:10000.0}
    by_factor={d0:1.0,d1:None}
    value,reason,count,fb=v5._corporate_action_adjusted_return(by_date,by_factor,d0,d1)
    assert value is None and reason=='NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING'
    assert count==1 and fb==0


def test_direction_codes_are_normalized() -> None:
    falling={
        'compareToPreviousClosePrice':'100',
        'compareToPreviousPrice':{'code':'5','text':'하락','name':'FALLING'},
        'fluctuationsRatio':'0.99',
    }
    rising={
        'compareToPreviousClosePrice':'100',
        'compareToPreviousPrice':{'code':'2','text':'상승','name':'RISING'},
        'fluctuationsRatio':'0.99',
    }
    assert abs(v5._daily_reference_factor(falling,10000.0)-(10000.0/10100.0))<1e-12
    assert abs(v5._daily_reference_factor(rising,10100.0)-1.01)<1e-12


def test_stale_notice_cannot_relabel_event() -> None:
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
        got=v5._annotate_action_types('111111',[event.copy()])
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
    print('M04_GAP_AWARE_V5_CLEANPASS',{'status':'PASS','tests':len(tests)})


if __name__=='__main__':
    main()
