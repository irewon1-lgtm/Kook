#!/usr/bin/env python3
"""Finalize legacy-scale M04 patch without brittle multi-line string matching.

Run only after patch_legacy_scale_m04_to_v2.py. It performs two narrow edits:
1) require an actual adjacent raw/adjusted scale jump at the changepoint;
2) confirm M03 corporate-action cross-checks only for stable regimes or dated notices.
"""
from __future__ import annotations

from pathlib import Path

P = Path(__file__).resolve().parents[1] / "scripts/collect_real_quant_v2.py"


def _line_indent(text: str, token_pos: int) -> tuple[int, str]:
    start = text.rfind("\n", 0, token_pos) + 1
    return start, text[start:token_pos]


def _patch_exact_changepoint(s: str) -> str:
    fn_start = s.index("def _detect_scale_transition_events(")
    fn_end = s.index("\ndef _legacy_adjusted_return(", fn_start)
    chunk = s[fn_start:fn_end]
    if "immediate_reset = shared[i][1] / shared[i - 1][1]" in chunk:
        return s

    token = "for i in range(1, len(shared)):"
    pos = s.index(token, fn_start, fn_end)
    line_start, indent = _line_indent(s, pos)
    line_end = s.index("\n", pos) + 1
    body_indent = indent + "    "
    insertion = (
        f"{body_indent}# Require the scale itself to jump at this exact boundary.\n"
        f"{body_indent}# Median windows validate regimes; they must not invent\n"
        f"{body_indent}# an event one day before or after a real transition.\n"
        f"{body_indent}immediate_reset = shared[i][1] / shared[i - 1][1]\n"
        f"{body_indent}if _relative_diff(immediate_reset, 1.0) < _SCALE_RESET_REL_TOL:\n"
        f"{body_indent}    continue\n"
    )
    return s[:line_end] + insertion + s[line_end:]


def _patch_confirmation_filter(s: str) -> str:
    fn_start = s.index("def naver_metric_worker(")
    fn_end = s.index("\ndef _apply_loss_safe_per_percentiles(", fn_start)
    marker = "scale_candidates = _detect_scale_transition_events("
    start_token = s.index(marker, fn_start, fn_end)
    line_start, indent = _line_indent(s, start_token)
    end_token = "action_tokens = _format_action_event_tokens(action_events)"
    end = s.index(end_token, start_token, fn_end) + len(end_token)

    if "annotated_candidates = _annotate_action_types" in s[start_token:end]:
        return s

    replacement = (
        f"{indent}scale_candidates = _detect_scale_transition_events(\n"
        f"{indent}    raw_by_date, adjusted_by_date, scan_start, scan_end\n"
        f"{indent})\n"
        f"{indent}annotated_candidates = _annotate_action_types(code, [dict(e) for e in scale_candidates])\n"
        f"{indent}# A one-day scale spike is not enough. Confirm only stable\n"
        f"{indent}# multi-day regimes or an independently dated CA notice.\n"
        f"{indent}action_events = [\n"
        f"{indent}    e for e in annotated_candidates\n"
        f"{indent}    if bool(e.get('scale_stable_regimes'))\n"
        f"{indent}    or e.get('notice_distance_days') is not None\n"
        f"{indent}]\n"
        f"{indent}action_tokens = _format_action_event_tokens(action_events)"
    )
    return s[:line_start] + replacement + s[end:]


def main() -> None:
    s = P.read_text(encoding="utf-8")
    s = _patch_exact_changepoint(s)
    s = _patch_confirmation_filter(s)
    P.write_text(s, encoding="utf-8")
    print("PATCH_LEGACY_SCALE_FINALIZE_PASS")


if __name__ == "__main__":
    main()
