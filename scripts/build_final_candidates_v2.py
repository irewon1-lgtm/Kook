#!/usr/bin/env python3
"""Stage 5/6/7: build KR4 final research candidates with financial-safety gating.

Inputs:
- validated 4-metric KR4 snapshot;
- Stage-4 financial_safety section generated for the exact same snapshot date.

Policy:
- no new composite score is invented;
- ordering reuses the existing KR4 rank;
- rows must have all four metrics and positive trailing PER;
- financial-safety status must be PASS;
- Stage 5 is an auditable market-relative trailing-PER band, derived only from the
  already validated M03 percentile. It is descriptive and does not rerank.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_VERSION = "KR4_FINAL_CANDIDATE_V2"
POLICY_VERSION = "STAGE4567_SAFETY_AND_RELATIVE_PER_BAND_V1"
DEFAULT_LIMIT = 10


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--out", default="evidence/final_candidates.json")
    p.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    p.add_argument("--self-test", action="store_true")
    return p.parse_args()


def valuation_band(percentile: float) -> str:
    p = float(percentile)
    if not 0.0 <= p <= 100.0:
        raise ValueError(f"valuation percentile out of range: {p}")
    if p >= 80.0:
        return "LOW_RELATIVE_PER"
    if p >= 60.0:
        return "BELOW_MEDIAN_PER"
    if p >= 40.0:
        return "MID_PER"
    if p >= 20.0:
        return "ABOVE_MEDIAN_PER"
    return "HIGH_RELATIVE_PER"


def band_ko(band: str) -> str:
    return {
        "LOW_RELATIVE_PER": "시장 내 낮은 PER 구간",
        "BELOW_MEDIAN_PER": "시장 중앙값보다 낮은 PER 구간",
        "MID_PER": "시장 중간 PER 구간",
        "ABOVE_MEDIAN_PER": "시장 중앙값보다 높은 PER 구간",
        "HIGH_RELATIVE_PER": "시장 내 높은 PER 구간",
    }[band]


def _metric_snapshot(metric_id: str, metric: dict[str, Any], as_of: str) -> dict[str, Any]:
    raw = metric.get("raw")
    pct = metric.get("percentile")
    if raw is None or pct is None:
        raise AssertionError((metric_id, metric))
    return {
        "id": metric_id.upper(),
        "raw": float(raw),
        "percentile": float(pct),
        "basis": metric.get("basis") or "",
        "as_of_date": as_of,
    }


def _base_eligible(row: dict[str, Any]) -> bool:
    metrics = [row.get(f"m0{i}") or {} for i in range(1, 5)]
    if any(m.get("raw") is None or m.get("percentile") is None for m in metrics):
        return False
    if row.get("composite") is None or row.get("rank") is None:
        return False
    if float(metrics[2]["raw"]) <= 0.0:
        return False
    return True


def build_payload(quant: dict[str, Any], limit: int = DEFAULT_LIMIT) -> dict[str, Any]:
    if not 1 <= limit <= 100:
        raise ValueError("limit must be between 1 and 100")
    safety = quant.get("financial_safety")
    if not isinstance(safety, dict):
        raise RuntimeError("financial_safety section is missing")
    if safety.get("snapshot_date_kst") != quant.get("snapshot_date_kst"):
        raise RuntimeError("financial_safety snapshot date mismatch")
    safety_records = safety.get("records") or {}
    records = quant.get("records") or {}
    if set(safety_records) != set(records):
        raise RuntimeError("financial_safety identity set mismatch")

    ranked: list[tuple[str, dict[str, Any], dict[str, Any]]] = []
    reject_counts: dict[str, int] = {
        "base_incomplete_or_nonpositive_per": 0,
        "financial_safety_not_pass": 0,
    }
    for code, row in records.items():
        if not _base_eligible(row):
            reject_counts["base_incomplete_or_nonpositive_per"] += 1
            continue
        fs = safety_records[code]
        if fs.get("status") != "PASS":
            reject_counts["financial_safety_not_pass"] += 1
            continue
        ranked.append((code, row, fs))
    ranked.sort(key=lambda x: (int(x[1]["rank"]), x[0]))
    selected = ranked[:limit]

    financial_date = quant["snapshot_date_kst"]
    price_date = quant["price_cutoff_date_kst"]
    candidates: list[dict[str, Any]] = []
    for candidate_rank, (code, row, fs) in enumerate(selected, 1):
        m03_pct = float(row["m03"]["percentile"])
        band = valuation_band(m03_pct)
        flags = [
            "FOUR_METRICS_COMPLETE",
            "POSITIVE_TRAILING_PER",
            "FINANCIAL_SAFETY_PASS",
            "VALUATION_BAND_CLASSIFIED",
            "EXISTING_COMPOSITE_RANK_REUSED",
        ]
        if float(row["m01"]["raw"]) > 0:
            flags.append("POSITIVE_REVENUE_GROWTH")
        if float(row["m02"]["raw"]) > 0:
            flags.append("POSITIVE_OPERATING_MARGIN")
        if float(row["m04"]["raw"]) > 0:
            flags.append("POSITIVE_6M_MOMENTUM")

        source_rank = int(row["rank"])
        candidates.append(
            {
                "schema_version": SCHEMA_VERSION,
                "policy_version": POLICY_VERSION,
                "candidate_rank": candidate_rank,
                "source_rank": source_rank,
                "code": code,
                "name": row["name"],
                "market": row["market"],
                "sector": row.get("sector") or "기타",
                "composite_score": float(row["composite"]),
                "financial_snapshot_date": financial_date,
                "price_cutoff_date": price_date,
                "metrics": {
                    "M01": _metric_snapshot("m01", row["m01"], financial_date),
                    "M02": _metric_snapshot("m02", row["m02"], financial_date),
                    "M03": _metric_snapshot("m03", row["m03"], price_date),
                    "M04": _metric_snapshot("m04", row["m04"], price_date),
                },
                "financial_safety": {
                    "status": "PASS",
                    "reason": fs.get("reason") or "PASS",
                    "basis": fs.get("basis") or "",
                    "debt_to_equity_pct": fs.get("debt_to_equity_pct"),
                    "current_ratio_pct": fs.get("current_ratio_pct"),
                    "identity_gap_pct": fs.get("identity_gap_pct"),
                },
                "valuation": {
                    "method": "MARKET_RELATIVE_TRAILING_PER_PERCENTILE",
                    "band": band,
                    "band_ko": band_ko(band),
                    "m03_percentile": m03_pct,
                    "changes_rank": False,
                },
                "selection_flags": flags,
                "research_status": "FINAL_RESEARCH_CANDIDATE",
                "selection_reason_ko": (
                    f"4지표 검증 · 재무안정성 PASS · {band_ko(band)} · 기존 종합순위 {source_rank}위 기준 조사 후보"
                ),
            }
        )

    payload = {
        "schema_version": SCHEMA_VERSION,
        "policy_version": POLICY_VERSION,
        "generated_from": "real_quant_snapshot.json#financial_safety",
        "snapshot_date_kst": financial_date,
        "price_cutoff_date_kst": price_date,
        "universe_count": quant["universe_count"],
        "financial_safety_policy_version": safety.get("policy_version") or "",
        "eligible_count": len(ranked),
        "candidate_limit": limit,
        "candidate_count": len(candidates),
        "reject_counts": reject_counts,
        "selection_policy": {
            "purpose": "research_priority_only",
            "requires_four_validated_metrics": True,
            "requires_existing_composite_rank": True,
            "requires_positive_trailing_per": True,
            "requires_financial_safety_pass": True,
            "valuation_band": "descriptive market-relative trailing-PER percentile band",
            "valuation_band_changes_rank": False,
            "new_score_formula": False,
            "ordering": "existing KR4 composite rank ascending, then issue code",
            "missing_value_policy": "fail_closed_no_guess",
        },
        "candidates": candidates,
    }
    validate_payload(payload)
    return payload


def validate_payload(payload: dict[str, Any]) -> None:
    candidates = payload["candidates"]
    assert payload["schema_version"] == SCHEMA_VERSION
    assert payload["policy_version"] == POLICY_VERSION
    assert payload["candidate_count"] == len(candidates)
    assert len({c["code"] for c in candidates}) == len(candidates)
    assert [c["candidate_rank"] for c in candidates] == list(range(1, len(candidates) + 1))
    assert [c["source_rank"] for c in candidates] == sorted(c["source_rank"] for c in candidates)
    for row in candidates:
        assert row["financial_safety"]["status"] == "PASS"
        assert row["metrics"]["M03"]["raw"] > 0
        assert row["valuation"]["band"] == valuation_band(row["metrics"]["M03"]["percentile"])
        assert row["valuation"]["changes_rank"] is False
        assert row["research_status"] == "FINAL_RESEARCH_CANDIDATE"
        for metric_id in ("M01", "M02", "M03", "M04"):
            metric = row["metrics"][metric_id]
            assert metric["raw"] is not None
            assert metric["percentile"] is not None
            assert metric["basis"]


def _self_test() -> None:
    assert valuation_band(100) == "LOW_RELATIVE_PER"
    assert valuation_band(80) == "LOW_RELATIVE_PER"
    assert valuation_band(79.999) == "BELOW_MEDIAN_PER"
    assert valuation_band(60) == "BELOW_MEDIAN_PER"
    assert valuation_band(40) == "MID_PER"
    assert valuation_band(20) == "ABOVE_MEDIAN_PER"
    assert valuation_band(0) == "HIGH_RELATIVE_PER"

    def metric(raw: float | None, pct: float | None = 70.0) -> dict[str, Any]:
        return {"raw": raw, "percentile": pct if raw is not None else None, "reason": None, "basis": "TEST"}

    def row(rank: int, per: float, complete: bool = True) -> dict[str, Any]:
        return {
            "name": f"회사{rank}", "market": "KOSPI", "sector": "제조",
            "m01": metric(10.0 if complete else None),
            "m02": metric(15.0 if complete else None),
            "m03": metric(per if complete else None, 85.0),
            "m04": metric(20.0 if complete else None),
            "composite": 80.0 if complete else None,
            "rank": rank if complete else None,
        }

    records = {
        "000001": row(1, 11.0),
        "000002": row(2, 9.0),
        "000003": row(3, -4.0),
        "000004": row(4, 8.0, complete=False),
    }
    safety_records = {
        "000001": {"status": "PASS", "reason": "PASS", "basis": "BS", "debt_to_equity_pct": 100.0, "current_ratio_pct": 120.0, "identity_gap_pct": 0.0},
        "000002": {"status": "FAIL", "reason": "CURRENT_RATIO_UNDER_70", "basis": "BS", "debt_to_equity_pct": 100.0, "current_ratio_pct": 50.0, "identity_gap_pct": 0.0},
        "000003": {"status": "PASS", "reason": "PASS", "basis": "BS", "debt_to_equity_pct": 100.0, "current_ratio_pct": 120.0, "identity_gap_pct": 0.0},
        "000004": {"status": "PASS", "reason": "PASS", "basis": "BS", "debt_to_equity_pct": 100.0, "current_ratio_pct": 120.0, "identity_gap_pct": 0.0},
    }
    q = {
        "snapshot_date_kst": "2026-09-16", "price_cutoff_date_kst": "2026-09-15", "universe_count": 4,
        "records": records,
        "financial_safety": {"snapshot_date_kst": "2026-09-16", "policy_version": "STAGE4_BS_SAFETY_V1", "records": safety_records},
    }
    out = build_payload(q, limit=10)
    assert [c["code"] for c in out["candidates"]] == ["000001"]
    assert out["candidate_count"] == 1
    bad = json.loads(json.dumps(q))
    bad["financial_safety"]["snapshot_date_kst"] = "2026-09-15"
    try:
        build_payload(bad)
    except RuntimeError as exc:
        assert "date mismatch" in str(exc)
    else:
        raise AssertionError("date mismatch must fail closed")
    print("FINAL_CANDIDATE_V2_SELF_TEST_PASS")


def main() -> None:
    args = parse_args()
    if args.self_test:
        _self_test()
        return
    qpath = (ROOT / args.quant) if not Path(args.quant).is_absolute() else Path(args.quant)
    opath = (ROOT / args.out) if not Path(args.out).is_absolute() else Path(args.out)
    quant = json.loads(qpath.read_text(encoding="utf-8"))
    payload = build_payload(quant, limit=args.limit)
    opath.parent.mkdir(parents=True, exist_ok=True)
    opath.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("FINAL_CANDIDATE_V2_BUILD_PASS", json.dumps({
        "snapshot": payload["snapshot_date_kst"],
        "eligible": payload["eligible_count"],
        "candidates": payload["candidate_count"],
        "codes": [x["code"] for x in payload["candidates"]],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
