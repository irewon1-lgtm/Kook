#!/usr/bin/env python3
"""Collect 8-quarter KR4 operating history from official OpenDART bulk PL files.

Design:
- Download the latest 12 available quarterly PL bulk periods so the visible 8
  quarters have enough support to derive Q4 and calculate YoY comparisons.
- Prefer direct 3-month values for Q1/Q2/Q3.
- When a direct 3-month value is unavailable, derive the quarter from cumulative
  values only when the current and previous cumulative records use the same
  CFS/OFS scope.
- Q4 is annual FY minus Q3 cumulative on the same scope.
- Financial/insurance sectors keep revenue history, but operating margin is
  intentionally omitted because KR4 treats banking/insurance with dedicated KPIs.
- Missing or ambiguous values stay null with an explicit reason. No estimates.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import re
import time
import zipfile
from collections import defaultdict
from datetime import date, datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests

import collect_real_quant as base

KST = ZoneInfo("Asia/Seoul")
PERIOD_TO_Q = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}
Q_TO_PERIOD = {v: k for k, v in PERIOD_TO_Q.items()}
VISIBLE_QUARTERS = 8
SUPPORT_QUARTERS = 12


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--as-of", default="auto")
    p.add_argument("--out", default="evidence/quarterly_history.json")
    p.add_argument("--visible-quarters", type=int, default=VISIBLE_QUARTERS)
    p.add_argument("--support-quarters", type=int, default=SUPPORT_QUARTERS)
    return p.parse_args()


def resolved_as_of(value: str) -> date:
    return datetime.now(KST).date() if value == "auto" else date.fromisoformat(value)


def quarter_index(year: int, quarter: int) -> int:
    return year * 4 + quarter


def period_label(year: int, quarter: int) -> str:
    return f"{year}Q{quarter}"


def list_pl_entries(as_of: date) -> list[dict[str, Any]]:
    s = requests.Session()
    s.headers.update(base.DART_HEADERS)
    s.get(base.DART_BASE, timeout=30).raise_for_status()
    r = s.post(base.DART_LIST, timeout=60)
    r.raise_for_status()
    raw = base.DART_ENTRY_RE.findall(r.text)

    by_key: dict[tuple[int, int], dict[str, Any]] = {}
    for year_s, period, statement, fname in raw:
        if statement != "PL" or period not in PERIOD_TO_Q:
            continue
        year = int(year_s)
        quarter = PERIOD_TO_Q[period]
        if year > as_of.year:
            continue
        key = (year, quarter)
        candidate = {
            "year": year,
            "quarter": quarter,
            "period": period,
            "file": fname,
            "index": quarter_index(year, quarter),
        }
        # The page occasionally contains repeated references. Same quarter with
        # different filenames is treated deterministically by filename so runs
        # remain reproducible rather than depending on HTML order.
        prev = by_key.get(key)
        if prev is None or fname > prev["file"]:
            by_key[key] = candidate

    entries = sorted(by_key.values(), key=lambda x: x["index"])
    if not entries:
        raise RuntimeError("no OpenDART quarterly PL entries found")
    return entries


def download_zip(session: requests.Session, entry: dict[str, Any]) -> bytes:
    r = session.get(base.DART_DOWNLOAD, params={"fl_nm": entry["file"]}, timeout=300)
    r.raise_for_status()
    if r.content[:2] != b"PK":
        raise RuntimeError(f"OpenDART bulk response is not ZIP: {entry['file']}")
    return r.content


def mode_key(row: dict[str, str] | None, mode: str) -> tuple[str, float] | None:
    if row is None:
        return None
    candidates: list[tuple[int, str, float]] = []
    for key in row:
        if not key.startswith("당기"):
            continue
        value = base.amount(row, key)
        if value is None:
            continue
        compact = key.replace(" ", "")
        if mode == "DIRECT":
            if "3개월" not in compact:
                continue
            score = 300
        elif mode == "YTD":
            if "누적" not in compact:
                continue
            score = 200
        elif mode == "ANNUAL":
            if "3개월" in compact or "누적" in compact:
                continue
            score = 250 if compact == "당기" else 100
        else:
            raise ValueError(mode)
        candidates.append((score, key, value))
    if not candidates:
        return None
    candidates.sort(key=lambda x: (-x[0], x[1]))
    _, key, value = candidates[0]
    return key, value


def parse_period(
    issuers: list[base.Issuer],
    entry: dict[str, Any],
    zip_bytes: bytes,
) -> dict[str, dict[str, Any]]:
    universe = {i.code: i for i in issuers}
    groups: dict[str, dict[str, list[dict[str, str]]]] = defaultdict(lambda: defaultdict(list))
    zf = zipfile.ZipFile(io.BytesIO(zip_bytes))
    for member in zf.namelist():
        raw = zf.read(member)
        text = raw.decode("cp949")
        reader = csv.DictReader(io.StringIO(text), delimiter="\t")
        if not reader.fieldnames:
            continue
        reader.fieldnames = [x.strip() for x in reader.fieldnames]
        for row0 in reader:
            row = {(k or "").strip(): (v or "").strip() for k, v in row0.items()}
            code = re.sub(r"[^0-9A-Z]", "", (row.get("종목코드") or "").upper())
            if code not in universe:
                continue
            statement = row.get("재무제표종류") or ""
            groups[code][statement].append(row)

    out: dict[str, dict[str, Any]] = {}
    q = int(entry["quarter"])
    for issuer in issuers:
        candidates: list[tuple[int, str, dict[str, str], dict[str, str] | None]] = []
        for statement, rows in groups.get(issuer.code, {}).items():
            rev = base.best_row(rows, base.revenue_score)
            if rev is None:
                continue
            op = base.best_row(rows, base.operating_score)
            score = 0
            if "연결" in statement:
                score += 10000
            if "손익계산서" in statement:
                score += 1000
            if mode_key(rev, "DIRECT") is not None:
                score += 200
            if mode_key(rev, "YTD") is not None:
                score += 100
            if q == 4 and mode_key(rev, "ANNUAL") is not None:
                score += 120
            if op is not None:
                score += 20
            candidates.append((score, statement, rev, op))

        if not candidates:
            out[issuer.code] = {
                "scope": "",
                "direct_rev": None,
                "direct_op": None,
                "cum_rev": None,
                "cum_op": None,
                "source_file": entry["file"],
                "reason": "DART_NO_REVENUE",
            }
            continue

        candidates.sort(key=lambda x: (-x[0], x[1]))
        _, statement, rev, op = candidates[0]
        scope = "CFS" if "연결" in statement else "OFS"
        direct_rev_hit = mode_key(rev, "DIRECT")
        direct_op_hit = mode_key(op, "DIRECT")
        ytd_rev_hit = mode_key(rev, "YTD")
        ytd_op_hit = mode_key(op, "YTD")
        annual_rev_hit = mode_key(rev, "ANNUAL")
        annual_op_hit = mode_key(op, "ANNUAL")

        direct_rev = direct_rev_hit[1] if direct_rev_hit else None
        direct_op = direct_op_hit[1] if direct_op_hit else None
        if q == 4:
            cum_rev = annual_rev_hit[1] if annual_rev_hit else None
            cum_op = annual_op_hit[1] if annual_op_hit else None
            cum_basis = annual_rev_hit[0] if annual_rev_hit else ""
        else:
            # Q1 is one quarter, so a direct 3M value is also a valid cumulative
            # fallback if the filing does not separately expose a 누적 column.
            cum_rev = ytd_rev_hit[1] if ytd_rev_hit else (direct_rev if q == 1 else None)
            cum_op = ytd_op_hit[1] if ytd_op_hit else (direct_op if q == 1 else None)
            cum_basis = ytd_rev_hit[0] if ytd_rev_hit else (direct_rev_hit[0] if q == 1 and direct_rev_hit else "")

        out[issuer.code] = {
            "scope": scope,
            "direct_rev": direct_rev,
            "direct_op": direct_op,
            "cum_rev": cum_rev,
            "cum_op": cum_op,
            "direct_basis": direct_rev_hit[0] if direct_rev_hit else "",
            "cum_basis": cum_basis,
            "source_file": entry["file"],
            "reason": None,
        }
    return out


def finite_or_none(value: float | None) -> float | None:
    if value is None or not math.isfinite(value):
        return None
    return value


def safe_pct(numer: float | None, denom: float | None) -> float | None:
    if numer is None or denom is None or denom <= 0:
        return None
    value = (numer / denom - 1.0) * 100.0
    if not math.isfinite(value) or abs(value) > 100000:
        return None
    return round(value, 6)


def derive_visible_history(
    issuers: list[base.Issuer],
    support_entries: list[dict[str, Any]],
    parsed: dict[str, dict[str, dict[str, Any]]],
    visible_quarters: int,
) -> dict[str, dict[str, Any]]:
    support_labels = [period_label(int(e["year"]), int(e["quarter"])) for e in support_entries]
    visible_entries = support_entries[-visible_quarters:]
    visible_labels = [period_label(int(e["year"]), int(e["quarter"])) for e in visible_entries]
    entry_by_label = {period_label(int(e["year"]), int(e["quarter"])): e for e in support_entries}

    records: dict[str, dict[str, Any]] = {}
    for issuer in issuers:
        discrete: dict[str, dict[str, Any]] = {}
        for label in support_labels:
            entry = entry_by_label[label]
            q = int(entry["quarter"])
            year = int(entry["year"])
            snap = parsed[label][issuer.code]
            scope = snap["scope"]
            revenue = finite_or_none(snap["direct_rev"])
            op_income = finite_or_none(snap["direct_op"])
            basis = "DIRECT_3M" if revenue is not None else ""
            reason = snap.get("reason")

            if revenue is None:
                current_cum = finite_or_none(snap["cum_rev"])
                current_op_cum = finite_or_none(snap["cum_op"])
                if q == 1 and current_cum is not None:
                    revenue = current_cum
                    op_income = current_op_cum
                    basis = "Q1_CUMULATIVE"
                elif q > 1 and current_cum is not None:
                    prev_q = q - 1
                    prev_label = period_label(year, prev_q)
                    prev = parsed.get(prev_label, {}).get(issuer.code)
                    if prev is None:
                        reason = "PREVIOUS_CUMULATIVE_PERIOD_MISSING"
                    elif not scope or scope != prev.get("scope"):
                        reason = "CFS_OFS_SCOPE_MISMATCH"
                    else:
                        prev_cum = finite_or_none(prev.get("cum_rev"))
                        prev_op_cum = finite_or_none(prev.get("cum_op"))
                        if prev_cum is None:
                            reason = "PREVIOUS_CUMULATIVE_VALUE_MISSING"
                        else:
                            revenue = current_cum - prev_cum
                            op_income = (
                                current_op_cum - prev_op_cum
                                if current_op_cum is not None and prev_op_cum is not None
                                else None
                            )
                            basis = "FY_MINUS_Q3" if q == 4 else "CUMULATIVE_DELTA"
                elif reason is None:
                    reason = "NO_DIRECT_OR_DERIVABLE_QUARTER_VALUE"

            if revenue is not None and (not math.isfinite(revenue) or revenue <= -1e18 or revenue >= 1e18):
                revenue = None
                op_income = None
                basis = ""
                reason = "QUARTER_VALUE_OUTLIER_GUARD"

            if base.is_financial(issuer):
                op_income = None
                op_margin = None
                op_reason = "FINANCIAL_SECTOR_DEDICATED_KPI_REQUIRED"
            else:
                op_margin = None
                if revenue not in (None, 0.0) and op_income is not None:
                    candidate_margin = op_income / revenue * 100.0
                    if math.isfinite(candidate_margin) and abs(candidate_margin) <= 10000:
                        op_margin = round(candidate_margin, 6)
                op_reason = None if op_margin is not None else "OPERATING_MARGIN_UNAVAILABLE"

            discrete[label] = {
                "period": label,
                "fiscal_year": year,
                "quarter": q,
                "revenue": round(revenue, 2) if revenue is not None else None,
                "operating_income": round(op_income, 2) if op_income is not None else None,
                "operating_margin": op_margin,
                "scope": scope,
                "basis": basis,
                "source_file": snap.get("source_file", ""),
                "reason": reason or op_reason,
            }

        points = []
        for label in visible_labels:
            point = dict(discrete[label])
            year = int(point["fiscal_year"])
            q = int(point["quarter"])
            yoy = discrete.get(period_label(year - 1, q))
            prev_index = quarter_index(year, q) - 1
            prev_year, prev_q0 = divmod(prev_index - 1, 4)
            prev_label = period_label(prev_year, prev_q0 + 1)
            prev = discrete.get(prev_label)
            point["revenue_yoy"] = safe_pct(
                point["revenue"],
                yoy.get("revenue") if yoy else None,
            )
            point["revenue_qoq"] = safe_pct(
                point["revenue"],
                prev.get("revenue") if prev else None,
            )
            points.append(point)

        records[issuer.code] = {
            "name": issuer.name,
            "market": issuer.market,
            "sector": issuer.sector,
            "is_financial": base.is_financial(issuer),
            "points": points,
        }
    return records


def coverage(records: dict[str, dict[str, Any]]) -> dict[str, int]:
    counts = [sum(1 for p in r["points"] if p["revenue"] is not None) for r in records.values()]
    margin_counts = [sum(1 for p in r["points"] if p["operating_margin"] is not None) for r in records.values()]
    return {
        "issuer_count": len(records),
        "stocks_with_1q": sum(c >= 1 for c in counts),
        "stocks_with_4q": sum(c >= 4 for c in counts),
        "stocks_with_8q": sum(c >= 8 for c in counts),
        "stocks_with_4q_margin": sum(c >= 4 for c in margin_counts),
        "stocks_with_8q_margin": sum(c >= 8 for c in margin_counts),
    }


def main() -> None:
    args = parse_args()
    as_of = resolved_as_of(args.as_of)
    if args.visible_quarters < 4 or args.visible_quarters > 8:
        raise SystemExit("visible quarters must be between 4 and 8")
    if args.support_quarters < args.visible_quarters + 4:
        raise SystemExit("support quarters must cover visible window + one YoY year")

    issuers = base.load_issuers()
    entries = list_pl_entries(as_of)
    support = entries[-args.support_quarters:]
    if len(support) < args.support_quarters:
        raise RuntimeError(f"insufficient DART quarterly support periods: {len(support)}")

    session = requests.Session()
    session.headers.update(base.DART_HEADERS)
    session.get(base.DART_BASE, timeout=30).raise_for_status()
    parsed: dict[str, dict[str, dict[str, Any]]] = {}
    proof = []

    for i, entry in enumerate(support, 1):
        label = period_label(int(entry["year"]), int(entry["quarter"]))
        started = time.time()
        zip_bytes = download_zip(session, entry)
        parsed[label] = parse_period(issuers, entry, zip_bytes)
        proof.append({
            "period": label,
            "period_code": entry["period"],
            "file": entry["file"],
            "zip_bytes": len(zip_bytes),
            "seconds": round(time.time() - started, 3),
        })
        print(f"QUARTERLY_PERIOD_PARSED {i}/{len(support)} {label} {entry['file']} bytes={len(zip_bytes)}", flush=True)

    records = derive_visible_history(issuers, support, parsed, args.visible_quarters)
    cov = coverage(records)
    visible = [period_label(int(e["year"]), int(e["quarter"])) for e in support[-args.visible_quarters:]]
    doc = {
        "schema_version": 1,
        "generated_at_kst": datetime.now(KST).isoformat(timespec="seconds"),
        "as_of_date_kst": as_of.isoformat(),
        "source": "OpenDART financial-information bulk PL",
        "policy": {
            "visible_quarters": args.visible_quarters,
            "support_quarters": args.support_quarters,
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
        "QUARTERLY_HISTORY_PASS",
        json.dumps({"periods": visible, "coverage": cov, "output": str(out)}, ensure_ascii=False),
        flush=True,
    )


if __name__ == "__main__":
    main()
