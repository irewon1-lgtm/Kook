#!/usr/bin/env python3
"""Fetch the official KIND KOSPI listed-company master and generate Kotlin source.

This registers issuer identity only. It does NOT fabricate financial metrics, prices,
ranks, news, filings, or investment conclusions.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
from datetime import datetime
from pathlib import Path
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

import pandas as pd

SOURCE_URL = (
    "https://kind.krx.co.kr/corpgeneral/corpList.do?"
    "method=download&searchType=13&marketType=stockMkt"
)
MIN_EXPECTED = 700
MAX_EXPECTED = 1200


def fetch_table() -> pd.DataFrame:
    req = Request(
        SOURCE_URL,
        headers={
            "User-Agent": "Mozilla/5.0 KR4-KOSPI-Master/1.0",
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


def normalize(df: pd.DataFrame) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for _, row in df.iterrows():
        raw_code = str(row["종목코드"]).strip()
        if raw_code.endswith(".0"):
            raw_code = raw_code[:-2]
        digits = re.sub(r"\D", "", raw_code)
        code = digits.zfill(6)
        name = str(row["회사명"]).strip()
        sector = str(row["업종"]).strip()
        listing_date = str(row["상장일"]).strip()
        if not re.fullmatch(r"\d{6}", code):
            raise RuntimeError(f"invalid issue code: {raw_code!r}")
        if not name or name.lower() == "nan":
            raise RuntimeError(f"empty company name for {code}")
        if sector.lower() == "nan":
            sector = "기타"
        if listing_date.lower() == "nan":
            listing_date = ""
        rows.append({
            "code": code,
            "name": name,
            "sector": sector,
            "listingDate": listing_date,
        })

    unique: dict[str, dict[str, str]] = {}
    for row in rows:
        if row["code"] in unique and unique[row["code"]] != row:
            raise RuntimeError(f"duplicate issue code with conflicting data: {row['code']}")
        unique[row["code"]] = row
    rows = sorted(unique.values(), key=lambda r: r["code"])

    if not (MIN_EXPECTED <= len(rows) <= MAX_EXPECTED):
        raise RuntimeError(
            f"unexpected KOSPI company count {len(rows)}; expected {MIN_EXPECTED}..{MAX_EXPECTED}"
        )
    return rows


def kt_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ").replace("\r", " ")


def write_kotlin(rows: list[dict[str, str]], output: Path, snapshot_date: str) -> None:
    lines = [
        "package com.krstock.v3.data.generated",
        "",
        "import com.krstock.v3.data.model.KospiIssuer",
        "",
        "/** AUTO-GENERATED from KRX KIND. Do not hand-edit. */",
        "object GeneratedKospiMaster {",
        f'    const val sourceUrl: String = "{kt_escape(SOURCE_URL)}"',
        f'    const val snapshotDate: String = "{snapshot_date}"',
        f"    const val issuerCount: Int = {len(rows)}",
        "    val issuers: List<KospiIssuer> = listOf(",
    ]
    for row in rows:
        lines.append(
            '        KospiIssuer(code = "{code}", name = "{name}", sector = "{sector}", listingDate = "{listingDate}"),'.format(
                **{k: kt_escape(v) for k, v in row.items()}
            )
        )
    lines += ["    )", "}", ""]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")


def write_evidence(rows: list[dict[str, str]], path: Path, snapshot_date: str) -> None:
    canonical = "\n".join(
        f"{r['code']}|{r['name']}|{r['sector']}|{r['listingDate']}" for r in rows
    ).encode("utf-8")
    evidence = {
        "scope": "KOSPI listed-company registration master only; no financial metrics or prices",
        "source": "KRX KIND 상장법인목록",
        "source_url": SOURCE_URL,
        "market": "KOSPI",
        "snapshot_date_kst": snapshot_date,
        "issuer_count": len(rows),
        "unique_issue_codes": len({r["code"] for r in rows}),
        "six_digit_codes": all(re.fullmatch(r"\d{6}", r["code"]) for r in rows),
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
    rows = normalize(fetch_table())
    write_kotlin(rows, Path(args.output), snapshot_date)
    write_evidence(rows, Path(args.evidence), snapshot_date)
    print(f"KOSPI_MASTER_OK count={len(rows)} snapshot={snapshot_date}")


if __name__ == "__main__":
    main()
