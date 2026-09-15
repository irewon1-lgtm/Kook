#!/usr/bin/env python3
"""Adversarial tests for the KR4 automatic-update collector.

These tests intentionally simulate pre-close runs, weekends/holidays, one failed
price probe, conflicting probes, future DART periods, Q1/HY/Q3/FY column shapes,
and runtime-Kotlin generation. No external network is required.
"""
from __future__ import annotations

import csv
import io
import json
import sys
import tempfile
import zipfile
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import collect_real_quant as base
import collect_real_quant_v2 as v2
import collect_real_quant_v3 as v3

KST = ZoneInfo("Asia/Seoul")


def bars(*days: str):
    return [{"localTradedAt": d, "closePrice": "100,000"} for d in days]


def test_price_cutoff_preclose_rejects_same_day() -> None:
    original = v2.get_json
    try:
        v2.get_json = lambda _url: bars("2026-09-15", "2026-09-14", "2026-09-11")
        now = datetime(2026, 9, 15, 14, 30, tzinfo=KST)
        assert v3.resolve_price_cutoff(date(2026, 9, 15), now) == date(2026, 9, 14)
    finally:
        v2.get_json = original


def test_price_cutoff_after_close_accepts_same_day() -> None:
    original = v2.get_json
    try:
        v2.get_json = lambda _url: bars("2026-09-15", "2026-09-14")
        now = datetime(2026, 9, 15, 17, 30, tzinfo=KST)
        assert v3.resolve_price_cutoff(date(2026, 9, 15), now) == date(2026, 9, 15)
    finally:
        v2.get_json = original


def test_price_cutoff_weekend_and_holiday_gap() -> None:
    original = v2.get_json
    try:
        v2.get_json = lambda _url: bars("2026-09-11", "2026-09-10")
        now = datetime(2026, 9, 13, 18, 0, tzinfo=KST)
        assert v3.resolve_price_cutoff(date(2026, 9, 13), now) == date(2026, 9, 11)
    finally:
        v2.get_json = original


def test_price_cutoff_one_probe_failure_still_has_quorum() -> None:
    original = v2.get_json
    calls = {"n": 0}
    try:
        def fake(_url):
            calls["n"] += 1
            if calls["n"] == 1:
                raise RuntimeError("temporary outage")
            return bars("2026-09-14")
        v2.get_json = fake
        now = datetime(2026, 9, 15, 14, 0, tzinfo=KST)
        assert v3.resolve_price_cutoff(date(2026, 9, 15), now) == date(2026, 9, 14)
    finally:
        v2.get_json = original


def test_price_cutoff_conflict_fails_closed() -> None:
    original = v2.get_json
    calls = {"n": 0}
    try:
        def fake(_url):
            calls["n"] += 1
            ds = ["2026-09-14", "2026-09-11", "2026-09-10"]
            return bars(ds[calls["n"] - 1])
        v2.get_json = fake
        now = datetime(2026, 9, 15, 14, 0, tzinfo=KST)
        try:
            v3.resolve_price_cutoff(date(2026, 9, 15), now)
        except RuntimeError as exc:
            assert "disagree" in str(exc)
        else:
            raise AssertionError("conflicting price probes must fail closed")
    finally:
        v2.get_json = original


def test_dart_latest_period_progression_and_future_rejection() -> None:
    entries = [
        ("2025", "FY", "PL", "2025_FY.zip"),
        ("2026", "1Q", "PL", "2026_Q1.zip"),
        ("2026", "HY", "PL", "2026_HY.zip"),
        ("2027", "1Q", "PL", "future.zip"),
        ("2026", "Q3", "BS", "not_pl.zip"),
    ]
    assert v3._select_latest_dart_entry(entries, date(2026, 9, 15)) == ("2026", "HY", "2026_HY.zip")
    entries.append(("2026", "3Q", "PL", "2026_Q3.zip"))
    assert v3._select_latest_dart_entry(entries, date(2026, 11, 20)) == ("2026", "Q3", "2026_Q3.zip")
    entries.append(("2026", "FY", "PL", "2026_FY.zip"))
    assert v3._select_latest_dart_entry(entries, date(2026, 12, 31)) == ("2026", "FY", "2026_FY.zip")
    assert v3._select_latest_dart_entry(entries, date(2027, 4, 1)) == ("2027", "Q1", "future.zip")
    assert v3.DART_PERIOD_CANONICAL["1Q"] == "Q1" and v3.DART_PERIOD_CANONICAL["3Q"] == "Q3"


def _make_dart_zip(period_label: str, current: int, prior: int, op: int) -> bytes:
    if period_label == "FY":
        cur_key, prev_key = "당기", "전기"
    else:
        cur_key, prev_key = f"당기 {period_label} 3개월", f"전기 {period_label} 3개월"
    fields = ["종목코드", "재무제표종류", "항목코드", "항목명", cur_key, prev_key]
    rows = [
        {"종목코드": "005930", "재무제표종류": "연결 손익계산서", "항목코드": "ifrs-full_Revenue", "항목명": "매출액", cur_key: str(current), prev_key: str(prior)},
        {"종목코드": "005930", "재무제표종류": "연결 손익계산서", "항목코드": "dart_OperatingIncomeLoss", "항목명": "영업이익", cur_key: str(op), prev_key: str(op - 1)},
    ]
    sio = io.StringIO()
    w = csv.DictWriter(sio, fieldnames=fields, delimiter="\t", lineterminator="\n")
    w.writeheader(); w.writerows(rows)
    bio = io.BytesIO()
    with zipfile.ZipFile(bio, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("sample.txt", sio.getvalue().encode("cp949"))
    return bio.getvalue()


def test_dynamic_dart_headers_q1_hy_q3_fy() -> None:
    issuer = base.Issuer("005930", "삼성전자", "전기전자", "1975-06-11", "KOSPI")
    for period, label in [("Q1", "1분기"), ("HY", "반기"), ("Q3", "3분기"), ("FY", "FY")]:
        v3._SELECTED_DART_YEAR = "2026"; v3._SELECTED_DART_PERIOD = period
        out = v3.parse_dart_metrics_dynamic([issuer], _make_dart_zip(label, 120, 100, 12))["005930"]
        assert round(out["m01_raw"], 6) == 20.0, (period, out)
        assert round(out["m02_raw"], 6) == 10.0, (period, out)
        assert out["m01_basis"].startswith(f"2026{period}_"), (period, out)
        assert out["m02_basis"].endswith("_CFS"), (period, out)


def test_runtime_kotlin_hook_is_generated() -> None:
    r = {"005930": {"m01_raw":1.0,"m01_pct":50.0,"m01_reason":None,"m01_basis":"B1","m02_raw":2.0,"m02_pct":50.0,"m02_reason":None,"m02_basis":"B2","m03_raw":10.0,"m03_pct":50.0,"m03_reason":None,"m03_basis":"B3","m04_raw":4.0,"m04_pct":50.0,"m04_reason":None,"m04_basis":"B4","composite":50.0,"rank":1}}
    meta = {"snapshot_date":"2026-09-15","price_cutoff":"2026-09-14","dart_file_name":"x.zip","coverage":{"m01_available":1,"m02_available":1,"m03_available":1,"m04_available":1,"complete_count":1}}
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "Generated.kt"
        v3.write_kotlin_runtime_capable(r, p, meta)
        text = p.read_text(encoding="utf-8")
        assert "fun installRuntimeSnapshot(" in text
        assert "runtimeRows" in text
        assert "bundledSnapshotDate" in text
        assert "val rows: List<RealQuantRecord> get() = runtimeRows ?: bundledRows" in text


def main() -> None:
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    failures = []
    for fn in sorted(tests, key=lambda f: f.__name__):
        try:
            fn(); print("PASS", fn.__name__)
        except Exception as exc:
            failures.append((fn.__name__, repr(exc))); print("FAIL", fn.__name__, repr(exc))
    result = {"status": "PASS" if not failures else "FAIL", "tests": len(tests), "failures": failures}
    print("AUTO_UPDATE_ADVERSARIAL", json.dumps(result, ensure_ascii=False))
    if failures: raise SystemExit(1)


if __name__ == "__main__":
    main()
