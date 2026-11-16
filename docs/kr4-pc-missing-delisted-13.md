# KR4: 가격 미확보 13종목 — 상장폐지 영향 분리 감사

## 발견
- GitHub Actions [#43779878087](https://github.com/irewon1-lgtm/Kook/actions/runs/43779878087)의 실제 `.pc-work/price-collection.jsonl`에서 수집 대상 2,649개, 정상 가격 2,636개, `MISSING` 13개 확인.
- 13개의 오류 문자열이 모두 `ValueError: No completed daily prices`임을 대조.
- 기존 `kr4-pc-site`의 실제 종목 상세정보에서 코드/회사명/기록 시장을 확인.
- 독립적인 상장폐지 목록에서 동일 13종목이 모두 2026-11-13 이전 상장폐지된 것으로 보고됨.
  - [Kokstock 상장폐지 종목 목록](https://www.kokstock.com/stock/delisting.asp)
  - [KRX KIND 상장폐지 확인](https://kind.krx.co.kr/investwarn/delcompany.do?currentPageSize=30&method=searchDelCompanySub)
  - [휴맥스홀딩스 KRX 공시](https://kind.krx.co.kr/external/2026/07/23/000954/20260723002008/10081.htm)

## 결과 및 한계
- 추가 지연·HTTP 503 때문에 가격 수집이 실패했다는 증거는 13종목에서 없음.
- 주 거래소에서 상장폐지된 종목은 2026-11-13 KOSPI/KOSDAQ 정상 최신 종가가 없는 것이 예상되며, `MISSING` 가격을 현재 가격/0원으로 대체하지 않는다.
- **현재 2,649개 분석 유니버스에는 과거 상장폐지 종목이 포함되어 있을 가능성이 높다.** 따라서 2,649개를 *현 시점 상장된 종목 수*라고 표기하면 안 된다.
- 본 감사의 상장폐지 근거는 주로 제3자 목록이며, KRX 종목별 원문 전체 대조를 완료했다는 의미는 아니다. 따라서 감사 판정은 `HOLD`이고 운영 유니버스 재선정 승인 전까지 원본 점수/순위/후보 목록을 수정하지 않는다.

## 실행
```bash
python scripts/audit_pc_missing_delisted.py --collection .pc-work/price-collection.jsonl
python -m pytest -q tests/test_pc_missing_delisted.py
```

## 완료 기준
1. 전체 2,649개 수집 로그에 13종목만 `MISSING`으로 기록돼 있는지 코드 기준 검증
2. 해당 13개가 모두 상장폐지로 별도 보고되는지 KRX 공식 종목별 확정 자료 확보
3. 기존 계산식 보호 상태에서 *유니버스 갱신 규칙*과 역사 자료 보존 정책을 승인받은 뒤 적용
4. 변경 시 2,649 대상/4지표/순위/최종후보 영향을 정량 비교하고 Android·PC 동시 회귀 테스트

### 주의
**단위시험 PASS는 KRX 공식 상장폐지 종목별 인증을 의미하지 않는다.**
기존 `main`, `kr4-pc-site`, Android 프로젝트, 가격·순위 공식은 수정하지 않는다.
