#!/usr/bin/env python3
"""One-shot live smoke probe for the public context endpoints used by KR4.

This is intentionally tiny: it verifies that the unauthenticated Naver mobile
JSON endpoints used by the Android evidence loader still answer and contain
recognizable evidence objects. It does not scrape full articles and does not
store third-party content in the repository.
"""
from __future__ import annotations

import json
import time
from typing import Any

import requests

BASE = "https://m.stock.naver.com/front-api"
CODES = ("005930", "000660", "000250")
HEADERS = {
    "Accept": "application/json,text/plain,*/*",
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) KR4/1.0",
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


def get_json(url: str) -> Any:
    last = None
    for attempt in range(3):
        try:
            r = requests.get(url, headers=HEADERS, timeout=10)
            r.raise_for_status()
            return r.json()
        except Exception as exc:  # noqa: BLE001 - probe reports exact class below
            last = exc
            if attempt < 2:
                time.sleep(0.5 * (2**attempt))
    raise RuntimeError(f"{url}: {type(last).__name__}: {last}")


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


def main() -> None:
    news_success = 0
    disclosure_success = 0
    shapes = []
    for code in CODES:
        news_url = f"{BASE}/news/list/integration?itemCode={code}&page=1&pageSize=20"
        disc_url = f"{BASE}/stock/domestic/disclosure?code={code}&page=1&pageSize=20"
        news = get_json(news_url)
        disclosure = get_json(disc_url)
        n = evidence_count(news, NEWS_TITLE_KEYS)
        d = evidence_count(disclosure, DISC_TITLE_KEYS)
        news_success += int(n > 0)
        disclosure_success += int(d > 0)
        shapes.append({
            "code": code,
            "news_root": type(news).__name__,
            "news_candidates": n,
            "disclosure_root": type(disclosure).__name__,
            "disclosure_candidates": d,
        })

    assert news_success >= 2, ("news endpoint shape not recognized", shapes)
    assert disclosure_success >= 1, ("disclosure endpoint shape not recognized", shapes)
    print("CONTEXT_SOURCE_LIVE_PASS", json.dumps(shapes, ensure_ascii=False))


if __name__ == "__main__":
    main()
