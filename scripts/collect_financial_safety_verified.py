#!/usr/bin/env python3
"""Independent Stage4 facts. Official raw observations only; quant is read-only."""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import re
import time
import zipfile
from collections import Counter, defaultdict
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse, parse_qs

import requests

import collect_financial_safety as policy
import collect_real_quant as base

ROOT = Path(__file__).resolve().parents[1]
FIELDS = tuple(policy.ACCOUNT_SPECS)
CURRENCIES = {"KRW", "USD", "CNY", "JPY", "EUR", "HKD", "TWD", "SGD", "GBP"}


def normalized_name(value: str) -> str:
    value = re.sub(r"주식회사|\(주\)|㈜", "", value)
    return re.sub(r"[^0-9A-Za-z가-힣]", "", value).upper()


def exact_amount(raw: str) -> int | None:
    raw = raw.strip().replace(",", "")
    if raw in {"", "-", "－", "N/A"}:
        return None
    if re.fullmatch(r"\(\d+\)", raw):
        raw = "-" + raw[1:-1]
    if not re.fullmatch(r"-?\d+", raw):
        raise ValueError("not an unscaled integer")
    value = int(raw)
    if not -(2**63) <= value < 2**63:
        raise ValueError("amount outside Android exact integer range")
    return value


def current_amount(row: dict[str, str]) -> tuple[int | None, str]:
    candidates = []
    for key in row:
        compact = policy.compact_name(key)
        if not compact.startswith("당기") or ("초" in compact and "말" not in compact):
            continue
        score = 500 if "당기말" in compact else (450 if any(x in compact for x in ("반기말", "분기말", "기말")) else 100)
        candidates.append((score, key))
    if not candidates:
        return None, ""
    key = sorted(candidates, key=lambda x: (-x[0], x[1]))[0][1]
    return exact_amount(row[key]), key


def empty_record(code: str, issuer: dict[str, str]) -> dict[str, Any]:
    return {
        "issuer_code": code, "issuer_name": issuer["name"], "sector": issuer["sector"],
        "status": "HOLD", "reason": "DART_NO_BALANCE_SHEET", "scope": "", "basis": "",
        **dict.fromkeys(FIELDS), "debt_to_equity_pct": None, "current_ratio_pct": None,
        "identity_gap_pct": None, "currency": "", "unit": "", "report_period": "",
        "report_kind": "", "source_file": "", "source_member": "", "source_zip_sha256": "",
        "validation_issues": ["DART_NO_BALANCE_SHEET"], "account_evidence": {},
        "facts_verified": False,
    }


def parse_bulk_sources(
    issuers: dict[str, dict[str, str]],
    sources: list[tuple[str, bytes]],
    as_of: str,
) -> dict[str, dict[str, Any]]:
    cutoff = date.fromisoformat(as_of)
    groups: dict[str, dict[tuple[str, str], list[tuple[str, dict[str, str]]]]] = defaultdict(lambda: defaultdict(list))
    hashes = {}
    for filename, payload in sources:
        hashes[filename] = hashlib.sha256(payload).hexdigest()
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            for member in archive.namelist():
                if member.endswith("/"):
                    continue
                text = archive.read(member).decode("cp949")
                for raw in csv.DictReader(io.StringIO(text), delimiter="\t"):
                    row = {k.strip(): (v or "").strip() for k, v in raw.items() if k}
                    code = re.sub(r"[^0-9A-Z]", "", row.get("종목코드", "").upper())
                    if code not in issuers:
                        continue
                    statement = row.get("재무제표종류", "")
                    groups[code][(filename, statement)].append((member, row))

    result = {}
    for code, issuer in issuers.items():
        candidates = []
        for (filename, statement), observations in groups.get(code, {}).items():
            rows = [r for _, r in observations]
            record = empty_record(code, issuer)
            issues: list[str] = []
            periods = {r.get("결산기준일", "") for r in rows}
            currencies = {r.get("통화", "") for r in rows}
            names = {normalized_name(r.get("회사명", "")) for r in rows}
            kinds = {r.get("보고서종류", "") for r in rows}
            period = max(periods)
            try:
                if date.fromisoformat(period) > cutoff:
                    issues.append("FUTURE_REPORT_PERIOD")
            except ValueError:
                issues.append("INVALID_REPORT_PERIOD")
            if len(periods) != 1:
                issues.append("MIXED_PERIOD")
            if len(currencies) != 1:
                issues.append("MIXED_CURRENCY")
            currency = next(iter(currencies)) if len(currencies) == 1 else ""
            if currency not in CURRENCIES:
                issues.append("UNVERIFIED_CURRENCY_UNIT")
            if names != {normalized_name(issuer["name"])}:
                issues.append("ISSUER_NAME_MISMATCH")
            if len(kinds) != 1:
                issues.append("MIXED_REPORT_KIND")
            scope = "CFS" if "연결" in statement else ("OFS" if "별도" in statement else "")
            if not scope:
                issues.append("UNVERIFIED_FINANCIAL_SCOPE")
            record.update(
                scope=scope, report_period=period, report_kind=sorted(kinds)[0],
                currency=currency, unit=currency, source_file=filename,
                source_member=sorted({m for m, _ in observations})[0],
                source_zip_sha256=hashes[filename], validation_issues=issues,
            )
            quality = 0
            for field in FIELDS:
                scored = [(policy.account_score(field, row), member, row) for member, row in observations]
                scored = [x for x in scored if x[0] >= 0]
                if not scored:
                    record[field] = None
                    continue
                maximum = max(x[0] for x in scored)
                selected = [x for x in scored if x[0] == maximum]
                values = []
                for _, _, row in selected:
                    try:
                        values.append(current_amount(row)[0])
                    except ValueError:
                        issues.append("INVALID_AMOUNT_" + field.upper())
                if len(set(values)) > 1:
                    issues.append("CONFLICTING_ACCOUNT_" + field.upper())
                record[field] = values[0] if values and len(set(values)) == 1 else None
                row = selected[0][2]
                record["account_evidence"][field] = {
                    "account_code": row.get("항목코드", ""), "account_name": row.get("항목명", ""),
                    "column": current_amount(row)[1] if "INVALID_AMOUNT_" + field.upper() not in issues else "",
                    "raw": row.get(current_amount(row)[1], "") if "INVALID_AMOUNT_" + field.upper() not in issues else "",
                    "source_member": selected[0][1],
                }
                quality += maximum
            record["basis"] = f"{filename}:{scope}:{period}:{currency}"
            record["reason"] = issues[0] if issues else "FILING_PROVENANCE_PENDING"
            coverage = sum(record[k] is not None for k in FIELDS)
            score = coverage * 100000 + quality + (50000 if scope == "CFS" else 0)
            candidates.append((period, score, statement, record))
        if candidates:
            # Latest observed period first; never mix currencies, scopes or periods.
            candidates.sort(key=lambda x: (x[0], x[1], x[2]), reverse=True)
            result[code] = candidates[0][3]
        else:
            result[code] = empty_record(code, issuer)
    return result


def correction_proof_matches(raw: dict[str, Any], filing: dict[str, Any]) -> bool:
    proof = filing.get("correction_reconciliation")
    if filing.get("values_reconciled") is not True or not isinstance(proof, dict):
        return False
    expected = {
        "receipt_no": filing.get("receipt_no"), "corp_code": filing.get("corp_code"),
        "report_period": raw["report_period"], "scope": raw["scope"], "currency": raw["currency"],
    }
    if any(proof.get(k) != value for k, value in expected.items()):
        return False
    if proof.get("validation_error") or proof.get("mismatches"):
        return False
    for key, value in (("observed_receipt_nos", expected["receipt_no"]),
                       ("observed_corp_codes", expected["corp_code"]),
                       ("observed_currencies", expected["currency"])):
        if proof.get(key) != [value]:
            return False
    source_file = re.fullmatch(r"(\d{4})_([1-4])Q_BS_\d{14}\.zip", raw["source_file"])
    if not source_file:
        return False
    report_code = {"1": "11013", "2": "11012", "3": "11014", "4": "11011"}[source_file[2]]
    params = {"corp_code": expected["corp_code"], "bsns_year": source_file[1],
              "reprt_code": report_code, "fs_div": expected["scope"]}
    if proof.get("source_api_params") != params or proof.get("bsns_year") != source_file[1] or proof.get("reprt_code") != report_code:
        return False
    amounts = proof.get("amounts")
    if not isinstance(amounts, dict) or set(amounts) != set(FIELDS):
        return False
    if any(type(amounts[k]) is not int or amounts[k] != raw[k] for k in FIELDS):
        return False
    if proof.get("bulk_amounts") != amounts:
        return False
    if not re.fullmatch(r"[a-f0-9]{64}", str(proof.get("source_api_sha256", ""))):
        return False
    source = urlparse(str(proof.get("source_api_url", "")))
    if source.scheme != "https" or source.netloc != "opendart.fss.or.kr" or source.path != "/api/fnlttSinglAcntAll.json":
        return False
    if source.fragment or (source.query and parse_qs(source.query) != {k: [v] for k, v in params.items()}):
        return False
    return True


def validate_record(raw: dict[str, Any], filing: dict[str, Any], as_of: str) -> dict[str, Any]:
    row = json.loads(json.dumps(raw))
    issues = list(row["validation_issues"])
    for key in ("corp_code", "receipt_no", "filing_date", "source_url", "report_name",
                "correction_status", "correction_check", "search_sha256", "profile_sha256"):
        row[key] = filing.get(key) or ""
    row["reconciliation_error"] = filing.get("reconciliation_error") or ""
    row["values_reconciled"] = correction_proof_matches(raw, filing)
    row["correction_reconciliation"] = filing["correction_reconciliation"] if row["values_reconciled"] else None
    if not filing.get("verified"):
        issues.append(filing.get("error") or "DART_FILING_UNVERIFIED")
    else:
        if not re.fullmatch(r"\d{8}", row["corp_code"]) or not re.fullmatch(r"\d{14}", row["receipt_no"]):
            issues.append("DART_IDENTITY_UNCONFIRMED")
        if filing.get("report_period") != row["report_period"]:
            issues.append("DART_REPORT_PERIOD_MISMATCH")
        try:
            filing_day = date.fromisoformat(row["filing_date"])
            if not date.fromisoformat(row["report_period"]) <= filing_day <= date.fromisoformat(as_of):
                issues.append("INVALID_FILING_DATE")
        except ValueError:
            issues.append("INVALID_FILING_DATE")
        if row["source_url"] != "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + row["receipt_no"]:
            issues.append("DART_RECEIPT_URL_MISMATCH")
        # The bulk file has no receipt identifier: a corrected filing is not
        # proven to match its five amounts merely because the ZIP is newer.
        if row["correction_status"] != "ORIGINAL" and not row["values_reconciled"]:
            issues.append("CORRECTION_VALUES_NOT_RECONCILED")
    row["validation_issues"] = list(dict.fromkeys(issues))
    row["facts_verified"] = False
    if policy.is_financial_sector(row["sector"]):
        row.update(status="NOT_APPLICABLE", reason="FINANCIAL_SECTOR_NOT_COMPARABLE")
        row["facts_verified"] = not issues and all(row[k] is not None for k in ("assets", "liabilities", "equity"))
        return row
    if issues:
        row.update(status="HOLD", reason=issues[0])
        return row
    negative = [k for k in ("liabilities", "current_assets", "current_liabilities") if row[k] is not None and row[k] < 0]
    if negative:
        row.update(status="HOLD", reason="NEGATIVE_BALANCE_SHEET_INPUT")
        row["validation_issues"].append(row["reason"])
        return row
    status, reason, ratios = policy.classify({k: row[k] for k in FIELDS})
    row.update(status=status, reason=reason, **ratios)
    row["facts_verified"] = status in {"PASS", "FAIL"}
    if status == "HOLD":
        row["validation_issues"].append(reason)
    return row


def build_snapshot(quant: dict[str, Any], raw: dict[str, Any], filings: dict[str, Any]) -> dict[str, Any]:
    codes = set(quant["records"])
    if set(raw) != codes or set(filings) != codes:
        raise ValueError("financial identity set differs from2649 quant universe")
    records = {code: validate_record(raw[code], filings[code], quant["snapshot_date_kst"]) for code in sorted(codes)}
    counts = Counter(r["status"] for r in records.values())
    reasons = Counter(r["reason"] for r in records.values() if r["status"] == "HOLD")
    files = sorted({r["source_file"] for r in records.values() if r["source_file"]})
    return {
        "schema_version": 2, "policy_version": policy.POLICY_VERSION,
        "snapshot_date_kst": quant["snapshot_date_kst"],
        "source": "FSS OpenDART bulk balance sheets + " + ", ".join(sorted({str(r.get("source") or "PUBLIC_DART_FINAL_REPORT") for r in filings.values()})),
        "source_file": "|".join(files),
        "collected_at_utc": datetime.now(timezone.utc).isoformat(),
        "quant_records_sha256": hashlib.sha256(json.dumps(quant["records"], sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
        "thresholds": {"debt_to_equity_max_pct": policy.DEBT_TO_EQUITY_MAX,
                       "current_ratio_min_pct": policy.CURRENT_RATIO_MIN,
                       "accounting_identity_tolerance_pct": policy.IDENTITY_TOLERANCE_PCT},
        "coverage": {"pass": counts["PASS"], "fail": counts["FAIL"], "hold": counts["HOLD"],
                     "not_applicable": counts["NOT_APPLICABLE"], "total": len(records),
                     "raw_balance_sheet": sum(bool(r["source_file"]) for r in records.values()),
                     "verified_facts": sum(r["facts_verified"] for r in records.values()),
                     "filing_verified": sum(bool(filings[c].get("verified")) for c in records),
                     "hold_reasons": dict(sorted(reasons.items()))},
        "records": records,
    }


def download_sources(cache: Path, as_of: str) -> list[tuple[str, bytes]]:
    """Latest four official BS periods available as-of, discovered without a frozen filename."""
    cache.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    session.headers.update(base.DART_HEADERS)
    session.get(base.DART_BASE, timeout=30).raise_for_status()
    response = session.post(base.DART_LIST, timeout=60)
    response.raise_for_status()
    entries = base.DART_ENTRY_RE.findall(response.text)
    quarter = {"FQ": 1, "Q1": 1, "1Q": 1, "HY": 2, "TQ": 3, "Q3": 3, "3Q": 3, "FY": 4}
    cutoff = date.fromisoformat(as_of)
    candidates = []
    for year, period, statement, name in entries:
        if statement != "BS" or period not in quarter:
            continue
        if (int(year), quarter[period]) > (cutoff.year, (cutoff.month - 1) // 3 + 1):
            continue
        if int(year) < cutoff.year - 2:
            continue
        candidates.append((int(year), quarter[period], name))
    candidates.sort(reverse=True)
    chosen = candidates[:4]
    if len(chosen) != 4:
        raise RuntimeError("official BS listing did not expose four recent periods")
    sources = []
    for _, _, filename in chosen:
        print("OFFICIAL_BS_START", filename, flush=True)
        local = cache / filename
        if local.exists() and local.stat().st_size:
            payload = local.read_bytes()
        else:
            last = None
            for attempt in range(3):
                try:
                    result = session.get(base.DART_DOWNLOAD, params={"fl_nm": filename}, timeout=300)
                    result.raise_for_status()
                    if result.content[:2] != b"PK":
                        raise ValueError("BS download is notZIP")
                    payload = result.content
                    local.write_bytes(payload)
                    break
                except (requests.RequestException, ValueError) as exc:
                    last = exc
                    if attempt < 2:
                        time.sleep(1 + attempt)
            else:
                raise RuntimeError(f"official BS download failed: {filename}: {last}")
        sources.append((filename, payload))
        print("OFFICIAL_BS_RECEIVED", filename, len(payload), hashlib.sha256(payload).hexdigest(), flush=True)
    return sources


def collect_with_retry(expected: dict[str, Any], cache: Path, workers: int, as_of: str,
                       *, attempts: int = 3, collector=None, pause=time.sleep) -> dict[str, Any]:
    if collector is None:
        from dart_filing_provenance import collect_filing_provenance
        collector = collect_filing_provenance
    if not 1 <= attempts <= 3:
        raise ValueError("official collection retry budget must be1..3")
    cache.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, attempts + 1):
        print("OFFICIAL_FILINGS_ATTEMPT_START", attempt, len(expected), flush=True)
        rows = collector(expected, cache / "dart", workers=workers, as_of=as_of)
        if set(rows) != set(expected):
            raise ValueError("official collector dropped issuer identities")
        errors = sum(r.get("error") in {"SOURCE_UNAVAILABLE", "COLLECTION_ERROR", "STALE_CORRECTION_CHECK"} for r in rows.values())
        (cache / ("filing-attempt-" + str(attempt) + ".json")).write_text(json.dumps(rows, ensure_ascii=False, indent=2))
        print("OFFICIAL_FILINGS_ATTEMPT_RESULT", attempt, "verified", sum(r.get("verified") is True for r in rows.values()),
              "source_errors", errors, flush=True)
        if not errors or attempt == attempts:
            return rows
        # Resume hash-verified successful requests and retry only unavailable ones.
        pause(30)
    raise AssertionError("unreachable")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quant", default="evidence/real_quant_snapshot.json")
    parser.add_argument("--out", default="evidence/financial_safety_snapshot.json")
    parser.add_argument("--cache", default="/tmp/kr4-financial-raw")
    parser.add_argument("--filings")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("--api-key-file", help="Private local credential file; never copied into collection evidence")
    args = parser.parse_args()
    quant = json.loads(Path(args.quant).read_text())
    issuers = {x.code: {"name": x.name, "sector": x.sector} for x in base.load_issuers()}
    if set(issuers) != set(quant["records"]) or len(issuers) != 2649:
        raise ValueError("universe mismatch")
    cache = Path(args.cache)
    sources = download_sources(cache / "bulk", quant["snapshot_date_kst"])
    raw = parse_bulk_sources(issuers, sources, quant["snapshot_date_kst"])
    print("OFFICIAL_BS_PARSED", len(raw), "raw_available", sum(bool(r["source_file"]) for r in raw.values()), flush=True)
    (cache / "raw_facts.json").write_text(json.dumps(raw, ensure_ascii=False, indent=2))
    expected = {code: {"name": row["issuer_name"], "period_end": row["report_period"] or "",
                       "report_kind": row["report_kind"] or ""} for code, row in raw.items()}
    (cache / "expected_filings.json").write_text(json.dumps(expected, ensure_ascii=False, indent=2))
    if args.filings:
        filings = json.loads(Path(args.filings).read_text())
    else:
        api_key = Path(args.api_key_file).read_text().strip() if args.api_key_file else os.environ.get("OPENDART_API_KEY", "").strip()
        collector = None
        if api_key:
            from opendart_filing_provenance import collect_authenticated_filing_provenance
            from reconcile_financial_corrections import reconcile_corrections
            def collector(expected, directory, *, workers, as_of):
                filings = collect_authenticated_filing_provenance(expected, directory, api_key, as_of)
                # A corrected receipt alone cannot validate the bulk values.
                # Keep this inside the bounded retry so transient account API
                # failures resume the same sanitized, hash-verified raw cache.
                return reconcile_corrections(raw, filings, directory / "corrections", api_key)
            print("OFFICIAL_FILING_PROVIDER", "OPENDART_AUTHENTICATED_API", flush=True)
        else:
            print("OFFICIAL_FILING_PROVIDER", "PUBLIC_DART", flush=True)
        filings = collect_with_retry(expected, cache, args.workers, quant["snapshot_date_kst"], attempts=args.attempts, collector=collector)
    (cache / "filing_provenance.json").write_text(json.dumps(filings, ensure_ascii=False, indent=2))
    snapshot = build_snapshot(quant, raw, filings)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n")
    print("INDEPENDENT_FINANCIAL_COLLECTION", json.dumps(snapshot["coverage"], ensure_ascii=False))


if __name__ == "__main__":
    main()
