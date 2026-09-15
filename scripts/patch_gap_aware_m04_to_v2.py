#!/usr/bin/env python3
"""One-shot source patcher for the permanent gap-aware M04 v2 engine."""
from __future__ import annotations

import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "scripts/collect_real_quant_v2.py"


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[:start] + textwrap.dedent(replacement).lstrip() + text[end:]


def main() -> None:
    s = P.read_text(encoding="utf-8")

    if "import bisect\n" not in s:
        s = s.replace(
            "import math\nimport re\nimport time\n",
            "import bisect\nimport math\nimport re\nimport threading\nimport time\n",
            1,
        )

    anchor = '_POLLING_BASE = "https://polling.finance.naver.com/api/realtime"\n'
    if "_INDEX_PRICE_URL" not in s:
        s = s.replace(
            anchor,
            anchor
            + '_INDEX_PRICE_URL = "https://m.stock.naver.com/api/index/KOSPI/price"\n'
            + '_MARKET_DATES: list[date] = []\n'
            + '_MARKET_DATE_SET: set[date] = set()\n'
            + '_MARKET_CALENDAR_ERROR: str | None = None\n'
            + '_MARKET_CALENDAR_LOCK = threading.Lock()\n',
            1,
        )

    s = replace_between(
        s,
        "def _daily_reference_factor(",
        "\ndef _parse_price_bars(",
        r'''
        def _direction_sign(bar: dict[str, Any]) -> int | None:
            raw = bar.get("compareToPreviousPrice")
            code = ""
            tokens: set[str] = set()
            if isinstance(raw, dict):
                code = str(raw.get("code") or "").strip()
                for key in ("name", "text"):
                    value = str(raw.get(key) or "").strip()
                    if value:
                        tokens.add(value.upper())
                        tokens.add(value)
            else:
                token = str(raw or "").strip()
                if token.isdigit():
                    code = token
                elif token:
                    tokens.add(token.upper())
                    tokens.add(token)

            if code in {"4", "5"} or tokens & {"FALLING", "LOWER", "DOWN", "LOWER_LIMIT", "LIMIT_DOWN", "하락", "하한"}:
                return -1
            if code in {"1", "2"} or tokens & {"RISING", "HIGHER", "UP", "UPPER_LIMIT", "LIMIT_UP", "상승", "상한"}:
                return 1
            if code == "3" or tokens & {"UNCHANGED", "SAME", "FLAT", "보합"}:
                return 0
            return None


        def _daily_reference_factor(bar: dict[str, Any], close: float) -> float | None:
            """One-session Naver reference factor with ambiguity-safe direction."""
            sign = _direction_sign(bar)
            ratio = base.parse_number(bar.get("fluctuationsRatio"))
            if ratio is not None and sign is not None:
                ratio = 0.0 if sign == 0 else abs(ratio) * sign
            ratio_factor = None
            if ratio is not None:
                candidate = 1.0 + ratio / 100.0
                if _valid_daily_factor(candidate):
                    ratio_factor = candidate

            change = base.parse_number(bar.get("compareToPreviousClosePrice"))
            if change is not None and sign is not None:
                change = 0.0 if sign == 0 else abs(change) * sign
            elif sign is None:
                # The absolute change field can be magnitude-only. Without
                # explicit direction, trust a signed ratio or return no factor.
                change = None

            change_factor = None
            if change is not None:
                reference_price = close - change
                if reference_price > 0:
                    candidate = close / reference_price
                    if _valid_daily_factor(candidate):
                        change_factor = candidate

            if change_factor is not None and ratio_factor is not None:
                return change_factor if abs(change_factor - ratio_factor) <= 0.005 else ratio_factor
            return ratio_factor if ratio_factor is not None else change_factor


        def _set_market_calendar(days: set[date] | list[date]) -> None:
            global _MARKET_DATES, _MARKET_DATE_SET, _MARKET_CALENDAR_ERROR
            _MARKET_DATES = sorted(set(days))
            _MARKET_DATE_SET = set(_MARKET_DATES)
            _MARKET_CALENDAR_ERROR = None


        def _ensure_market_calendar(cutoff: date, six_month_target: date) -> None:
            global _MARKET_CALENDAR_ERROR
            if _MARKET_DATES and _MARKET_DATES[-1] == cutoff and _MARKET_DATES[0] <= six_month_target:
                return
            if _MARKET_CALENDAR_ERROR:
                raise RuntimeError(_MARKET_CALENDAR_ERROR)
            with _MARKET_CALENDAR_LOCK:
                if _MARKET_DATES and _MARKET_DATES[-1] == cutoff and _MARKET_DATES[0] <= six_month_target:
                    return
                if _MARKET_CALENDAR_ERROR:
                    raise RuntimeError(_MARKET_CALENDAR_ERROR)
                try:
                    days: set[date] = set()
                    for page in range(1, 6):
                        bars = get_json(f"{_INDEX_PRICE_URL}?pageSize=60&page={page}")
                        if not isinstance(bars, list):
                            raise RuntimeError("KRX_CALENDAR_NOT_LIST")
                        if not bars:
                            break
                        for bar in bars:
                            if not isinstance(bar, dict):
                                continue
                            d = base.parse_date(str(bar.get("localTradedAt") or ""))
                            p = base.parse_number(bar.get("closePrice"))
                            if d and p is not None and p > 0 and d <= cutoff:
                                days.add(d)
                        if days and min(days) <= six_month_target:
                            break
                        if len(bars) < 60:
                            break
                    ordered = sorted(days)
                    if not ordered or ordered[-1] != cutoff or ordered[0] > six_month_target:
                        raise RuntimeError(
                            f"KRX_CALENDAR_COVERAGE_INVALID:{ordered[0] if ordered else None}:{ordered[-1] if ordered else None}"
                        )
                    _set_market_calendar(days)
                except Exception as exc:
                    _MARKET_CALENDAR_ERROR = f"KRX_CALENDAR_ERROR:{type(exc).__name__}:{exc}"
                    raise RuntimeError(_MARKET_CALENDAR_ERROR) from exc


        def _is_consecutive_market_session(previous: date, current: date) -> bool:
            if not _MARKET_DATES:
                return True
            if current not in _MARKET_DATE_SET or previous not in _MARKET_DATE_SET:
                return False
            pos = bisect.bisect_left(_MARKET_DATES, current)
            return pos > 0 and _MARKET_DATES[pos - 1] == previous
        ''',
    )

    s = replace_between(
        s,
        "def _detect_reference_reset_events(",
        "\ndef _notice_dates(",
        r'''
        def _detect_reference_reset_events(
            by_date: dict[date, float],
            by_factor: dict[date, float | None],
            start_date: date,
            end_date: date,
        ) -> list[dict[str, Any]]:
            """Detect reference resets only across consecutive KRX sessions."""
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

                # Never compare a one-session change ratio with a raw close ratio
                # spanning multiple KRX sessions. That caused the 546-stock
                # false corporate-action cluster on 2026-09-15.
                if not _is_consecutive_market_session(previous, current):
                    previous = current
                    continue

                if raw_factor is None or not math.isfinite(raw_factor) or raw_factor <= 0:
                    previous = current
                    continue
                if adjusted_factor is None:
                    if not _valid_daily_factor(raw_factor):
                        events.append({
                            "date": current,
                            "previous_date": previous,
                            "reset_ratio": None,
                            "raw_factor": raw_factor,
                            "adjusted_factor": None,
                            "type": "UNRESOLVED_REFERENCE_RESET",
                        })
                    previous = current
                    continue
                if not _valid_daily_factor(adjusted_factor):
                    previous = current
                    continue
                reset_ratio = raw_factor / adjusted_factor
                if math.isfinite(reset_ratio) and reset_ratio > 0 and abs(reset_ratio - 1.0) >= _REFERENCE_RESET_REL_TOL:
                    family = "SPLIT_OR_BONUS_ISSUE" if reset_ratio < 1.0 else "REVERSE_SPLIT_OR_CAPITAL_REDUCTION"
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
        ''',
    )

    s = replace_between(
        s,
        "def _annotate_action_types(",
        "\ndef _format_action_event_tokens(",
        r'''
        def _annotate_action_types(code: str, events: list[dict[str, Any]]) -> list[dict[str, Any]]:
            """Label resets only from dated notices close to the effective day."""
            if not events:
                return events
            try:
                payload = get_json(f"{_NOTICE_BASE}?itemCode={code}&startIdx=0&pageSize=100")
            except Exception:
                return events

            nodes: list[tuple[str, list[date]]] = []
            for node in _iter_dict_nodes(payload):
                scalars = [
                    f"{k}={value}"
                    for k, value in node.items()
                    if isinstance(value, (str, int, float, bool)) or value is None
                ]
                text = " ".join(scalars)
                label = _notice_action_label(text)
                dates = _notice_dates(text)
                if label and dates:
                    nodes.append((label, dates))

            for event in events:
                event_date = event["date"]
                reset_ratio = event.get("reset_ratio")
                best: tuple[int, str] | None = None
                for label, notice_dates in nodes:
                    if not _direction_compatible(label, reset_ratio):
                        continue
                    distance = min(abs((d - event_date).days) for d in notice_dates)
                    if distance > 7:
                        continue
                    if best is None or distance < best[0]:
                        best = (distance, label)
                if best is not None:
                    event["type"] = best[1]
            return events
        ''',
    )

    s = replace_between(
        s,
        "def _corporate_action_adjusted_return(",
        "\ndef _valid_positive_per(",
        r'''
        def _corporate_action_adjusted_return(
            by_date: dict[date, float],
            by_factor: dict[date, float | None],
            start_date: date,
            end_date: date,
        ) -> tuple[float | None, str | None, int, int]:
            """Endpoint close return adjusted only by verified reset ratios."""
            if start_date not in by_date or end_date not in by_date:
                return None, "NAVER_ADJUSTED_RETURN_UNAVAILABLE", 0, 0
            start_close = by_date[start_date]
            end_close = by_date[end_date]
            if start_close <= 0 or end_close <= 0:
                return None, "NAVER_ADJUSTED_RETURN_INVALID", 0, 0

            events = _detect_reference_reset_events(by_date, by_factor, start_date, end_date)
            growth = end_close / start_close
            if not math.isfinite(growth) or growth <= 0:
                return None, "NAVER_ADJUSTED_RETURN_INVALID", len(events), 0
            for event in events:
                reset_ratio = event.get("reset_ratio")
                if reset_ratio is None or not math.isfinite(float(reset_ratio)) or float(reset_ratio) <= 0:
                    return None, "NAVER_CORPORATE_ACTION_ADJUSTMENT_MISSING", len(events), 0
                growth /= float(reset_ratio)
                if not math.isfinite(growth) or growth <= 0 or growth > 1_000_000:
                    return None, "NAVER_ADJUSTED_RETURN_INVALID", len(events), 0
            value = (growth - 1.0) * 100.0
            if not math.isfinite(value) or abs(value) > 100000:
                return None, "NAVER_RETURN_OUTLIER_GUARD", len(events), 0
            return value, None, len(events), 0
        ''',
    )

    worker_anchor = "def naver_metric_worker(issuer: base.Issuer, cutoff: date, six_month_target: date) -> tuple[str, dict[str, Any]]:\n    code = issuer.code\n"
    if "_ensure_market_calendar(cutoff, six_month_target)" not in s:
        s = s.replace(
            worker_anchor,
            worker_anchor + "    _ensure_market_calendar(cutoff, six_month_target)\n",
            1,
        )

    s = s.replace(
        "M04 / corporate-action policy:\n1) Never divide the raw six-month start/end closes directly.\n2) Compound each trading day's KRX/Naver reference-price return instead.",
        "M04 / corporate-action policy:\n1) Use six-month endpoint closes as the economic-return backbone.\n2) Remove only reference-price reset ratios proven across consecutive KRX sessions.",
    )

    P.write_text(s, encoding="utf-8")
    print("PATCH_GAP_AWARE_M04_TO_V2_PASS")


if __name__ == "__main__":
    main()
