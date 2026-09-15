#!/usr/bin/env python3
"""Parallel runner for the fail-closed OpenDART quarterly history collector.

It reuses every parsing/derivation rule from collect_quarterly_history.py while
processing independent DART period ZIPs concurrently. Output schema and gates are
identical; only wall-clock collection time changes.
"""
from __future__ import annotations

import argparse
import json
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from typing import Any

import requests

import collect_quarterly_history as core
import collect_real_quant as base


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--as-of", default="auto")
    p.add_argument("--out", default="evidence/quarterly_history.json")
    p.add_argument("--visible-quarters", type=int, default=core.VISIBLE_QUARTERS)
    p.add_argument("--support-quarters", type=int, default=core.SUPPORT_QUARTERS)
    p.add_argument("--period-workers", type=int, default=3)
    return p.parse_args()


def collect_one(entry: dict[str, Any], issuers: list[base.Issuer]) -> tuple[str, dict[str, dict[str, Any]], dict[str, Any]]:
    session = requests.Session()
    session.headers.update(base.DART_HEADERS)
    session.get(base.DART_BASE, timeout=30).raise_for_status()
    label = core.period_label(int(entry["year"]), int(entry["quarter"]))
    started = time.time()
    zip_bytes = core.download_zip(session, entry)
    parsed = core.parse_period(issuers, entry, zip_bytes)
    proof = {
        "period": label,
        "period_code": entry["period"],
        "file": entry["file"],
        "zip_bytes": len(zip_bytes),
        "seconds": round(time.time() - started, 3),
    }
    return label, parsed, proof


def main() -> None:
    args = parse_args()
    as_of = core.resolved_as_of(args.as_of)
    if args.visible_quarters < 4 or args.visible_quarters > 8:
        raise SystemExit("visible quarters must be between 4 and 8")
    if args.support_quarters < args.visible_quarters + 4:
        raise SystemExit("support quarters must cover visible window + one YoY year")
    if args.period_workers < 1 or args.period_workers > 4:
        raise SystemExit("period workers must be between 1 and 4")

    issuers = base.load_issuers()
    entries = core.list_pl_entries(as_of)
    support = entries[-args.support_quarters:]
    if len(support) < args.support_quarters:
        raise RuntimeError(f"insufficient DART quarterly support periods: {len(support)}")
    core.assert_contiguous_entries(support, args.support_quarters)

    parsed: dict[str, dict[str, dict[str, Any]]] = {}
    proofs: dict[str, dict[str, Any]] = {}
    with ThreadPoolExecutor(max_workers=args.period_workers) as pool:
        futures = {pool.submit(collect_one, entry, issuers): entry for entry in support}
        completed = 0
        for future in as_completed(futures):
            entry = futures[future]
            label = core.period_label(int(entry["year"]), int(entry["quarter"]))
            try:
                result_label, rows, proof = future.result()
            except Exception as exc:
                raise RuntimeError(f"quarterly period failed {label}: {type(exc).__name__}: {exc}") from exc
            parsed[result_label] = rows
            proofs[result_label] = proof
            completed += 1
            print(
                f"QUARTERLY_PERIOD_PARSED {completed}/{len(support)} {result_label} {proof['file']} bytes={proof['zip_bytes']}",
                flush=True,
            )

    support_labels = [core.period_label(int(e["year"]), int(e["quarter"])) for e in support]
    proof = [proofs[label] for label in support_labels]
    records = core.derive_visible_history(issuers, support, parsed, args.visible_quarters)
    cov = core.coverage(records)
    visible = [core.period_label(int(e["year"]), int(e["quarter"])) for e in support[-args.visible_quarters:]]
    doc = {
        "schema_version": 1,
        "generated_at_kst": datetime.now(core.KST).isoformat(timespec="seconds"),
        "as_of_date_kst": as_of.isoformat(),
        "source": "OpenDART financial-information bulk PL",
        "policy": {
            "visible_quarters": args.visible_quarters,
            "support_quarters": args.support_quarters,
            "period_workers": args.period_workers,
            "direct_quarter_policy": "Q1/Q2/Q3 direct 3-month preferred",
            "fallback_policy": "same-scope cumulative delta only; Q4 = FY annual - Q3 cumulative",
            "scope_policy": "CFS preferred; CFS/OFS mismatch fails closed",
            "financial_policy": "revenue retained; operating margin omitted in favor of financial-sector dedicated KPIs",
        },
        "target_periods": visible,
        "coverage": cov,
        "download_proof": proof,
        "records": records,
    }

    out = Path(args.out)
    if not out.is_absolute():
        out = base.ROOT / out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(
        "QUARTERLY_HISTORY_PARALLEL_PASS",
        json.dumps({"periods": visible, "coverage": cov, "workers": args.period_workers, "output": str(out)}, ensure_ascii=False),
        flush=True,
    )


if __name__ == "__main__":
    main()
