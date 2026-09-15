#!/usr/bin/env python3
"""Replace M04 regression suite with deterministic legacy-adjusted/scale-regime tests."""
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "tests/test_m04_corporate_actions.py"

TESTS = r'''#!/usr/bin/env python3
"""Permanent M04 adjusted-price + M03 corporate-action cross-check regressions."""
from __future__ import annotations

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


def _payload(closes: dict[str, float]) -> list[dict[str, str]]:
    return [
        {"localTradedAt": ds, "closePrice": f"{value:g}"}
        for ds, value in sorted(closes.items(), reverse=True)
    ]


def _dates(closes: dict[str, float]) -> dict[date, float]:
    return {date.fromisoformat(ds): float(value) for ds, value in closes.items()}


def _run_worker(
    code: str,
    name: str,
    raw_closes: dict[str, float],
    adjusted_closes: dict[str, float],
    *,
    per_value: str = "10.0",
    integration_eps: str = "1,000",
    polling_eps: str = "1,000",
    notice_title: str | None = None,
    notice_date: str | None = None,
    legacy_error: str | None = None,
) -> dict:
    original_json = v2.get_json
    original_legacy = v2._fetch_legacy_adjusted_closes
    try:
        payload = _payload(raw_closes)
        end_close = adjusted_closes[max(adjusted_closes)] if adjusted_closes else 10000.0

        def fake_json(url: str):
            if url.endswith("/integration"):
                return _integration(per_value, integration_eps, str(end_close))
            if "/price?" in url:
                return payload if "page=1" in url else []
            if "polling.finance.naver.com" in url:
                return _polling(polling_eps, str(end_close))
            if "stock.naver.com/api/domestic/detail/notice" in url:
                if notice_title:
                    return {"content": [{"title": notice_title, "date": notice_date or "2026-04-17"}]}
                return {"content": []}
            if url.endswith("/finance/annual"):
                return {"financeInfo": {"trTitleList": [], "rowList": []}}
            raise AssertionError(url)

        v2.get_json = fake_json
        v2._fetch_legacy_adjusted_closes = lambda _code, _start, _end: (
            _dates(adjusted_closes), legacy_error
        )
        issuer = base.Issuer(code, name, "테스트", "2020-01-01", "KOSPI")
        _, row = v2.naver_metric_worker(issuer, date(2026, 9, 14), date(2026, 3, 14))
        return row
    finally:
        v2.get_json = original_json
        v2._fetch_legacy_adjusted_closes = original_legacy


def test_miwon_split_uses_adjusted_prices_and_triggers_m03_xcheck() -> None:
    raw = {
        "2026-03-13": 100000, "2026-04-15": 100000, "2026-04-16": 100000,
        "2026-04-17": 10000, "2026-04-20": 10000, "2026-04-21": 10000,
        "2026-09-14": 10500,
    }
    adj = {
        "2026-03-13": 10000, "2026-04-15": 10000, "2026-04-16": 10000,
        "2026-04-17": 10000, "2026-04-20": 10000, "2026-04-21": 10000,
        "2026-09-14": 10500,
    }
    row = _run_worker("134380", "미원화학", raw, adj, per_value="10.5",
                      notice_title="주식분할결정", notice_date="2026-04-17")
    assert row["m04_raw"] == 5.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "STOCK_SPLIT" in row["m04_basis"], row
    assert row["m03_raw"] == 10.5 and "CA_XCHECK_PASS" in row["m03_basis"], row


def test_valof_reverse_split_uses_adjusted_prices_and_triggers_m03_xcheck() -> None:
    raw = {
        "2026-03-13": 1000, "2026-04-29": 1000, "2026-04-30": 1000,
        "2026-05-01": 1000, "2026-05-04": 5000, "2026-05-05": 5000,
        "2026-05-06": 5000, "2026-09-14": 4900,
    }
    adj = {
        "2026-03-13": 5000, "2026-04-29": 5000, "2026-04-30": 5000,
        "2026-05-01": 5000, "2026-05-04": 5000, "2026-05-05": 5000,
        "2026-05-06": 5000, "2026-09-14": 4900,
    }
    row = _run_worker("331520", "밸로프", raw, adj, per_value="4.9",
                      notice_title="주식병합결정", notice_date="2026-05-04")
    assert row["m04_raw"] == -2.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "REVERSE_SPLIT" in row["m04_basis"], row
    assert row["m03_raw"] == 4.9 and "CA_XCHECK_PASS" in row["m03_basis"], row


def test_small_bonus_issue_is_detected_from_stable_scale_regimes() -> None:
    raw = {
        "2026-03-13": 9900, "2026-05-27": 9900, "2026-05-28": 9900,
        "2026-05-29": 9900, "2026-06-01": 9000, "2026-06-02": 9000,
        "2026-06-03": 9000, "2026-09-14": 9450,
    }
    adj = {k: (9450 if k == "2026-09-14" else 9000) for k in raw}
    row = _run_worker("111111", "무상증자테스트", raw, adj, per_value="9.45",
                      notice_title="무상증자 결정", notice_date="2026-06-01")
    assert row["m04_raw"] == 5.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "BONUS_ISSUE" in row["m04_basis"], row


def test_small_capital_reduction_is_detected_from_stable_scale_regimes() -> None:
    raw = {
        "2026-03-13": 9900, "2026-06-26": 9900, "2026-06-29": 9900,
        "2026-06-30": 9900, "2026-07-01": 11000, "2026-07-02": 11000,
        "2026-07-03": 11000, "2026-09-14": 10780,
    }
    adj = {k: (10780 if k == "2026-09-14" else 11000) for k in raw}
    row = _run_worker("222222", "감자테스트", raw, adj, per_value="10.78",
                      notice_title="감자결정", notice_date="2026-07-01")
    assert row["m04_raw"] == -2.0, row
    assert row["naver_m04_adjustment_days"] == 1, row
    assert "CAPITAL_REDUCTION" in row["m04_basis"], row


def test_one_day_scale_spike_is_not_confirmed_as_corporate_action() -> None:
    raw = {
        "2026-03-13": 10000, "2026-06-01": 10000, "2026-06-02": 12000,
        "2026-06-03": 10000, "2026-06-04": 10000, "2026-09-14": 11000,
    }
    adj = {k: (11000 if k == "2026-09-14" else 10000) for k in raw}
    row = _run_worker("005930", "삼성전자", raw, adj, per_value="11.0")
    assert row["m04_raw"] == 10.0, row
    assert row["naver_m04_adjustment_days"] == 0, row
    assert "CA_XCHECK" not in row["m03_basis"], row


def test_normal_stock_uses_adjusted_endpoint_return_without_ca_xcheck() -> None:
    raw = {"2026-03-13": 10000, "2026-06-01": 10500, "2026-09-14": 11000}
    row = _run_worker("005930", "삼성전자", raw, raw, per_value="11.0")
    assert row["m04_raw"] == 10.0, row
    assert row["naver_m04_adjustment_days"] == 0, row
    assert "NAVER_LEGACY_ADJUSTED_CLOSE_CA0" in row["m04_basis"], row
    assert "CA_XCHECK" not in row["m03_basis"], row


def test_legacy_adjusted_price_unavailable_fails_m04_closed() -> None:
    raw = {"2026-03-13": 10000, "2026-09-14": 11000}
    row = _run_worker("123456", "레거시누락", raw, {}, legacy_error="HTTP_404")
    assert row["m04_raw"] is None, row
    assert row["m04_reason"] == "NAVER_LEGACY_PRICE_ERROR", row


def _split_case(per_value: str, integration_eps: str, polling_eps: str) -> dict:
    raw = {
        "2026-03-13": 100000, "2026-04-15": 100000, "2026-04-16": 100000,
        "2026-04-17": 10000, "2026-04-20": 10000, "2026-04-21": 10000,
        "2026-09-14": 10500,
    }
    adj = {
        "2026-03-13": 10000, "2026-04-15": 10000, "2026-04-16": 10000,
        "2026-04-17": 10000, "2026-04-20": 10000, "2026-04-21": 10000,
        "2026-09-14": 10500,
    }
    return _run_worker("333333", "분할PER테스트", raw, adj, per_value=per_value,
                       integration_eps=integration_eps, polling_eps=polling_eps,
                       notice_title="주식분할결정", notice_date="2026-04-17")


def test_corporate_action_stale_eps_is_blocked() -> None:
    row = _split_case("-", "5,000", "1,000")
    assert row["m03_raw"] is None, row
    assert row["m03_reason"] == "CORPORATE_ACTION_PER_XCHECK_EPS_MISMATCH", row


def test_corporate_action_adjusted_eps_passes() -> None:
    row = _split_case("-", "1,000", "1,000")
    assert row["m03_raw"] == 10.5, row
    assert "CA_XCHECK_PASS_POLLING_EPS" in row["m03_basis"], row


def test_corporate_action_reported_per_mismatch_is_blocked() -> None:
    row = _split_case("5.0", "1,000", "1,000")
    assert row["m03_raw"] is None, row
    assert row["m03_reason"] == "CORPORATE_ACTION_PER_XCHECK_PER_MISMATCH", row


def test_scale_event_reset_ratios_match_known_split_directions() -> None:
    raw = _dates({
        "2026-04-15":100000,"2026-04-16":100000,"2026-04-17":10000,
        "2026-04-20":10000,"2026-04-21":10000,
    })
    adj = _dates({k:10000 for k in ["2026-04-15","2026-04-16","2026-04-17","2026-04-20","2026-04-21"]})
    events=v2._detect_scale_transition_events(raw,adj,date(2026,4,15),date(2026,4,21))
    assert len(events)==1 and abs(events[0]["reset_ratio"]-0.1)<1e-12,events


def main() -> None:
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    failures = []
    for fn in sorted(tests, key=lambda f: f.__name__):
        try:
            fn(); print("PASS", fn.__name__)
        except Exception as exc:
            failures.append((fn.__name__, repr(exc))); print("FAIL", fn.__name__, repr(exc))
    assert not failures, failures
    print("M04_LEGACY_SCALE_CLEANPASS", {"status": "PASS", "tests": len(tests)})


if __name__ == "__main__":
    main()
'''


def main() -> None:
    P.write_text(TESTS, encoding="utf-8")
    print("REPLACE_M04_TESTS_LEGACY_SCALE_PASS")


if __name__ == "__main__":
    main()
