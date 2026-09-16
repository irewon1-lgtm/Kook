#!/usr/bin/env python3
from __future__ import annotations

import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import collect_financial_safety as fs
import build_final_candidates_v2 as fc


def base_values() -> dict[str, float | None]:
    return {
        "assets": 100.0,
        "liabilities": 50.0,
        "equity": 50.0,
        "current_assets": 80.0,
        "current_liabilities": 50.0,
    }


def test_boundaries() -> None:
    v = base_values(); v.update(assets=500.0, liabilities=400.0, equity=100.0)
    status, reason, d = fs.classify(v)
    assert status == "PASS" and abs(d["debt_to_equity_pct"] - 400.0) < 1e-9

    v = base_values(); v.update(assets=500.001, liabilities=400.001, equity=100.0)
    status, reason, _ = fs.classify(v)
    assert (status, reason) == ("FAIL", "DEBT_TO_EQUITY_OVER_400")

    v = base_values(); v.update(current_assets=35.0, current_liabilities=50.0)
    assert fs.classify(v)[0] == "PASS"
    v["current_assets"] = 34.999
    assert fs.classify(v)[0:2] == ("FAIL", "CURRENT_RATIO_UNDER_70")


def test_fail_closed_missing_and_identity() -> None:
    for key in base_values():
        v = base_values(); v[key] = None
        status, reason, _ = fs.classify(v)
        assert status == "HOLD", (key, status, reason)
    v = base_values(); v["assets"] = 110.0
    assert fs.classify(v)[0:2] == ("HOLD", "ACCOUNTING_IDENTITY_MISMATCH")


def test_valuation_bands() -> None:
    cases = [
        (100, "LOW_RELATIVE_PER"), (80, "LOW_RELATIVE_PER"),
        (79.999, "BELOW_MEDIAN_PER"), (60, "BELOW_MEDIAN_PER"),
        (59.999, "MID_PER"), (40, "MID_PER"),
        (39.999, "ABOVE_MEDIAN_PER"), (20, "ABOVE_MEDIAN_PER"),
        (19.999, "HIGH_RELATIVE_PER"), (0, "HIGH_RELATIVE_PER"),
    ]
    for value, expected in cases:
        assert fc.valuation_band(value) == expected, (value, expected, fc.valuation_band(value))


def make_metric(raw: float | None, pct: float | None = 70.0) -> dict:
    return {"raw": raw, "percentile": pct if raw is not None else None, "reason": None if raw is not None else "MISSING", "basis": "TEST" if raw is not None else ""}


def make_row(rank: int | None, per: float | None, complete: bool = True) -> dict:
    return {
        "name": f"C{rank}", "market": "KOSPI", "sector": "제조",
        "m01": make_metric(10.0 if complete else None, 70.0),
        "m02": make_metric(20.0 if complete else None, 80.0),
        "m03": make_metric(per if complete else None, 90.0),
        "m04": make_metric(30.0 if complete else None, 85.0),
        "composite": 81.25 if complete else None,
        "rank": rank if complete else None,
    }


def safety(status: str = "PASS") -> dict:
    return {
        "status": status,
        "reason": "PASS" if status == "PASS" else "TEST",
        "basis": "BS",
        "debt_to_equity_pct": 100.0,
        "current_ratio_pct": 150.0,
        "identity_gap_pct": 0.0,
    }


def test_candidate_fail_closed_and_determinism() -> None:
    records = {
        "000003": make_row(3, 5.0),
        "000001": make_row(1, 8.0),
        "000002": make_row(2, 6.0),
        "000004": make_row(4, -1.0),
        "000005": make_row(None, None, complete=False),
    }
    s = {c: safety() for c in records}
    s["000002"] = safety("FAIL")
    q = {
        "snapshot_date_kst": "2026-09-16", "price_cutoff_date_kst": "2026-09-15", "universe_count": len(records),
        "records": records,
        "financial_safety": {"snapshot_date_kst": "2026-09-16", "policy_version": fs.POLICY_VERSION, "records": s},
    }
    out = fc.build_payload(q, limit=10)
    assert [x["code"] for x in out["candidates"]] == ["000001", "000003"]
    assert all(x["financial_safety"]["status"] == "PASS" for x in out["candidates"])
    assert all(x["valuation"]["changes_rank"] is False for x in out["candidates"])

    q2 = json.loads(json.dumps(q))
    q2["financial_safety"]["snapshot_date_kst"] = "2026-09-15"
    try:
        fc.build_payload(q2)
    except RuntimeError:
        pass
    else:
        raise AssertionError("snapshot mismatch must fail")


def test_randomized_2649_x_100() -> None:
    rnd = random.Random(4567)
    for round_idx in range(100):
        records = {}
        safety_records = {}
        for i in range(2649):
            code = f"{i:06d}"
            per = rnd.uniform(0.1, 200.0)
            records[code] = {
                "name": code, "market": "KOSPI" if i % 2 == 0 else "KOSDAQ", "sector": "제조",
                "m01": make_metric(rnd.uniform(-50, 500), rnd.uniform(0, 100)),
                "m02": make_metric(rnd.uniform(-50, 100), rnd.uniform(0, 100)),
                "m03": make_metric(per, rnd.uniform(0, 100)),
                "m04": make_metric(rnd.uniform(-90, 300), rnd.uniform(0, 100)),
                "composite": rnd.uniform(0, 100), "rank": i + 1,
            }
            safety_records[code] = safety("PASS" if rnd.random() > 0.2 else rnd.choice(["FAIL", "HOLD"]))
        q = {
            "snapshot_date_kst": "2026-09-16", "price_cutoff_date_kst": "2026-09-15", "universe_count": 2649,
            "records": records,
            "financial_safety": {"snapshot_date_kst": "2026-09-16", "policy_version": fs.POLICY_VERSION, "records": safety_records},
        }
        out = fc.build_payload(q, limit=10)
        assert out["candidate_count"] == 10
        source_ranks = [x["source_rank"] for x in out["candidates"]]
        assert source_ranks == sorted(source_ranks)
        assert len(set(x["code"] for x in out["candidates"])) == 10
        assert all(x["financial_safety"]["status"] == "PASS" for x in out["candidates"])
    print("RANDOMIZED_STAGE4567_PASS rows=264900")


def main() -> None:
    fs.self_test()
    fc._self_test()
    test_boundaries()
    test_fail_closed_missing_and_identity()
    test_valuation_bands()
    test_candidate_fail_closed_and_determinism()
    test_randomized_2649_x_100()
    print("STAGE4567_CLEANPASS_PASS")


if __name__ == "__main__":
    main()
