#!/usr/bin/env python3
"""Second-stage patch: harden nonconsecutive stock-bar gaps in patched v2."""
from __future__ import annotations

import textwrap
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "scripts/collect_real_quant_v2.py"


def main() -> None:
    s = P.read_text(encoding="utf-8")
    anchor = "_MARKET_CALENDAR_LOCK = threading.Lock()\n"
    if "_GAP_AMBIGUOUS_RESET_REL_TOL" not in s:
        s = s.replace(
            anchor,
            anchor
            + "_GAP_AMBIGUOUS_RESET_REL_TOL = 0.08\n"
            + "_GAP_EXTREME_LOW = 0.50\n"
            + "_GAP_EXTREME_HIGH = 2.00\n",
            1,
        )

    start = s.index("def _detect_reference_reset_events(")
    end = s.index("\ndef _notice_dates(", start)
    replacement = textwrap.dedent(r'''
    def _unresolved_reference_event(
        previous: date,
        current: date,
        raw_factor: float,
        adjusted_factor: float | None,
        kind: str,
    ) -> dict[str, Any]:
        return {
            "date": current,
            "previous_date": previous,
            "reset_ratio": None,
            "raw_factor": raw_factor,
            "adjusted_factor": adjusted_factor,
            "type": kind,
        }


    def _detect_reference_reset_events(
        by_date: dict[date, float],
        by_factor: dict[date, float | None],
        start_date: date,
        end_date: date,
    ) -> list[dict[str, Any]]:
        """Detect CA resets without mixing one-session and multi-session returns.

        Consecutive KRX sessions keep the sensitive 1.5% detector. Across a
        missing stock bar, small mismatches are ordinary multi-session return,
        extreme 2x/0.5x unit changes remain actionable, and intermediate large
        mismatches become unresolved/HOLD instead of being guessed.
        """
        dates = sorted(d for d in by_date if start_date <= d <= end_date)
        events: list[dict[str, Any]] = []
        if len(dates) < 2:
            return events

        previous = dates[0]
        for current in dates[1:]:
            prev_close = by_date[previous]
            close = by_date[current]
            raw_factor = close / prev_close if prev_close > 0 else None
            adjusted_factor = by_factor.get(current)
            if raw_factor is None or not math.isfinite(raw_factor) or raw_factor <= 0:
                previous = current
                continue

            consecutive = _is_consecutive_market_session(previous, current)

            if adjusted_factor is None:
                # Do not lose the existing fail-closed protection for an
                # unexplained split/liquidation-sized move, even after a halt.
                if not _valid_daily_factor(raw_factor):
                    events.append(_unresolved_reference_event(
                        previous,
                        current,
                        raw_factor,
                        None,
                        "UNRESOLVED_REFERENCE_RESET",
                    ))
                previous = current
                continue

            if not _valid_daily_factor(adjusted_factor):
                previous = current
                continue

            reset_ratio = raw_factor / adjusted_factor
            if not math.isfinite(reset_ratio) or reset_ratio <= 0:
                previous = current
                continue
            deviation = abs(reset_ratio - 1.0)

            if not consecutive:
                if reset_ratio <= _GAP_EXTREME_LOW or reset_ratio >= _GAP_EXTREME_HIGH:
                    family = (
                        "SPLIT_OR_BONUS_ISSUE"
                        if reset_ratio < 1.0
                        else "REVERSE_SPLIT_OR_CAPITAL_REDUCTION"
                    )
                    events.append({
                        "date": current,
                        "previous_date": previous,
                        "reset_ratio": reset_ratio,
                        "raw_factor": raw_factor,
                        "adjusted_factor": adjusted_factor,
                        "type": family,
                        "gap_verified_extreme": True,
                    })
                elif deviation >= _GAP_AMBIGUOUS_RESET_REL_TOL:
                    events.append(_unresolved_reference_event(
                        previous,
                        current,
                        raw_factor,
                        adjusted_factor,
                        "UNRESOLVED_GAP_REFERENCE_RESET",
                    ))
                previous = current
                continue

            if deviation >= _REFERENCE_RESET_REL_TOL:
                family = (
                    "SPLIT_OR_BONUS_ISSUE"
                    if reset_ratio < 1.0
                    else "REVERSE_SPLIT_OR_CAPITAL_REDUCTION"
                )
                events.append({
                    "date": current,
                    "previous_date": previous,
                    "reset_ratio": reset_ratio,
                    "raw_factor": raw_factor,
                    "adjusted_factor": adjusted_factor,
                    "type": family,
                })
            previous = current
        return events
    ''').lstrip()
    P.write_text(s[:start] + replacement + s[end:], encoding="utf-8")
    print("PATCH_GAP_GAPCASES_V2_FOLLOWUP_PASS")


if __name__ == "__main__":
    main()
