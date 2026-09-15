#!/usr/bin/env python3
"""Permanent M04 regression tests for corporate-action adjusted 6M returns.

The two named fixtures intentionally model the exact failure class that broke
KR4: Miwon Chemical's 10:1 stock split and Valof's 1:5 reverse split. The tests
are deterministic and do not use the network.
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
