# PC 종목 상세 UI 구현·검증 보고서

담당 파일: `web/src/screens/Detail.tsx`, `web/src/screens/detail.css`. Android·원본 Kotlin 엔진·원천 JSON은 수정하지 않았습니다. 검토 기준은 `docs/superpowers/specs/2026-11-06-kr4-pc-design.md`, `detail-inventory.md`, `android-inventory.md`, 승인된 `detail.jpg`/`chart.jpg`입니다.

## 다섯 탭 대응

| 원래 기능 | PC 위치·동작 |
| --- | --- |
| 요약 | 원본 4지표 종합점수, 전체 등록종목 수, 기존 순위, 가용 지표 수, 재무·가격 기준일, 실데이터 상태, 네 지표 실제 값/시장 상대점수/위치 막대, 후보 원본, 재무현황, Stage5 밴드, 핵심 판독, 기본정보 |
| 4지표 | 요약 점수/네 지표 재표시, 재무자료 진입, 금융업 비교 제외 설명, 네 지표 교육문·해석·주의·출처·기준일·결측사유, 업종 피어 표본/기준/중앙값/차이/유불리, 원시 지표값·상대점수 |
| 종합해석 | 아래 엔진 20개 필드 모두 별도 표시, 전체 분기 숫자 표와 원본·회계범위·계산 방식·결측 이유, 공시 비교 원본 |
| 체크·출처 | 원래 기업 리포트 14필드 모두 표시, 위험/반대/다음 확인, 실제 근거 원문 링크·본문·발표일·출처·등급·접수번호, 수집/확인 시각, 개별 부분 실패, 기존 리포트 뉴스/공시 원본 |
| 차트 | 차트 담당 `PriceChart`를 동일 issuerId/실제 priceCutoffDate로 탭 선택 시에만 마운트. 0–3 탭의 공통 시세도 같은 `usePriceData` 저장소 사용 |

탭 다섯 개는 항상 표시됩니다. ArrowLeft/ArrowRight/Home/End의 자동 선택, roving tabindex, 유효한 tab/tabpanel 연결, 패널별 창 스크롤 위치 복원, 키보드 포커스 표시를 제공합니다. 미방문 탭은 맨 위에서 시작합니다. 즐겨찾기·뒤로·갱신은 부모 콜백으로 연결했습니다. 저장·검색·알 수 없는 코드·딥링크는 부모 앱 책임입니다.

## IntegratedAnalysis 20필드 대응

| 원본 필드 | 종합해석 표시 위치 |
| --- | --- |
| regimeTitle | CORE READ 제목, 요약 핵심 판독 |
| thesis | CORE READ 전문, 요약 핵심 판독 전문 |
| industryContext | 이 산업에서 숫자를 읽는 법 |
| combinationMeaning | 네 지표 조합의 숨은 뜻 |
| causeInvestigation | 왜 이런 숫자가 나왔는지 최근 근거 점검 |
| consequence | 이 조합이 이어질 때 생길 일 |
| falsifiers | 이 해석이 틀렸다고 볼 조건 전체 목록 |
| confidenceNote | 해석의 신뢰도와 한계 전문 |
| evidenceHighlights | 해석에 실제로 사용한 최근 단서 전체 행과 원문 링크/본문 접근 |
| industryKpiGuide | 산업별 핵심 확인 지표 |
| disclosureDiffNote | 공시 변화와 경영진 설명 비교 |
| causeCandidates | 서로 경쟁하는 원인 후보 전체 목록 |
| nextQuarterWatch | 다음 분기 확인할 연결고리 전체 목록 |
| businessState | 사업 상태 |
| priceBurden | 가격 부담 |
| causeConfidence | 원인 확신도 |
| futureUncertainty | 미래 불확실성 |
| quarterlyTrend | 실제 분기 숫자가 어떻게 변했는지 전문 |
| quarterlySignal | 분기 신호 |
| quarterlyCoverage | 실제 엔진 분기 비교 범위 배지 |

Kotlin에서 생성된 문장·배열을 그대로 React 텍스트로 표시합니다. 브라우저에서 원인·기업 해석을 다시 생성하지 않습니다.

## 리포트·재무·분기·후보 세부 보존

CompanyReport의 `issuerId, recentChanges, changeReason, positiveFactors, riskFactors, counterArguments, nextVerificationConditions, updatedAt, oneLineView, quantSummary, businessQuality, valuationView, momentumView, dataLimitations` 전부 체크·출처에 연결했습니다.

재무현황 배지 및 별도 `근거 및 참고정보` 버튼이 같은 네이티브 모달을 엽니다. 판정·이유·자료 기준일·보고기간·공시일·연결/별도·검증상태·다섯 원문 금액·통화·비율·회계오차·기존 기준·법인/접수번호·정정/오류/해시·출처를 모두 표시합니다. `amountsExact`의 문자열을 우선해 원 단위 정밀도를 보존합니다. 비안전 정수만 있고 정확한 문자열이 없으면 정확한 원 단위 미확인으로 표시합니다. 0은 실제 0이며 null은 미확보입니다. `공식 공시 원문 열기`는 정확한 DART 접수번호 14자리 URL에만 제공합니다. Escape, 닫기 버튼, 모달 바깥 클릭을 지원합니다.

분기 표는 원본 points만 최신순으로 렌더링합니다. 실제 수록수가 8보다 적으면 행을 늘리지 않습니다. 매출/영업이익/영업률/매출 YoY·QoQ를 표시하며, 영업이익 YoY·QoQ는 같은 CFS/OFS 범위의 정확한 전년/전분기 원본 금액 및 양수 전기 영업이익이 있을 때만 단순 비율을 표시합니다. 이 브라우저 산술값은 엔진의 추세 판단을 대체하지 않는다는 설명을 포함했습니다. 원본 원 금액, 회계범위, DIRECT_3M와 FY_MINUS_Q3 등 재구성 근거, sourceFile/reason, 비교 가능 분기·직접/재구성 수·최신 결측·생성일·오류·추가 범위 원본도 접근 가능합니다.

후보는 존재할 때만 표시합니다. 후보순위와 기존순위를 구분합니다. 기본 카드 아래 기본 펼침 원본 목록이 후보 객체의 모든 키와 중첩 metric/selectionFlags를 재귀 렌더링해 정책/스키마/기준일/각 원본 지표/선정 사유/재무판정/상대 PER/추가 필드를 누락하지 않습니다. 미확보 후보를 순위만으로 생성하지 않습니다.

EvidenceBundle은 실제 4개 카테고리의 자료를 표시합니다. 모든 원문 본문은 `수집한 원문 본문 전체 보기 (N자)` 접근을 제공하며 전문을 생략하지 않습니다. 본문 미확보는 분명하게 표시합니다. 원래 `disclosureDiff`의 모든 필드와 sourceStates의 부분 오류/확인 시각을 별도 표시합니다. 수신 시각을 원문의 발표일 또는 실제 확인 시각으로 바꾸지 않습니다.

## 실제 실행한 검증

2026-11-06 UTC 작업 중 Linux Chromium 155.0.8059.79, 실제 Kotlin exporter 산출물 `oracle-v2/details/*.json`, 실제 수집된 가격 `price-cache/prices/005930.json`을 읽는 격리된 Vite UI 하네스를 사용했습니다. 하네스의 가격 수신만 동일 파일 읽기로 연결했으므로 이 결과는 UI 검증이며 실제 앱의 manifest/hash/IndexedDB 수신 경로 검증은 아닙니다.

- `npx tsc -b --pretty false`: 종료코드 0.
- 실제 삼성전자: 5개 탭, 네 원본 지표 설명/해석/계산 근거/출처/주의 전문, 20개 해석 필드, 14개 리포트 필드, 원본 분기 행 수, 다섯 정확한 재무 금액, 공식 DART URL, Escape 닫기, ArrowRight/End 탭 선택, 실제 차트 마운트, 관심/갱신 콜백을 실행해 통과했습니다.
- 1024/1280/1366/1920/2560 CSS 너비 × 22/26/30px 문서 글자 크기 × 5개 탭 = 75개 조건에서 document.scrollWidth ≤ viewportWidth를 확인했습니다. 확대 후 핵심내용 가로 넘침이 없었습니다.
- 실제 재무 FAIL(003060), HOLD(395400), NOT_APPLICABLE(474930) 각 배지와 모달 상태 확인 통과.
- 실제 적자 101680: 음수 PER 표시 및 Stage5 밴드 미적용 확인 통과.
- 실제 후보 013700: 모든 54개 비어 있지 않은 재귀 원본 값의 접근 확인 통과.
- 명시적 시뮬레이션: 빈 history/null financial에서 분기 표 행 생성 없음, 미확보 재무현황 표시 확인 통과. 이것은 실제 회사 자료를 바꾼 상태 경로 테스트이며 실제 수집 결과로 주장하지 않습니다.
- 브라우저 pageerror 0.

근거: `/workspace/kr4-pc-work/detail-browser-result.json`, `detail-edge-result.json`; 캡처 `detail-summary-1366.png`, `detail-metrics-1366.png`, `detail-analysis-1366.png`; 실행 하네스 `detail-harness/`.

## APK와의 의도적 차이·미검증 범위

PC는 승인된 네이비/파란색 위계와 큰 글자를 유지하면서 넓은 화면에서 의미 있는 두 열을 사용합니다. Android의 가로 스와이프 대신 클릭/키보드 탭을 사용합니다. 원래 화면에서 별도 표시하지 않았던 해석 보조 필드와 기업 리포트 필드도 전부 공개했습니다. 긴 원문 본문은 명확한 전체 보기 펼침으로 접근합니다. Android 재무 대화상자를 PC 네이티브 모달로 옮겼고 실제 정정 근거·해시도 추가 표시했습니다.

실제 삼성전자 fixture에는 뉴스 10건·공시 24건, 총 34건이 있지만 bodyText가 모두 비어 있고 원래 수집 오류 `official=official=IllegalStateException`가 남아 있습니다. 따라서 실제 원문 본문 수집 성공을 주장하지 않습니다. UI는 이 부분 확인과 본문 미확보를 유지합니다. 본문을 확보한 별도 실제 fixture가 없으므로 실제 비어 있지 않은 DART 본문 전문에 대한 브라우저 검증은 미실행입니다.

이 보고서는 공개 배포 사이트, Windows/LG 물리 모니터, APK 실기기, 최종 공개사이트 22/26/30px 매트릭스 검증을 대신하지 않습니다. 수신·배포·차트 24모드·엔진 전수 검증은 각 담당자의 별도 실행 증거가 필요합니다. 전체 프로젝트 최종 PASS를 주장하지 않습니다.
