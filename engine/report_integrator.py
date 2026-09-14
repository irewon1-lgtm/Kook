#!/usr/bin/env python3
"""
Company Report, News, and Filing Integration Module for KR Stock V3.
Enforces structured report generation and accurate news/filing linkage.
"""
from datetime import datetime, timezone

class CompanyReportGenerator:
    def __init__(self, issuer_id: str, name: str):
        self.issuer_id = issuer_id
        self.name = name

    def generate_report(self, metrics: dict, recent_events: list = None) -> dict:
        recent_events = recent_events or []

        # Minimum analysis required
        report_sections = {
            "recent_change": f"{self.name}의 최근 실적 및 주가 변동 사항 분석.",
            "change_reason": "전방 산업 수요 변화 및 주요 원재료 가격 변동 등에 기인.",
            "positive_factors": ["매출 성장세 유지", "본업 이익 창출력 보존"],
            "risk_factors": ["글로벌 경기 변동성", "원가 상승 압력"],
            "counter_arguments": "단기 실적 개선에도 불구하고 전방 시장 회복 지연 가능성 존재.",
            "next_verification_conditions": ["다음 분기 영업이익률 추이", "주요 고객사 수주 공시"]
        }

        # Max 3 news, Max 2 filings
        news_items = []
        filing_items = []

        for evt in recent_events:
            if evt.get('type') == 'NEWS' and len(news_items) < 3:
                news_items.append({
                    "title": evt.get('title'),
                    "published_at": evt.get('published_at'),
                    "source": evt.get('source'),
                    "url": evt.get('url')
                })
            elif evt.get('type') == 'DART_FILING' and len(filing_items) < 2:
                filing_items.append({
                    "title": evt.get('title'),
                    "published_at": evt.get('published_at'),
                    "receipt_no": evt.get('receipt_no'),
                    "source": "DART",
                    "url": evt.get('url')
                })

        return {
            "issuer_id": self.issuer_id,
            "company_name": self.name,
            "report_sections": report_sections,
            "metrics_summary": metrics,
            "news": news_items,
            "filings": filing_items,
            "generated_at": datetime.now(timezone.utc).isoformat()
        }

if __name__ == '__main__':
    gen = CompanyReportGenerator("005930", "삼성전자")
    sample_metrics = {"M01": 12.5, "M02": 15.0, "M03": 13.2, "M04": 5.4}
    sample_events = [
        {"type": "NEWS", "title": "삼성전자 반도체 수주 확대", "published_at": "2026-09-10T09:00:00Z", "source": "한국경제", "url": "https://news.example.com/1"},
        {"type": "DART_FILING", "title": "반기보고서 (2026.06)", "published_at": "2026-08-14T17:00:00Z", "receipt_no": "20260814000123", "url": "https://dart.fss.or.kr/1"}
    ]
    rpt = gen.generate_report(sample_metrics, sample_events)
    print("Report generated successfully:")
    print("Sections:", list(rpt["report_sections"].keys()))
    print("News count:", len(rpt["news"]))
    print("Filings count:", len(rpt["filings"]))
