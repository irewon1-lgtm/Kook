#!/usr/bin/env python3
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import collect_quarterly_history as qh  # noqa: E402
import collect_real_quant as base  # noqa: E402


def listed_entries(as_of):
    # Mirror the bulk index: future and newly opened reporting periods can
    # already have links. Only the HTTP boundary is replaced; selection is real.
    html = "\n".join(
        f"download_ext002('{year}','{period}','PL','{year}_{period}.zip');"
        for year in range(2023, 2027)
        for period in ("FQ", "HY", "TQ", "FY")
    )
    response = Mock(text=html)
    session = Mock()
    session.post.return_value = response
    with patch.object(qh.requests, "Session", return_value=session):
        return qh.list_pl_entries(as_of)


def test_preliminary_q3_does_not_displace_complete_eight_quarter_window():
    entries = listed_entries(date(2026, 11, 5))[-12:]
    qh.assert_contiguous_entries(entries, 12)
    assert [f"{e['year']}Q{e['quarter']}" for e in entries[-8:]] == [
        "2024Q3", "2024Q4", "2025Q1", "2025Q2",
        "2025Q3", "2025Q4", "2026Q1", "2026Q2",
    ]


def test_q3_becomes_eligible_only_after_reporting_window_closes():
    # Nov 14 is Saturday in 2026: filers still have Monday Nov 16.
    for as_of, latest in [(date(2026, 11, 14), (2026, 2)),
                          (date(2026, 11, 15), (2026, 2)),
                          (date(2026, 11, 16), (2026, 2)),
                          (date(2026, 11, 17), (2026, 3))]:
        entries = listed_entries(as_of)
        assert (entries[-1]["year"], entries[-1]["quarter"]) == latest


def test_annual_window_uses_ninety_days_and_handles_leap_year():
    for as_of, latest in [(date(2026, 3, 31), (2025, 3)),
                          (date(2026, 4, 1), (2025, 4)),
                          (date(2024, 3, 30), (2023, 3)),
                          (date(2024, 4, 1), (2023, 3)),
                          (date(2024, 4, 2), (2023, 4))]:
        entries = listed_entries(as_of)
        assert (entries[-1]["year"], entries[-1]["quarter"]) == latest


def test_q1_and_half_year_windows_do_not_accept_early_bulk_links():
    for as_of, latest in [(date(2026, 5, 15), (2025, 4)),
                          (date(2026, 5, 16), (2026, 1)),
                          (date(2026, 8, 14), (2026, 1)),
                          (date(2026, 8, 15), (2026, 2)),
                          (date(2026, 12, 31), (2026, 3))]:
        entries = listed_entries(as_of)
        assert (entries[-1]["year"], entries[-1]["quarter"]) == latest


def test_eight_early_filers_do_not_erase_the_rest_of_the_universe():
    # Synthetic reproduction of the incident, not substitute market data.
    issuers = [base.Issuer(f"{i:06}", "테스트", "제조업", "2020-01-01", "KOSPI")
               for i in range(2649)]
    support = listed_entries(date(2026, 11, 5))[-12:]
    preliminary = {"year": 2026, "quarter": 3, "index": 8107,
        "period": "Q3", "file": "2026_TQ.zip"}
    parsed = {}
    for entry in support + [preliminary]:
        label = f"{entry['year']}Q{entry['quarter']}"
        parsed[label] = {}
        for i, issuer in enumerate(issuers):
            available = label != "2026Q3" or i < 8
            quarter = entry["quarter"]
            parsed[label][issuer.code] = {
                "scope": "CFS" if available else "",
                "direct_rev": 100.0 if available and quarter != 4 else None,
                "direct_op": 10.0 if available and quarter != 4 else None,
                "cum_rev": 100.0 * quarter if available else None,
                "cum_op": 10.0 * quarter if available else None,
                "source_file": entry["file"],
                "reason": None if available else "DART_NO_REVENUE",
            }
    healthy = qh.derive_visible_history(issuers, support, parsed, 8)
    assert qh.coverage(healthy)["stocks_with_8q"] == 2649
    # Once Q3 is eligible, insufficient/malformed mature data must remain
    # visible to the unchanged 1192-stock gate, with no data-based rollback.
    mature_support = listed_entries(date(2026, 11, 17))[-12:]
    partial = qh.derive_visible_history(issuers, mature_support, parsed, 8)
    assert qh.coverage(partial)["stocks_with_8q"] == 8
    assert partial[issuers[-1].code]["points"][-1]["revenue"] is None


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
                annual = annual_base.get(year, cumulative)
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


def test_dart_entry_regex_accepts_q1_and_q3_period_codes():
    html = """
      <script>
      download_ext002('2025','FQ','PL','2025_Q1_PL.zip');
      download_ext002('2025','HY','PL','2025_HY_PL.zip');
      download_ext002('2025','TQ','PL','2025_Q3_PL.zip');
      download_ext002('2025','FY','PL','2025_FY_PL.zip');
      </script>
    """
    rows = base.DART_ENTRY_RE.findall(html)
    assert [row[1] for row in rows] == ["FQ", "HY", "TQ", "FY"], rows
    assert qh.PERIOD_TO_Q["FQ"] == 1 and qh.PERIOD_TO_Q["TQ"] == 3
    assert qh.Q_TO_PERIOD[1] == "Q1" and qh.Q_TO_PERIOD[3] == "Q3"


def test_contiguous_quarter_gate_rejects_missing_q1_q3_style_gaps():
    good = entries_12()
    qh.assert_contiguous_entries(good, 12)
    bad = [e for e in good if e["period"] not in {"Q1", "Q3"}]
    try:
        qh.assert_contiguous_entries(bad)
        raise AssertionError("non-contiguous HY/FY-only timeline must fail")
    except RuntimeError as exc:
        assert "not contiguous" in str(exc)


def test_q4_is_annual_minus_q3_and_yoy_uses_same_quarter():
    issuer = base.Issuer("000001", "테스트", "제조업", "2020-01-01", "KOSPI")
    records = qh.derive_visible_history([issuer], entries_12(), parsed_for(), 8)
    points = records[issuer.code]["points"]
    assert len(points) == 8
    indexes = [qh.quarter_index(p["fiscal_year"], p["quarter"]) for p in points]
    assert indexes == list(range(indexes[0], indexes[0] + 8)), indexes
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
