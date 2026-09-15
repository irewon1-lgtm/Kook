#!/usr/bin/env python3
"""Live smoke probe for KR4 context evidence and direct DART primary-source search.

Naver is used only as a recent news/disclosure index. DART receipt numbers are
resolved independently from DART's public company-by-company search because the
Naver disclosure payload does not expose rcept_no. The probe stores no filing
body in the repository.
"""
from __future__ import annotations

import json
import re
import time
from typing import Any

import requests

BASE = "https://m.stock.naver.com/front-api"
DART = "https://dart.fss.or.kr"
CODES = ("005930", "000660", "000250")
HEADERS = {
    "Accept": "application/json,text/plain,text/html,*/*",
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) KR4/2.0",
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
        except Exception as exc:  # noqa: BLE001 - probe reports exact class below
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


def dart_search_receipts(stock_code: str) -> list[str]:
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
    receipts = []
    for receipt in re.findall(r"rcpNo=(\d{12,16})", r.text):
        if receipt not in receipts:
            receipts.append(receipt)
    return receipts


def main() -> None:
    news_success = 0
    disclosure_success = 0
    dart_success = 0
    shapes = []
    sample_receipt = ""

    for code in CODES:
        news_url = f"{BASE}/news/list/integration?itemCode={code}&page=1&pageSize=20"
        disc_url = f"{BASE}/stock/domestic/disclosure?code={code}&page=1&pageSize=20"
        news = get_json(news_url)
        disclosure = get_json(disc_url)
        n = evidence_count(news, NEWS_TITLE_KEYS)
        d = evidence_count(disclosure, DISC_TITLE_KEYS)
        receipts = dart_search_receipts(code)
        news_success += int(n > 0)
        disclosure_success += int(d > 0)
        dart_success += int(bool(receipts))
        if receipts and not sample_receipt:
            sample_receipt = receipts[0]
        shapes.append({
            "code": code,
            "news_candidates": n,
            "naver_disclosure_candidates": d,
            "direct_dart_receipts": len(receipts),
        })

    assert news_success >= 2, ("news endpoint shape not recognized", shapes)
    assert disclosure_success >= 1, ("disclosure endpoint shape not recognized", shapes)
    assert dart_success >= 2 and sample_receipt, ("direct DART company search did not expose receipt numbers", shapes)

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
            },
            ensure_ascii=False,
        ),
    )


if __name__ == "__main__":
    main()
