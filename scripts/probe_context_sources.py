#!/usr/bin/env python3
"""Live smoke probe for KR4 evidence sources.

Hard contracts checked here:
- Naver remains a recent news/disclosure index.
- DART independently exposes receipt numbers and filing viewer references.
- DART public company search exposes a corporation code.
- DART corporate overview exposes an authoritative company/IR web root when one is registered.

The probe intentionally does not require arbitrary company websites to be reachable because
corporate anti-bot/CDN policy is external and must not break the app CI. Runtime crawling is
best-effort and fail-closed after DART root discovery.
"""
from __future__ import annotations

import html as html_lib
import json
import re
import time
from typing import Any
from urllib.parse import urljoin, urlparse

import requests

BASE = "https://m.stock.naver.com/front-api"
DART = "https://dart.fss.or.kr"
CODES = ("005930", "000660", "000250")
HEADERS = {
    "Accept": "application/json,text/plain,text/html,*/*",
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) KR4/3.0",
    "Referer": "https://m.stock.naver.com/",
}

NEWS_TITLE_KEYS = {"title", "articleTitle", "newsTitle", "headline", "subject"}
DISC_TITLE_KEYS = {"title", "reportName", "disclosureTitle", "reportNm", "subject", "name"}
EVIDENCE_KEYS = {
    "datetime", "date", "publishedAt", "publishDate", "writeDate", "createdAt",
    "rceptDt", "receiptDate", "disclosureDate", "regDate", "url", "link", "endUrl",
    "detailUrl", "articleUrl", "oid", "aid", "receiptNo", "rceptNo", "officeName",
    "reportName", "disclosureTitle",
}


def request(method: str, url: str, *, referer: str | None = None, data: dict[str, str] | None = None) -> requests.Response:
    last = None
    for attempt in range(3):
        try:
            headers = dict(HEADERS)
            if referer:
                headers["Referer"] = referer
            r = requests.request(method, url, headers=headers, data=data, timeout=20)
            r.raise_for_status()
            return r
        except Exception as exc:  # noqa: BLE001
            last = exc
            if attempt < 2:
                time.sleep(0.5 * (2**attempt))
    raise RuntimeError(f"{method} {url}: {type(last).__name__}: {last}")


def get_json(url: str) -> Any:
    return request("GET", url).json()


def objects(node: Any):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from objects(value)
    elif isinstance(node, list):
        for value in node:
            yield from objects(value)


def evidence_count(payload: Any, title_keys: set[str]) -> int:
    count = 0
    for obj in objects(payload):
        title = next((str(obj.get(k, "")).strip() for k in title_keys if str(obj.get(k, "")).strip()), "")
        if not title:
            continue
        if any(k in obj and str(obj.get(k, "")).strip() for k in EVIDENCE_KEYS):
            count += 1
    return count


def parse_corp_code(search_html: str) -> str:
    patterns = (
        r"openCorpInfoNew\(\s*['\"](\d{8})['\"]",
        r"openCorpInfo\(\s*['\"](\d{8})['\"]",
        r"selectPopup\.ax\?selectKey=(\d{8})",
    )
    for pattern in patterns:
        m = re.search(pattern, search_html, flags=re.I)
        if m:
            return m.group(1)
    return ""


def dart_search(stock_code: str) -> tuple[list[str], str, str]:
    url = f"{DART}/dsab001/search.ax"
    data = {
        "textCrpNm": stock_code,
        "currentPage": "1",
        "maxResults": "30",
        "maxLinks": "10",
        "sort": "date",
        "series": "desc",
        "finalReport": "recent",
    }
    r = request("POST", url, referer=f"{DART}/dsab001/main.do", data=data)
    receipts: list[str] = []
    for receipt in re.findall(r"rcpNo=(\d{12,16})", r.text):
        if receipt not in receipts:
            receipts.append(receipt)
    return receipts, r.text, parse_corp_code(r.text)


def normalize_root(raw: str) -> str:
    raw = html_lib.unescape(raw).strip().strip("\"'()")
    if not raw:
        return ""
    if raw.startswith("//"):
        raw = "https:" + raw
    if not re.match(r"https?://", raw, flags=re.I):
        if not re.match(r"(?i)(?:www\.)?[a-z0-9.-]+\.[a-z]{2,}(?:/.*)?$", raw):
            return ""
        raw = "https://" + raw
    try:
        parsed = urlparse(raw)
    except Exception:
        return ""
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        return ""
    host = parsed.hostname.lower().removeprefix("www.")
    if host == "dart.fss.or.kr" or host.endswith(".dart.fss.or.kr"):
        return ""
    return raw.rstrip("/")


def dart_profile_roots(corp_code: str) -> list[str]:
    if not re.fullmatch(r"\d{8}", corp_code):
        return []
    url = f"{DART}/dsae001/selectPopup.ax?selectKey={corp_code}"
    text = request("GET", url, referer=DART + "/").text
    roots: list[str] = []

    for row in re.findall(r"(?is)<tr[^>]*>(.*?)</tr>", text):
        label = re.sub(r"<[^>]+>", " ", html_lib.unescape(row))
        label = re.sub(r"\s+", " ", label).strip().lower().replace(" ", "")
        if "홈페이지" not in label and "website" not in label and "irhome" not in label:
            continue
        hrefs = re.findall(r"(?is)href\s*=\s*['\"]([^'\"]+)['\"]", row)
        plain_urls = re.findall(r"https?://[^\s'\"<>]+", html_lib.unescape(row), flags=re.I)
        plain_domains = re.findall(r"(?i)(?:www\.)?[a-z0-9][a-z0-9.-]+\.[a-z]{2,}(?:/[a-z0-9._~:/?#@!$&()*+,;=%-]*)?", re.sub(r"<[^>]+>", " ", row))
        for raw in hrefs + plain_urls + plain_domains:
            candidate = normalize_root(urljoin(url, html_lib.unescape(raw))) if raw.startswith("/") else normalize_root(raw)
            if candidate and candidate not in roots:
                roots.append(candidate)

    if not roots:
        for raw in re.findall(r"https?://[^\s'\"<>]+", html_lib.unescape(text), flags=re.I):
            candidate = normalize_root(raw)
            if candidate and candidate not in roots:
                roots.append(candidate)
    return roots[:6]


def main() -> None:
    news_success = 0
    disclosure_success = 0
    dart_success = 0
    corp_code_success = 0
    official_root_success = 0
    shapes = []
    sample_receipt = ""

    for code in CODES:
        news_url = f"{BASE}/news/list/integration?itemCode={code}&page=1&pageSize=20"
        disc_url = f"{BASE}/stock/domestic/disclosure?code={code}&page=1&pageSize=20"
        news = get_json(news_url)
        disclosure = get_json(disc_url)
        n = evidence_count(news, NEWS_TITLE_KEYS)
        d = evidence_count(disclosure, DISC_TITLE_KEYS)
        receipts, _search_html, corp_code = dart_search(code)
        roots = dart_profile_roots(corp_code) if corp_code else []
        news_success += int(n > 0)
        disclosure_success += int(d > 0)
        dart_success += int(bool(receipts))
        corp_code_success += int(bool(corp_code))
        official_root_success += int(bool(roots))
        if receipts and not sample_receipt:
            sample_receipt = receipts[0]
        shapes.append({
            "code": code,
            "news_candidates": n,
            "naver_disclosure_candidates": d,
            "direct_dart_receipts": len(receipts),
            "dart_corp_code_found": bool(corp_code),
            "dart_official_roots": len(roots),
        })

    assert news_success >= 2, ("news endpoint shape not recognized", shapes)
    assert disclosure_success >= 1, ("disclosure endpoint shape not recognized", shapes)
    assert dart_success >= 2 and sample_receipt, ("direct DART company search did not expose receipt numbers", shapes)
    assert corp_code_success >= 2, ("DART public search did not expose corporation codes", shapes)
    assert official_root_success >= 2, ("DART corporate overview did not expose registered official roots", shapes)

    main_url = f"{DART}/dsaf001/main.do?rcpNo={sample_receipt}"
    filing = request("GET", main_url, referer=f"{DART}/")
    text = filing.text
    assert len(text) >= 1000, ("DART filing page unexpectedly small", sample_receipt, len(text))
    has_viewer = "viewer.do" in text or "viewDoc(" in text
    assert has_viewer, ("DART filing page has no recognizable viewer reference", sample_receipt)

    print(
        "CONTEXT_SOURCE_LIVE_PASS",
        json.dumps(
            {
                "sources": shapes,
                "dart_receipt_sample": sample_receipt,
                "dart_main_bytes": len(filing.content),
                "viewer_reference": has_viewer,
                "corp_code_success": corp_code_success,
                "official_root_success": official_root_success,
            },
            ensure_ascii=False,
        ),
    )


if __name__ == "__main__":
    main()
