#!/usr/bin/env python3
"""Stage 4: collect fail-closed KR4 financial-safety facts from OpenDART BS bulk data.

This augments an existing validated KR4 quant snapshot. It never alters M01~M04,
composite scores, or ranks. Missing/ambiguous BS facts become HOLD; they are never
guessed. The safety policy is intentionally narrow and auditable:
- positive equity is required;
- liabilities/equity <= 400% is required;
- current assets/current liabilities >= 70% is required;
- the accounting identity Assets ~= Liabilities + Equity must reconcile within 5%.
Financial-sector rows are marked NOT_APPLICABLE because their balance-sheet
leverage is structurally different and KR4 already excludes them from M02 ranking.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import re
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Callable

import requests

import collect_real_quant as base

ROOT = Path(__file__).resolve().parents[1]
POLICY_VERSION = "STAGE4_BS_SAFETY_V1"
DEBT_TO_EQUITY_MAX = 400.0
CURRENT_RATIO_MIN = 70.0
IDENTITY_TOLERANCE_PCT = 5.0

ACCOUNT_SPECS: dict[str, tuple[dict[str, int], set[str]]] = {
    "assets": (
        {
            "ifrs-full_Assets": 1500,
            "ifrs_Assets": 1450,
            "dart_Assets": 1400,
        },
        {"자산총계", "자산 총계", "총자산"},
    ),
    "liabilities": (
        {
            "ifrs-full_Liabilities": 1500,
            "ifrs_Liabilities": 1450,
            "dart_Liabilities": 1400,
        },
        {"부채총계", "부채 총계", "총부채"},
    ),
    "equity": (
        {
            "ifrs-full_Equity": 1500,
            "ifrs_Equity": 1450,
            "dart_Equity": 1400,
        },
        {"자본총계", "자본 총계", "총자본"},
    ),
    "current_assets": (
        {
            "ifrs-full_CurrentAssets": 1500,
            "ifrs_CurrentAssets": 1450,
            "dart_CurrentAssets": 1400,
        },
        {"유동자산", "유동 자산"},
    ),
    "current_liabilities": (
        {
            "ifrs-full_CurrentLiabilities": 1500,
            "ifrs_CurrentLiabilities": 1450,
            "dart_CurrentLiabilities": 1400,
        },
        {"유동부채", "유동 부채"},
    ),
}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    p.add_argument("--out", default="evidence/real_quant_snapshot.json")
    p.add_argument("--self-test", action="store_true")
    return p.parse_args()


def compact_name(value: str) -> str:
    return re.sub(r"\s+", "", value or "").strip()


def account_score(kind: str, row: dict[str, str]) -> int:
    codes, names = ACCOUNT_SPECS[kind]
    code = (row.get("항목코드") or "").strip()
    name = compact_name(row.get("항목명") or "")
    if code in codes:
        return codes[code]
    compact_names = {compact_name(x) for x in names}
    if name in compact_names:
        return 1200
    return -1


def best_account(rows: list[dict[str, str]], kind: str) -> dict[str, str] | None:
    scored = [(account_score(kind, r), i, r) for i, r in enumerate(rows)]
    scored = [x for x in scored if x[0] >= 0]
    if not scored:
        return None
    scored.sort(key=lambda x: (-x[0], x[1]))
    return scored[0][2]


def current_value(row: dict[str, str] | None) -> tuple[float | None, str]:
    if row is None:
        return None, ""
    candidates: list[tuple[int, str, float]] = []
    for key in row:
        k = compact_name(key)
        if not k.startswith("당기"):
            continue
        if "초" in k and "말" not in k:
            continue
        value = base.amount(row, key)
        if value is None:
            continue
        score = 100
        if "당기말" in k:
            score = 500
        elif any(x in k for x in ("반기말", "분기말", "기말")):
            score = 450
        elif "말" in k:
            score = 400
        candidates.append((score, key, value))
    if not candidates:
        return None, ""
    candidates.sort(key=lambda x: (-x[0], x[1]))
    _, key, value = candidates[0]
    return value, key


def is_financial_sector(sector: str) -> bool:
    return any(x in (sector or "") for x in ("금융", "보험", "은행", "증권", "신탁", "여신"))


def classify(values: dict[str, float | None]) -> tuple[str, str, dict[str, float | None]]:
    assets = values.get("assets")
    liabilities = values.get("liabilities")
    equity = values.get("equity")
    current_assets = values.get("current_assets")
    current_liabilities = values.get("current_liabilities")

    required = {
        "assets": assets,
        "liabilities": liabilities,
        "equity": equity,
        "current_assets": current_assets,
        "current_liabilities": current_liabilities,
    }
    missing = [k for k, v in required.items() if v is None]
    if missing:
        return "HOLD", "MISSING_" + "_".join(x.upper() for x in missing), {
            "debt_to_equity_pct": None,
            "current_ratio_pct": None,
            "identity_gap_pct": None,
        }

    assert assets is not None and liabilities is not None and equity is not None
    assert current_assets is not None and current_liabilities is not None

    if assets <= 0:
        return "HOLD", "NONPOSITIVE_ASSETS", {"debt_to_equity_pct": None, "current_ratio_pct": None, "identity_gap_pct": None}

    identity_gap_pct = abs(assets - (liabilities + equity)) / assets * 100.0
    if not math.isfinite(identity_gap_pct) or identity_gap_pct > IDENTITY_TOLERANCE_PCT:
        return "HOLD", "ACCOUNTING_IDENTITY_MISMATCH", {
            "debt_to_equity_pct": None,
            "current_ratio_pct": None,
            "identity_gap_pct": round(identity_gap_pct, 6) if math.isfinite(identity_gap_pct) else None,
        }

    if equity <= 0:
        return "FAIL", "NONPOSITIVE_EQUITY", {
            "debt_to_equity_pct": None,
            "current_ratio_pct": None,
            "identity_gap_pct": round(identity_gap_pct, 6),
        }

    debt_to_equity = liabilities / equity * 100.0
    if current_liabilities <= 0:
        return "HOLD", "NONPOSITIVE_CURRENT_LIABILITIES", {
            "debt_to_equity_pct": round(debt_to_equity, 6),
            "current_ratio_pct": None,
            "identity_gap_pct": round(identity_gap_pct, 6),
        }
    current_ratio = current_assets / current_liabilities * 100.0

    derived = {
        "debt_to_equity_pct": round(debt_to_equity, 6),
        "current_ratio_pct": round(current_ratio, 6),
        "identity_gap_pct": round(identity_gap_pct, 6),
    }
    if not all(math.isfinite(float(v)) for v in derived.values() if v is not None):
        return "HOLD", "NONFINITE_DERIVED_RATIO", derived
    if debt_to_equity > DEBT_TO_EQUITY_MAX:
        return "FAIL", "DEBT_TO_EQUITY_OVER_400", derived
    if current_ratio < CURRENT_RATIO_MIN:
        return "FAIL", "CURRENT_RATIO_UNDER_70", derived
    return "PASS", "PASS", derived


def download_bs(quant: dict[str, Any]) -> tuple[str, bytes]:
    auto = quant.get("auto_update") or {}
    year = str(auto.get("dart_year") or "")
    period = str(auto.get("dart_period") or "")
    if not re.fullmatch(r"20\d{2}", year) or period not in {"Q1", "HY", "Q3", "FY"}:
        raise RuntimeError(f"invalid quant DART period provenance year={year!r} period={period!r}")

    aliases = {"Q1": {"Q1", "1Q"}, "HY": {"HY"}, "Q3": {"Q3", "3Q"}, "FY": {"FY"}}[period]
    session = requests.Session()
    session.headers.update(base.DART_HEADERS)
    session.get(base.DART_BASE, timeout=30).raise_for_status()
    listing = session.post(base.DART_LIST, timeout=60)
    listing.raise_for_status()
    entries = base.DART_ENTRY_RE.findall(listing.text)
    hits = [(y, p, f) for y, p, statement, f in entries if y == year and p in aliases and statement == "BS"]
    if len(hits) != 1:
        raise RuntimeError(f"expected exactly one matching OpenDART BS file for {year}/{period}, got {hits}")
    fname = hits[0][2]
    response = session.get(base.DART_DOWNLOAD, params={"fl_nm": fname}, timeout=300)
    response.raise_for_status()
    if response.content[:2] != b"PK":
        raise RuntimeError(f"OpenDART BS response is not ZIP: {fname}")
    return fname, response.content


def parse_bs(quant: dict[str, Any], zip_bytes: bytes, source_file: str) -> dict[str, Any]:
    issuers = {x.code: x for x in base.load_issuers()}
    qcodes = set(quant["records"])
    if set(issuers) != qcodes:
        raise RuntimeError("quant identity set differs from KRX masters")

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
            if code not in issuers:
                continue
            groups[code][row.get("재무제표종류") or ""].append(row)

    records: dict[str, Any] = {}
    counts: Counter[str] = Counter()
    for code, issuer in issuers.items():
        if is_financial_sector(issuer.sector):
            row = {
                "status": "NOT_APPLICABLE",
                "reason": "FINANCIAL_SECTOR_NOT_COMPARABLE",
                "scope": "",
                "assets": None,
                "liabilities": None,
                "equity": None,
                "current_assets": None,
                "current_liabilities": None,
                "debt_to_equity_pct": None,
                "current_ratio_pct": None,
                "identity_gap_pct": None,
                "basis": "",
            }
            records[code] = row
            counts[row["status"]] += 1
            continue

        candidates: list[tuple[int, str, dict[str, float | None], dict[str, str]]] = []
        for statement, rows in groups.get(code, {}).items():
            values: dict[str, float | None] = {}
            bases: dict[str, str] = {}
            coverage = 0
            account_quality = 0
            for kind in ACCOUNT_SPECS:
                hit = best_account(rows, kind)
                value, basis = current_value(hit)
                values[kind] = value
                bases[kind] = basis
                if value is not None:
                    coverage += 1
                if hit is not None:
                    account_quality += max(account_score(kind, hit), 0)
            score = coverage * 100000 + account_quality
            if "연결" in statement:
                score += 50000
            if "재무상태표" in statement:
                score += 5000
            candidates.append((score, statement, values, bases))

        if not candidates:
            status, reason, derived = "HOLD", "DART_NO_BALANCE_SHEET", {
                "debt_to_equity_pct": None,
                "current_ratio_pct": None,
                "identity_gap_pct": None,
            }
            values = {k: None for k in ACCOUNT_SPECS}
            statement = ""
            bases = {}
        else:
            candidates.sort(key=lambda x: (-x[0], x[1]))
            _, statement, values, bases = candidates[0]
            status, reason, derived = classify(values)

        scope = "CFS" if "연결" in statement else ("OFS" if statement else "")
        basis_keys = sorted({v for v in bases.values() if v})
        row = {
            "status": status,
            "reason": reason,
            "scope": scope,
            "assets": values.get("assets"),
            "liabilities": values.get("liabilities"),
            "equity": values.get("equity"),
            "current_assets": values.get("current_assets"),
            "current_liabilities": values.get("current_liabilities"),
            **derived,
            "basis": f"{source_file}:{scope}:{'|'.join(basis_keys)}" if basis_keys else "",
        }
        records[code] = row
        counts[status] += 1

    return {
        "schema_version": 1,
        "policy_version": POLICY_VERSION,
        "snapshot_date_kst": quant["snapshot_date_kst"],
        "source": "Financial Supervisory Service OpenDART financial-information bulk BS",
        "source_file": source_file,
        "thresholds": {
            "debt_to_equity_max_pct": DEBT_TO_EQUITY_MAX,
            "current_ratio_min_pct": CURRENT_RATIO_MIN,
            "accounting_identity_tolerance_pct": IDENTITY_TOLERANCE_PCT,
        },
        "coverage": {
            "pass": counts["PASS"],
            "fail": counts["FAIL"],
            "hold": counts["HOLD"],
            "not_applicable": counts["NOT_APPLICABLE"],
            "total": len(records),
        },
        "records": dict(sorted(records.items())),
    }


def augment_quant(quant: dict[str, Any], safety: dict[str, Any]) -> dict[str, Any]:
    if safety["snapshot_date_kst"] != quant["snapshot_date_kst"]:
        raise RuntimeError("safety snapshot date mismatch")
    if set(safety["records"]) != set(quant["records"]):
        raise RuntimeError("safety identity set mismatch")
    out = json.loads(json.dumps(quant, ensure_ascii=False))
    out["financial_safety"] = safety
    out.setdefault("sources", {})["STAGE4_FINANCIAL_SAFETY"] = safety["source"]
    out.setdefault("rules", {})["STAGE4_FINANCIAL_SAFETY"] = (
        "positive equity; liabilities/equity <= 400%; current assets/current liabilities >= 70%; "
        "Assets≈Liabilities+Equity within 5%; missing/ambiguous values HOLD; financial sectors N/A"
    )
    return out


def validate_augmented(quant: dict[str, Any]) -> None:
    fs = quant["financial_safety"]
    assert fs["policy_version"] == POLICY_VERSION
    assert fs["snapshot_date_kst"] == quant["snapshot_date_kst"]
    assert fs["coverage"]["total"] == quant["universe_count"] == len(fs["records"])
    assert set(fs["records"]) == set(quant["records"])
    counted = Counter(r["status"] for r in fs["records"].values())
    assert fs["coverage"]["pass"] == counted["PASS"]
    assert fs["coverage"]["fail"] == counted["FAIL"]
    assert fs["coverage"]["hold"] == counted["HOLD"]
    assert fs["coverage"]["not_applicable"] == counted["NOT_APPLICABLE"]
    for code, row in fs["records"].items():
        assert row["status"] in {"PASS", "FAIL", "HOLD", "NOT_APPLICABLE"}, (code, row)
        if row["status"] == "PASS":
            assert row["equity"] is not None and row["equity"] > 0
            assert row["debt_to_equity_pct"] is not None and row["debt_to_equity_pct"] <= DEBT_TO_EQUITY_MAX
            assert row["current_ratio_pct"] is not None and row["current_ratio_pct"] >= CURRENT_RATIO_MIN
            assert row["identity_gap_pct"] is not None and row["identity_gap_pct"] <= IDENTITY_TOLERANCE_PCT


def self_test() -> None:
    def c(**kwargs: float | None) -> tuple[str, str, dict[str, float | None]]:
        basev = dict(assets=100.0, liabilities=50.0, equity=50.0, current_assets=80.0, current_liabilities=50.0)
        basev.update(kwargs)
        return classify(basev)

    assert c()[0:2] == ("PASS", "PASS")
    assert c(equity=0.0, liabilities=100.0)[0:2] == ("FAIL", "NONPOSITIVE_EQUITY")
    assert c(assets=501.0, liabilities=401.0, equity=100.0)[0:2] == ("FAIL", "DEBT_TO_EQUITY_OVER_400")
    assert c(current_assets=34.9, current_liabilities=50.0)[0:2] == ("FAIL", "CURRENT_RATIO_UNDER_70")
    assert c(current_assets=35.0, current_liabilities=50.0)[0] == "PASS"
    assert c(assets=120.0, liabilities=50.0, equity=50.0)[0:2] == ("HOLD", "ACCOUNTING_IDENTITY_MISMATCH")
    assert c(current_assets=None)[0].startswith("HOLD")
    assert c(current_liabilities=0.0)[0:2] == ("HOLD", "NONPOSITIVE_CURRENT_LIABILITIES")
    print("FINANCIAL_SAFETY_SELF_TEST_PASS")


def main() -> None:
    args = parse_args()
    if args.self_test:
        self_test()
        return
    qpath = (ROOT / args.quant) if not Path(args.quant).is_absolute() else Path(args.quant)
    opath = (ROOT / args.out) if not Path(args.out).is_absolute() else Path(args.out)
    quant = json.loads(qpath.read_text(encoding="utf-8"))
    source_file, payload = download_bs(quant)
    safety = parse_bs(quant, payload, source_file)
    safety["source_zip_sha256"] = hashlib.sha256(payload).hexdigest()
    out = augment_quant(quant, safety)
    validate_augmented(out)
    opath.parent.mkdir(parents=True, exist_ok=True)
    opath.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("FINANCIAL_SAFETY_BUILD_PASS", json.dumps({"source_file": source_file, **safety["coverage"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
