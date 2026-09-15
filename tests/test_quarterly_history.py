#!/usr/bin/env python3
from __future__ import annotations

import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import collect_quarterly_history as qh  # noqa: E402
import collect_real_quant as base  # noqa: E402


def entries_12():
    out = []
    for year in (2024, 2025, 2026):
        for quarter in (1, 2, 3, 4):
            out.append({
                "year": year,
                "quarter": quarter,
                "period": qh.Q_TO_PERIOD[quarter],
                "file": f"{year}Q{quarter}.zip",
                "index": qh.quarter_index(year, quarter),
            })
    return out


def parsed_for(code="000001", scope="CFS"):
    parsed = {}
    annual_base = {2024: 400.0, 2025: 520.0, 2026: 680.0}
    quarters = {
        2024: [85.0, 95.0, 105.0, 115.0],
        2025: [100.0, 120.0, 135.0, 165.0],
        2026: [125.0, 145.0, 170.0, 240.0],
    }
    for year, vals in quarters.items():
        cumulative = 0.0
        for quarter, revenue in enumerate(vals, 1):
            cumulative += revenue
            label = qh.period_label(year, quarter)
            op = revenue * (0.08 + quarter * 0.01)
            if quarter < 4:
                parsed[label] = {
                    code: {
                        "scope": scope,
                        "direct_rev": revenue,
                        "direct_op": op,
                        "cum_rev": cumulative,
                        "cum_op": sum(vals[i] * (0.08 + (i + 1) * 0.01) for i in range(quarter)),
                        "direct_basis": "당기3개월",
                        "cum_basis": "당기누적",
                        "source_file": f"{label}.zip",
                        "reason": None,
                    }
                }
            else:
                # FY intentionally has no direct 3M value; Q4 must be annual-Q3 YTD.
                annual = annual_base.get(year, cumulative)
                # Keep annual equal to the four quarter sum in the synthetic oracle.
                annual = cumulative
                annual_op = sum(vals[i] * (0.08 + (i + 1) * 0.01) for i in range(4))
                parsed[label] = {
                    code: {
                        "scope": scope,
                        "direct_rev": None,
                        "direct_op": None,
                        "cum_rev": annual,
                        "cum_op": annual_op,
                        "direct_basis": "",
                        "cum_basis": "당기",
                        "source_file": f"{label}.zip",
                        "reason": None,
                    }
                }
    return parsed


def test_q4_is_annual_minus_q3_and_yoy_uses_same_quarter():
    issuer = base.Issuer("000001", "테스트", "제조업", "2020-01-01", "KOSPI")
    records = qh.derive_visible_history([issuer], entries_12(), parsed_for(), 8)
    points = records[issuer.code]["points"]
    assert len(points) == 8
    p_2025q4 = next(p for p in points if p["period"] == "2025Q4")
    assert p_2025q4["basis"] == "FY_MINUS_Q3"
    assert abs(p_2025q4["revenue"] - 165.0) < 1e-9
    p_2026q1 = next(p for p in points if p["period"] == "2026Q1")
    assert abs(p_2026q1["revenue_yoy"] - 25.0) < 1e-9


def test_direct_three_month_value_wins_over_cumulative_delta():
    issuer = base.Issuer("000001", "테스트", "제조업", "2020-01-01", "KOSPI")
    parsed = parsed_for()
    parsed["2026Q2"][issuer.code]["direct_rev"] = 999.0
    records = qh.derive_visible_history([issuer], entries_12(), parsed, 8)
    p = next(p for p in records[issuer.code]["points"] if p["period"] == "2026Q2")
    assert p["basis"] == "DIRECT_3M"
    assert p["revenue"] == 999.0


def test_scope_mismatch_fails_closed_for_derived_q4():
    issuer = base.Issuer("000001", "테스트", "제조업", "2020-01-01", "KOSPI")
    parsed = parsed_for()
    parsed["2026Q4"][issuer.code]["scope"] = "OFS"
    records = qh.derive_visible_history([issuer], entries_12(), parsed, 8)
    p = next(p for p in records[issuer.code]["points"] if p["period"] == "2026Q4")
    assert p["revenue"] is None
    assert p["reason"] == "CFS_OFS_SCOPE_MISMATCH"


def test_financial_sector_does_not_fake_operating_margin():
    issuer = base.Issuer("000001", "테스트은행", "은행", "2020-01-01", "KOSPI")
    records = qh.derive_visible_history([issuer], entries_12(), parsed_for(), 8)
    assert all(p["operating_margin"] is None for p in records[issuer.code]["points"])


def test_output_has_no_nonfinite_values():
    issuer = base.Issuer("000001", "테스트", "제조업", "2020-01-01", "KOSPI")
    records = qh.derive_visible_history([issuer], entries_12(), parsed_for(), 8)
    for p in records[issuer.code]["points"]:
        for key in ("revenue", "operating_income", "operating_margin", "revenue_yoy", "revenue_qoq"):
            value = p[key]
            assert value is None or math.isfinite(float(value)), (key, value)


def main():
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    failures = []
    for test in sorted(tests, key=lambda f: f.__name__):
        try:
            test()
            print("PASS", test.__name__)
        except Exception as exc:
            failures.append((test.__name__, repr(exc)))
            print("FAIL", test.__name__, repr(exc))
    if failures:
        raise SystemExit(f"QUARTERLY_HISTORY_TEST_FAILURES {failures}")
    print("QUARTERLY_HISTORY_UNIT_PASS", len(tests))


if __name__ == "__main__":
    main()
