#!/usr/bin/env python3
"""Build stage-6/7 KR4 final research candidates from the validated quant snapshot.

The builder is intentionally conservative. It does not create a new score or guess
missing values. It reuses the already-validated KR4 composite rank, excludes rows
with incomplete metrics and non-positive trailing PER, then persists the top N in
a stable JSON schema for audit/app handoff.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import collect_real_quant as base

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_VERSION = "KR4_FINAL_CANDIDATE_V1"
POLICY_VERSION = "STAGE6_POSITIVE_PER_COMPLETE_TOP_RANK_V1"
DEFAULT_LIMIT = 10


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--out", default="evidence/final_candidates.json")
    p.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    p.add_argument("--self-test", action="store_true")
    return p.parse_args()


def _metric_snapshot(metric_id: str, metric: dict[str, Any], as_of: str) -> dict[str, Any]:
    raw = metric.get("raw")
    pct = metric.get("percentile")
    assert raw is not None and pct is not None, (metric_id, metric)
    return {
        "id": metric_id.upper(),
        "raw": raw,
        "percentile": pct,
        "basis": metric.get("basis") or "",
        "as_of_date": as_of,
    }


def _eligible(row: dict[str, Any]) -> bool:
    metrics = [row.get(f"m0{i}") or {} for i in range(1, 5)]
    if any(m.get("raw") is None or m.get("percentile") is None for m in metrics):
        return False
    if row.get("composite") is None or row.get("rank") is None:
        return False
    if float(metrics[2]["raw"]) <= 0.0:
        return False
    return True


def build_payload(
    quant: dict[str, Any],
    sectors_by_code: dict[str, str],
    limit: int = DEFAULT_LIMIT,
) -> dict[str, Any]:
    if not 1 <= limit <= 100:
        raise ValueError("limit must be between 1 and 100")

    records = quant["records"]
    ranked: list[tuple[str, dict[str, Any]]] = [
        (code, row) for code, row in records.items() if _eligible(row)
    ]
    ranked.sort(key=lambda item: (int(item[1]["rank"]), item[0]))
    selected = ranked[:limit]

    financial_date = quant["snapshot_date_kst"]
    price_date = quant["price_cutoff_date_kst"]
    candidates: list[dict[str, Any]] = []
    for final_rank, (code, row) in enumerate(selected, 1):
        flags = [
            "FOUR_METRICS_COMPLETE",
            "POSITIVE_TRAILING_PER",
            "EXISTING_COMPOSITE_RANK_REUSED",
        ]
        if float(row["m01"]["raw"]) > 0:
            flags.append("POSITIVE_REVENUE_GROWTH")
        if float(row["m02"]["raw"]) > 0:
            flags.append("POSITIVE_OPERATING_MARGIN")
        if float(row["m04"]["raw"]) > 0:
            flags.append("POSITIVE_6M_MOMENTUM")

        candidates.append(
            {
                "schema_version": SCHEMA_VERSION,
                "policy_version": POLICY_VERSION,
                "candidate_rank": final_rank,
                "source_rank": int(row["rank"]),
                "code": code,
                "name": row["name"],
                "market": row["market"],
                "sector": sectors_by_code.get(code, "기타"),
                "composite_score": row["composite"],
                "financial_snapshot_date": financial_date,
                "price_cutoff_date": price_date,
                "metrics": {
                    "M01": _metric_snapshot("m01", row["m01"], financial_date),
                    "M02": _metric_snapshot("m02", row["m02"], financial_date),
                    "M03": _metric_snapshot("m03", row["m03"], price_date),
                    "M04": _metric_snapshot("m04", row["m04"], price_date),
                },
                "selection_flags": flags,
                "research_status": "FINAL_RESEARCH_CANDIDATE",
                "selection_reason_ko": (
                    f"4지표 검증 완료 · 실적 PER 양수 · 기존 종합순위 {int(row['rank'])}위 기준 최종 조사 후보"
                ),
            }
        )

    payload = {
        "schema_version": SCHEMA_VERSION,
        "policy_version": POLICY_VERSION,
        "generated_from": "real_quant_snapshot.json",
        "snapshot_date_kst": financial_date,
        "price_cutoff_date_kst": price_date,
        "universe_count": quant["universe_count"],
        "eligible_count": len(ranked),
        "candidate_limit": limit,
        "candidate_count": len(candidates),
        "selection_policy": {
            "purpose": "research_priority_only",
            "requires_four_validated_metrics": True,
            "requires_existing_composite_rank": True,
            "requires_positive_trailing_per": True,
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
    assert payload["candidate_count"] == len(candidates)
    assert len({c["code"] for c in candidates}) == len(candidates)
    assert [c["candidate_rank"] for c in candidates] == list(range(1, len(candidates) + 1))
    assert [c["source_rank"] for c in candidates] == sorted(c["source_rank"] for c in candidates)
    for row in candidates:
        assert row["metrics"]["M03"]["raw"] > 0
        assert row["research_status"] == "FINAL_RESEARCH_CANDIDATE"
        assert row["schema_version"] == SCHEMA_VERSION
        assert row["policy_version"] == POLICY_VERSION
        for metric_id in ("M01", "M02", "M03", "M04"):
            metric = row["metrics"][metric_id]
            assert metric["raw"] is not None
            assert metric["percentile"] is not None


def _self_test() -> None:
    def row(rank: int | None, per: float | None, complete: bool = True) -> dict[str, Any]:
        def metric(raw: float | None) -> dict[str, Any]:
            return {
                "raw": raw,
                "percentile": 70.0 if raw is not None else None,
                "reason": None if raw is not None else "TEST_MISSING",
                "basis": "TEST",
            }
        return {
            "name": f"회사{rank}",
            "market": "KOSPI",
            "m01": metric(10.0 if complete else None),
            "m02": metric(15.0 if complete else None),
            "m03": metric(per if complete else None),
            "m04": metric(20.0 if complete else None),
            "composite": 80.0 if complete else None,
            "rank": rank if complete else None,
        }

    q = {
        "snapshot_date_kst": "2026-09-15",
        "price_cutoff_date_kst": "2026-09-15",
        "universe_count": 4,
        "records": {
            "000003": row(3, 9.0),
            "000001": row(1, 11.0),
            "000002": row(2, -3.0),
            "000004": row(None, None, complete=False),
        },
    }
    out = build_payload(q, {"000001": "A", "000003": "B"}, limit=2)
    assert [x["code"] for x in out["candidates"]] == ["000001", "000003"]
    assert out["eligible_count"] == 2
    print("FINAL_CANDIDATE_SELF_TEST_PASS", json.dumps(out, ensure_ascii=False))


def main() -> None:
    args = parse_args()
    if args.self_test:
        _self_test()
        return

    quant_path = (ROOT / args.quant).resolve() if not Path(args.quant).is_absolute() else Path(args.quant)
    out_path = (ROOT / args.out).resolve() if not Path(args.out).is_absolute() else Path(args.out)
    quant = json.loads(quant_path.read_text(encoding="utf-8"))
    issuers = base.load_issuers()
    sectors = {issuer.code: issuer.sector for issuer in issuers}
    payload = build_payload(quant, sectors, limit=args.limit)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "FINAL_CANDIDATE_BUILD_PASS",
        json.dumps(
            {
                "snapshot": payload["snapshot_date_kst"],
                "eligible": payload["eligible_count"],
                "candidates": payload["candidate_count"],
                "codes": [x["code"] for x in payload["candidates"]],
            },
            ensure_ascii=False,
        ),
    )


if __name__ == "__main__":
    main()
