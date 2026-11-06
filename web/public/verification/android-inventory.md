# KR4 PC full parity inventory — Android 4.3.210 reference

Read-only audit against `/workspace/Kook` commit `a1676eab7bfe6b0d3765e4340504f8ff2a2d2a3f` (latest main at audit). All source paths below are relative to that repository. No Android source, data engine, generated snapshot, or service was edited. Version is a release property override: `app/build.gradle.kts:36`; its default versionName is not release evidence.

## Integration interfaces to implement first

1. **Universe/details:** `StockRepository.getAllStocks()` → all 2,649 enriched `StockSummary`; `getStockDetail(issuerId)` → `StockDetail(summary, report, news=[], filings=[])`, null for unknown issuer. Entry points: `app/src/main/java/com/krstock/v3/data/repository/StockRepository.kt:221`, `:244`. Call the original repository so Korean metric education, missing reasons, peer comparisons and reports remain exact.
2. **All metric combinations:** `DynamicRankingEngine.rank(stocks, selectedMetricIds)` → map keyed by issuerId with `issuerId, score, rank, selectedMetricCount, isEligible`; preserve all 15 non-empty subsets. `app/src/main/java/com/krstock/v3/data/ranking/DynamicRankingEngine.kt:27`. Compute globally before filtering/searching. Engine tie order is score descending then issuerId ascending, ranks are consecutive (no tied rank sharing).
3. **Actual five detail tabs:** `요약 / 4지표 / 종합해석 / 체크·출처 / 차트`, not three: `app/src/main/java/com/krstock/v3/ui/screens/StockDetailScreen.kt:230`. Inputs: issuerId plus current snapshot revision, evidence/history asynchronous loads, safety and candidate state.
4. **Interpretation oracle:** `QuarterlyTrendAnalyzer.enrich(IntegratedInterpretationEngine.analyze(stock, evidence), history)` → all 20 `IntegratedAnalysis` fields. Entry points `data/analysis/IntegratedInterpretationEngine.kt:61`, `data/analysis/QuarterlyTrendAnalyzer.kt:9`; exact call chain `ui/screens/StockDetailScreen.kt:130`. Export original Kotlin results, not rewritten 614-line interpretation logic.
5. **Financial data:** parse/install current-date validated financial snapshot with `FinancialSafetySnapshotParser.parse(JSONObject, expectedSnapshotDate, expectedCodes)` then `FinancialSafetyRuntimeStore.install`; access `FinancialSafetyRepository.get/getAll/currentSnapshot`. `data/stage/Stage45Data.kt:24`, `:225`, `:245`. Preserve optional facts, exact integers, official evidence, HOLD versus FAIL versus NOT_APPLICABLE.
6. **Candidates:** `FinalCandidateSnapshotUpdater.parseAndValidate(text, expectedSnapshot, activeStocks, expectedPriceCutoffDate)` after safety is installed → `FinalCandidateRuntimeStore.install` → `FinalCandidateRepository.getFinalCandidate/getFinalCandidateMap`. `data/candidate/FinalCandidateSnapshotUpdater.kt:105`, `data/candidate/FinalCandidateRuntimeStore.kt:18`, `data/candidate/FinalCandidateRepository.kt:18`. Current candidate data is stage 4–7 remote format; never replace with legacy `FinalCandidateEngine.select`.
7. **Evidence:** `ContextEvidenceRepository.load(issuerId)` → `EvidenceBundle`; original collector and diff engine are JVM-compatible. `data/evidence/ContextEvidenceRepository.kt:24`. Do not substitute an empty bundle and imply evidence has loaded or that causes were verified. A snapshot may truthfully retain `loaded=false` or `loaded=true,error=...`.
8. **History:** `QuarterlyHistoryRepository.parseIssuer(root, issuerId, source)` is `internal` and callable from the same oracle Kotlin module. `data/history/QuarterlyHistoryRepository.kt:92`. Its root loader has the only Android dependency in this path (`Context` and assets); thin JVM Context/AssetManager shims suffice to compile without changing Android sources.
9. **Price chart:** independent OHLCV repository and calendar aggregation; quote must remain latest daily observation even on weekly/monthly charts: `ui/components/PriceChartPanel.kt:61`. Full data/chart collection recon is a separate task; UI checklist is included below.

All short paths beginning `data/` or `ui/` below are under `app/src/main/java/com/krstock/v3/`.

## Screen and action checklist

### App navigation and lifecycle

| Requirement | Exact source | Data / behavior |
|---|---|---|
| Five primary menus 홈, 종목순위, 종목검색, 관심종목, 더보기 | `ui/navigation/NavyAppNavigation.kt:53` | Each real destination is retained; search is ranking screen with initialSearch=true. |
| Home/ranking pair retains page state and supports horizontal swipe | `ui/navigation/NavyAppNavigation.kt:66`, `:115` | PC tabs/sidebar may adapt gesture; state/route remains usable. |
| Issuer route opens at summary, preserves origin menu | `ui/navigation/NavyAppNavigation.kt:90` | Detail issuerId stable; back returns origin; search from detail opens search. |
| Bottom menu availability | `ui/navigation/NavyAppNavigation.kt:160` | Baseline hides bottom nav on detail tabs 0–3 and shows it on chart. PC can use consistent navigation without losing destinations. |
| Bootstrap current known-good data immediately, update async | `MainActivity.kt:48` | Quant refresh cannot be blocked by optional safety/candidate failures. |
| APK update prepared/install/later/mandatory dialog | `MainActivity.kt:68` | Android-specific installation; PC counterpart should identify its own build/release and refresh availability, not attempt APK installation. |
| Actual browser history/back semantics | `ui/navigation/NavyAppNavigation.kt:218` | Preserve user route/filter state; Android predictive back internals are platform-specific. |

### Shared header, snapshot and stock row

| Requirement | Exact source | Data / behavior |
|---|---|---|
| KR4 국내주식 identity + 성장 · 수익성 · 가치 · 흐름 | `ui/screens/HomeScreen.kt:115` | Home/ranking shared header. |
| Search icon performs real navigation/toggle | `ui/screens/HomeScreen.kt:158`; `ui/screens/StockListScreen.kt:98` | Home opens search route; ranking toggles search; detail opens search route. |
| Bell/update-status action | `ui/screens/HomeScreen.kt:164` | Opens scrollable dialog with source, financial date, price cutoff, last refresh/check, message, failure, Close. Dialog: `HomeScreen.kt:133`. |
| Live successful refresh indicator | `ui/screens/HomeScreen.kt:172` | Only `Source.LIVE && failure==null`. |
| Snapshot provenance matches actual displayed dates | `ui/screens/HomeScreen.kt:219`, `:243` | Mismatch says 확인 중; never label stale metadata as current. |
| Snapshot coverage, dates, timestamps, failure-retain-last-good | `ui/screens/HomeScreen.kt:225`; `ui/screens/StockListScreen.kt:252` | Coverage = complete/total, percent; date/source/check/update labels; failure text. KST display at `HomeScreen.kt:321`. |
| Universe counters: total / ranked / missing | `ui/screens/HomeScreen.kt:327` | Ranking counters depend on selected metrics, home on original complete rank. |
| Row body opens correct issuer detail | `ui/screens/HomeScreen.kt:388` | Key = market:issuerId; full Korean name and code distinguish issuers. |
| Row rank, name, code, market, sector, relative score | `ui/screens/HomeScreen.kt:389` | rank null displays —; score null 미산출; decimal score 1 place. Home also shows four mini metrics; ranking shows market/sector. |
| Safety badge in every stock row | `ui/screens/HomeScreen.kt:399`, `:430` | Separate from score; optional safety null remains 미확보. |
| Separate row expand-chevron action | `ui/screens/HomeScreen.kt:432` | Expands inline evidence instead of navigating. Expanded state keyed by issuerId. |
| Expanded row evidence retains all 4 metric raw values, relative positions or missing reasons | `ui/screens/HomeScreen.kt:437` | Includes status badge, identity, correct selected metric score label, plus original Stage4/5 compact content. |
| Raw-value format and market-position thresholds | `ui/screens/HomeScreen.kt:484`, `:490` | M03 one decimal 배; other metrics one decimal %; unavailable N/A; top10 ≥90, top25 ≥75, upper-mid ≥50, lower-mid ≥25, else lower. |
| Stage4/5 expanded compact content | `ui/screens/HomeScreen.kt:95`; `ui/screens/StockListScreen.kt:211` | Candidate, safety, valuation-band classification. Home showRatios=true; ranking false. |

### Home

| Requirement | Exact source | Data / behavior |
|---|---|---|
| Prominent entire-universe exploration action | `ui/screens/HomeScreen.kt:69`, `:192` | Total, ranked, missing counts; opens complete ranking. |
| Snapshot compact card with coverage ring | `ui/screens/HomeScreen.kt:73`, `:234` | True available completeness ratio, including zero total guard. |
| Original four-metric top-five preview | `ui/screens/HomeScreen.kt:57`, `:90` | Existing rankOrder sorted, first 5; must not accidentally show final-candidate ranking or selected-combination order. |
| Full ranking action with count | `ui/screens/HomeScreen.kt:102` | All ranked + missing records accessible via ranking page. |
| Data coverage for each metric, count and percent | `ui/screens/HomeScreen.kt:104`, `:503` | isAvailable counts for M01, M02, M03, M04 over all 2,649. |
| Four-metric interpretation framework | `ui/screens/HomeScreen.kt:519` | 성장=매출, 수익성=영업이익률, 가치=실적 PER, 흐름=6개월 주가. |
| Data source attribution | `ui/screens/HomeScreen.kt:107` | KRX KIND / OpenDART / Naver price and EPS. |

### Ranking and search

| Requirement | Exact source | Data / behavior |
|---|---|---|
| Defaults | `ui/screens/StockListScreen.kt:51` | Empty query, all markets, completeOnly=false, safety=ALL, combination sort, all four metrics, picker initially collapsed. |
| Initial search destination opens/focuses search | `ui/screens/StockListScreen.kt:88` | Scrolls top, focus and keyboard; desktop should focus input. |
| Search company / code / sector / market, trim and case-insensitive | `ui/screens/StockListScreen.kt:69` | Clear-text trailing action at `:126`; toggling visibility does not clear the query. |
| Market filters 전체/KOSPI/KOSDAQ | `ui/screens/StockListScreen.kt:132` | Apply after global ranking calculation. |
| Metric picker collapse/expand | `ui/screens/StockListScreen.kt:136` | Shows selected count, names and weight 100%, 50%, 33.3%, 25%. |
| Four independent metric toggles | `ui/screens/StockListScreen.kt:147` | M01 매출 증가율, M02 영업이익률, M03 실적 PER, M04 6개월 상승률; last selected metric cannot be deselected. All 15 subsets. |
| Selected-metrics complete-only filter | `ui/screens/StockListScreen.kt:157` | Eligibility based on selected metrics, not original all-four completion. |
| Sorting menu | `ui/screens/StockListScreen.kt:164`, `:287` | 종합점수 순 = rank null last/rank/code; 종목코드 = issuerId then market; 회사명 = name then code; selection indicators. |
| Safety filters 전체 / 미확보만 / 재무위험만 | `ui/screens/StockListScreen.kt:40`, `:75`, `:176` | HOLD includes absent safety; FAIL only true FAIL; no reranking. |
| Filtered result count and eligible result count | `ui/screens/StockListScreen.kt:183` | Visible when any query/market/complete/safety filter active; show both all matching and rank-eligible matching. |
| All records, including selected-metric missing | `ui/screens/StockListScreen.kt:200` | Missing: 선택지표 결측 · 조합순위 보류, no synthetic score. |
| Empty results state with guidance | `ui/screens/StockListScreen.kt:190` | 검색 결과가 없습니다 / change query, market, completeness. |
| Full list continuation after first 10 rows | `ui/screens/StockListScreen.kt:214` | It scrolls to remaining list, does not load a limited universe. Desktop pagination/virtualization must retain every row. |
| Ranking explanation info dialog | `ui/screens/StockListScreen.kt:225` | Selected metric count, equal weights/names, missing data retained, safety does not change scores; Close and 홈으로 actions. |

### Favorites

| Requirement | Exact source | Data / behavior |
|---|---|---|
| Persistent user-selected issuer-code set independent from market cache | `ui/navigation/FavoritesScreen.kt:29`, `:59` | Android preferences `kr4_navy_ui_preferences/favorite_codes`; web can use namespaced localStorage and storage event. |
| Favorite count and empty guidance | `ui/navigation/FavoritesScreen.kt:71`, `:78` | No seeded fake favorites. |
| Add-stock dialog | `ui/navigation/FavoritesScreen.kt:143`, `:160` | Opens with current set; draft selections do not persist until confirm. |
| Search add picker by company or code | `ui/navigation/FavoritesScreen.kt:168` | Trim, ignore case; alphabetical company/code order; selected count and search result count; empty state. |
| Multi-select checkbox row | `ui/navigation/FavoritesScreen.kt:193` | Full universe, stable issuer IDs, checked states and name/code/market. |
| 선택 완료 and 취소 | `ui/navigation/FavoritesScreen.kt:215` | Confirm commits set, Cancel/outside dismiss discards draft. |
| Favorite rows open detail and independent remove action | `ui/navigation/FavoritesScreen.kt:96`, `:114` | Name, code/market, sector; removal must not trigger row navigation. |
| Saved issuer missing from new universe | `ui/navigation/FavoritesScreen.kt:124` | Show actual code with missing-identity message and delete action; do not silently discard. |

### More

| Requirement | Exact source | Data / behavior |
|---|---|---|
| App/site identity and version | `ui/navigation/NavyMoreScreen.kt:45` | Own PC build label with Android reference version separate. |
| Data source, actual snapshot and price dates, refresh/check times and message | `ui/navigation/NavyMoreScreen.kt:50` | Use date-matching guard; KST conversion at `:111`. |
| Refresh button executes real data refresh | `ui/navigation/NavyMoreScreen.kt:64` | Loading label 데이터 갱신 중…; disabled while running; reset prior error. |
| Refresh error retains last good dataset | `ui/navigation/NavyMoreScreen.kt:60`, `:73` | Explicit failure explanation; no emptying valid data. |
| Source material card | `ui/navigation/NavyMoreScreen.kt:87` | KRX identities, OpenDART financials, Naver price/actual EPS, no estimated missing metrics. |

## Data and engine contract

### Exact source models

`data/model/StockModels.kt:49`: stock fields `issuerId,name,market,sector,listingDate,isFinancial,isLossMaking,m01RevGrowth,m02OpMargin,m03Per,m04Price6m,compositeScore,rankOrder,isCompositeComplete,asOfDate,dataSource,status`.

`data/model/StockModels.kt:32`: each metric retains `id,nameKo,rawValue,percentileScore,unit,isAvailable,reason,description,interpretation,caution,source,basis,asOfDate,peerComparison`. Metric availability in repository requires both raw and percentile (`StockRepository.kt:106`); null and zero differ. Reasons translated by original `reasonKo` at `:70` (DART absent/prior mismatch/outlier, financial-sector exclusion, zero EPS, Naver absent/error, short six-month history, unknown reason fallback). M03 negative EPS is an available negative PER with percentile zero, not a missing value (`StockRepository.kt:57`).

`data/model/StockModels.kt:17`: peer fields `groupLabel,basis,sampleSize,median,deltaFromMedian,relativeToMedianPct,betterThanMedian,higherIsBetter,reason`, plus isSufficient. `PeerBenchmarkEngine.enrich` at `data/peer/PeerBenchmarkEngine.kt:48` uses exact KRX sector first, transparent sector-family fallback, or insufficient status; never invent a peer median. Original repository performs enrichment at `StockRepository.kt:212`.

`data/model/StockModels.kt:74`: CompanyReport fields `issuerId,recentChanges,changeReason,positiveFactors,riskFactors,counterArguments,nextVerificationConditions,updatedAt,oneLineView,quantSummary,businessQuality,valuationView,momentumView,dataLimitations`. Keep original report, not a replacement paragraph. StockDetail model at `:107`; report generation at `data/repository/StockRepository.kt:316`.

### Dynamic rank engine

`data/ranking/DynamicRankingEngine.kt:31` rejects empty metric set or unknown IDs. At `:36` every selected metric must be available and have percentile; score is arithmetic mean; unselected missing metrics are irrelevant. At `:43` scores descend, issuerIds ascend. Output retains null rank/score for missing selected inputs. UI sorts and filters afterwards (`ui/screens/StockListScreen.kt:66`, `:68`). Export all 15 subsets keyed by sorted metric IDs; original 4-metric rankOrder remains an independently retained source field.

### Integrated interpretation (all 20 fields)

Schema: `data/model/IntegratedAnalysisModels.kt:69`. Fields: `regimeTitle,thesis,industryContext,combinationMeaning,causeInvestigation,consequence,falsifiers,confidenceNote,evidenceHighlights,industryKpiGuide,disclosureDiffNote,causeCandidates,nextQuarterWatch,businessState,priceBurden,causeConfidence,futureUncertainty,quarterlyTrend,quarterlySignal,quarterlyCoverage`.

Input dependencies: StockSummary including real sector/isFinancial/completeness and all metric peer fields; `IndustryInsightCatalog.profileFor(sector)` and `IndustryKpiCatalog.profileFor(sector)`; loaded/error-aware EvidenceBundle including original bodies and DisclosureDiff; QuarterlyHistory after enrichment.

| Engine behavior that must survive | Source |
|---|---|
| Peer-aware levels: first sufficient peer betterThanMedian + percentile; fallback percentile thresholds; unknown metric stays unknown | `data/analysis/IntegratedInterpretationEngine.kt:104` |
| Six regimes: QUALITY_GROWTH, REVENUE_FIRST_GROWTH, EFFICIENCY_WITHOUT_GROWTH, FUNDAMENTAL_COMPRESSION, FINANCIAL_PARTIAL, PARTIAL | `data/analysis/IntegratedInterpretationEngine.kt:133` |
| Regime title and thesis overlays for valuation/momentum combinations | `data/analysis/IntegratedInterpretationEngine.kt:145`, `:164` |
| Industry economics, growth/margin/value lenses, first five KPIs, sector-family caveat | `data/analysis/IntegratedInterpretationEngine.kt:194` |
| KPI guide and next-quarter chain | `data/analysis/IntegratedInterpretationEngine.kt:213`, `:448` |
| Four-factor combination narrative and peer insufficiency caveat | `data/analysis/IntegratedInterpretationEngine.kt:221` |
| Competing causal hypotheses per regime, supporting and contradicting evidence | `data/analysis/IntegratedInterpretationEngine.kt:265`, `:311` |
| Evidence not loaded = pending, total evidence fetch failure = cause verification withheld | `data/analysis/IntegratedInterpretationEngine.kt:320` |
| Cause score = supports*2 + diffSupport*3 − contra*2 − diffContra*2; strongest ≥4 has strongest support, never a proven cause | `data/analysis/IntegratedInterpretationEngine.kt:337` |
| DART body diff versus lower-confidence list fallback, management explanation changes | `data/analysis/IntegratedInterpretationEngine.kt:389`, `:406` |
| Consequence and explicit falsifiers, including missing-data/loss/financial caveats | `data/analysis/IntegratedInterpretationEngine.kt:421`, `:459` |
| Four separate conclusions businessState/priceBurden/causeConfidence/futureUncertainty, not one score | `data/analysis/IntegratedInterpretationEngine.kt:491`, `:500`, `:509`, `:524` |
| Confidence includes metric availability, sufficient peers, primary source count, highlight count, body-diff availability | `data/analysis/IntegratedInterpretationEngine.kt:545` |
| 12 evidence signals from title and first 20k body text; source tier priority and regime signal order; up to six highlights | `data/analysis/IntegratedInterpretationEngine.kt:567`, `:587`, `:72` |

### Quarterly history and trend

Model `data/model/QuarterlyHistoryModels.kt:3`: point `period,fiscalYear,quarter,revenue,operatingIncome,operatingMargin,revenueYoY,revenueQoQ,scope,basis,sourceFile,reason`; history at `:23`: `issuerId,points,loaded,source,generatedAt,error` plus comparisonScope, comparablePoints, availability/direct/reconstructed counts, has4/8, trailingGap.

`QuarterlyHistoryRepository.load` at `data/history/QuarterlyHistoryRepository.kt:22`: in-memory cache; fresh validated local ≤12h; remote full snapshot; stale valid local offline; bundled asset fallback. Root schema=1, full records≥2,000, target periods 4..8 (`:76`). `parseIssuer` at `:92` sorts/takes last 8, finite numbers only; absent issuer gives loaded=true/error=QUARTERLY_HISTORY_NOT_AVAILABLE.

Trend requires consecutive suffix **ending at newest target quarter**, same CFS/OFS, available revenue, max 8; missing latest fails closed rather than shifting backward (`QuarterlyHistoryModels.kt:37`, `:55`). UI must retain all source points/table, while analytical comparablePoints can be shorter.

`QuarterlyTrendAnalyzer.enrich` at `data/analysis/QuarterlyTrendAnalyzer.kt:9` adds quarterlyTrend, quarterlySignal, quarterlyCoverage. Before load show loading; fewer than 4 refuses trend, explains latest gap/scope/shortage (`:23`). With 4+ uses latest four revenue/margin directions, latest quarter amount/YoY/QoQ/margin, growth acceleration ≥3pp / deceleration ≤−3pp, inflections and direct/reconstructed quality. With 8 compares latest/prior 4 summed revenue and **revenue-weighted** margins, not mean margins (`:99`, `:151`). Direction counts adjacent changes above 2% normalized threshold (`:196`); safe percentages reject prior≤0/nonfinite/extreme (`:214`). Formatting comes from `formatPct` / `formatAmount` at `:220`, `:224`.

### Context evidence, original sources and DART diff

`data/model/IntegratedAnalysisModels.kt:18`: evidence `id,kind,title,source,publishedAt,url,sourceTier,receiptNo,bodyText`. Kinds NEWS/DISCLOSURE/IR/OFFICIAL; tiers DART_PRIMARY(1), COMPANY_IR(2), COMPANY_OFFICIAL(3), TRUSTED_MEDIA(4), OTHER(5). Bundle at `:47`: issuerId, separate news/disclosures/ir/official arrays, disclosureDiff, loaded,error; all de-duplicates kind:id:title and sorts tier then newest. primarySourceCount uses tiers≤3.

`ContextEvidenceRepository.kt:24` caches per issuer. Fetch at `:35` collects news via Naver front news list, Naver domestic disclosures, direct DART index, and `OfficialSourceCollector.collect`. Each source error is recorded independently; successful evidence survives partial failure. Direct DART plus Naver disclosures are normalized/deduplicated and capped at 24 (`:75`); periodic DART report bodies hydrated (`:173`), body max120k, then original `DisclosureDiffEngine.compare` at `:84`. Category parse max12 (`:20`). Original URLs/receiptNos/body/source tier are semantically important, not presentation-only strings.

`DisclosureDiffEngine.compare` (`data/evidence/DisclosureDiffEngine.kt:30`): sorted periodic reports; two normalized bodies≥400 chars allow full text diff. Nine topics: risk/uncertainty, CAPEX/capacity, order/customer/demand, pricing/mix, cost/yield, new business/product, R&D/clinical/approval, financing/dilution, management outlook/strategy. Body diff fields `available,scope,currentTitle,previousTitle,newlyAppeared,disappeared,strengthened,weakened,note`; count-based strength thresholds at `:61`. Without bodies, timeline-only fallback recent6 vs prior6 at `:89`, `available=false`, explicit not-body-diff wording.

### Financial safety and valuation

`data/model/Stage45Models.kt:15`: record `issuerId,status,reasonCode,scope,debtToEquityPct,currentRatioPct,accountingIdentityGapPct,basis,facts`. Facts at `:28`: `amounts,currency,reportPeriod,filingDate,corporationCode,receiptNo,sourceUrl,sourceFile,sourceZipSha256,correctionStatus,correctionCheck,verified,validationIssues,correctionReconciled,correctionProofSha256,reconciliationError`. Amounts are exact nullable Long integers; serialize as strings where JS precision would lose values. Snapshot at `:47`: policy/date/source/file, 4 status counts, records.

`FinancialSafetySnapshotParser.parse` (`data/stage/Stage45Data.kt:24`) validates schema1/2, exact `STAGE4_BS_SAFETY_V1`, active date, source/file, debt threshold400/current70/identity tolerance5, exact identity universe and coverage accounting. Schema2 uses `VerifiedFinancialFactsParser` with exact source calculations. PASS/FAIL must match actual policy; missing inputs remain HOLD; financial sector may NOT_APPLICABLE. Runtime current() only returns matching date (`:238`). UI missing/null safety is withheld data, never PASS. None of this changes four-metric score/rank.

`ValuationBandPolicy.classify` (`data/stage/Stage45Data.kt:258`): finite positive trailing PER and percentile in 0..100 only; ≥80 LOW_RELATIVE_PER, ≥60 BELOW_MEDIAN_PER, ≥40 MID_PER, ≥20 ABOVE_MEDIAN_PER, else HIGH_RELATIVE_PER. Labels/descriptive percentile retained, changesRank=false. Zero/negative/missing PER has no valuation band; do not infer an attractive band from loss-making PER.

### Final candidates

Repository is a view over current runtime store (`data/candidate/FinalCandidateRepository.kt:24`); public limit1..100 at `:13`. Store validates unique IDs, sequential candidate ranks, PASS rows and max100 (`FinalCandidateRuntimeStore.kt:18`), then exposes only when exact active snapshot and **all** rows still have current safety PASS (`:32`); any stale/missing safety removes whole shortlist. No legacy fallback.

Use original remote `FinalCandidateSnapshotUpdater.parseAndValidate` (`:105`) for stage4–7 format. It checks schema/policy/date/price cutoff/universe2649/count, unique known six-character codes, safety PASS, active quant identity/date/completeness, exact source rank/composite and four metric snapshots, positive trailing PER, descriptive valuation method/no-rerank and required flags (`:111` through `:181`). Do not regenerate from rank alone. Model `data/model/FinalCandidateModels.kt:16` retains `schemaVersion,policyVersion,candidateRank,sourceRank,issuerId,name,market,sector,compositeScore,financialSnapshotDate,priceCutoffDate,m01,m02,m03,m04,selectionFlags,researchStatus,selectionReasonKo,financialSafetyStatus,financialSafetyReason,financialSafetyBasis,debtToEquityPct,currentRatioPct,accountingIdentityGapPct,valuationBand,valuationBandKo,valuationPercentile`.

### Update state

`data/update/SnapshotAutoUpdater.kt:45` result fields `source,snapshotDate,priceCutoffDate,message,networkSucceeded,refreshedAt,lastAttemptAt,failure,revision`. Sources LIVE/CACHE/BACKUP/BUNDLED at `:43`. Original refresh serializes warm-cache/backups/network/validation and refuses downgrades (`:87`); core quant publishes before optional safety/candidates at `:169`. Optional refresh completion increments revision and repaints mounted screens (`:182`, `:192`). Web must retain provenance and distinguish actual update time from last attempted check; preserve current valid data on errors.

## Chart UI parity checklist

| Requirement | Exact source | Data / behavior |
|---|---|---|
| Default six months, candle mode=true, daily interval; state preserved | `ui/components/PriceChartPanel.kt:53` | Intervals DAY/WEEK/MONTH; periods1,3,6,12 months. |
| Latest daily close quote independent of displayed interval | `ui/components/PriceChartPanel.kt:61`, `:327` | Close, absolute daily change and percent, arrow, OHLC, date, volume; last daily bar not aggregated weekly/monthly candle. |
| Quote missing / previous day missing states | `ui/components/PriceChartPanel.kt:343`, `:364` | 가격 자료 대기 / 전 거래일 비교자료 미확보; do not compute against zero or unavailable. |
| Adjustment provenance badge | `ui/components/PriceChartPanel.kt:97` | Label from original PriceAdjustmentProvenance, including unverified status. |
| Daily/weekly/monthly choices | `ui/components/PriceChartPanel.kt:112` | Original calendar aggregation, not fixed-size trading-day chunks. |
| 1m /3m /6m /1y period choices | `ui/components/PriceChartPanel.kt:128` | Actual first/last observed dates. |
| Candle/line switch | `ui/components/PriceChartPanel.kt:142` | Line uses closes; candle uses true OHLC. |
| Loading and no-data/detail errors | `ui/components/PriceChartPanel.kt:158` | loading only when result==null; missing bars displays result.detail or 조회된 차트가 없습니다. |
| Observed plot, price/date axes | `ui/components/PriceChartPanel.kt:175`, `:397`, `:473` | Red rises/blue falls; candle wicks high/low, body open/close, real close line; 5 price ticks/≤6 observed dates. No fake candles, interpolation or flat fills. |
| Selected-period last candle OHLC, volume and real trading-days count | `ui/components/PriceChartPanel.kt:191` | Volume nullable→미확보; weekly/monthly candle aggregates actual tradingDays. |
| Source enum and cutoff | `ui/components/PriceChartPanel.kt:237` | Never label live simply because bundle fetch succeeded. |
| Lowest/highest, bar count, selected period raw change, exact dates | `ui/components/PriceChartPanel.kt:250` | Candle extrema use high/low; line extrema use closes. Change last.close/first.close−1. |
| Start period and partial final candle note | `ui/components/PriceChartPanel.kt:268` | Last periodEnd>cutoff means partial week/month candle. |
| Source detail, adjustment reason/treatment/source URL | `ui/components/PriceChartPanel.kt:278` | Full provenance, not badge alone. |
| Corporate-action and missing-session caution; chart separate from ranking | `ui/components/PriceChartPanel.kt:293` | No adjustment claim without evidence; no fabrication on missing dates. |

## Original Kotlin oracle reuse plan

Use a separate JVM Gradle subproject under `web/oracle` whose sourceSets **include original Android pure Kotlin files by path**, with Kotlin JVM/JDK17+, `org.json`, and `kotlinx-coroutines-core`. Do not copy/rewrite their algorithms or modify their package declarations. Compile wrapper code in same module to call internal parsers. Export JSON plus source-commit/snapshot/hash metadata at build time; browser consumes checked export and owns presentation/interaction only.

Pure source closure for stock/ranks/interpretation: original `data/generated/GeneratedKospiMaster.kt`, `GeneratedKosdaqMaster.kt`, `GeneratedRealQuantSnapshot.kt`; all `data/model/*.kt` (in particular issuer, real quant, stock, integrated, quarterly, stage, candidate models); `data/repository/StockRepository.kt`; `data/peer/PeerBenchmarkEngine.kt`; `data/ranking/DynamicRankingEngine.kt`; `data/analysis/IndustryInsightCatalog.kt`, `IndustryKpiCatalog.kt`, `IntegratedInterpretationEngine.kt`, `QuarterlyTrendAnalyzer.kt`. These import no android classes. Core domain oracle needs no Context stub.

Add original `data/stage/Stage45Data.kt`, `VerifiedFinancialFactsParser.kt`; `data/candidate/FinalCandidateEngine.kt`, `FinalCandidateRuntimeStore.kt`, `FinalCandidateRepository.kt`; `data/evidence/ContextEvidenceRepository.kt`, `OfficialSourceCollector.kt`, `DisclosureDiffEngine.kt`. These are also pure JVM with org.json/coroutines (HTTP collectors optional execution).

To call original internal quarterly/candidate parsers instead of reimplementing, compile `data/history/QuarterlyHistoryRepository.kt`, `data/candidate/FinalCandidateSnapshotUpdater.kt` with a tiny oracle-only `android.content.Context` shim exposing `applicationContext`, `filesDir`, `assets`, and `android.content.res.AssetManager.open(name)` backed by pinned local source files. Call `parseIssuer`, `parseAndValidate` directly, so there is no unrequested network or Android scheduling. If original updater source adds other Context operations, implement only its compile-required shim surface in oracle module. Do not include UI/WorkManager/MainActivity.

Export order: (1) original stocks from generated bundled snapshot (or original validated quant parser+install if newer pinned JSON is selected); (2) financial safety parser with active codes/date, install; (3) candidate parser with exact active stocks/date/cutoff, install; (4) original history parseIssuer for each issuer; (5) optional captured real EvidenceBundle per issuer, with truthful collection state/source errors; (6) original full analysis/enrich; (7) all15 global rank maps; (8) all models/reports/safety/candidates/valuation, counts and provenance. Preserve engine Korean strings verbatim, including line breaks and arrays.

For interactive evidence refresh, browser cannot directly assume Naver/DART cross-origin support. A PC-only local backend can invoke original JVM collector/engine and return typed bundles/analysis, or a build collector may snapshot real evidence. Static no-evidence export is a truthful offline state but not full live evidence parity; keep clear loaded/error states, source links and make refresh data pipeline concrete before claiming full parity.

Recommended parity validation: export source SHA plus payload hashes; compare all2649 StockSummary identities/metric nulls/peer records and source rank/score; compare all15 original rank subsets including missing eligibility and tie order; assert all20 integrated fields exact for every issuer from identical inputs; compare original history comparable suffix/coverage and selected 4/8-quarter cases; exact current-date safety/candidate sets; exercise five menus, five detail tabs, inline expanders, dialogs, favorites persistence, real refresh/error retention, all chart controls/provenance. Tests should call the original Kotlin oracle with fixed real inputs; do not mirror a rewritten interpretation engine with a matching rewritten test.

## Detail checklist supplement

See `/workspace/kr4-pc-work/detail-inventory.md` for a deeper line-by-line audit of the five StockDetailScreen tabs, Stage4/5 cards, all evidence/financial dialogs and individual UI output fields. That supplement is part of this inventory.
