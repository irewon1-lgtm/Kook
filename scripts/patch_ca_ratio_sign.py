#!/usr/bin/env python3
from __future__ import annotations

import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    source = ROOT / "scripts/collect_real_quant_v2.py"
    text = source.read_text(encoding="utf-8")
    start = text.index("def _daily_reference_factor(")
    end = text.index("\ndef _parse_price_bars(", start)
    replacement = textwrap.dedent('''
    def _daily_reference_factor(bar: dict[str, Any], close: float) -> float | None:
        """Return the KRX-reference one-day factor without direction ambiguity.

        ``fluctuationsRatio`` is signed and already measured from the exchange
        reference/base price, so it is authoritative. When Naver also supplies
        ``compareToPreviousPrice.name`` we normalize the absolute KRW change to
        that direction and use the more precise change-derived factor only when
        it agrees with the signed ratio. A positive change magnitude without
        direction is never assumed to mean a rise.
        """
        ratio = base.parse_number(bar.get("fluctuationsRatio"))
        ratio_factor = None
        if ratio is not None:
            candidate = 1.0 + ratio / 100.0
            if _valid_daily_factor(candidate):
                ratio_factor = candidate

        change = base.parse_number(bar.get("compareToPreviousClosePrice"))
        direction_raw = bar.get("compareToPreviousPrice")
        if isinstance(direction_raw, dict):
            direction = str(direction_raw.get("name") or "").strip().upper()
        else:
            direction = str(direction_raw or "").strip().upper()

        change_factor = None
        if change is not None and direction:
            if direction in {"FALLING", "LOWER", "DOWN"}:
                change = -abs(change)
            elif direction in {"RISING", "HIGHER", "UP"}:
                change = abs(change)
            elif direction in {"UNCHANGED", "SAME", "FLAT"}:
                change = 0.0
            reference_price = close - change
            if reference_price > 0:
                candidate = close / reference_price
                if _valid_daily_factor(candidate):
                    change_factor = candidate

        if change_factor is not None and ratio_factor is not None:
            # fluctuationsRatio is rounded while KRW change is exact. Use the
            # precise factor only after direction normalization and agreement.
            if abs(change_factor - ratio_factor) <= 0.005:
                return change_factor
            return ratio_factor
        if ratio_factor is not None:
            return ratio_factor
        return change_factor
    ''').lstrip()
    source.write_text(text[:start] + replacement + text[end:], encoding="utf-8")

    tests = ROOT / "tests/test_m04_corporate_actions.py"
    test_text = tests.read_text(encoding="utf-8")
    marker = "\ndef test_small_bonus_issue_inside_daily_limit_is_still_detected() -> None:\n"
    assert marker in test_text
    if "test_falling_bar_direction_or_signed_ratio_does_not_fake_reference_reset" not in test_text:
        regression = r'''

def test_falling_bar_direction_or_signed_ratio_does_not_fake_reference_reset() -> None:
    falling = {
        "localTradedAt": "2026-09-15",
        "closePrice": "10,000",
        "compareToPreviousClosePrice": "100",
        "compareToPreviousPrice": {"name": "FALLING"},
        "fluctuationsRatio": "-0.99",
    }
    factor = v2._daily_reference_factor(falling, 10000.0)
    assert factor is not None
    assert abs(factor - (10000.0 / 10100.0)) < 1e-12, factor
    by_date = {date(2026, 9, 14): 10100.0, date(2026, 9, 15): 10000.0}
    by_factor = {date(2026, 9, 14): 1.0, date(2026, 9, 15): factor}
    assert v2._detect_reference_reset_events(
        by_date, by_factor, date(2026, 9, 14), date(2026, 9, 15)
    ) == []


def test_falling_bar_without_direction_prefers_signed_fluctuation_ratio() -> None:
    falling = {
        "localTradedAt": "2026-09-15",
        "closePrice": "10,000",
        "compareToPreviousClosePrice": "100",
        "fluctuationsRatio": "-0.99",
    }
    factor = v2._daily_reference_factor(falling, 10000.0)
    assert factor is not None and abs(factor - 0.9901) < 1e-12, factor
    raw = 10000.0 / 10100.0
    assert abs(raw / factor - 1.0) < v2._REFERENCE_RESET_REL_TOL
'''
        test_text = test_text.replace(marker, regression + marker, 1)
        tests.write_text(test_text, encoding="utf-8")


if __name__ == "__main__":
    main()
