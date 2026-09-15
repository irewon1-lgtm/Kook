#!/usr/bin/env python3
"""Fetch the official KIND KOSDAQ listed-company master and generate Kotlin source.

This registers issuer identity only. It does NOT fabricate financial metrics, prices,
ranks, news, filings, or investment conclusions.

KRX issue codes are six characters and modern listings may contain letters.
Alphanumeric codes must be preserved exactly.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

import pandas as pd

SOURCE_URL = (
    "https://kind.krx.co.kr/corpgeneral/corpList.do?"
    "method=download&searchType=13&marketType=kosdaqMkt"
)
MIN_EXPECTED = 1200
MAX_EXPECTED = 2500
ISSUE_CODE_RE = re.compile(r"[0-9A-Z]{6}")


def fetch_table() -> pd.DataFrame:
    req = Request(
        SOURCE_URL,
        headers={
            "User-Agent": "Mozilla/5.0 KR4-KOSDAQ-Master/1.0",
            "Accept": "text/html,application/xhtml+xml",
        },
    )
    with urlopen(req, timeout=45) as resp:
        raw = resp.read()
    tables = pd.read_html(io.BytesIO(raw), header=0)
    if not tables:
        raise RuntimeError("KIND returned no HTML table")
    df = tables[0].copy()
    df.columns = [str(c).strip() for c in df.columns]
    required = {"회사명", "종목코드", "업종", "상장일"}
    missing = required - set(df.columns)
    if missing:
        raise RuntimeError(f"KIND schema changed; missing columns: {sorted(missing)}")
    return df


def clean_text(value: object, fallback: str = "") -> str:
    text = re.sub(r"\s+", " ", str(value)).strip()
    return fallback if text.lower() == "nan" else text


def normalize_issue_code(value: object) -> str:
    raw = clean_text(value).upper()
    if re.fullmatch(r"\d+\.0", raw):
        raw = raw[:-2]
    raw = re.sub(r"\s+", "", raw)
    if raw.isdigit():
        raw = raw.zfill(6)
    if not ISSUE_CODE_RE.fullmatch(raw):
        raise RuntimeError(f"invalid KRX issue code: {value!r} -> {raw!r}")
    return raw


def normalize(df: pd.DataFrame) -> tuple[list[dict[str, str]], int, list[str]]:
    source_rows: list[dict[str, str]] = []
    for _, row in df.iterrows():
        code = normalize_issue_code(row["종목코드"])
        name = clean_text(row["회사명"])
        sector = clean_text(row["업종"], "기타")
        listing_date = clean_text(row["상장일"])
        if not name:
            raise RuntimeError(f"empty company name for {code}")
        source_rows.append({
            "code": code,
            "name": name,
            "sector": sector,
            "listingDate": listing_date,
        })

    grouped: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in source_rows:
        grouped[row["code"]].append(row)

    duplicate_codes = sorted(code for code, variants in grouped.items() if len(variants) > 1)
    rows: list[dict[str, str]] = []
    for code, variants in grouped.items():
        names = {v["name"] for v in variants}
        if len(names) != 1:
            raise RuntimeError(
                f"same exact KRX issue code maps to multiple company names: {code} -> {sorted(names)}"
            )
        name = next(iter(names))
        sector_candidates = [v["sector"] for v in variants if v["sector"] and v["sector"] != "기타"]
        sector = Counter(sector_candidates).most_common(1)[0][0] if sector_candidates else "기타"
        date_candidates = sorted({v["listingDate"] for v in variants if v["listingDate"]})
        listing_date = date_candidates[0] if date_candidates else ""
        rows.append({
            "code": code,
            "name": name,
            "sector": sector,
            "listingDate": listing_date,
        })

    rows.sort(key=lambda r: r["code"])
    if not (MIN_EXPECTED <= len(rows) <= MAX_EXPECTED):
        raise RuntimeError(
            f"unexpected KOSDAQ company count {len(rows)}; expected {MIN_EXPECTED}..{MAX_EXPECTED}"
        )
    return rows, len(source_rows), duplicate_codes


def kt_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ").replace("\r", " ")


def write_kotlin(rows: list[dict[str, str]], output: Path, snapshot_date: str) -> None:
    lines = [
        "package com.krstock.v3.data.generated",
        "",
        "import com.krstock.v3.data.model.KosdaqIssuer",
        "",
        "/** AUTO-GENERATED from KRX KIND. Do not hand-edit. */",
        "object GeneratedKosdaqMaster {",
        f'    const val sourceUrl: String = "{kt_escape(SOURCE_URL)}"',
        f'    const val snapshotDate: String = "{snapshot_date}"',
        f"    const val issuerCount: Int = {len(rows)}",
        "    val issuers: List<KosdaqIssuer> = listOf(",
    ]
    for row in rows:
        lines.append(
            '        KosdaqIssuer(code = "{code}", name = "{name}", sector = "{sector}", listingDate = "{listingDate}"),'.format(
                **{k: kt_escape(v) for k, v in row.items()}
            )
        )
    lines += ["    )", "}", ""]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")


def write_evidence(
    rows: list[dict[str, str]],
    source_row_count: int,
    duplicate_codes: list[str],
    path: Path,
    snapshot_date: str,
) -> None:
    canonical = "\n".join(
        f"{r['code']}|{r['name']}|{r['sector']}|{r['listingDate']}" for r in rows
    ).encode("utf-8")
    alphanumeric = [r["code"] for r in rows if not r["code"].isdigit()]
    evidence = {
        "scope": "KOSDAQ listed-company registration master only; no financial metrics or prices",
        "source": "KRX KIND 상장법인목록",
        "source_url": SOURCE_URL,
        "market": "KOSDAQ",
        "snapshot_date_kst": snapshot_date,
        "source_row_count": source_row_count,
        "issuer_count": len(rows),
        "unique_issue_codes": len({r["code"] for r in rows}),
        "duplicate_exact_source_codes_deduplicated": duplicate_codes,
        "six_character_krx_codes": all(ISSUE_CODE_RE.fullmatch(r["code"]) for r in rows),
        "alphanumeric_issue_code_count": len(alphanumeric),
        "alphanumeric_issue_code_samples": alphanumeric[:20],
        "sha256_normalized_master": hashlib.sha256(canonical).hexdigest(),
        "sample_first_5": rows[:5],
        "sample_last_5": rows[-5:],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()

    snapshot_date = datetime.now(ZoneInfo("Asia/Seoul")).date().isoformat()
    rows, source_row_count, duplicate_codes = normalize(fetch_table())
    write_kotlin(rows, Path(args.output), snapshot_date)
    write_evidence(rows, source_row_count, duplicate_codes, Path(args.evidence), snapshot_date)
    print(
        f"KOSDAQ_MASTER_OK count={len(rows)} source_rows={source_row_count} "
        f"deduped_exact_codes={len(duplicate_codes)} snapshot={snapshot_date}"
    )


if __name__ == "__main__":
    main()
