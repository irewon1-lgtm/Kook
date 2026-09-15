#!/usr/bin/env python3
"""Live smoke probe for the public context sources used by KR4.

The mobile Naver disclosure JSON is a useful list source but does not currently
expose DART receipt numbers. Research Forensics v2 therefore uses Naver's public
PC disclosure table only as a receipt-number index, then fetches the actual
primary-source filing text from DART.

CI validates the whole public chain:
    mobile news/disclosure list -> PC disclosure index -> DART receipt -> DART viewer.
If any link breaks, the app's primary-source analysis fails closed.
"""
from __future__ import annotations

import html
import json
import re
import time
from typing import Any
from urllib.parse import urlencode

import requests

BASE = "https://m.stock.naver.com/front-api"
NAVER_PC_NOTICE = "https://finance.naver.com/item/news_notice.naver"
DART_MAIN = "https://dart.fss.or.kr/dsaf001/main.do"
DART_VIEWER = "https://dart.fss.or.kr/report/viewer.do"
CODES = ("005930", "000660", "000250")
HEADERS = {
    "Accept": "application/json,text/plain,*/*",
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Safari/537.36 KR4/2.0",
    "Referer": "https://m.stock.naver.com/",
}
PC_HEADERS = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "User-Agent": HEADERS["User-Agent"],
    "Referer": "https://finance.naver.com/",
}
DART_HEADERS = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "User-Agent": HEADERS["User-Agent"],
    "Referer": "https://dart.fss.or.kr/",
}

NEWS_TITLE_KEYS = {"title", "articleTitle", "newsTitle", "headline", "subject"}
DISC_TITLE_KEYS = {"title", "reportName", "disclosureTitle", "reportNm", "subject", "name"}
EVIDENCE_KEYS = {
    "datetime", "date", "publishedAt", "publishDate", "writeDate", "createdAt",
    "rceptDt", "receiptDate", "disclosureDate", "regDate", "url", "link", "endUrl",
    "detailUrl", "articleUrl", "oid", "aid", "receiptNo", "rceptNo", "rcept_no",
    "rcpNo", "officeName", "reportName", "disclosureTitle",
}
RECEIPT_KEYS = ("rceptNo", "receiptNo", "rcept_no", "rcpNo", "rceptno", "receiptno")
URL_KEYS = ("url", "link", "endUrl", "detailUrl", "articleUrl")


def request(url: str, *, headers: dict[str, str], as_json: bool = False, encoding: str | None = None) -> Any:
    last = None
    for attempt in range(3):
        try:
            r = requests.get(url, headers=headers, timeout=12)
            r.raise_for_status()
            if as_json:
                return r.json()
            if encoding:
                r.encoding = encoding
            return r.text
        except Exception as exc:  # noqa: BLE001 - smoke probe reports exact class
            last = exc
            if attempt < 2:
                time.sleep(0.5 * (2**attempt))
    raise RuntimeError(f"{url}: {type(last).__name__}: {last}")


def get_json(url: str) -> Any:
    return request(url, headers=HEADERS, as_json=True)


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


def receipt_from_object(obj: dict[str, Any]) -> str | None:
    for key in RECEIPT_KEYS:
        value = str(obj.get(key, "")).strip()
        if re.fullmatch(r"\d{14}", value):
            return value
    for key in URL_KEYS:
        value = html.unescape(str(obj.get(key, "")))
        match = re.search(r"(?:rcpNo|rceptNo|receiptNo)\s*=\s*(\d{14})", value, re.I)
        if match:
            return match.group(1)
        match = re.search(r"\b(20\d{12})\b", value)
        if match and "dart" in value.lower():
            return match.group(1)
    return None


def disclosure_receipts(payload: Any) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for obj in objects(payload):
        receipt = receipt_from_object(obj)
        if receipt and receipt not in seen:
            seen.add(receipt)
            result.append(receipt)
    return result


def pc_disclosure_receipts(code: str) -> list[str]:
    """Extract DART receipts from the public Naver PC notice table.

    Naver serves this legacy page as EUC-KR. We deliberately accept several link
    spellings because the table has changed markup historically; a valid DART
    receipt remains a 14-digit identity beginning with 20.
    """
    result: list[str] = []
    seen: set[str] = set()
    for page in (1, 2):
        url = f"{NAVER_PC_NOTICE}?{urlencode({'code': code, 'page': page})}"
        text = request(url, headers=PC_HEADERS, encoding="euc-kr")
        decoded = html.unescape(text).replace("&amp;", "&")
        patterns = (
            re.compile(r"(?:rcpNo|rceptNo|receiptNo)\s*=\s*(20\d{12})", re.I),
            re.compile(r"dart\.fss\.or\.kr[^'\"<>\s]{0,300}?\b(20\d{12})\b", re.I),
        )
        for pattern in patterns:
            for match in pattern.finditer(decoded):
                receipt = match.group(1)
                if receipt not in seen:
                    seen.add(receipt)
                    result.append(receipt)
        if result:
            break
    return result


def public_dart_document_probe(receipt: str) -> dict[str, Any]:
    main_url = f"{DART_MAIN}?{urlencode({'rcpNo': receipt})}"
    main = request(main_url, headers=DART_HEADERS)
    assert len(main) > 2_000, ("DART main document unexpectedly short", receipt, len(main))
    assert receipt in main, ("DART main lost receipt identity", receipt)

    normalized = html.unescape(main).replace("&amp;", "&")
    patterns = [
        re.compile(
            r"viewDoc\(\s*['\"](?P<rcp>\d{14})['\"]\s*,\s*['\"](?P<dcm>\d+)['\"]\s*,\s*['\"](?P<ele>\d+)['\"]\s*,\s*['\"](?P<off>\d+)['\"]\s*,\s*['\"](?P<len>\d+)['\"]\s*,\s*['\"](?P<dtd>[^'\"]+)['\"]",
            re.I,
        ),
        re.compile(
            r"rcpNo['\"]?\s*[:=]\s*['\"](?P<rcp>\d{14})['\"].{0,1000}?dcmNo['\"]?\s*[:=]\s*['\"](?P<dcm>\d+)['\"].{0,1000}?eleId['\"]?\s*[:=]\s*['\"](?P<ele>\d+)['\"].{0,1000}?offset['\"]?\s*[:=]\s*['\"](?P<off>\d+)['\"].{0,1000}?length['\"]?\s*[:=]\s*['\"](?P<len>\d+)['\"]",
            re.I | re.S,
        ),
    ]
    match = None
    for pattern in patterns:
        match = pattern.search(normalized)
        if match:
            break
    assert match, ("DART viewer metadata not recognized", receipt, normalized[:700])

    params = match.groupdict()
    params.setdefault("dtd", "dart3.xsd")
    viewer_url = DART_VIEWER + "?" + urlencode({
        "rcpNo": params["rcp"],
        "dcmNo": params["dcm"],
        "eleId": params["ele"],
        "offset": params["off"],
        "length": params["len"],
        "dtd": params.get("dtd") or "dart3.xsd",
    })
    viewer = request(viewer_url, headers=DART_HEADERS)
    text = re.sub(r"(?is)<script[^>]*>.*?</script>", " ", viewer)
    text = re.sub(r"(?is)<style[^>]*>.*?</style>", " ", text)
    text = re.sub(r"<[^>]+>", " ", html.unescape(text))
    text = re.sub(r"\s+", " ", text).strip()
    assert len(viewer) > 1_000 and len(text) > 200, ("DART viewer content too short", receipt, len(viewer), len(text))
    assert re.search(r"[가-힣]", text), ("DART viewer lost Korean filing text", receipt)
    return {
        "receipt": receipt,
        "main_bytes": len(main.encode("utf-8")),
        "viewer_bytes": len(viewer.encode("utf-8")),
        "text_chars": len(text),
        "viewer_url_shape": "viewer.do?rcpNo+dcmNo+eleId+offset+length+dtd",
    }


def main() -> None:
    news_success = 0
    disclosure_success = 0
    pc_receipt_success = 0
    shapes = []
    receipts: list[str] = []

    for code in CODES:
        news_url = f"{BASE}/news/list/integration?itemCode={code}&page=1&pageSize=20"
        disc_url = f"{BASE}/stock/domestic/disclosure?code={code}&page=1&pageSize=20"
        news = get_json(news_url)
        disclosure = get_json(disc_url)
        n = evidence_count(news, NEWS_TITLE_KEYS)
        d = evidence_count(disclosure, DISC_TITLE_KEYS)
        mobile_receipts = disclosure_receipts(disclosure)
        pc_receipts = pc_disclosure_receipts(code) if not mobile_receipts else []
        found_receipts = mobile_receipts or pc_receipts
        news_success += int(n > 0)
        disclosure_success += int(d > 0)
        pc_receipt_success += int(bool(pc_receipts))
        receipts.extend(found_receipts)
        shapes.append({
            "code": code,
            "news_root": type(news).__name__,
            "news_candidates": n,
            "disclosure_root": type(disclosure).__name__,
            "disclosure_candidates": d,
            "mobile_dart_receipts": len(mobile_receipts),
            "pc_dart_receipts": len(pc_receipts),
        })

    assert news_success >= 2, ("news endpoint shape not recognized", shapes)
    assert disclosure_success >= 1, ("disclosure endpoint shape not recognized", shapes)
    assert receipts, ("no DART receipt number exposed by either public disclosure index", shapes)
    # Current expected path is PC fallback. If mobile begins supplying receipts in
    # the future this assertion is intentionally not mandatory.
    if all(s["mobile_dart_receipts"] == 0 for s in shapes):
        assert pc_receipt_success >= 1, ("PC DART receipt fallback failed", shapes)

    dart = public_dart_document_probe(receipts[0])
    print("CONTEXT_SOURCE_LIVE_PASS", json.dumps(shapes, ensure_ascii=False))
    print("DART_PRIMARY_DOCUMENT_LIVE_PASS", json.dumps(dart, ensure_ascii=False))


if __name__ == "__main__":
    main()
