#!/usr/bin/env python3
"""Live smoke probe for KR4 context evidence and DART primary-source resolution.

The probe intentionally checks only metadata and one public DART viewer path. It
never stores third-party filing bodies in the repository. A PASS means that the
runtime can discover current news/disclosure evidence and resolve at least one
DART receipt number to a public filing page; it does not claim that every issuer
has a comparable pair of periodic reports.
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
    "detailUrl", "articleUrl", "oid", "aid", "receiptNo", "rceptNo", "rcept_no", "officeName",
    "reportName", "disclosureTitle",
}
RECEIPT_KEYS = ("receiptNo", "rceptNo", "rcept_no", "reportNo")


def get(url: str, *, referer: str | None = None) -> requests.Response:
    last = None
    for attempt in range(3):
        try:
            headers = dict(HEADERS)
            if referer:
                headers["Referer"] = referer
            r = requests.get(url, headers=headers, timeout=15)
            r.raise_for_status()
            return r
        except Exception as exc:  # noqa: BLE001 - probe reports exact class below
            last = exc
            if attempt < 2:
                time.sleep(0.5 * (2**attempt))
    raise RuntimeError(f"{url}: {type(last).__name__}: {last}")


def get_json(url: str) -> Any:
    return get(url).json()


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


def receipt_numbers(payload: Any) -> list[str]:
    found: list[str] = []
    for obj in objects(payload):
        for key in RECEIPT_KEYS:
            value = re.sub(r"\D", "", str(obj.get(key, "")))
            if 12 <= len(value) <= 16 and value not in found:
                found.append(value)
    return found


def main() -> None:
    news_success = 0
    disclosure_success = 0
    shapes = []
    receipts: list[str] = []

    for code in CODES:
        news_url = f"{BASE}/news/list/integration?itemCode={code}&page=1&pageSize=20"
        disc_url = f"{BASE}/stock/domestic/disclosure?code={code}&page=1&pageSize=20"
        news = get_json(news_url)
        disclosure = get_json(disc_url)
        n = evidence_count(news, NEWS_TITLE_KEYS)
        d = evidence_count(disclosure, DISC_TITLE_KEYS)
        rs = receipt_numbers(disclosure)
        news_success += int(n > 0)
        disclosure_success += int(d > 0)
        receipts.extend(r for r in rs if r not in receipts)
        shapes.append({
            "code": code,
            "news_root": type(news).__name__,
            "news_candidates": n,
            "disclosure_root": type(disclosure).__name__,
            "disclosure_candidates": d,
            "dart_receipts": len(rs),
        })

    assert news_success >= 2, ("news endpoint shape not recognized", shapes)
    assert disclosure_success >= 1, ("disclosure endpoint shape not recognized", shapes)
    assert receipts, ("no DART receipt number exposed by disclosure index", shapes)

    receipt = receipts[0]
    main_url = f"{DART}/dsaf001/main.do?rcpNo={receipt}"
    filing = get(main_url, referer=f"{DART}/")
    text = filing.text
    assert len(text) >= 1000, ("DART filing page unexpectedly small", receipt, len(text))
    has_viewer = "viewer.do" in text or "viewDoc(" in text
    assert has_viewer, ("DART filing page has no recognizable viewer reference", receipt)

    print(
        "CONTEXT_SOURCE_LIVE_PASS",
        json.dumps(
            {
                "sources": shapes,
                "dart_receipt_sample": receipt,
                "dart_main_bytes": len(filing.content),
                "viewer_reference": has_viewer,
            },
            ensure_ascii=False,
        ),
    )


if __name__ == "__main__":
    main()
