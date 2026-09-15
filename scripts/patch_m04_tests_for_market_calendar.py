#!/usr/bin/env python3
"""Make existing M04 worker regressions deterministic for gap-aware production v2."""
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "tests/test_m04_corporate_actions.py"


def main() -> None:
    s = P.read_text(encoding="utf-8")
    old = '''        v2.get_json = fake\n        issuer = base.Issuer(code, name, "테스트", "2020-01-01", "KOSPI")\n        _, row = v2.naver_metric_worker(issuer, date(2026, 9, 14), date(2026, 3, 14))\n        return row\n    finally:\n        v2.get_json = original\n'''
    new = '''        v2.get_json = fake\n        # Patched production v2 loads a shared KRX session calendar. Synthetic\n        # worker tests inject their own deterministic session set so no live\n        # index request is needed and intended synthetic adjacencies stay exact.\n        if hasattr(v2, "_set_market_calendar"):\n            synthetic_dates = {\n                date.fromisoformat(str(bar["localTradedAt"]))\n                for bar in payload\n                if isinstance(bar, dict) and bar.get("localTradedAt")\n            }\n            v2._set_market_calendar(synthetic_dates)\n        issuer = base.Issuer(code, name, "테스트", "2020-01-01", "KOSPI")\n        _, row = v2.naver_metric_worker(issuer, date(2026, 9, 14), date(2026, 3, 14))\n        return row\n    finally:\n        if hasattr(v2, "_set_market_calendar"):\n            v2._set_market_calendar([])\n        v2.get_json = original\n'''
    if old not in s:
        raise SystemExit("target _run_worker block not found")
    s = s.replace(old, new, 1)

    # Endpoint-close M04 no longer needs a daily raw fallback when metadata is
    # absent on a normal-size move. The economic return comes directly from
    # start/end closes, so the correct fallback count is zero.
    s = s.replace(
        '    assert row["naver_m04_factor_fallback_days"] == 1, row\n'
        '    assert "KRX_ADJ_DAILY_CA0_FB1" in row["m04_basis"], row\n',
        '    assert row["naver_m04_factor_fallback_days"] == 0, row\n'
        '    assert "KRX_ADJ_DAILY_CA0_FB0" in row["m04_basis"], row\n',
        1,
    )

    P.write_text(s, encoding="utf-8")
    print("PATCH_M04_TESTS_FOR_MARKET_CALENDAR_PASS")


if __name__ == "__main__":
    main()
