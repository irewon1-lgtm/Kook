#!/usr/bin/env python3
"""One-shot patcher: use Naver legacy adjusted closes for M04 and CA scale regimes."""
from __future__ import annotations

import textwrap
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "scripts/collect_real_quant_v2.py"


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[:start] + textwrap.dedent(replacement).lstrip() + text[end:]


def main() -> None:
    s = P.read_text(encoding="utf-8")
    s = s.replace("import math\nimport re\nimport time\n", "import ast\nimport math\nimport re\nimport statistics\nimport time\n", 1)
    s = s.replace("from datetime import date\n", "from datetime import date, timedelta\n", 1)
    s = s.replace("from typing import Any\n", "from typing import Any\nfrom urllib.parse import urlencode\n", 1)

    anchor = '_POLLING_BASE = "https://polling.finance.naver.com/api/realtime"\n'
    if "_LEGACY_PRICE_BASE" not in s:
        s = s.replace(
            anchor,
            anchor
            + '_LEGACY_PRICE_BASE = "https://api.finance.naver.com/siseJson.naver"\n'
            + '_SCALE_RESET_REL_TOL = 0.015\n'
            + '_SCALE_STRONG_RATIO = 1.08\n'
            + '_SCALE_STABILITY_REL_TOL = 0.006\n'
            + '_SCALE_WINDOW = 3\n',
            1,
        )

    insert_at = s.index("\ndef _valid_daily_factor(")
    helpers = textwrap.dedent(r'''

    def get_text(url: str, retries: int = 3) -> str:
        sess = base.naver_session()
        last: Exception | None = None
        for attempt in range(retries):
            try:
                r = sess.get(url, timeout=15, headers={"Referer": "https://finance.naver.com/"})
                if r.status_code == 200:
                    return r.text
                if r.status_code in (400, 404, 409):
                    raise LookupError(f"HTTP_{r.status_code}")
                if r.status_code == 429 or r.status_code >= 500:
                    time.sleep(min(0.35 * (2**attempt), 3.0))
                    continue
                r.raise_for_status()
            except LookupError:
                raise
            except Exception as exc:
                last = exc
                if attempt + 1 < retries:
                    time.sleep(0.25 * (2**attempt))
        raise RuntimeError(type(last).__name__ if last else "NAVER_TEXT_RETRY_EXHAUSTED")


    def _fetch_legacy_adjusted_closes(
        code: str,
        start: date,
        end: date,
    ) -> tuple[dict[date, float], str | None]:
        params = urlencode({
            "symbol": code,
            "requestType": 1,
            "startTime": start.strftime("%Y%m%d"),
            "endTime": end.strftime("%Y%m%d"),
            "timeframe": "day",
        })
        try:
            raw = get_text(f"{_LEGACY_PRICE_BASE}?{params}")
            cleaned = "\n".join(line.strip() for line in raw.splitlines() if line.strip())
            rows = ast.literal_eval(cleaned)
            if not isinstance(rows, list) or len(rows) < 2:
                return {}, "LEGACY_PRICE_EMPTY"
            header = [str(x).strip() for x in rows[0]]
            try:
                date_idx = header.index("날짜")
                close_idx = header.index("종가")
            except ValueError:
                return {}, "LEGACY_PRICE_HEADER_MISSING"
            out: dict[date, float] = {}
            for row in rows[1:]:
                if not isinstance(row, (list, tuple)) or len(row) <= max(date_idx, close_idx):
                    continue
                digits = re.sub(r"\D", "", str(row[date_idx]))[:8]
                if len(digits) != 8:
                    continue
                try:
                    d = date(int(digits[:4]), int(digits[4:6]), int(digits[6:8]))
                except ValueError:
                    continue
                close = base.parse_number(row[close_idx])
                if close is not None and math.isfinite(close) and close > 0:
                    out[d] = float(close)
            return out, None if out else "LEGACY_PRICE_EMPTY"
        except LookupError as exc:
            return {}, str(exc)
        except Exception as exc:
            return {}, type(exc).__name__


    def _stable_scale(values: list[float], center: float) -> bool:
        return bool(values) and all(_relative_diff(v, center) <= _SCALE_STABILITY_REL_TOL for v in values)


    def _detect_scale_transition_events(
        raw_closes: dict[date, float],
        adjusted_closes: dict[date, float],
        start_date: date,
        end_date: date,
    ) -> list[dict[str, Any]]:
        shared = []
        for d in sorted(set(raw_closes) & set(adjusted_closes)):
            if d < start_date or d > end_date:
                continue
            raw = raw_closes[d]
            adj = adjusted_closes[d]
            if raw <= 0 or adj <= 0:
                continue
            scale = raw / adj
            if math.isfinite(scale) and scale > 0:
                shared.append((d, scale))
        if len(shared) < 2:
            return []

        events: list[dict[str, Any]] = []
        last_event_index = -10
        for i in range(1, len(shared)):
            left = [x[1] for x in shared[max(0, i - _SCALE_WINDOW):i]]
            right = [x[1] for x in shared[i:min(len(shared), i + _SCALE_WINDOW)]]
            if not left or not right:
                continue
            before = float(statistics.median(left))
            after = float(statistics.median(right))
            if before <= 0 or after <= 0:
                continue
            reset_ratio = after / before
            if _relative_diff(reset_ratio, 1.0) < _SCALE_RESET_REL_TOL:
                continue

            before_stable = _stable_scale(left, before)
            after_stable = _stable_scale(right, after)
            strong = max(reset_ratio, 1.0 / reset_ratio) >= _SCALE_STRONG_RATIO
            stable_regimes = len(left) >= 2 and len(right) >= 2 and before_stable and after_stable
            if not strong and not stable_regimes:
                continue
            # Suppress repeated detections from the same transition window.
            if i - last_event_index <= 1:
                continue

            family = "SPLIT_OR_BONUS_ISSUE" if reset_ratio < 1.0 else "REVERSE_SPLIT_OR_CAPITAL_REDUCTION"
            events.append({
                "date": shared[i][0],
                "previous_date": shared[i - 1][0],
                "reset_ratio": reset_ratio,
                "scale_before": before,
                "scale_after": after,
                "scale_before_count": len(left),
                "scale_after_count": len(right),
                "scale_before_stable": before_stable,
                "scale_after_stable": after_stable,
                "scale_strong": strong,
                "scale_stable_regimes": stable_regimes,
                "type": family,
            })
            last_event_index = i
        return events


    def _legacy_adjusted_return(
        adjusted_closes: dict[date, float],
        six_month_target: date,
        cutoff: date,
    ) -> tuple[float | None, str | None, date | None, date | None]:
        end_dates = [d for d in adjusted_closes if d <= cutoff]
        start_dates = [d for d in adjusted_closes if d <= six_month_target]
        if not end_dates:
            return None, "NAVER_LEGACY_NO_PRICE_AT_CUTOFF", None, None
        if not start_dates:
            return None, "PRICE_HISTORY_SHORTER_THAN_6M", None, max(end_dates)
        end_date = max(end_dates)
        start_date = max(start_dates)
        if (end_date - start_date).days < 150:
            return None, "PRICE_HISTORY_SHORTER_THAN_6M", start_date, end_date
        start_close = adjusted_closes[start_date]
        end_close = adjusted_closes[end_date]
        if start_close <= 0 or end_close <= 0:
            return None, "NAVER_LEGACY_ADJUSTED_RETURN_INVALID", start_date, end_date
        value = (end_close / start_close - 1.0) * 100.0
        if not math.isfinite(value) or abs(value) > 100000:
            return None, "NAVER_RETURN_OUTLIER_GUARD", start_date, end_date
        return value, None, start_date, end_date
    ''')
    s = s[:insert_at] + helpers + s[insert_at:]

    # Tighten notice matching: no undated notice may label a scale transition.
    s = replace_between(
        s,
        "def _annotate_action_types(",
        "\ndef _format_action_event_tokens(",
        r'''
        def _annotate_action_types(code: str, events: list[dict[str, Any]]) -> list[dict[str, Any]]:
            if not events:
                return events
            try:
                payload = get_json(f"{_NOTICE_BASE}?itemCode={code}&startIdx=0&pageSize=100")
            except Exception:
                return events

            nodes: list[tuple[str, list[date]]] = []
            for node in _iter_dict_nodes(payload):
                scalars = [
                    f"{k}={v}"
                    for k, v in node.items()
                    if isinstance(v, (str, int, float, bool)) or v is None
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
                for label, dates in nodes:
                    if not _direction_compatible(label, reset_ratio):
                        continue
                    # Corporate-action decisions can precede the effective date by
                    # weeks. Keep a bounded window but never accept undated/stale
                    # notices as automatic evidence.
                    distance = min(abs((dt - event_date).days) for dt in dates)
                    if distance > 120:
                        continue
                    if best is None or distance < best[0]:
                        best = (distance, label)
                if best is not None:
                    event["type"] = best[1]
                    event["notice_distance_days"] = best[0]
            return events
        ''',
    )

    # Replace the complete worker so M04 and CA detection no longer consume the
    # unreliable mobile daily comparison factor at all.
    s = replace_between(
        s,
        "def naver_metric_worker(",
        "\ndef _apply_loss_safe_per_percentiles(",
        r'''
        def naver_metric_worker(issuer: base.Issuer, cutoff: date, six_month_target: date) -> tuple[str, dict[str, Any]]:
            code = issuer.code
            eps = None
            eps_desc = ""
            eps_source = ""
            eps_fallback_error = None
            provider_per = None
            integration_last_close = None
            integration_error = None

            try:
                integ = get_json(f"{base.NAVER_BASE}/{code}/integration")
                rows = integ.get("totalInfos") or [] if isinstance(integ, dict) else []
                info_rows = {
                    str(x.get("code")): x
                    for x in rows
                    if isinstance(x, dict) and x.get("code")
                }
                eps_row = info_rows.get("eps") or {}
                per_row = info_rows.get("per") or {}
                close_row = info_rows.get("lastClosePrice") or {}
                eps = base.parse_number(eps_row.get("value"))
                eps_desc = str(eps_row.get("valueDesc") or "").strip()
                if eps is not None:
                    eps_source = "NAVER_INTEGRATION_EPS"
                provider_per = base.parse_number(per_row.get("value"))
                integration_last_close = base.parse_number(close_row.get("value"))
            except LookupError as exc:
                integration_error = str(exc)
            except Exception as exc:
                integration_error = type(exc).__name__

            if eps is None:
                fallback_eps, fallback_period, eps_fallback_error = _fallback_actual_annual_eps(code)
                if fallback_eps is not None:
                    eps = fallback_eps
                    eps_desc = f"ANNUAL_ACTUAL_{fallback_period}" if fallback_period else "ANNUAL_ACTUAL"
                    eps_source = "NAVER_FINANCE_ANNUAL_EPS"

            # Mobile raw history is now used ONLY to detect raw/adjusted scale
            # regimes. Its daily fluctuationsRatio is not used by M04.
            raw_by_date, _unused_daily_factor, price_error = _parse_price_bars(code, cutoff, six_month_target)
            lookup_start = six_month_target - timedelta(days=14)
            adjusted_by_date, legacy_error = _fetch_legacy_adjusted_closes(code, lookup_start, cutoff)

            m04_raw, m04_reason, start_date, end_date = _legacy_adjusted_return(
                adjusted_by_date, six_month_target, cutoff
            )
            adjusted_end_close = adjusted_by_date.get(end_date) if end_date else None
            raw_end_dates = [d for d in raw_by_date if d <= cutoff]
            raw_end_date = max(raw_end_dates) if raw_end_dates else None
            raw_end_close = raw_by_date.get(raw_end_date) if raw_end_date else None

            scan_start = start_date or (min(adjusted_by_date) if adjusted_by_date else six_month_target)
            scan_end = end_date or cutoff
            scale_candidates = _detect_scale_transition_events(
                raw_by_date, adjusted_by_date, scan_start, scan_end
            )
            action_events = _annotate_action_types(code, [dict(e) for e in scale_candidates])
            action_tokens = _format_action_event_tokens(action_events)

            # Use the adjusted/regular historical close for calculation and CA
            # cross-check where available. Fall back to mobile/integration only
            # when legacy history is missing, without changing M04 fail-closed.
            per_close = adjusted_end_close or raw_end_close
            per_close_date = end_date or raw_end_date
            if per_close is None and integration_last_close is not None and integration_last_close > 0:
                per_close = integration_last_close
                per_close_date = cutoff

            m03_raw = None
            m03_reason = None
            m03_basis = ""
            if _valid_positive_per(provider_per):
                m03_raw = provider_per
                m03_basis = f"{cutoff.isoformat()}_NAVER_REPORTED_TRAILING_PER"
            else:
                if eps is not None and eps != 0 and per_close is not None and per_close > 0:
                    calculated = per_close / eps
                    if not _valid_calculated_per(calculated):
                        m03_reason = "NAVER_PER_OUTLIER_GUARD"
                    else:
                        m03_raw = calculated
                        eps_basis = eps_desc if eps_desc else "ACTUAL_EPS"
                        source_tag = "ANNUAL" if eps_source == "NAVER_FINANCE_ANNUAL_EPS" else "INTEGRATION"
                        price_tag = "LEGACY_ADJ_CLOSE" if adjusted_end_close is not None else "CLOSE"
                        m03_basis = (
                            f"{per_close_date.isoformat() if per_close_date else cutoff.isoformat()}_"
                            f"{price_tag}/NAVER_{source_tag}_EPS_{eps_basis}"
                        )
                elif eps is not None and eps == 0:
                    m03_reason = "ZERO_EPS"
                elif integration_error and eps_fallback_error:
                    m03_reason = "NAVER_INTEGRATION_ERROR"
                elif per_close is None:
                    m03_reason = "NAVER_NO_PRICE_AT_CUTOFF"
                else:
                    m03_reason = "NAVER_EPS_MISSING"

            m04_basis = ""
            if m04_raw is not None and start_date is not None and end_date is not None:
                m04_basis = (
                    f"{start_date.isoformat()}->{end_date.isoformat()}_"
                    f"NAVER_LEGACY_ADJUSTED_CLOSE_CA{len(action_events)}{action_tokens}"
                )
            elif action_events:
                m04_basis = f"{scan_start.isoformat()}->{scan_end.isoformat()}_CA_SCALE_SCAN{action_tokens}"
            if m04_raw is None and legacy_error and m04_reason in {None, "NAVER_LEGACY_NO_PRICE_AT_CUTOFF"}:
                m04_reason = "NAVER_LEGACY_PRICE_ERROR"

            row = {
                "m03_raw": round(m03_raw, 6) if m03_raw is not None else None,
                "m03_reason": m03_reason,
                "m03_basis": m03_basis,
                "m04_raw": round(m04_raw, 6) if m04_raw is not None else None,
                "m04_reason": m04_reason,
                "m04_basis": m04_basis,
                "naver_provider_per": provider_per,
                "naver_eps": eps,
                "naver_eps_source": eps_source,
                "naver_end_close": per_close,
                "naver_mobile_end_close": raw_end_close,
                "naver_legacy_adjusted_end_close": adjusted_end_close,
                "naver_m04_adjustment_days": len(action_events),
                "naver_m04_factor_fallback_days": 0,
                "naver_corporate_action_events": action_events,
                "naver_scale_transition_candidates": scale_candidates,
                "integration_error": integration_error,
                "eps_fallback_error": eps_fallback_error,
                "price_error": price_error,
                "legacy_price_error": legacy_error,
            }
            if action_events:
                row = _m03_corporate_action_crosscheck(code, row, per_close)
            return code, row
        ''',
    )

    # Replace stale policy prose so future maintenance does not reintroduce the
    # mobile daily-factor design.
    start_doc = s.index("M04 / corporate-action policy:")
    end_doc = s.index('"""', start_doc)
    new_doc = '''M04 / corporate-action policy:\n1) M04 is computed from Naver legacy adjusted historical closes.\n2) Mobile raw close history is used only to compare raw/adjusted price scale.\n3) A stable scale-regime change identifies split/merge/bonus/reduction effects;\n   mobile fluctuationsRatio is never treated as the corporate-action baseline.\n4) Corporate-action stocks must pass the M03 polling-EPS/PER cross-check.\n5) Legacy adjusted-price failure leaves M04 unavailable rather than guessing.\n'''
    s = s[:start_doc] + new_doc + s[end_doc:]

    P.write_text(s, encoding="utf-8")
    print("PATCH_LEGACY_SCALE_M04_TO_V2_PASS")


if __name__ == "__main__":
    main()
