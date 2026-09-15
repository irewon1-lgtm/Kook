#!/usr/bin/env python3
"""Permanent corporate-action + M03 cross-check regressions.

Covers the exact KR4 failure class plus smaller reference resets that remain
inside the ordinary +/-30% daily band. All tests are deterministic/no-network.
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


def _integration(per_value: str = "10.0", eps: str = "1,000", close: str = "10,000") -> dict:
    return {"totalInfos": [
        {"code": "eps", "value": eps, "valueDesc": "최근실적"},
        {"code": "per", "value": per_value},
        {"code": "lastClosePrice", "value": close},
    ]}


def _polling(eps: str = "1,000", price: str = "10,000") -> dict:
    return {"result": {"areas": [{"datas": [{
        "eps": eps,
        "nv": price,
        "sv": price,
        "countOfListedStock": "10,000,000",
    }]}]}}


def _run_worker(
    code: str,
    name: str,
    payload: list[dict[str, str]],
    *,
    per_value: str = "10.0",
    integration_eps: str = "1,000",
    polling_eps: str = "1,000",
    notice_title: str | None = None,
    notice_date: str | None = None,
) -> dict:
    original = v2.get_json
    try:
        end_close = payload[0]["closePrice"] if payload else "10,000"

        def fake(url: str):
            if url.endswith("/integration"):
                return _integration(per_value, integration_eps, end_close)
            if "/price?" in url:
                return payload
            if "polling.finance.naver.com" in url:
                return _polling(polling_eps, end_close)
            if "stock.naver.com/api/domestic/detail/notice" in url:
                if notice_title:
                    return {"content": [{"title": notice_title, "date": notice_date or "2026-04-17"}]}
                return {"content": []}
            if url.endswith("/finance/annual"):
                return {"financeInfo": {"trTitleList": [], "rowList": []}}
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
        # 10:1 split: raw close unit changes 100,000 -> 10,000, while the KRX
        # reference-price return on the effective day is 0%.
        {"localTradedAt": "2026-04-17", "closePrice": "10,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "100,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker(
        "134380", "미원화학", payload,
        per_value="10.5", notice_title="주식분할결정", notice_date="2026-04-17",
    )
    assert row["m04_raw"] == 5.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "STOCK_SPLIT" in row["m04_basis"], row
    assert row["m03_raw"] == 10.5 and "CA_XCHECK_PASS" in row["m03_basis"], row
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
    row = _run_worker(
        "331520", "밸로프", payload,
        per_value="4.9", notice_title="주식병합결정", notice_date="2026-05-04",
    )
    assert row["m04_raw"] == -2.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "REVERSE_SPLIT" in row["m04_basis"], row
    assert row["m03_raw"] == 4.9 and "CA_XCHECK_PASS" in row["m03_basis"], row
    raw_broken_return = (4900 / 1000 - 1) * 100
    assert raw_broken_return > 300 and row["m04_raw"] < 300, row


def test_small_bonus_issue_inside_daily_limit_is_still_detected() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "9,450", "compareToPreviousClosePrice": "450", "fluctuationsRatio": "5.00"},
        # 10% reference reset stays well inside +/-30%, so an outlier-only detector
        # would miss it. raw=.90, adjusted=1.00 => reset_ratio=.90.
        {"localTradedAt": "2026-06-01", "closePrice": "9,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "10,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker(
        "111111", "무상증자테스트", payload,
        per_value="9.45", notice_title="무상증자 결정", notice_date="2026-06-01",
    )
    assert row["m04_raw"] == 5.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "BONUS_ISSUE" in row["m04_basis"], row
    assert "EVT20260601" in row["m04_basis"], row


def test_small_capital_reduction_inside_daily_limit_is_still_detected() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "10,780", "compareToPreviousClosePrice": "-220", "fluctuationsRatio": "-2.00"},
        # 10% upward reference reset: raw=1.10, adjusted=1.00.
        {"localTradedAt": "2026-07-01", "closePrice": "11,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "10,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker(
        "222222", "감자테스트", payload,
        per_value="10.78", notice_title="감자결정", notice_date="2026-07-01",
    )
    assert row["m04_raw"] == -2.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "CAPITAL_REDUCTION" in row["m04_basis"], row


def test_split_like_gap_without_adjustment_metadata_fails_closed() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "10,500", "compareToPreviousClosePrice": "500", "fluctuationsRatio": "5.00"},
        {"localTradedAt": "2026-04-17", "closePrice": "10,000"},
        {"localTradedAt": "2026-03-13", "closePrice": "100,000"},
    ]
    row = _run_worker("134380", "미원화학", payload, per_value="10.5")
    assert row["m04_raw"] is None, row
    assert row["m04_reason"] == "NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING", row
    assert "UNRESOLVED_REFERENCE_RESET" in row["m04_basis"], row


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
    assert "CA_XCHECK" not in row["m03_basis"], row


def test_corporate_action_fallback_per_with_stale_eps_is_blocked() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "10,500", "compareToPreviousClosePrice": "500", "fluctuationsRatio": "5.00"},
        {"localTradedAt": "2026-04-17", "closePrice": "10,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "100,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker(
        "333333", "EPS불일치", payload,
        per_value="-", integration_eps="5,000", polling_eps="1,000",
        notice_title="주식분할결정", notice_date="2026-04-17",
    )
    assert row["m03_raw"] is None, row
    assert row["m03_reason"] == "CORPORATE_ACTION_PER_XCHECK_EPS_MISMATCH", row


def test_corporate_action_fallback_per_with_adjusted_eps_passes() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "10,500", "compareToPreviousClosePrice": "500", "fluctuationsRatio": "5.00"},
        {"localTradedAt": "2026-04-17", "closePrice": "10,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "100,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker(
        "444444", "EPS일치", payload,
        per_value="-", integration_eps="1,000", polling_eps="1,000",
        notice_title="주식분할결정", notice_date="2026-04-17",
    )
    assert row["m03_raw"] == 10.5, row
    assert "CA_XCHECK_PASS_POLLING_EPS" in row["m03_basis"], row


def test_corporate_action_reported_per_mismatch_is_blocked() -> None:
    payload = [
        {"localTradedAt": "2026-09-14", "closePrice": "10,500", "compareToPreviousClosePrice": "500", "fluctuationsRatio": "5.00"},
        {"localTradedAt": "2026-04-17", "closePrice": "10,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
        {"localTradedAt": "2026-03-13", "closePrice": "100,000", "compareToPreviousClosePrice": "0", "fluctuationsRatio": "0.00"},
    ]
    row = _run_worker(
        "555555", "PER불일치", payload,
        per_value="5.0", integration_eps="1,000", polling_eps="1,000",
        notice_title="주식분할결정", notice_date="2026-04-17",
    )
    assert row["m03_raw"] is None, row
    assert row["m03_reason"] == "CORPORATE_ACTION_PER_XCHECK_PER_MISMATCH", row


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
