#!/usr/bin/env python3
"""Follow-up patch: require an immediate raw/adjusted scale change at the event boundary."""
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "scripts/collect_real_quant_v2.py"


def main() -> None:
    s = P.read_text(encoding="utf-8")
    old = '''        for i in range(1, len(shared)):\n            left = [x[1] for x in shared[max(0, i - _SCALE_WINDOW):i]]\n            right = [x[1] for x in shared[i:min(len(shared), i + _SCALE_WINDOW)]]\n            if not left or not right:\n                continue\n            before = float(statistics.median(left))\n            after = float(statistics.median(right))\n            if before <= 0 or after <= 0:\n                continue\n            reset_ratio = after / before\n            if _relative_diff(reset_ratio, 1.0) < _SCALE_RESET_REL_TOL:\n                continue\n'''
    new = '''        for i in range(1, len(shared)):\n            # A regime event must occur at an actual adjacent scale boundary.\n            # Median windows are used only to validate the regimes around that\n            # boundary; they must never create an event before/after the change.\n            immediate_reset = shared[i][1] / shared[i - 1][1]\n            if _relative_diff(immediate_reset, 1.0) < _SCALE_RESET_REL_TOL:\n                continue\n            left = [x[1] for x in shared[max(0, i - _SCALE_WINDOW):i]]\n            right = [x[1] for x in shared[i:min(len(shared), i + _SCALE_WINDOW)]]\n            if not left or not right:\n                continue\n            before = float(statistics.median(left))\n            after = float(statistics.median(right))\n            if before <= 0 or after <= 0:\n                continue\n            reset_ratio = after / before\n            if _relative_diff(reset_ratio, 1.0) < _SCALE_RESET_REL_TOL:\n                continue\n'''
    if old not in s:
        raise SystemExit("scale transition loop not found")
    P.write_text(s.replace(old, new, 1), encoding="utf-8")
    print("PATCH_SCALE_CHANGEPOINT_BOUNDARY_PASS")


if __name__ == "__main__":
    main()
