#!/usr/bin/env python3
"""Follow-up patch: exact scale boundary + stable/notice-backed M03 CA confirmation."""
from pathlib import Path

from patch_scale_changepoint_boundary import main as patch_boundary_main

P = Path(__file__).resolve().parents[1] / "scripts/collect_real_quant_v2.py"


def main() -> None:
    # First remove duplicate early/late detections around one scale regime shift.
    patch_boundary_main()

    s = P.read_text(encoding="utf-8")
    marker = "scale_candidates = _detect_scale_transition_events("
    start_token = s.index(marker)
    line_start = s.rfind("\n", 0, start_token) + 1
    indent = s[line_start:start_token]
    end_token = "action_tokens = _format_action_event_tokens(action_events)"
    end = s.index(end_token, start_token) + len(end_token)

    replacement = (
        f'{indent}scale_candidates = _detect_scale_transition_events(\n'
        f'{indent}    raw_by_date, adjusted_by_date, scan_start, scan_end\n'
        f'{indent})\n'
        f'{indent}annotated_candidates = _annotate_action_types(code, [dict(e) for e in scale_candidates])\n'
        f'{indent}# A one-day scale spike is not enough. Confirm only when both sides\n'
        f'{indent}# form stable multi-day regimes, or a dated corporate-action notice\n'
        f'{indent}# independently supports the candidate. M04 itself remains safe\n'
        f'{indent}# because it comes from the adjusted legacy series regardless.\n'
        f'{indent}action_events = [\n'
        f'{indent}    e for e in annotated_candidates\n'
        f'{indent}    if bool(e.get("scale_stable_regimes"))\n'
        f'{indent}    or e.get("notice_distance_days") is not None\n'
        f'{indent}]\n'
        f'{indent}action_tokens = _format_action_event_tokens(action_events)'
    )
    P.write_text(s[:line_start] + replacement + s[end:], encoding="utf-8")
    print("PATCH_LEGACY_SCALE_CONFIRMATION_PASS")


if __name__ == "__main__":
    main()
