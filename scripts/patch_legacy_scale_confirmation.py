#!/usr/bin/env python3
"""Follow-up patch: only stable or notice-backed scale transitions trigger M03 CA checks."""
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "scripts/collect_real_quant_v2.py"


def main() -> None:
    s = P.read_text(encoding="utf-8")
    old = '''            scale_candidates = _detect_scale_transition_events(\n                raw_by_date, adjusted_by_date, scan_start, scan_end\n            )\n            action_events = _annotate_action_types(code, [dict(e) for e in scale_candidates])\n            action_tokens = _format_action_event_tokens(action_events)\n'''
    new = '''            scale_candidates = _detect_scale_transition_events(\n                raw_by_date, adjusted_by_date, scan_start, scan_end\n            )\n            annotated_candidates = _annotate_action_types(code, [dict(e) for e in scale_candidates])\n            # A one-day scale spike is not enough. Confirm only when both sides\n            # form stable multi-day regimes, or a dated corporate-action notice\n            # independently supports the candidate. M04 itself remains safe\n            # because it comes from the adjusted legacy series regardless.\n            action_events = [\n                e for e in annotated_candidates\n                if bool(e.get("scale_stable_regimes"))\n                or e.get("notice_distance_days") is not None\n            ]\n            action_tokens = _format_action_event_tokens(action_events)\n'''
    if old not in s:
        raise SystemExit("scale-candidate worker block not found")
    P.write_text(s.replace(old, new, 1), encoding="utf-8")
    print("PATCH_LEGACY_SCALE_CONFIRMATION_PASS")


if __name__ == "__main__":
    main()
