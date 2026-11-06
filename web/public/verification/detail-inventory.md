# Stock detail parity inventory

Read-only source audit of `/workspace/Kook` at HEAD `a1676eab7bfe6b0d3765e4340504f8ff2a2d2a3f`. All paths below are relative to that repository. This document was written outside the repository. Android release names are environment driven: `app/build.gradle.kts:36` (`KR4_VERSION_CODE`) and `app/build.gradle.kts:37` (`KR4_VERSION_NAME`); the fallback name is older than the requested release and is not proof of the published APK's identity.

For compact references, `D` means `app/src/main/java/com/krstock/v3/ui/screens/StockDetailScreen.kt`, `S` means `app/src/main/java/com/krstock/v3/ui/components/Stage45Components.kt`, and `F` means `app/src/main/java/com/krstock/v3/ui/components/FinancialSafetyPresentation.kt`.

## Integration contract and actual navigation

- [ ] Preserve all **five** tabs, in this order: `요약`, `4지표`, `종합해석`, `체크·출처`, `차트`. Source `D:137`, `D:140`, `D:230`. Tab click and horizontal swipe update the selected page; `onTabChanged(Int)` is called whenever current page changes (`D:138`, `D:204`, `D:226`). Initial page is 0.
- [ ] Public screen contract is `StockDetailScreen(issuerId: String, onBack, onSearch: (() -> Unit)? = null, onTabChanged: (Int) -> Unit = {})` (`D:55`). Use the exact issuer ID, including letter-bearing six-character codes supported by price repository, for all joins.
- [ ] Subscribe to `SnapshotAutoUpdater.state`. Requery `StockRepository.getStockDetail(issuerId)`, `FinalCandidateRepository.getFinalCandidate(issuerId)`, and `FinancialSafetyRepository.get(issuerId)` on `revision` (`D:61`, `D:62`, `D:118`, `D:119`). Classify valuation with `ValuationBandPolicy.classify(summary.m03Per)` (`D:120`).
- [ ] Load contextual evidence asynchronously with `ContextEvidenceRepository.load(issuerId)`, initial value `EvidenceBundle(issuerId)` (`D:122`, `D:124`). Load quarterly history asynchronously with `QuarterlyHistoryRepository.load(context, issuerId)`, initial `QuarterlyHistory.empty(issuerId)` (`D:123`, `D:127`). These two loads currently depend on issuer ID, not snapshot revision.
- [ ] Run **the actual composed interpretation** `QuarterlyTrendAnalyzer.enrich(IntegratedInterpretationEngine.analyze(summary, evidence), quarterlyHistory)` whenever summary, evidence or history changes (`D:130`). Both the compact thesis in Summary and complete Analysis consume this enriched result.
- [ ] Load charts with `PriceHistoryRepository.load(context, issuerId, StockRepository.priceCutoffDate())`; trigger on issuer ID or updater `priceCutoffDate`; set a separate loading state, catch load exception to null result (`D:142`, `D:146`). Do not substitute financial snapshot date for price cutoff.
- [ ] Header always provides back, company name with `(issuerId)`, market and sector, optional search action, update status action (`D:162`, `D:167`, `D:172`, `D:176`). Search and update icons have explicit content descriptions in `HomeScreen.kt:158` and `HomeScreen.kt:164`.
- [ ] Every page scrolls independently (`D:920`). The quote panel appears above tabs on pages 0–3 and is hidden on page 4 (`D:182`). The chart page uses `PriceChartPanel` (`D:241`), whose controls/data calculations are inventoried separately by the parent task.

## Unknown issuer, empty and failure states

- [ ] An unknown exact issuer ID produces `종목을 찾을 수 없음`, message `요청한 종목코드 '<id>'가 KOSPI·KOSDAQ 마스터에 없습니다.`, the statement that no other company is substituted, header back/search/update actions, and `목록으로 돌아가기` (`D:89`, `D:95`, `D:106`, `D:110`). Return before normal detail UI.
- [ ] Missing metric values remain `N/A`; missing scores/ranks remain `미산출` / `보류`; missing listing date is `확인 필요` (`D:408`, `D:438`, `D:449`, `D:572`, `D:586`). Do not fill with zero or infer missing values.
- [ ] Final-candidate card only exists when a candidate record exists (`D:379`); FinancialSafetyCard and ValuationCard remain visible with their own missing states even when safety/band is null (`D:383`, `D:388`).
- [ ] Evidence initially shows a spinner; after loaded it shows `확인 완료` if error null, `부분 확인` otherwise (`D:694`). Source page independently shows loaded/list status and the actual connection error string (`D:904`, `D:906`).
- [ ] Quarterly card starts with coverage `확인 중` if blank and linear progress when no points and not loaded; no points after loaded shows `해당 종목은 아직 검증 가능한 4분기 이상 시계열이 없습니다.` (`D:779`, `D:830`). With points, show the latest eight rows even if comparable coverage is insufficient; interpretation still comes from the analyzer's fail-closed policy.

## Quote panel above Summary/Metrics/Analysis/Sources

- [ ] Sort bars by date; latest bar supplies close/open/high/low/volume; previous bar supplies absolute close delta and percentage `delta / previous.close * 100` (`D:256`). Format close with comma-separated won; delta includes `▲`/`▼`/`—`, signed won and two-decimal percent (`D:261`, `D:263`). Positive delta uses error/red color, negative/zero uses primary (`D:260`).
- [ ] Quote empty state is `종가 확인 중` while loading and `종가 미확보` otherwise (`D:262`); absent OHLC/volume values render em dash (`D:344`, `D:355`). Do not show invented change when fewer than two bars.
- [ ] Provenance text: CACHE => `네이버증권 · 저장자료`, LIVE => `네이버증권 · 완료 일봉`, otherwise `네이버증권 · 완료 일봉 기준`; prefix latest bar date if available (`D:268`, `D:335`).
- [ ] Retain compact sparkline of latest 24 closes (`D:285`) when space permits; flat data is drawn at mid-height. The Android layout hides it at narrow/large-font sizes and can stack quote/change (`D:254`). PC can reflow more generously while retaining the same information.
- [ ] Quote facts display `시가`, `고가`, `저가`, `거래량`, volume with `주` (`D:344`). These are completed daily bars, not an intraday quote.

## Tab 0 — Summary

- [ ] CompositeScorePanel: title `종합점수`, explanation `4지표 종합 상대평가 · 백분위와 구분`, score `%.1f점` or `미산출`; count all registered stocks from `StockRepository.registeredCount()`, basic four-metric rank or `보류`, available metrics count `/4` (`D:419`, `D:434`, `D:438`, `D:446`).
- [ ] Summary-specific composite footer: financial and price dates, `StatusBadge(summary.status)`, and either `4지표 완성 · 순위 산출 가능` or `결측값은 추정하지 않고 보류` (`D:455`). Available count uses `metric.isAvailable`, while actual raw display separately requires rawValue.
- [ ] Compact four-metric overview: headings `성장 / 수익성 / 가치 / 흐름`, names `매출 증가율 / 영업이익률 / 실적 PER / 6개월 수익률`, actual value, relative score with one decimal or `보류`, score position bar, financial as-of label (`D:529`, `D:545`, `D:556`, `D:567`, `D:570`, `D:585`). M03 raw uses two decimals `배`; all others one decimal `%` (`D:1113`).
- [ ] Optional final research candidate card: `최종 조사 후보`, `Stage6/7 · 기존 KR4 순위 재사용`, `FINAL #candidateRank`, three distinct facts `최종후보 candidateRank위`, `기존 종합 sourceRank위`, `종합점수 compositeScore(1 decimal)`, and complete `selectionReasonKo` (`S:214`, `S:229`, `S:239`, `S:245`). Never confuse candidate rank with basic composite rank.
- [ ] Financial status card and evidence entry: detailed below (`D:383`).
- [ ] Valuation card: detailed below (`D:388`).
- [ ] Core read card `핵심 판독`: `analysis.regimeTitle` and full `analysis.thesis` (`D:393`).
- [ ] Identity card: market, sector, listing date, financial date, price date (`D:405`). Retain page navigation affordance to Metrics (`D:414`).

## Tab 1 — Four metrics

- [ ] Repeat composite panel and compact four-metric overview; financial safety card/evidence entry; visible financial-industry note (`D:598`).
- [ ] Preserve note: `은행·보험·증권 등 금융업은 일반기업의 부채·유동비율과 직접 비교하지 않아 비교에서 제외합니다.` (`D:640`). It appears on the metrics page regardless of selected company.
- [ ] Section `지표별 근거와 업종 비교` explains that each card has actual value, peer median, whole-market relative position and calculation basis (`D:605`). Show four expanded cards in M01/M02/M03/M04 order (`D:613`).
- [ ] Each expanded metric card has `nameKo`, `description`, raw value (`N/A` unless `isAvailable && rawValue != null`), whole-market percentile label `시장 상대 %.1f점`, and special M03 loss-making label `적자 · PER 미적용`; other missing scores `점수 보류` (`D:970`, `D:986`, `D:993`, `D:1009`).
- [ ] If percentile exists, show proportional position bar and relative category: >=90 top 10%, >=75 top 25%, >=50 upper-middle, >=25 lower-middle, else lower (`D:1019`, `D:1139`, `D:1153`). These labels describe the whole market, not the peer sample.
- [ ] Each expanded metric always includes peer-median panel (`D:1027`). If peer null, show `피어 엔진 정보 없음` (`D:1071`). Otherwise show `groupLabel · N=sampleSize · basisLabel`, mapping exact KRX sector / standard family / insufficient to `KRX 세부업종 / 표준 피어그룹 / 표본 부족` (`D:1076`).
- [ ] With sufficient peer sample and median, show formatted median plus direct comparison (`D:1090`): unavailable raw => comparison held; missing delta => failed comparison; |delta| <=1e-12 => same level. PER compares relative percent if finite, otherwise absolute multiples, labels lower/higher and favorable/burden from `betterThanMedian`. Other metrics compare signed percentage points, above/below and advantage/disadvantage (`D:1116`).
- [ ] Insufficient peer sample shows `중앙값 미산출 · <peer.reason or 충분한 동종업계 표본 없음>` (`D:1102`). No peer median guessing.
- [ ] Available card shows full `metric.interpretation`; unavailable shows `metric.reason` or `검증 가능한 값 없음` in error color (`D:1030`).
- [ ] Metadata block conditionally includes nonblank calculation `basis`, source `source`, date `asOfDate`; always retain `주의 · metric.caution` (`D:1044`, `D:1051`).

## Financial status card, evidence drawer/dialog and official document action

- [ ] Shared `FinancialSafetyCard(safety: FinancialSafetyRecord?, snapshotDate: String)` title `재무현황`, subtitle `Stage4 재무안정성` (`S:252`, `S:271`). Keep the actual supplied summary snapshot date.
- [ ] Clickable status badge labels: PASS `자료 확인`, FAIL `재무위험`, HOLD/null `미확보`, NOT_APPLICABLE `비교제외` (`S:53`, `S:60`). Clicking the badge opens `재무자료와 판정 근거` (`S:69`, `S:85`, `S:91`). The badge dialog derives the current repository quant date; the evidence entry dialog receives the card snapshot date (`S:86`, `S:354`).
- [ ] Card status explanation separately labels existing criteria pass/fail, financial-sector comparison exclusion, or verification held (`S:286`). Display accounting identity gap with three decimals and `허용 오차 ≤ 5%`; facts verification state `검증 완료 / 검증 대기 / 미확보` plus explanation (`S:296`, `S:302`).
- [ ] Card facts: current ratio with two decimals, debt-to-equity with two decimals, equity amount. KRW equity is formatted in `억원` (divide by 100,000,000); other currencies show exact integer plus currency; missing is `미확보` (`S:307`). Footer `OpenDART · <reportPeriod or 보고기간 미확보> · 기준일 <snapshotDate>` (`S:320`).
- [ ] A separate full-width entry `근거 및 참고정보` with explanation `계산식, 실제 금액, 데이터 출처, 기준일 확인` opens the same scrollable dialog (`S:326`, `S:346`, `S:354`). This is easy to lose in a shallow PC migration.
- [ ] Dialog rows, in source order: 판정, 판정 사유, 자료 기준일, 보고기간, 실제 공시일, 연결·별도, 자료 검증, 자산총계, 부채총계, 자본총계, 유동자산, 유동부채, 통화·단위, 부채비율, 유동비율, 회계 일치 오차, 기존 판정 기준, 법인번호, 공시 접수번호, 정정 확인, optional 검증 보류 항목, 공식 공시 출처, OpenDART 원본, 원본 ZIP SHA-256 (`F:7`).
- [ ] Keep exact original integer amounts, including zero, with null as `미확보`; no conversion in dialog (`F:19`). Exact criteria string is `자본 > 0 · 부채비율 ≤ 400% · 유동비율 ≥ 70% · 회계 오차 ≤ 5%` (`F:31`). CFS/OFS translate to 연결/별도 (`F:16`).
- [ ] Correction state distinguishes ORIGINAL, latest correction reconciled, reconciliation error, correction found but values unverified, and unavailable (`F:34`). Validation issues are separate lines translated through `financialSafetyReasonKo` (`F:43`).
- [ ] Preserve all reason translations for nonpositive equity/assets/current liabilities; debt >400; current ratio <70; identity mismatch; missing balance sheet; financial industry exclusion; unavailable/collection error; unconfirmed company mapping; unreconciled correction; bulk amount/receipt mismatch; mixed period/currency; nonfinite derived ratio; missing fields (`S:471`). Preserve unknown reason code as text.
- [ ] Official original action `공식 공시 원문 열기` only exists for source URL beginning `https://dart.fss.or.kr/dsaf001/main.do?rcpNo=` followed by exactly 14 digits (`S:117`). It opens that URL. Dialog closes via `닫기` or dismiss (`S:91`).
- [ ] Retain statement that financial status does not alter four-metric scores/ranks or hide stocks (`S:122`). FAIL/HOLD/N/A are visible informational statuses, not implicit stock exclusion.

## Valuation explanatory card

- [ ] Show `Stage5 상대 PER 밴드`, subtitle `양수 trailing PER의 시장 상대 분류`, band short label badge (fallback `PER 밴드 N/A`), actual trailing PER (two decimal multiples or `N/A`), relative percentile (one decimal or `미적용`), and long band label (`S:127`, `S:380`, `S:399`, `S:405`).
- [ ] Missing-band explanations are distinct: no PER data, nonpositive PER, missing relative score, generic not applied (`S:492`). `ValuationBandPolicy` is actually in `app/src/main/java/com/krstock/v3/data/stage/Stage45Data.kt:257`.
- [ ] Preserve explanation that descriptive band does not change composite score, combination rank, or existing composite rank (`S:415`).

## Tab 2 — Integrated interpretation

- [ ] Heading `4지표 종합해석` and explanation of combined business meaning/recent evidence (`D:648`). Render full prose, not shortened cards.
- [ ] `CORE READ`: regime title + thesis (`D:664`).
- [ ] `4–8 QUARTER TREND` card: `실제 분기 숫자가 어떻게 변했는지`, coverage badge from `analysis.quarterlyCoverage`, full `analysis.quarterlyTrend` (`D:755`).
- [ ] Quarterly table: latest eight `history.points` in reverse order, columns `분기 / 매출 / YoY / 영업률`; use `QuarterlyTrendAnalyzer.formatAmount(point.revenue)` and `formatPct(point.revenueYoY/operatingMargin)`; source and generation timestamp footer (`D:789`, `D:799`, `D:806`, `D:826`).
- [ ] `INDUSTRY MAP / 이 산업에서 숫자를 읽는 법`: `analysis.industryContext` (`D:670`).
- [ ] `HIDDEN MEANING / 네 지표 조합의 숨은 뜻`: `analysis.combinationMeaning` (`D:674`).
- [ ] `NEWS & DISCLOSURE CHECK / 왜 이런 숫자가 나왔는지 최근 근거 점검`: load/error indicator, full `analysis.causeInvestigation`; if evidenceHighlights nonempty, divider and `해석에 실제로 사용한 최근 단서`, then every selected evidence row (`D:678`, `D:712`, `D:714`).
- [ ] Evidence rows show kind translated DISCLOSURE→공시, IR→IR, OFFICIAL→공식자료, NEWS→뉴스; nonblank published date and source joined by middle dot; full title (`D:842`). Model URL/body/source tier/receipt are retained as engine inputs. The baseline row has no click handler; the official financial-source action above is a distinct real action.
- [ ] `OUTCOME / 이 조합이 이어질 때 생길 일`: `analysis.consequence` (`D:727`).
- [ ] `FALSIFIERS / 이 해석이 틀렸다고 볼 조건`: every `analysis.falsifiers` bullet (`D:731`).
- [ ] Full `analysis.confidenceNote` in final information panel (`D:737`), then navigation hint to checks/sources (`D:750`).
- [ ] Output model also carries industryKpiGuide, disclosureDiffNote, causeCandidates, nextQuarterWatch, businessState, priceBurden, causeConfidence, futureUncertainty, quarterlySignal. They are not rendered as separate baseline sections; engine can incorporate them into displayed prose. Export them with the oracle so interpretation detail is not lost (`app/src/main/java/com/krstock/v3/data/model/IntegratedAnalysisModels.kt:69`).

## Tab 3 — Research checks and sources

- [ ] Heading `조사 체크포인트`; `RESEARCH CHECK / 정량 밖에서 반드시 확인할 것` card includes every `report.riskFactors` bullet under `위험·결측`, full `report.counterArguments` under `반대 근거`, every `report.nextVerificationConditions` under `다음 확인 조건` (`D:870`).
- [ ] `데이터 한계와 출처` card shows full `report.dataLimitations` (`D:880`, `D:890`).
- [ ] Source rows: 종목·업종=KRX KIND, 재무=금융감독원 OpenDART, PER·가격=네이버증권 공개 데이터, 뉴스·공시=네이버증권 공개 목록 · 실시간 확인 when evidence loaded / 불러오는 중 otherwise, 주가 마감=`StockRepository.priceCutoffDate()` (`D:901`).
- [ ] Nonblank evidence error is additionally shown as `최근 근거 연결 상태: <error>` (`D:906`).
- [ ] CompanyReport has many other fields but this baseline screen only consumes riskFactors, counterArguments, nextVerificationConditions and dataLimitations; summary and analysis use the new integrated engine, not report.oneLineView (`app/src/main/java/com/krstock/v3/data/model/StockModels.kt:74`).

## Header update status dialog

- [ ] Update icon opens modal `데이터 갱신 상태`; show SnapshotCompactCard and exact updater message, close/dismiss action (`D:64`). It works in unknown-issuer state too (`D:97`).
- [ ] Recompute total and complete counts from all stocks when snapshot revision changes. Derive displayed financial/price dates from first stock when present, updater date otherwise (`D:66`).
- [ ] Snapshot card shows source LIVE/CACHE/BACKUP/BUNDLED only if supplied dates match updater dates, otherwise `데이터 출처 확인 중`; completion `4지표 완성 complete/total`, financial and price dates, recent refresh time or pending, optional last attempt, completion percentage ring (`HomeScreen.kt:219`, `HomeScreen.kt:243`).
- [ ] Failed refresh with matching dates shows `갱신 실패 · 마지막 정상 자료를 표시합니다`; updater result fields are `source, snapshotDate, priceCutoffDate, message, networkSucceeded, refreshedAt, lastAttemptAt, failure, revision` (`HomeScreen.kt:231`; `app/src/main/java/com/krstock/v3/data/update/SnapshotAutoUpdater.kt:45`). Update icon adds an activity dot on LIVE + no failure (`HomeScreen.kt:172`).

## DTO surface needed by web/exporter

| DTO | Required source reference and fields |
| --- | --- |
| StockDetail | `app/src/main/java/com/krstock/v3/data/model/StockModels.kt:107`: summary, report, news, filings. Baseline screen does not directly render repository news/filings; it uses EvidenceBundle. |
| StockSummary | `StockModels.kt:49`: issuerId, name, market, sector, listingDate, isFinancial, isLossMaking, m01RevGrowth, m02OpMargin, m03Per, m04Price6m, compositeScore, rankOrder, isCompositeComplete, asOfDate, dataSource, status. |
| MetricValue | `StockModels.kt:32`: id, nameKo, rawValue, percentileScore, unit, isAvailable, reason, description, interpretation, caution, source, basis, asOfDate, peerComparison. |
| PeerComparison | `StockModels.kt:17`: groupLabel, basis, sampleSize, median, deltaFromMedian, relativeToMedianPct, betterThanMedian, higherIsBetter, reason; isSufficient is derived. |
| IntegratedAnalysis / EvidenceBundle | `app/src/main/java/com/krstock/v3/data/model/IntegratedAnalysisModels.kt:69` and `:47`; preserve all fields and selected evidence rows, not only thesis. |
| QuarterlyHistory / QuarterlyPoint | `app/src/main/java/com/krstock/v3/data/model/QuarterlyHistoryModels.kt:23` and `:3`; point includes period/year/quarter, revenue, operatingIncome, operatingMargin, YoY, QoQ, scope, basis, sourceFile, reason; history includes points, loaded, source, generatedAt, error. |
| FinancialSafetyRecord / Facts | `app/src/main/java/com/krstock/v3/data/model/Stage45Models.kt:15` and `:28`; retain exact five source amounts as integers/strings safe from JavaScript precision loss, currency, report/filing/source/receipt, validation and correction provenance. |
| FinalCandidateRecord | `app/src/main/java/com/krstock/v3/data/model/FinalCandidateModels.kt:16`; Summary visibly uses candidateRank, sourceRank, compositeScore, selectionReasonKo; export remaining audit/provenance fields too. |
| ValuationBand | `Stage45Models.kt:63`: code, labelKo, shortLabelKo, percentile, changesRank=false. |
| Price Result / DailyBar | `app/src/main/java/com/krstock/v3/data/price/PriceHistoryRepository.kt:38` and `:29`: bars(date,open,high,low,close,volume), source, priceCutoffDate, detail, adjustment. |

StatusBadge mapping source is `app/src/main/java/com/krstock/v3/ui/components/StatusBadge.kt:16`: REAL→실데이터; REGISTERED→등록; DEMO→데모; MISSING→결측; WAITING_FOR_AUTH→연결 대기. Preserve text, not color alone.

## Suggested parity assertions

- [ ] Exactly five reachable detail tabs and direct deep link by issuer ID; keyboard focusable tab/header/dialog controls and browser back restoration.
- [ ] Golden summary includes distinct source rank and candidate rank, original composite score and each raw/percentile value.
- [ ] Golden metric cards include all four peer comparison results with exact sample/basis and every original reason/metadata/caution.
- [ ] Golden integrated output compares all enriched engine fields before prose is rendered; loading evidence/history transitions do not erase the base analysis.
- [ ] Golden finance dialog compares exact original integer amounts, source URL, correction state, report/filing dates and all validation issues, including null and zero.
- [ ] Exercise unknown issuer, incomplete metrics, loss-making PER, insufficient peer sample, null safety, all four safety statuses, missing band, empty history, partial evidence, cache chart and missing chart.
- [ ] Check update modal date match/mismatch and failure while preserving last valid data. Verify source action actually opens the allowed official DART URL.
