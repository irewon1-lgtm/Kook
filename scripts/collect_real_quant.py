#!/usr/bin/env python3
"""Collect the four KR4 quantitative metrics from real external sources.

M01 revenue growth and M02 operating margin come from the official OpenDART
financial-information bulk download (2026 half-year PL).  The current Q2
three-month values are preferred; if a filer does not expose comparable
three-month columns, H1 cumulative values are used.

M03 actual-earnings PER uses Naver Finance's non-consensus EPS together with the
last completed-session close from Naver's price history.  M04 is the close-to-
close return from the last trading session on/before six months prior to the
price cutoff through the cutoff session.

Missing or ambiguous values remain null with an explicit reason code.  This
script never guesses a financial value.
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
import threading
import time
import zipfile
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any

import requests

ROOT = Path(__file__).resolve().parents[1]
DART_BASE = "https://opendart.fss.or.kr"
DART_LIST = DART_BASE + "/disclosureinfo/fnltt/dwld/list.do"
DART_DOWNLOAD = DART_BASE + "/cmm/downloadFnlttZip.do"
DART_REFERER = DART_BASE + "/disclosureinfo/fnltt/dwld/main.do"
NAVER_BASE = "https://m.stock.naver.com/api/stock"

DART_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36",
    "Referer": DART_REFERER,
}
NAVER_HEADERS = {
    "User-Agent": "Mozilla/5.0 KR4-RealQuant/1.0",
    "Referer": "https://m.stock.naver.com/",
    "Accept": "application/json, text/plain, */*",
}

MASTER_RE = re.compile(
    r'(?:KospiIssuer|KosdaqIssuer)\(code = "([^"]+)", name = "([^"]*)", '
    r'sector = "([^"]*)", listingDate = "([^"]*)"\)'
)
DART_ENTRY_RE = re.compile(
    r"download_ext002\('(\d{4})','([A-Z]{2})',\s*'([A-Z]{2})',\s*'([^']+)'\)"
)

REVENUE_EXACT_NAMES = {
    "매출액",
    "수익(매출액)",
    "영업수익",
    "영업수익(매출액)",
    "순매출액",
    "매출",
    "수익",
}
OPERATING_EXACT_NAMES = {
    "영업이익",
    "영업손실",
    "영업이익(손실)",
    "영업손익",
    "영업손익(손실)",
}
REVENUE_CODES = {
    "ifrs-full_Revenue": 1200,
    "ifrs_Revenue": 1190,
    "dart_Revenue": 1180,
    "dart_OperatingRevenue": 1170,
}
OPERATING_CODES = {
    "dart_OperatingIncomeLoss": 1200,
    "ifrs-full_ProfitLossFromOperatingActivities": 1190,
    "ifrs_OperatingIncomeLoss": 1180,
}


@dataclass(frozen=True)
class Issuer:
    code: str
    name: str
    sector: str
    listing_date: str
    market: str


_thread_local = threading.local()


def naver_session() -> requests.Session:
    sess = getattr(_thread_local, "naver_session", None)
    if sess is None:
        sess = requests.Session()
        sess.headers.update(NAVER_HEADERS)
        _thread_local.naver_session = sess
    return sess


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--as-of", default="2026-09-15")
    p.add_argument("--price-cutoff", default="2026-09-14")
    p.add_argument("--workers", type=int, default=14)
    p.add_argument(
        "--kotlin-out",
        default="app/src/main/java/com/krstock/v3/data/generated/GeneratedRealQuantSnapshot.kt",
    )
    p.add_argument("--evidence-out", default="evidence/real_quant_snapshot.json")
    return p.parse_args()


def load_issuers() -> list[Issuer]:
    specs = [
        (ROOT / "app/src/main/java/com/krstock/v3/data/generated/GeneratedKospiMaster.kt", "KOSPI"),
        (ROOT / "app/src/main/java/com/krstock/v3/data/generated/GeneratedKosdaqMaster.kt", "KOSDAQ"),
    ]
    out: list[Issuer] = []
    for path, market in specs:
        text = path.read_text(encoding="utf-8")
        rows = [Issuer(*m.groups(), market) for m in MASTER_RE.finditer(text)]
        if not rows:
            raise RuntimeError(f"could not parse issuer master: {path}")
        out.extend(rows)
    if len(out) != 2649:
        raise RuntimeError(f"unexpected issuer universe {len(out)}, expected 2649")
    codes = [x.code for x in out]
    if len(set(codes)) != len(codes):
        dup = [k for k, v in Counter(codes).items() if v > 1]
        raise RuntimeError(f"duplicate KRX issue codes across masters: {dup[:20]}")
    return sorted(out, key=lambda x: (x.market, x.code))


def download_dart_halfyear_pl() -> tuple[str, bytes]:
    s = requests.Session()
    s.headers.update(DART_HEADERS)
    s.get(DART_BASE, timeout=30).raise_for_status()
    r = s.post(DART_LIST, timeout=60)
    r.raise_for_status()
    entries = DART_ENTRY_RE.findall(r.text)
    hit = [e for e in entries if e[0] == "2026" and e[1] == "HY" and e[2] == "PL"]
    if len(hit) != 1:
        raise RuntimeError(f"expected exactly one 2026 HY PL bulk file, got {hit}")
    fname = hit[0][3]
    r = s.get(DART_DOWNLOAD, params={"fl_nm": fname}, timeout=300)
    r.raise_for_status()
    if r.content[:2] != b"PK":
        raise RuntimeError(f"OpenDART bulk response was not ZIP: {r.text[:300]!r}")
    return fname, r.content


def parse_number(value: Any) -> float | None:
    if value is None:
        return None
    s = str(value).strip()
    if not s or s in {"-", "--", "N/A", "null", "None"}:
        return None
    negative = s.startswith("(") and s.endswith(")")
    s = s.strip("()").replace(",", "").replace("원", "").replace("배", "").replace("%", "")
    m = re.search(r"[-+]?\d+(?:\.\d+)?", s)
    if not m:
        return None
    try:
        v = float(m.group(0))
    except ValueError:
        return None
    if negative:
        v = -abs(v)
    return v if math.isfinite(v) else None


def clean_name(value: str) -> str:
    return re.sub(r"\s+", "", value or "").strip()


def revenue_score(row: dict[str, str]) -> int:
    code = (row.get("항목코드") or "").strip()
    name = clean_name(row.get("항목명") or "")
    if code in REVENUE_CODES:
        return REVENUE_CODES[code]
    if name in REVENUE_EXACT_NAMES:
        return 1050
    if name.startswith("매출액") and all(x not in name for x in ("원가", "총이익", "증가")):
        return 900
    if name.startswith("영업수익") and all(x not in name for x in ("비용", "원가")):
        return 880
    # Custom taxonomies sometimes keep the English semantic in the account id.
    low = code.lower()
    if low.endswith("revenue") and not any(x in low for x in ("cost", "expense")):
        return 700
    return -1


def operating_score(row: dict[str, str]) -> int:
    code = (row.get("항목코드") or "").strip()
    name = clean_name(row.get("항목명") or "")
    if code in OPERATING_CODES:
        return OPERATING_CODES[code]
    if name in OPERATING_EXACT_NAMES:
        return 1050
    if name.startswith("영업이익") and "률" not in name:
        return 900
    if name.startswith("영업손실"):
        return 890
    return -1


def best_row(rows: list[dict[str, str]], scorer) -> dict[str, str] | None:
    scored = [(scorer(r), i, r) for i, r in enumerate(rows)]
    scored = [x for x in scored if x[0] >= 0]
    if not scored:
        return None
    scored.sort(key=lambda x: (-x[0], x[1]))
    return scored[0][2]


def amount(row: dict[str, str] | None, key: str) -> float | None:
    if row is None:
        return None
    # DictReader headers are stripped during load.
    return parse_number(row.get(key))


def parse_dart_metrics(issuers: list[Issuer], zip_bytes: bytes) -> dict[str, dict[str, Any]]:
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
            rev = best_row(rows, revenue_score)
            if rev is None:
                continue
            op = best_row(rows, operating_score)
            score = 0
            if "연결" in statement:
                score += 10000
            if "손익계산서" in statement:
                score += 1000
            if amount(rev, "당기 반기 3개월") is not None and amount(rev, "전기 반기 3개월") is not None:
                score += 100
            if op is not None:
                score += 20
            candidates.append((score, statement, rev, op))

        if not candidates:
            out[issuer.code] = {
                "m01_raw": None,
                "m01_reason": "DART_NO_REVENUE",
                "m01_basis": "",
                "m02_raw": None,
                "m02_reason": "FINANCIAL_SECTOR_EXCLUDED" if is_financial(issuer) else "DART_NO_OPERATING_INCOME",
                "m02_basis": "",
                "dart_statement": "",
            }
            continue

        candidates.sort(key=lambda x: (-x[0], x[1]))
        _, statement, rev, op = candidates[0]
        c3 = amount(rev, "당기 반기 3개월")
        p3 = amount(rev, "전기 반기 3개월")
        cy = amount(rev, "당기 반기 누적")
        py = amount(rev, "전기 반기 누적")

        m01 = None
        m01_reason = None
        rev_basis = ""
        current_rev = None
        if c3 is not None and p3 is not None and p3 > 0:
            m01 = (c3 / p3 - 1.0) * 100.0
            current_rev = c3
            rev_basis = "2026Q2_3M"
        elif cy is not None and py is not None and py > 0:
            m01 = (cy / py - 1.0) * 100.0
            current_rev = cy
            rev_basis = "2026H1_YTD"
        else:
            m01_reason = "DART_NO_COMPARABLE_PRIOR_REVENUE"

        if m01 is not None and (not math.isfinite(m01) or abs(m01) > 100000):
            m01 = None
            m01_reason = "DART_REVENUE_GROWTH_OUTLIER_GUARD"

        m02 = None
        m02_reason = None
        op_basis = ""
        if is_financial(issuer):
            m02_reason = "FINANCIAL_SECTOR_EXCLUDED"
        elif op is None:
            m02_reason = "DART_NO_OPERATING_INCOME"
        else:
            if rev_basis == "2026Q2_3M":
                opv = amount(op, "당기 반기 3개월")
            else:
                opv = amount(op, "당기 반기 누적")
            if current_rev is not None and current_rev != 0 and opv is not None:
                m02 = opv / current_rev * 100.0
                op_basis = rev_basis
            elif cy is not None and cy != 0 and amount(op, "당기 반기 누적") is not None:
                m02 = amount(op, "당기 반기 누적") / cy * 100.0
                op_basis = "2026H1_YTD"
            else:
                m02_reason = "DART_NO_COMPARABLE_OPERATING_MARGIN"
        if m02 is not None and (not math.isfinite(m02) or abs(m02) > 10000):
            m02 = None
            m02_reason = "DART_OPERATING_MARGIN_OUTLIER_GUARD"

        scope = "CFS" if "연결" in statement else "OFS"
        out[issuer.code] = {
            "m01_raw": round(m01, 6) if m01 is not None else None,
            "m01_reason": m01_reason,
            "m01_basis": f"{rev_basis}_{scope}" if rev_basis else "",
            "m02_raw": round(m02, 6) if m02 is not None else None,
            "m02_reason": m02_reason,
            "m02_basis": f"{op_basis}_{scope}" if op_basis else "",
            "dart_statement": statement,
        }
    return out


def is_financial(issuer: Issuer) -> bool:
    s = issuer.sector
    return any(x in s for x in ("금융", "보험", "은행", "증권", "신탁", "여신"))


def subtract_six_months(d: date) -> date:
    month = d.month - 6
    year = d.year
    if month <= 0:
        month += 12
        year -= 1
    # All current cutoffs use day <= 28? Still handle month length defensively.
    import calendar
    day = min(d.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def parse_date(s: str) -> date | None:
    for fmt in ("%Y-%m-%d", "%Y%m%d"):
        try:
            return datetime.strptime(s, fmt).date()
        except Exception:
            pass
    return None


def get_json(url: str, retries: int = 4) -> Any:
    sess = naver_session()
    last: Exception | None = None
    for attempt in range(retries):
        try:
            r = sess.get(url, timeout=20)
            if r.status_code == 200:
                return r.json()
            if r.status_code in (404, 409):
                raise LookupError(f"HTTP_{r.status_code}")
            if r.status_code == 429 or r.status_code >= 500:
                time.sleep(0.4 * (2**attempt))
                continue
            r.raise_for_status()
        except LookupError:
            raise
        except Exception as exc:
            last = exc
            time.sleep(0.35 * (2**attempt))
    raise RuntimeError(type(last).__name__ if last else "NAVER_RETRY_EXHAUSTED")


def naver_metric_worker(issuer: Issuer, cutoff: date, six_month_target: date) -> tuple[str, dict[str, Any]]:
    code = issuer.code
    m03_raw = None
    m03_reason = None
    m03_basis = ""
    m04_raw = None
    m04_reason = None
    m04_basis = ""
    provider_per = None
    eps = None
    end_close = None
    integration_error = None
    price_error = None

    try:
        integ = get_json(f"{NAVER_BASE}/{code}/integration")
        infos = {
            str(x.get("code")): x.get("value")
            for x in (integ.get("totalInfos") or [])
            if isinstance(x, dict) and x.get("code")
        }
        eps = parse_number(infos.get("eps"))
        provider_per = parse_number(infos.get("per"))
    except LookupError as exc:
        integration_error = str(exc)
    except Exception as exc:
        integration_error = type(exc).__name__

    try:
        bars = get_json(f"{NAVER_BASE}/{code}/price?pageSize=200&page=1")
        if not isinstance(bars, list):
            raise ValueError("PRICE_NOT_LIST")
        parsed: list[tuple[date, float]] = []
        for b in bars:
            if not isinstance(b, dict):
                continue
            d = parse_date(str(b.get("localTradedAt") or ""))
            p = parse_number(b.get("closePrice"))
            if d and p and p > 0:
                parsed.append((d, p))
        if parsed and min(d for d, _ in parsed) > six_month_target and len(bars) >= 190:
            older = get_json(f"{NAVER_BASE}/{code}/price?pageSize=120&page=2")
            if isinstance(older, list):
                for b in older:
                    if not isinstance(b, dict):
                        continue
                    d = parse_date(str(b.get("localTradedAt") or ""))
                    p = parse_number(b.get("closePrice"))
                    if d and p and p > 0:
                        parsed.append((d, p))
        by_date = {d: p for d, p in parsed}
        end_dates = [d for d in by_date if d <= cutoff]
        start_dates = [d for d in by_date if d <= six_month_target]
        if not end_dates:
            m04_reason = "NAVER_NO_PRICE_AT_CUTOFF"
        elif not start_dates:
            m04_reason = "PRICE_HISTORY_SHORTER_THAN_6M"
        else:
            end_d = max(end_dates)
            start_d = max(start_dates)
            end_close = by_date[end_d]
            start_close = by_date[start_d]
            if (end_d - start_d).days < 150:
                m04_reason = "PRICE_HISTORY_SHORTER_THAN_6M"
            else:
                m04_raw = (end_close / start_close - 1.0) * 100.0
                m04_basis = f"{start_d.isoformat()}->{end_d.isoformat()}"
    except LookupError as exc:
        price_error = str(exc)
    except Exception as exc:
        price_error = type(exc).__name__

    if m04_raw is None and m04_reason is None:
        m04_reason = "NAVER_PRICE_ERROR" if price_error else "NAVER_PRICE_MISSING"

    if eps is not None and eps > 0 and end_close is not None and end_close > 0:
        m03_raw = end_close / eps
        if math.isfinite(m03_raw) and m03_raw > 0:
            m03_basis = f"{cutoff.isoformat()}_CLOSE/NAVER_EPS"
        else:
            m03_raw = None
            m03_reason = "NAVER_PER_NONPOSITIVE"
    elif eps is not None and eps <= 0:
        m03_reason = "NONPOSITIVE_EPS"
    elif integration_error:
        m03_reason = "NAVER_INTEGRATION_ERROR"
    elif end_close is None:
        m03_reason = "NAVER_NO_PRICE_AT_CUTOFF"
    else:
        m03_reason = "NAVER_EPS_MISSING"

    return code, {
        "m03_raw": round(m03_raw, 6) if m03_raw is not None else None,
        "m03_reason": m03_reason,
        "m03_basis": m03_basis,
        "m04_raw": round(m04_raw, 6) if m04_raw is not None else None,
        "m04_reason": m04_reason,
        "m04_basis": m04_basis,
        "naver_provider_per": provider_per,
        "naver_eps": eps,
        "naver_end_close": end_close,
        "integration_error": integration_error,
        "price_error": price_error,
    }


def collect_naver(issuers: list[Issuer], cutoff: date, workers: int) -> dict[str, dict[str, Any]]:
    target = subtract_six_months(cutoff)
    out: dict[str, dict[str, Any]] = {}
    total = len(issuers)
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(naver_metric_worker, issuer, cutoff, target): issuer.code for issuer in issuers}
        for idx, fut in enumerate(as_completed(futs), 1):
            code = futs[fut]
            try:
                c, row = fut.result()
                out[c] = row
            except Exception as exc:
                out[code] = {
                    "m03_raw": None,
                    "m03_reason": "NAVER_WORKER_EXCEPTION",
                    "m03_basis": "",
                    "m04_raw": None,
                    "m04_reason": "NAVER_WORKER_EXCEPTION",
                    "m04_basis": "",
                    "integration_error": type(exc).__name__,
                    "price_error": type(exc).__name__,
                }
            if idx % 100 == 0 or idx == total:
                print(f"NAVER_PROGRESS {idx}/{total}", flush=True)
    return out


def apply_percentiles(records: dict[str, dict[str, Any]], field: str, out_field: str, higher_better: bool) -> int:
    vals = [(code, row[field]) for code, row in records.items() if row.get(field) is not None]
    vals.sort(key=lambda x: (x[1], x[0]))
    n = len(vals)
    if n == 0:
        return 0
    i = 0
    while i < n:
        j = i + 1
        while j < n and vals[j][1] == vals[i][1]:
            j += 1
        avg_index = (i + (j - 1)) / 2.0
        pct = 50.0 if n == 1 else avg_index / (n - 1) * 100.0
        if not higher_better:
            pct = 100.0 - pct
        for k in range(i, j):
            records[vals[k][0]][out_field] = round(pct, 6)
        i = j
    return n


def compute_scores(records: dict[str, dict[str, Any]]) -> None:
    apply_percentiles(records, "m01_raw", "m01_pct", True)
    apply_percentiles(records, "m02_raw", "m02_pct", True)
    apply_percentiles(records, "m03_raw", "m03_pct", False)
    apply_percentiles(records, "m04_raw", "m04_pct", True)
    complete: list[tuple[str, float]] = []
    for code, row in records.items():
        scores = [row.get(f"m{i:02d}_pct") for i in range(1, 5)]
        if all(v is not None for v in scores):
            comp = statistics.mean(scores)
            row["composite"] = round(comp, 6)
            complete.append((code, comp))
        else:
            row["composite"] = None
        row["rank"] = None
    complete.sort(key=lambda x: (-x[1], x[0]))
    for rank, (code, _) in enumerate(complete, 1):
        records[code]["rank"] = rank


def kotlin_string(value: str | None) -> str:
    if value is None:
        return "null"
    v = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ").replace("\r", " ")
    return f'"{v}"'


def kotlin_double(value: float | None) -> str:
    if value is None:
        return "null"
    s = f"{value:.8f}".rstrip("0").rstrip(".")
    if "." not in s:
        s += ".0"
    return s


def write_kotlin(records: dict[str, dict[str, Any]], out_path: Path, meta: dict[str, Any]) -> None:
    items = sorted(records.items())
    chunk_size = 70
    lines = [
        "package com.krstock.v3.data.generated",
        "",
        "import com.krstock.v3.data.model.RealQuantRecord",
        "",
        "/** AUTO-GENERATED from official OpenDART bulk financials and live Naver Finance. */",
        "object GeneratedRealQuantSnapshot {",
        f'    const val snapshotDate: String = "{meta["snapshot_date"]}"',
        f'    const val priceCutoffDate: String = "{meta["price_cutoff"]}"',
        f'    const val dartFileName: String = "{meta["dart_file_name"]}"',
        f"    const val universeCount: Int = {len(items)}",
        f"    const val m01Available: Int = {meta['coverage']['m01_available']}",
        f"    const val m02Available: Int = {meta['coverage']['m02_available']}",
        f"    const val m03Available: Int = {meta['coverage']['m03_available']}",
        f"    const val m04Available: Int = {meta['coverage']['m04_available']}",
        f"    const val completeCount: Int = {meta['coverage']['complete_count']}",
        "",
    ]
    chunk_names = []
    for ci in range(0, len(items), chunk_size):
        chunk = items[ci : ci + chunk_size]
        name = f"chunk{ci // chunk_size}"
        chunk_names.append(name)
        lines.append(f"    private fun {name}(): List<RealQuantRecord> = listOf(")
        for code, r in chunk:
            lines.append(
                "        RealQuantRecord(" +
                f"code={kotlin_string(code)}," +
                f"m01Raw={kotlin_double(r.get('m01_raw'))},m01Percentile={kotlin_double(r.get('m01_pct'))}," +
                f"m01ReasonCode={kotlin_string(r.get('m01_reason'))},m01Basis={kotlin_string(r.get('m01_basis') or '')}," +
                f"m02Raw={kotlin_double(r.get('m02_raw'))},m02Percentile={kotlin_double(r.get('m02_pct'))}," +
                f"m02ReasonCode={kotlin_string(r.get('m02_reason'))},m02Basis={kotlin_string(r.get('m02_basis') or '')}," +
                f"m03Raw={kotlin_double(r.get('m03_raw'))},m03Percentile={kotlin_double(r.get('m03_pct'))}," +
                f"m03ReasonCode={kotlin_string(r.get('m03_reason'))},m03Basis={kotlin_string(r.get('m03_basis') or '')}," +
                f"m04Raw={kotlin_double(r.get('m04_raw'))},m04Percentile={kotlin_double(r.get('m04_pct'))}," +
                f"m04ReasonCode={kotlin_string(r.get('m04_reason'))},m04Basis={kotlin_string(r.get('m04_basis') or '')}," +
                f"compositeScore={kotlin_double(r.get('composite'))},rankOrder={r.get('rank') if r.get('rank') is not None else 'null'}),"
            )
        lines.append("    )")
        lines.append("")
    lines.append("    val rows: List<RealQuantRecord> by lazy {")
    lines.append("        buildList {")
    for name in chunk_names:
        lines.append(f"            addAll({name}())")
    lines += ["        }", "    }", "}", ""]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines), encoding="utf-8")


def reason_counts(records: dict[str, dict[str, Any]], key: str) -> dict[str, int]:
    c = Counter(r.get(key) or "AVAILABLE" for r in records.values())
    return dict(sorted(c.items(), key=lambda x: (-x[1], x[0])))


def main() -> None:
    args = parse_args()
    as_of = date.fromisoformat(args.as_of)
    cutoff = date.fromisoformat(args.price_cutoff)
    if cutoff > as_of:
        raise SystemExit("price cutoff cannot be after snapshot date")

    issuers = load_issuers()
    print(f"UNIVERSE_OK {len(issuers)}")

    dart_fname, dart_zip = download_dart_halfyear_pl()
    dart_sha = hashlib.sha256(dart_zip).hexdigest()
    print(f"DART_BULK_OK {dart_fname} bytes={len(dart_zip)} sha256={dart_sha}")
    dart = parse_dart_metrics(issuers, dart_zip)
    print(
        "DART_COVERAGE",
        sum(v.get("m01_raw") is not None for v in dart.values()),
        sum(v.get("m02_raw") is not None for v in dart.values()),
    )

    naver = collect_naver(issuers, cutoff, args.workers)
    print(
        "NAVER_COVERAGE",
        sum(v.get("m03_raw") is not None for v in naver.values()),
        sum(v.get("m04_raw") is not None for v in naver.values()),
    )

    records: dict[str, dict[str, Any]] = {}
    identities: dict[str, dict[str, str]] = {}
    for issuer in issuers:
        r = {}
        r.update(dart.get(issuer.code, {}))
        r.update(naver.get(issuer.code, {}))
        for i in range(1, 5):
            r.setdefault(f"m{i:02d}_raw", None)
            r.setdefault(f"m{i:02d}_reason", "SOURCE_MISSING")
            r.setdefault(f"m{i:02d}_basis", "")
            r[f"m{i:02d}_pct"] = None
        records[issuer.code] = r
        identities[issuer.code] = {
            "name": issuer.name,
            "market": issuer.market,
            "sector": issuer.sector,
            "listing_date": issuer.listing_date,
        }

    compute_scores(records)
    coverage = {
        "m01_available": sum(r["m01_raw"] is not None for r in records.values()),
        "m02_available": sum(r["m02_raw"] is not None for r in records.values()),
        "m03_available": sum(r["m03_raw"] is not None for r in records.values()),
        "m04_available": sum(r["m04_raw"] is not None for r in records.values()),
        "complete_count": sum(r["composite"] is not None for r in records.values()),
        "ranked_count": sum(r["rank"] is not None for r in records.values()),
    }
    print("COVERAGE", json.dumps(coverage, ensure_ascii=False))
    if coverage["m04_available"] < 1500:
        raise RuntimeError(f"price coverage unexpectedly low: {coverage['m04_available']}")
    if coverage["m03_available"] < 800:
        raise RuntimeError(f"PER coverage unexpectedly low: {coverage['m03_available']}")
    if coverage["m01_available"] < 1000:
        raise RuntimeError(f"DART revenue coverage unexpectedly low: {coverage['m01_available']}")
    if coverage["m02_available"] < 900:
        raise RuntimeError(f"DART operating margin coverage unexpectedly low: {coverage['m02_available']}")

    summary_meta = {
        "snapshot_date": as_of.isoformat(),
        "price_cutoff": cutoff.isoformat(),
        "dart_file_name": dart_fname,
        "coverage": coverage,
    }
    kotlin_out = ROOT / args.kotlin_out
    write_kotlin(records, kotlin_out, summary_meta)
    kotlin_sha = hashlib.sha256(kotlin_out.read_bytes()).hexdigest()

    # Evidence keeps every raw value, basis, reason and score so CI results are independently auditable.
    evidence_records = {}
    for code in sorted(records):
        r = records[code]
        evidence_records[code] = {
            **identities[code],
            "m01": {"raw": r["m01_raw"], "percentile": r.get("m01_pct"), "reason": r.get("m01_reason"), "basis": r.get("m01_basis")},
            "m02": {"raw": r["m02_raw"], "percentile": r.get("m02_pct"), "reason": r.get("m02_reason"), "basis": r.get("m02_basis")},
            "m03": {"raw": r["m03_raw"], "percentile": r.get("m03_pct"), "reason": r.get("m03_reason"), "basis": r.get("m03_basis")},
            "m04": {"raw": r["m04_raw"], "percentile": r.get("m04_pct"), "reason": r.get("m04_reason"), "basis": r.get("m04_basis")},
            "composite": r.get("composite"),
            "rank": r.get("rank"),
        }

    proof = {
        "scope": "KR4 four-metric real-data snapshot for KOSPI+KOSDAQ 2,649 issue codes",
        "snapshot_date_kst": as_of.isoformat(),
        "price_cutoff_date_kst": cutoff.isoformat(),
        "universe_count": len(issuers),
        "sources": {
            "M01_M02": "Financial Supervisory Service OpenDART public financial-information bulk download",
            "M03": "Naver Finance actual EPS + last completed-session close",
            "M04": "Naver Finance daily close history",
            "dart_bulk_list_url": DART_LIST,
            "dart_bulk_download_url": DART_DOWNLOAD,
            "naver_integration_pattern": NAVER_BASE + "/{code}/integration",
            "naver_price_pattern": NAVER_BASE + "/{code}/price?pageSize=200&page=1",
        },
        "dart_bulk_file": dart_fname,
        "dart_bulk_zip_sha256": dart_sha,
        "generated_kotlin_sha256": kotlin_sha,
        "coverage": coverage,
        "reason_counts": {
            "m01": reason_counts(records, "m01_reason"),
            "m02": reason_counts(records, "m02_reason"),
            "m03": reason_counts(records, "m03_reason"),
            "m04": reason_counts(records, "m04_reason"),
        },
        "rules": {
            "M01": "2026 Q2 3-month revenue YoY preferred; H1 YTD YoY fallback; CFS preferred to OFS",
            "M02": "Q2 3-month operating income/revenue preferred; H1 YTD fallback; financial/insurance sectors excluded",
            "M03": "last completed-session close divided by Naver non-consensus EPS; EPS <= 0 is unavailable",
            "M04": "close-to-close return from last session on/before six-month target to price cutoff; at least 150 calendar days required",
            "percentiles": "available-universe percentile; higher is better except PER where lower is better",
            "composite": "equal 25% average only when all four percentile scores exist",
            "rank": "descending composite score, deterministic code tiebreak",
        },
        "samples": {k: evidence_records[k] for k in ("005930", "000660", "000250", "035420") if k in evidence_records},
        "records": evidence_records,
    }
    ev_path = ROOT / args.evidence_out
    ev_path.parent.mkdir(parents=True, exist_ok=True)
    ev_path.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OUTPUT_OK kotlin={kotlin_out} evidence={ev_path} kotlin_sha={kotlin_sha}")


if __name__ == "__main__":
    main()
