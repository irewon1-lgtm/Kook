#!/usr/bin/env python3
"""Permanent M04 regression tests for adjusted six-month returns.

Covers both corporate-action reference-price resets and legitimate exceptional
trading-session moves beyond the ordinary +/-30% daily price band.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import collect_real_quant as base
import collect_real_quant_v2 as v2


def _integration() -> dict:
    return {"totalInfos": [
        {"code": "eps", "value": "1,000", "valueDesc": "최근실적"},
        {"code": "per", "value": "10.0"},
        {"code": "lastClosePrice", "value": "10,000"},
    ]}


def _run_worker(code: str, name: str, payload: list[dict[str, str]]) -> dict:
    original = v2.get_json
    try:
        def fake(url: str):
            if url.endswith("/integration"):
                return _integration()
            if "/price?" in url:
                return payload
            raise AssertionError(url)
        v2.get_json = fake
        issuer = base.Issuer(code, name, "테스트", "2020-01-01", "KOSPI")
        _, row = v2.naver_metric_worker(issuer, date(2026, 9, 14), date(2026, 3, 14))
        return row
    finally:
        v2.get_json = original


def test_miwon_chemical_134380_split_does_not_create_minus_90pct_return() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "10,500", "compareToPreviousClosePrice": "500", "fluctuationsRatio": "5.00"},
        # 10:1 split: raw close unit changes 100,000 -> 10,000, but the KRX
        # adjusted reference price makes the economic one-day return 0%.
        {"localTradedAt": "2026-04-17", "closePrice": "10,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "100,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker("134380", "미원화학", payload)
    assert row["m04_raw"] == 5.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "KRX_ADJ_DAILY_CA1" in row["m04_basis"], row
    raw_broken_return = (10500 / 100000 - 1) * 100
    assert raw_broken_return < -80 and row["m04_raw"] > -80, row


def test_valof_331520_reverse_split_does_not_create_plus_300pct_return() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "4,900", "compareToPreviousClosePrice": "-100", "fluctuationsRatio": "-2.00"},
        # 1:5 reverse split: raw close unit changes 1,000 -> 5,000 while the
        # exchange-adjusted one-day economic return is 0%.
        {"localTradedAt": "2026-05-04", "closePrice": "5,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "1,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker("331520", "밸로프", payload)
    assert row["m04_raw"] == -2.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "KRX_ADJ_DAILY_CA1" in row["m04_basis"], row
    raw_broken_return = (4900 / 1000 - 1) * 100
    assert raw_broken_return > 300 and row["m04_raw"] < 300, row


def test_wonpung_008290_reverse_split_uses_reference_return_then_keeps_real_move() -> None:
    payload = [
        # Later special-session move below -30% is a real market return.
        {"localTradedAt": "2026-09-14", "closePrice": "381", "compareToPreviousClosePrice": "-315", "fluctuationsRatio": "-45.26"},
        # 2:1 reverse split: raw close 418 -> 696 is NOT a +66.5% return.
        # KRX reference price is 836, so the economic return is about -16.75%.
        {"localTradedAt": "2026-05-12", "closePrice": "696", "compareToPreviousClosePrice": "-140", "fluctuationsRatio": "-16.75"},
        {"localTradedAt": "2026-05-11", "closePrice": "418", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "418", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker("008290", "원풍물산", payload)
    expected = round((696 / 836) * (381 / 696) * 100 - 100, 6)
    assert row["m04_raw"] == expected, (row, expected)
    assert row["m04_reason"] is None, row
    assert row["naver_m04_adjustment_days"] == 1, row


def test_kodaco_046070_reduction_reset_and_minus_99pct_real_move_coexist() -> None:
    payload = [
        # Real post-restructuring collapse. Both raw and exchange-reference
        # factors agree, so it must stay in the investment return.
        {"localTradedAt": "2026-09-10", "closePrice": "410", "compareToPreviousClosePrice": "-81,890", "fluctuationsRatio": "-99.50"},
        {"localTradedAt": "2026-09-09", "closePrice": "82,300", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        # 8:1 capital-reduction/relisting reference-price reset: raw 10,280 ->
        # 82,300, while the economic return on the reset day is 0%.
        {"localTradedAt": "2026-09-04", "closePrice": "82,300", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-09-03", "closePrice": "10,280", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "10,280", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker("046070", "코다코", payload)
    expected = round((410 / 82300 - 1) * 100, 6)
    assert row["m04_raw"] == expected, (row, expected)
    assert row["m04_reason"] is None, row
    assert row["naver_m04_adjustment_days"] == 1, row


def test_capro_006380_minus_94pct_real_move_is_retained() -> None:
    payload = [
        {"localTradedAt": "2026-09-08", "closePrice": "185", "compareToPreviousClosePrice": "-3,475", "fluctuationsRatio": "-94.95"},
        {"localTradedAt": "2026-09-07", "closePrice": "3,660", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "3,660", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker("006380", "카프로", payload)
    expected = round((185 / 3660 - 1) * 100, 6)
    assert row["m04_raw"] == expected, (row, expected)
    assert row["m04_reason"] is None, row
    assert row["naver_m04_adjustment_days"] == 0, row


def test_cosnine_082660_liquidation_drop_is_real_return_not_corporate_action() -> None:
    payload = [
        {"localTradedAt": "2026-09-10", "closePrice": "13", "compareToPreviousClosePrice": "-467", "fluctuationsRatio": "-97.29"},
        {"localTradedAt": "2026-03-13", "closePrice": "480", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker("082660", "코스나인", payload)
    expected = round((13 / 480 - 1) * 100, 6)
    assert row["m04_raw"] == expected, (row, expected)
    assert row["m04_reason"] is None, row
    assert row["naver_m04_adjustment_days"] == 0, row


def test_split_like_gap_without_adjustment_metadata_fails_closed() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "10,500", "compareToPreviousClosePrice": "500", "fluctuationsRatio": "5.00"},
        {"localTradedAt": "2026-04-17", "closePrice": "10,000"},
        {"localTradedAt": "2026-03-13", "closePrice": "100,000"},
    ]
    row = _run_worker("134380", "미원화학", payload)
    assert row["m04_raw"] is None, row
    assert row["m04_reason"] == "NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING", row


def test_normal_move_without_metadata_can_use_safe_raw_fallback() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "11,000"},
        {"localTradedAt": "2026-03-13", "closePrice": "10,000"},
    ]
    row = _run_worker("005930", "삼성전자", payload)
    assert row["m04_raw"] == 10.0, row
    assert row["naver_m04_adjustment_days"] == 0, row
    assert row["naver_m04_factor_fallback_days"] == 1, row
    assert "KRX_ADJ_DAILY_CA0_FB1" in row["m04_basis"], row


def main() -> None:
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    failures = []
    for fn in sorted(tests, key=lambda f: f.__name__):
        try:
            fn()
            print("PASS", fn.__name__)
        except Exception as exc:
            failures.append((fn.__name__, repr(exc)))
            print("FAIL", fn.__name__, repr(exc))
    result = {"status": "PASS" if not failures else "FAIL", "tests": len(tests), "failures": failures}
    print("M04_CORPORATE_ACTION_CLEANPASS", json.dumps(result, ensure_ascii=False))
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
