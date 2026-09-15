#!/usr/bin/env python3
"""Production-grade KR4 automatic collector.

Key guarantees:
- KST snapshot date is resolved automatically.
- The price cutoff is the latest *completed* Korean trading session, resolved
  from multiple liquid Naver symbols with quorum instead of assuming weekdays.
- The newest available OpenDART PL bulk file is selected dynamically across
  Q1/HY/Q3/FY periods.
- DART period columns are discovered by semantics (당기/전기, 3개월/누적/연간)
  instead of being frozen to 2026 H1 headers.
- Generated Kotlin keeps a bundled fallback but exposes a guarded runtime
  install hook so an already-installed APK can adopt a newer validated JSON
  snapshot without reinstalling the APK.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import re
import statistics
import sys
import time
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time as dtime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests

import collect_real_quant as base
import collect_real_quant_v2 as v2

KST = ZoneInfo("Asia/Seoul")
DART_PERIOD_RANK = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}
DART_PERIOD_LABEL = {"Q1": "1분기", "HY": "반기", "Q3": "3분기", "FY": "사업연도"}
PRICE_PROBES = ("005930", "000660", "035420")
_SELECTED_DART_YEAR: str | None = None
_SELECTED_DART_PERIOD: str | None = None
_SELECTED_DART_FILE: str | None = None


def _kst_now() -> datetime:
    return datetime.now(tz=KST)


def _latest_allowed_bar_date(now: datetime) -> date:
    today = now.date()
    # During a normal weekday session, a same-day daily bar can still represent
    # an incomplete session. Before 16:00 KST, never treat today as completed.
    if now.weekday() < 5 and now.time() < dtime(16, 0):
        return today - timedelta(days=1)
    return today


def resolve_price_cutoff(as_of: date, now: datetime | None = None) -> date:
    now = now or _kst_now()
    latest_allowed = min(as_of, _latest_allowed_bar_date(now))
    votes: list[date] = []
    probe_errors: list[str] = []
    for code in PRICE_PROBES:
        try:
            bars = v2.get_json(f"{base.NAVER_BASE}/{code}/price?pageSize=10&page=1")
            if not isinstance(bars, list):
                raise ValueError("PRICE_NOT_LIST")
            ds = []
            for bar in bars:
                if not isinstance(bar, dict):
                    continue
                d = base.parse_date(str(bar.get("localTradedAt") or ""))
                p = base.parse_number(bar.get("closePrice"))
                if d and p is not None and p > 0 and d <= latest_allowed:
                    ds.append(d)
            if not ds:
                raise ValueError("NO_COMPLETED_BAR")
            votes.append(max(ds))
        except Exception as exc:
            probe_errors.append(f"{code}:{type(exc).__name__}:{exc}")

    if len(votes) < 2:
        raise RuntimeError(f"price-cutoff quorum failed votes={votes} errors={probe_errors}")
    counts = Counter(votes)
    cutoff, count = counts.most_common(1)[0]
    if count < 2:
        raise RuntimeError(f"price-cutoff probes disagree votes={votes} errors={probe_errors}")
    if cutoff > as_of:
        raise RuntimeError(f"resolved price cutoff {cutoff} is after snapshot date {as_of}")
    if (as_of - cutoff).days > 10:
        raise RuntimeError(f"resolved price cutoff is stale: as_of={as_of} cutoff={cutoff}")
    return cutoff


def parse_args_auto() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--as-of", default="auto")
    p.add_argument("--price-cutoff", default="auto")
    p.add_argument("--workers", type=int, default=14)
    p.add_argument(
        "--kotlin-out",
        default="app/src/main/java/com/krstock/v3/data/generated/GeneratedRealQuantSnapshot.kt",
    )
    p.add_argument("--evidence-out", default="evidence/real_quant_snapshot.json")
    ns = p.parse_args()
    now = _kst_now()
    as_of = now.date() if ns.as_of == "auto" else date.fromisoformat(ns.as_of)
    cutoff = resolve_price_cutoff(as_of, now) if ns.price_cutoff == "auto" else date.fromisoformat(ns.price_cutoff)
    if cutoff > as_of:
        raise SystemExit("price cutoff cannot be after snapshot date")
    ns.as_of = as_of.isoformat()
    ns.price_cutoff = cutoff.isoformat()
    print(f"AUTO_DATE_RESOLVED as_of={ns.as_of} price_cutoff={ns.price_cutoff} kst={now.isoformat()}", flush=True)
    return ns


def _select_latest_dart_entry(entries: list[tuple[str, str, str, str]], as_of: date) -> tuple[str, str, str]:
    candidates = []
    for year, period, statement, fname in entries:
        if statement != "PL" or period not in DART_PERIOD_RANK:
            continue
        y = int(year)
        if y > as_of.year:
            continue
        candidates.append((y, DART_PERIOD_RANK[period], year, period, fname))
    if not candidates:
        raise RuntimeError("no OpenDART PL bulk entries found")
    _, _, year, period, fname = max(candidates)
    return year, period, fname


def download_latest_dart_pl() -> tuple[str, bytes]:
    global _SELECTED_DART_YEAR, _SELECTED_DART_PERIOD, _SELECTED_DART_FILE
    s = requests.Session()
    s.headers.update(base.DART_HEADERS)
    s.get(base.DART_BASE, timeout=30).raise_for_status()
    r = s.post(base.DART_LIST, timeout=60)
    r.raise_for_status()
    entries = base.DART_ENTRY_RE.findall(r.text)
    as_of = date.fromisoformat(parse_args_auto_cached().as_of)
    year, period, fname = _select_latest_dart_entry(entries, as_of)
    _SELECTED_DART_YEAR = year
    _SELECTED_DART_PERIOD = period
    _SELECTED_DART_FILE = fname
    r = s.get(base.DART_DOWNLOAD, params={"fl_nm": fname}, timeout=300)
    r.raise_for_status()
    if r.content[:2] != b"PK":
        raise RuntimeError(f"OpenDART bulk response was not ZIP: {r.text[:300]!r}")
    print(f"DART_PERIOD_RESOLVED year={year} period={period} label={DART_PERIOD_LABEL[period]} file={fname}", flush=True)
    return fname, r.content


_ARGS_CACHE: argparse.Namespace | None = None


def parse_args_auto_cached() -> argparse.Namespace:
    global _ARGS_CACHE
    if _ARGS_CACHE is None:
        _ARGS_CACHE = parse_args_auto()
    return _ARGS_CACHE


def _period_kind(key: str) -> str:
    if "3개월" in key:
        return "3M"
    if "누적" in key:
        return "YTD"
    return "ANNUAL"


def _period_score(key: str) -> int:
    kind = _period_kind(key)
    if kind == "3M":
        return 300
    if kind == "YTD":
        return 200
    if key.strip() == "당기":
        return 180
    return 100


def _revenue_period_candidates(row: dict[str, str] | None) -> list[tuple[int, str, str, float, float | None]]:
    if row is None:
        return []
    out = []
    for key in row:
        if not key.startswith("당기"):
            continue
        cur = base.amount(row, key)
        if cur is None:
            continue
        suffix = key[len("당기"):]
        prior_key = "전기" + suffix
        prior = base.amount(row, prior_key) if prior_key in row else None
        out.append((_period_score(key), key, prior_key, cur, prior))
    out.sort(key=lambda x: (-x[0], x[1]))
    return out


def parse_dart_metrics_dynamic(issuers: list[base.Issuer], zip_bytes: bytes) -> dict[str, dict[str, Any]]:
    year = _SELECTED_DART_YEAR
    period = _SELECTED_DART_PERIOD
    if not year or not period:
        raise RuntimeError("DART period metadata not initialized")

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
            pc = _revenue_period_candidates(rev)
            if pc:
                score += pc[0][0]
            if op is not None:
                score += 20
            candidates.append((score, statement, rev, op))

        if not candidates:
            out[issuer.code] = {
                "m01_raw": None,
                "m01_reason": "DART_NO_REVENUE",
                "m01_basis": "",
                "m02_raw": None,
                "m02_reason": "FINANCIAL_SECTOR_EXCLUDED" if base.is_financial(issuer) else "DART_NO_OPERATING_INCOME",
                "m02_basis": "",
                "dart_statement": "",
            }
            continue

        candidates.sort(key=lambda x: (-x[0], x[1]))
        _, statement, rev, op = candidates[0]
        rev_periods = _revenue_period_candidates(rev)

        m01 = None
        m01_reason = None
        m01_basis = ""
        for _, cur_key, _prior_key, cur, prior in rev_periods:
            if prior is not None and prior > 0:
                m01 = (cur / prior - 1.0) * 100.0
                m01_basis = f"{year}{period}_{_period_kind(cur_key)}"
                break
        if m01 is None:
            m01_reason = "DART_NO_COMPARABLE_PRIOR_REVENUE"
        if m01 is not None and (not math.isfinite(m01) or abs(m01) > 100000):
            m01 = None
            m01_reason = "DART_REVENUE_GROWTH_OUTLIER_GUARD"
            m01_basis = ""

        m02 = None
        m02_reason = None
        m02_basis = ""
        if base.is_financial(issuer):
            m02_reason = "FINANCIAL_SECTOR_EXCLUDED"
        elif op is None:
            m02_reason = "DART_NO_OPERATING_INCOME"
        else:
            for _, cur_key, _prior_key, cur_rev, _prior in rev_periods:
                opv = base.amount(op, cur_key)
                if cur_rev != 0 and opv is not None:
                    m02 = opv / cur_rev * 100.0
                    m02_basis = f"{year}{period}_{_period_kind(cur_key)}"
                    break
            if m02 is None:
                m02_reason = "DART_NO_COMPARABLE_OPERATING_MARGIN"
        if m02 is not None and (not math.isfinite(m02) or abs(m02) > 10000):
            m02 = None
            m02_reason = "DART_OPERATING_MARGIN_OUTLIER_GUARD"
            m02_basis = ""

        scope = "CFS" if "연결" in statement else "OFS"
        out[issuer.code] = {
            "m01_raw": round(m01, 6) if m01 is not None else None,
            "m01_reason": m01_reason,
            "m01_basis": f"{m01_basis}_{scope}" if m01_basis else "",
            "m02_raw": round(m02, 6) if m02 is not None else None,
            "m02_reason": m02_reason,
            "m02_basis": f"{m02_basis}_{scope}" if m02_basis else "",
            "dart_statement": statement,
        }
    return out


def write_kotlin_runtime_capable(records: dict[str, dict[str, Any]], out_path: Path, meta: dict[str, Any]) -> None:
    items = sorted(records.items())
    chunk_size = 70
    c = meta["coverage"]
    lines = [
        "package com.krstock.v3.data.generated",
        "",
        "import com.krstock.v3.data.model.RealQuantRecord",
        "",
        "/** AUTO-GENERATED. Bundled fallback + guarded runtime snapshot override. */",
        "object GeneratedRealQuantSnapshot {",
        f'    const val bundledSnapshotDate: String = "{meta["snapshot_date"]}"',
        f'    const val bundledPriceCutoffDate: String = "{meta["price_cutoff"]}"',
        f'    const val bundledDartFileName: String = "{meta["dart_file_name"]}"',
        f"    const val universeCount: Int = {len(items)}",
        f"    const val bundledM01Available: Int = {c['m01_available']}",
        f"    const val bundledM02Available: Int = {c['m02_available']}",
        f"    const val bundledM03Available: Int = {c['m03_available']}",
        f"    const val bundledM04Available: Int = {c['m04_available']}",
        f"    const val bundledCompleteCount: Int = {c['complete_count']}",
        "",
        "    @Volatile var snapshotDate: String = bundledSnapshotDate",
        "        private set",
        "    @Volatile var priceCutoffDate: String = bundledPriceCutoffDate",
        "        private set",
        "    @Volatile var dartFileName: String = bundledDartFileName",
        "        private set",
        "    @Volatile var m01Available: Int = bundledM01Available",
        "        private set",
        "    @Volatile var m02Available: Int = bundledM02Available",
        "        private set",
        "    @Volatile var m03Available: Int = bundledM03Available",
        "        private set",
        "    @Volatile var m04Available: Int = bundledM04Available",
        "        private set",
        "    @Volatile var completeCount: Int = bundledCompleteCount",
        "        private set",
        "",
    ]
    chunk_names = []
    for ci in range(0, len(items), chunk_size):
        chunk = items[ci:ci + chunk_size]
        name = f"chunk{ci // chunk_size}"
        chunk_names.append(name)
        lines.append(f"    private fun {name}(): List<RealQuantRecord> = listOf(")
        for code, r in chunk:
            lines.append(
                "        RealQuantRecord(" +
                f"code={base.kotlin_string(code)}," +
                f"m01Raw={base.kotlin_double(r.get('m01_raw'))},m01Percentile={base.kotlin_double(r.get('m01_pct'))}," +
                f"m01ReasonCode={base.kotlin_string(r.get('m01_reason'))},m01Basis={base.kotlin_string(r.get('m01_basis') or '')}," +
                f"m02Raw={base.kotlin_double(r.get('m02_raw'))},m02Percentile={base.kotlin_double(r.get('m02_pct'))}," +
                f"m02ReasonCode={base.kotlin_string(r.get('m02_reason'))},m02Basis={base.kotlin_string(r.get('m02_basis') or '')}," +
                f"m03Raw={base.kotlin_double(r.get('m03_raw'))},m03Percentile={base.kotlin_double(r.get('m03_pct'))}," +
                f"m03ReasonCode={base.kotlin_string(r.get('m03_reason'))},m03Basis={base.kotlin_string(r.get('m03_basis') or '')}," +
                f"m04Raw={base.kotlin_double(r.get('m04_raw'))},m04Percentile={base.kotlin_double(r.get('m04_pct'))}," +
                f"m04ReasonCode={base.kotlin_string(r.get('m04_reason'))},m04Basis={base.kotlin_string(r.get('m04_basis') or '')}," +
                f"compositeScore={base.kotlin_double(r.get('composite'))},rankOrder={r.get('rank') if r.get('rank') is not None else 'null'}),"
            )
        lines += ["    )", ""]
    lines += ["    private val bundledRows: List<RealQuantRecord> by lazy {", "        buildList {"]
    for name in chunk_names:
        lines.append(f"            addAll({name}())")
    lines += [
        "        }",
        "    }",
        "",
        "    @Volatile private var runtimeRows: List<RealQuantRecord>? = null",
        "    val rows: List<RealQuantRecord> get() = runtimeRows ?: bundledRows",
        "",
        "    @Synchronized",
        "    fun installRuntimeSnapshot(",
        "        newSnapshotDate: String,",
        "        newPriceCutoffDate: String,",
        "        newDartFileName: String,",
        "        newRows: List<RealQuantRecord>,",
        "        newM01Available: Int,",
        "        newM02Available: Int,",
        "        newM03Available: Int,",
        "        newM04Available: Int,",
        "        newCompleteCount: Int",
        "    ) {",
        "        require(newRows.size == universeCount) { \"runtime snapshot universe mismatch: ${newRows.size} != $universeCount\" }",
        "        require(newRows.map { it.code }.toSet().size == universeCount) { \"runtime snapshot contains duplicate codes\" }",
        "        snapshotDate = newSnapshotDate",
        "        priceCutoffDate = newPriceCutoffDate",
        "        dartFileName = newDartFileName",
        "        m01Available = newM01Available",
        "        m02Available = newM02Available",
        "        m03Available = newM03Available",
        "        m04Available = newM04Available",
        "        completeCount = newCompleteCount",
        "        runtimeRows = newRows",
        "    }",
        "}",
        "",
    ]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines), encoding="utf-8")


def _postprocess_evidence(path: Path) -> None:
    d = json.loads(path.read_text(encoding="utf-8"))
    d["scope"] = f"KR4 four-metric automatic snapshot for KOSPI+KOSDAQ {d['universe_count']:,} issue codes"
    d["auto_update"] = {
        "collector": "collect_real_quant_v3.py",
        "timezone": "Asia/Seoul",
        "price_cutoff_policy": "multi-symbol quorum; same-day bar rejected before 16:00 KST",
        "dart_policy": "latest available Q1/HY/Q3/FY PL bulk period selected dynamically",
        "dart_year": _SELECTED_DART_YEAR,
        "dart_period": _SELECTED_DART_PERIOD,
        "dart_period_label": DART_PERIOD_LABEL.get(_SELECTED_DART_PERIOD or "", ""),
    }
    d["sources"]["M03"] = "Naver Finance reported positive trailing PER; actual EPS + completed-session close fallback"
    d["sources"]["naver_price_pattern"] = base.NAVER_BASE + "/{code}/price?pageSize=60&page={page}"
    d["rules"]["M01"] = "latest OpenDART PL period; 3-month YoY preferred, YTD/annual comparable fallback; CFS preferred to OFS"
    d["rules"]["M02"] = "same latest OpenDART PL period; operating income/revenue on a matched period column; financial/insurance sectors excluded"
    d["rules"]["M03"] = "Naver reported positive trailing PER preferred; completed-session close / actual non-consensus EPS fallback; EPS <= 0 remains unavailable"
    path.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    # Freeze resolved CLI args once; base.main() calls parse_args again.
    args = parse_args_auto_cached()
    base.parse_args = parse_args_auto_cached
    base.get_json = v2.get_json
    base.naver_metric_worker = v2.naver_metric_worker
    base.download_dart_halfyear_pl = download_latest_dart_pl
    base.parse_dart_metrics = parse_dart_metrics_dynamic
    base.write_kotlin = write_kotlin_runtime_capable
    base.main()
    evidence_path = base.ROOT / args.evidence_out
    _postprocess_evidence(evidence_path)
    print(
        "AUTO_COLLECTOR_V3_PASS",
        json.dumps(
            {
                "as_of": args.as_of,
                "price_cutoff": args.price_cutoff,
                "dart_year": _SELECTED_DART_YEAR,
                "dart_period": _SELECTED_DART_PERIOD,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
