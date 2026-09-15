#!/usr/bin/env python3
"""Apply a one-quarter OpenDART history fallback to KR4 M01/M02.

Policy:
- Use the current validated four-metric snapshot as the source of truth.
- Only fill M01/M02 when the latest OpenDART bulk period is missing/unusable.
- Use exactly the immediately preceding fiscal quarter from the validated
  quarterly-history snapshot; never skip back two or more quarters.
- Never override financial-sector M02 exclusion or any existing numeric value.
- Fail-close economically non-comparable M01/M02 extremes before scoring.
- Recompute all four percentiles, composite scores and contiguous ranks after recovery.
- Record explicit HISTORY_1Q_FALLBACK basis and recovery metadata.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
from copy import deepcopy
from pathlib import Path
from typing import Any

PERIOD_TO_QUARTER = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}
M01_RECOVERABLE_REASONS = {"DART_NO_REVENUE", "DART_NO_COMPARABLE_PRIOR_REVENUE"}
M02_RECOVERABLE_REASONS = {"DART_NO_OPERATING_INCOME", "DART_NO_COMPARABLE_OPERATING_MARGIN"}

# Final rankability bounds. These are intentionally wider than the normal live
# distribution, so ordinary high-growth / early-stage companies stay rankable.
# Values outside the bounds are never clipped into a percentile: they fail
# closed to missing and preserve the rejected raw/basis as audit fields.
M01_MIN = -100.0
M01_MAX = 5000.0
M02_MIN = -5000.0
M02_MAX = 200.0
M01_GUARD_REASON = "M01_ECONOMIC_OUTLIER_GUARD"
M02_GUARD_REASON = "M02_ECONOMIC_OUTLIER_GUARD"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--history", default="evidence/quarterly_history.json")
    p.add_argument("--out", default=None)
    p.add_argument("--self-test", action="store_true")
    return p.parse_args()


def previous_period(year: int, dart_period: str) -> str:
    q = PERIOD_TO_QUARTER[dart_period]
    if q == 1:
        return f"{year - 1}Q4"
    return f"{year}Q{q - 1}"


def _finite(value: Any) -> float | None:
    if value is None:
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def _guard_metric(metric: dict[str, Any], low: float, high: float, reason: str) -> bool:
    raw = metric.get("raw")
    if raw is None:
        return False
    value = _finite(raw)
    if value is not None and low <= value <= high:
        return False
    metric["guarded_raw"] = raw
    metric["guarded_basis"] = metric.get("basis") or ""
    metric.update(raw=None, percentile=None, reason=reason, basis="")
    return True


def apply_economic_outlier_guards(records: dict[str, dict[str, Any]]) -> dict[str, list[str]]:
    """Remove non-comparable M01/M02 extremes before any percentile calculation.

    M01 lower bound follows the economic identity that revenue cannot fall by
    more than 100% when prior-period revenue is positive. The +5,000% ceiling
    still allows a 51x YoY revenue jump. M02 is deliberately asymmetric: large
    early-stage loss margins remain eligible down to -5,000%, while positive
    OPM above +200% is treated as denominator/base distortion for ranking.
    """
    m01_codes: list[str] = []
    m02_codes: list[str] = []
    for code, row in records.items():
        if _guard_metric(row["m01"], M01_MIN, M01_MAX, M01_GUARD_REASON):
            m01_codes.append(code)
        if _guard_metric(row["m02"], M02_MIN, M02_MAX, M02_GUARD_REASON):
            m02_codes.append(code)
    return {"m01": sorted(m01_codes), "m02": sorted(m02_codes)}


def _apply_percentile(records: dict[str, dict[str, Any]], metric_id: str, higher_better: bool) -> None:
    vals = []
    for code, row in records.items():
        raw = _finite(row[metric_id].get("raw"))
        if raw is not None:
            vals.append((code, raw))
    vals.sort(key=lambda x: (x[1], x[0]))
    n = len(vals)
    for row in records.values():
        row[metric_id]["percentile"] = None
    if n == 0:
        return
    i = 0
    while i < n:
        j = i + 1
        while j < n and vals[j][1] == vals[i][1]:
            j += 1
        avg_index = (i + j - 1) / 2.0
        pct = 50.0 if n == 1 else avg_index / (n - 1) * 100.0
        if not higher_better:
            pct = 100.0 - pct
        for k in range(i, j):
            records[vals[k][0]][metric_id]["percentile"] = round(pct, 6)
        i = j


def _apply_loss_safe_per_percentile(records: dict[str, dict[str, Any]]) -> None:
    positives = []
    negatives = []
    for code, row in records.items():
        raw = _finite(row["m03"].get("raw"))
        row["m03"]["percentile"] = None
        if raw is None:
            continue
        if raw < 0:
            negatives.append(code)
        elif raw > 0:
            positives.append((code, raw))
    positives.sort(key=lambda x: (x[1], x[0]))
    n = len(positives)
    i = 0
    while i < n:
        j = i + 1
        while j < n and positives[j][1] == positives[i][1]:
            j += 1
        avg_index = (i + j - 1) / 2.0
        normal = 50.0 if n == 1 else 100.0 - avg_index / (n - 1) * 100.0
        score = 1.0 + normal * 0.99
        for k in range(i, j):
            records[positives[k][0]]["m03"]["percentile"] = round(score, 6)
        i = j
    for code in negatives:
        records[code]["m03"]["percentile"] = 0.0


def recompute_scores(records: dict[str, dict[str, Any]]) -> dict[str, int]:
    # Step 4: fail-close M01/M02 economic extremes over the *entire* universe.
    # This shared scorer is called after every recovery stage, so later fallback
    # sources cannot re-introduce an outlier that passed an earlier stage.
    apply_economic_outlier_guards(records)

    # Step 5: full-universe percentile + composite + rank rebuild. Never retain
    # stale percentile/rank state after a guard or recovery changes membership.
    _apply_percentile(records, "m01", True)
    _apply_percentile(records, "m02", True)
    _apply_loss_safe_per_percentile(records)
    _apply_percentile(records, "m04", True)

    ranked: list[tuple[str, float]] = []
    for code, row in records.items():
        pcts = [_finite(row[f"m0{i}"].get("percentile")) for i in range(1, 5)]
        if all(v is not None for v in pcts):
            comp = statistics.mean(v for v in pcts if v is not None)
            row["composite"] = round(comp, 6)
            ranked.append((code, comp))
        else:
            row["composite"] = None
        row["rank"] = None
    ranked.sort(key=lambda x: (-x[1], x[0]))
    for rank, (code, _) in enumerate(ranked, 1):
        records[code]["rank"] = rank

    return {
        "m01_available": sum(r["m01"].get("raw") is not None for r in records.values()),
        "m02_available": sum(r["m02"].get("raw") is not None for r in records.values()),
        "m03_available": sum(r["m03"].get("raw") is not None for r in records.values()),
        "m04_available": sum(r["m04"].get("raw") is not None for r in records.values()),
        "complete_count": len(ranked),
        "ranked_count": len(ranked),
    }


def apply_fallback(quant: dict[str, Any], history: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    out = deepcopy(quant)
    auto = out.get("auto_update") or {}
    year = int(auto.get("dart_year"))
    dart_period = str(auto.get("dart_period") or "")
    if dart_period not in PERIOD_TO_QUARTER:
        raise ValueError(f"unsupported DART period: {dart_period!r}")
    prior = previous_period(year, dart_period)

    if out.get("universe_count") != len(out.get("records") or {}):
        raise ValueError("quant universe mismatch")
    if history.get("as_of_date_kst") != out.get("snapshot_date_kst"):
        raise ValueError("history/quant snapshot date mismatch")
    if set(history.get("records") or {}) != set(out.get("records") or {}):
        raise ValueError("history/quant issue-code set mismatch")
    if prior not in set(history.get("target_periods") or []):
        raise ValueError(f"immediately preceding quarter {prior} absent from history")

    m01_recovered: list[str] = []
    m02_recovered: list[str] = []
    for code, row in out["records"].items():
        points = history["records"][code].get("points") or []
        point = next((p for p in points if p.get("period") == prior), None)
        if not isinstance(point, dict):
            continue
        scope = str(point.get("scope") or "")
        hist_basis = str(point.get("basis") or "")

        m1 = row["m01"]
        yoy = _finite(point.get("revenue_yoy"))
        if m1.get("raw") is None and m1.get("reason") in M01_RECOVERABLE_REASONS and yoy is not None:
            m1["raw"] = round(yoy, 6)
            m1["reason"] = None
            m1["basis"] = f"{prior}_HISTORY_1Q_FALLBACK_{scope}_{hist_basis}".rstrip("_")
            m01_recovered.append(code)

        m2 = row["m02"]
        margin = _finite(point.get("operating_margin"))
        if (
            m2.get("raw") is None
            and m2.get("reason") in M02_RECOVERABLE_REASONS
            and margin is not None
            and abs(margin) <= 10000
        ):
            m2["raw"] = round(margin, 6)
            m2["reason"] = None
            m2["basis"] = f"{prior}_HISTORY_1Q_FALLBACK_{scope}_{hist_basis}".rstrip("_")
            m02_recovered.append(code)

    old_coverage = dict(out.get("coverage") or {})
    guard_before = {
        "m01": sorted(code for code, row in out["records"].items() if row["m01"].get("raw") is not None and not (M01_MIN <= float(row["m01"]["raw"]) <= M01_MAX)),
        "m02": sorted(code for code, row in out["records"].items() if row["m02"].get("raw") is not None and not (M02_MIN <= float(row["m02"]["raw"]) <= M02_MAX)),
    }
    new_coverage = recompute_scores(out["records"])
    out["coverage"] = new_coverage
    out.setdefault("rules", {})["M01"] = (
        str(out.get("rules", {}).get("M01") or "")
        + "; if the latest DART period is unavailable, use exactly the immediately preceding validated quarter's revenue YoY"
        + f"; final rankability guard {M01_MIN:g}%..{M01_MAX:g}% with fail-close before full rerank"
    ).lstrip("; ")
    out.setdefault("rules", {})["M02"] = (
        str(out.get("rules", {}).get("M02") or "")
        + "; if the latest DART period is unavailable, use exactly the immediately preceding validated quarter's operating margin (financial-sector exclusion unchanged)"
        + f"; final rankability guard {M02_MIN:g}%..{M02_MAX:g}% with fail-close before full rerank"
    ).lstrip("; ")
    recovery = {
        "policy": "one-quarter-back validated OpenDART history only; no multi-quarter skip; final economic fail-close + full rerank",
        "source_period": prior,
        "m01_recovered": len(m01_recovered),
        "m02_recovered": len(m02_recovered),
        "m01_codes": m01_recovered,
        "m02_codes": m02_recovered,
        "economic_guard": {
            "m01_range": [M01_MIN, M01_MAX],
            "m02_range": [M02_MIN, M02_MAX],
            "m01_excluded_codes": guard_before["m01"],
            "m02_excluded_codes": guard_before["m02"],
        },
        "coverage_before": old_coverage,
        "coverage_after": new_coverage,
    }
    out["recent_dart_fallback"] = recovery
    return out, recovery


def validate_snapshot(d: dict[str, Any]) -> None:
    records = d["records"]
    coverage = d["coverage"]
    ranks = []
    actual = {f"m0{i}_available": 0 for i in range(1, 5)}
    complete = 0
    for code, row in records.items():
        if len(code) != 6:
            raise AssertionError(code)
        all_raw = True
        for i in range(1, 5):
            m = row[f"m0{i}"]
            raw = _finite(m.get("raw"))
            pct = _finite(m.get("percentile"))
            if raw is None:
                all_raw = False
                assert pct is None and m.get("reason"), (code, i, m)
            else:
                actual[f"m0{i}_available"] += 1
                assert pct is not None and 0 <= pct <= 100 and m.get("basis"), (code, i, m)
                if i == 1:
                    assert M01_MIN <= raw <= M01_MAX, (code, m)
                if i == 2:
                    assert M02_MIN <= raw <= M02_MAX, (code, m)
                if i == 3 and raw < 0:
                    assert pct == 0.0, (code, m)
        assert (row.get("composite") is not None) == all_raw, (code, "composite")
        assert (row.get("rank") is not None) == all_raw, (code, "rank")
        if all_raw:
            complete += 1
            ranks.append(int(row["rank"]))
    assert sorted(ranks) == list(range(1, complete + 1))
    actual["complete_count"] = complete
    actual["ranked_count"] = complete
    assert coverage == actual, (coverage, actual)


def self_test() -> None:
    records = {
        "000001": {
            "name": "A", "market": "KOSPI", "sector": "제조", "listing_date": "2020-01-01",
            "m01": {"raw": None, "percentile": None, "reason": "DART_NO_REVENUE", "basis": ""},
            "m02": {"raw": None, "percentile": None, "reason": "DART_NO_OPERATING_INCOME", "basis": ""},
            "m03": {"raw": -20.0, "percentile": 0.0, "reason": None, "basis": "PER"},
            "m04": {"raw": 5.0, "percentile": 50.0, "reason": None, "basis": "PRICE"},
            "composite": None, "rank": None,
        },
        "000002": {
            "name": "B", "market": "KOSPI", "sector": "제조", "listing_date": "2020-01-01",
            "m01": {"raw": 10.0, "percentile": 50.0, "reason": None, "basis": "CUR"},
            "m02": {"raw": 8.0, "percentile": 50.0, "reason": None, "basis": "CUR"},
            "m03": {"raw": 10.0, "percentile": 50.0, "reason": None, "basis": "PER"},
            "m04": {"raw": 7.0, "percentile": 50.0, "reason": None, "basis": "PRICE"},
            "composite": 50.0, "rank": 1,
        },
        "000003": {
            "name": "C", "market": "KOSPI", "sector": "금융", "listing_date": "2020-01-01",
            "m01": {"raw": None, "percentile": None, "reason": "DART_NO_REVENUE", "basis": ""},
            "m02": {"raw": None, "percentile": None, "reason": "FINANCIAL_SECTOR_EXCLUDED", "basis": ""},
            "m03": {"raw": 12.0, "percentile": 50.0, "reason": None, "basis": "PER"},
            "m04": {"raw": 1.0, "percentile": 50.0, "reason": None, "basis": "PRICE"},
            "composite": None, "rank": None,
        },
    }
    q = {
        "snapshot_date_kst": "2026-09-15", "universe_count": 3,
        "auto_update": {"dart_year": "2026", "dart_period": "HY"},
        "records": records,
        "coverage": {"m01_available": 1, "m02_available": 1, "m03_available": 3, "m04_available": 3, "complete_count": 1, "ranked_count": 1},
        "rules": {"M01": "x", "M02": "y"},
    }
    hrecords = {}
    for code in records:
        hrecords[code] = {"points": [{
            "period": "2026Q1", "revenue_yoy": 20.0 if code != "000003" else 30.0,
            "operating_margin": 6.0, "scope": "CFS", "basis": "DIRECT_3M"
        }]}
    h = {"as_of_date_kst": "2026-09-15", "target_periods": ["2026Q1"], "records": hrecords}
    out, rec = apply_fallback(q, h)
    assert rec["m01_recovered"] == 2
    assert rec["m02_recovered"] == 1
    assert out["records"]["000001"]["m01"]["raw"] == 20.0
    assert out["records"]["000001"]["m02"]["raw"] == 6.0
    assert out["records"]["000001"]["m03"]["percentile"] == 0.0
    assert out["records"]["000003"]["m02"]["raw"] is None
    assert out["records"]["000003"]["m02"]["reason"] == "FINANCIAL_SECTOR_EXCLUDED"
    assert out["coverage"]["complete_count"] == 2
    validate_snapshot(out)

    guard_records = {
        "G00001": {
            "m01": {"raw": 5000.0, "percentile": 1.0, "reason": None, "basis": "M01_OK"},
            "m02": {"raw": -5000.0, "percentile": 1.0, "reason": None, "basis": "M02_OK"},
            "m03": {"raw": 10.0, "percentile": 1.0, "reason": None, "basis": "PER"},
            "m04": {"raw": 1.0, "percentile": 1.0, "reason": None, "basis": "PRICE"},
            "composite": 1.0, "rank": 1,
        },
        "G00002": {
            "m01": {"raw": 5000.0001, "percentile": 99.0, "reason": None, "basis": "M01_HIGH"},
            "m02": {"raw": 200.0001, "percentile": 99.0, "reason": None, "basis": "M02_HIGH"},
            "m03": {"raw": 10.0, "percentile": 1.0, "reason": None, "basis": "PER"},
            "m04": {"raw": 1.0, "percentile": 1.0, "reason": None, "basis": "PRICE"},
            "composite": 99.0, "rank": 1,
        },
        "G00003": {
            "m01": {"raw": -100.0001, "percentile": 1.0, "reason": None, "basis": "M01_LOW"},
            "m02": {"raw": -5000.0001, "percentile": 1.0, "reason": None, "basis": "M02_LOW"},
            "m03": {"raw": 10.0, "percentile": 1.0, "reason": None, "basis": "PER"},
            "m04": {"raw": 1.0, "percentile": 1.0, "reason": None, "basis": "PRICE"},
            "composite": 1.0, "rank": 1,
        },
    }
    gcov = recompute_scores(guard_records)
    assert gcov["m01_available"] == 1 and gcov["m02_available"] == 1, gcov
    assert guard_records["G00001"]["m01"]["raw"] == 5000.0
    assert guard_records["G00001"]["m02"]["raw"] == -5000.0
    assert guard_records["G00002"]["m01"]["reason"] == M01_GUARD_REASON
    assert guard_records["G00002"]["m01"]["guarded_raw"] == 5000.0001
    assert guard_records["G00002"]["m02"]["reason"] == M02_GUARD_REASON
    assert guard_records["G00003"]["m01"]["reason"] == M01_GUARD_REASON
    assert guard_records["G00003"]["m02"]["reason"] == M02_GUARD_REASON
    assert guard_records["G00002"]["composite"] is None and guard_records["G00002"]["rank"] is None
    print("RECENT_DART_FALLBACK_SELF_TEST_PASS", json.dumps(rec, ensure_ascii=False))


def main() -> None:
    args = parse_args()
    if args.self_test:
        self_test()
        return
    quant_path = Path(args.quant)
    history_path = Path(args.history)
    out_path = Path(args.out) if args.out else quant_path
    quant = json.loads(quant_path.read_text(encoding="utf-8"))
    history = json.loads(history_path.read_text(encoding="utf-8"))
    updated, recovery = apply_fallback(quant, history)
    validate_snapshot(updated)
    out_path.write_text(json.dumps(updated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("RECENT_DART_FALLBACK_PASS", json.dumps(recovery, ensure_ascii=False))


if __name__ == "__main__":
    main()
