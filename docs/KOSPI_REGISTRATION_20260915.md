# KOSPI 실종목 등록 스냅샷 — 2026-09-15

- 공식 원천: KRX KIND 상장법인목록 (유가증권시장)
- 등록 범위: KOSPI 상장법인 대표 종목 마스터
- 등록 종목: 832개
- KOSDAQ: 0개
- 원본 행: 847개
- 고유 KRX 종목코드: 832개
- 종목코드 규칙: 6자리 숫자만 가정하지 않고 `[0-9A-Z]{6}` 보존
- 영문 포함 코드: 4개 (`0030R0`, `0120G0`, `0126Z0`, `0220W0`)
- 마스터 SHA-256: `1f53f7973a05fc559111b23b961033c472cb0168d18732da235805f593a2882b`

## 이번 단계에서 등록한 필드

- 종목코드
- 회사명
- 시장(KOSPI)
- 업종
- 상장일
- 마스터 기준일
- 공식 출처

## 의도적으로 아직 넣지 않은 것

- 매출 증가율
- 영업이익률
- 실적 기준 PER
- 최근 6개월 주가 상승률
- 종합점수/순위
- 뉴스/공시/AI 의견

실데이터 제공자 연결 전에는 위 항목을 임의 수치로 채우지 않는다.

## 검증

`KR4 KOSPI Registration CleanPass`에서 공식 스냅샷 검증, Python reference-engine CLEAN, Android repository regression, APK assemble, Android emulator install/UI smoke, APK existence/non-zero, fake-success guard를 모두 통과해야만 PASS 처리한다.
