# KR4 Remaining Completion Implementation Plan

> Execute continuously in the existing cloud checkout on a feature branch. Use systematic debugging, test-first behavior changes and independent parallel work only for disjoint files. One fresh whole-branch review before main integration. The user explicitly requests execution through publication, so no design/merge/publish confirmation gate is pending.

**Goal:** Collect verifiable official financial facts for the complete2649 universe, expose their independent status/evidence, label unverified chart adjustments honestly, and deliver a fully exercised stable-signed upgrade with a real-screen gallery.

**Spec:** The user's complete remaining-work directive in this conversation, based on39c64ee / KR4 4.3.208.

**Architecture:** Keep quant/ranking unmodified; publish date-matched Stage4 as an independent artifact so its outage cannot block M01–M04. Preserve official raw data and hashes, independently verify code/corporation, period, currency/unit/scope and latest corrections; unverified records remainHOLD. Extend existing native screens rather than recreate them. Prices remain sourced from actual daily OHLCV and official adjustment evidence is mandatory before any normalization.

**Global constraints:**2649 discoverable; missing-metric reasons visible; risk never changes quant rank; unchanged400% debt/70% current/5% identity thresholds; same4metric formulas/M04; redFAIL/orangeHOLD; DAY/WEEK/MONTH and1/3/6/12months andline/candle; no fabricated facts/trading days; com.krstock.v3 and original1070e82beee574cd29ffd2dc3e693fd7f7862374f16d1606567db303ab152477 signing cert; update must preserve existing data/settings and original install identity.

**Review focus:** corrections newer than bulk; preferred-stock vs corporation identity; mixed period/currency/scope/units; valid0 vs missing; Stage4 outage cannot block good quant; theme/viewport/large-font interactions; actual screenshots distinctly label controlled fixtures; public/install/build/manifest hashes agree; manual vs scheduled success and emulator vs physical-device evidence are distinct.

## Task1: Official financial provenance and raw facts
Files: scripts/dart_filing_provenance.py, scripts/collect_financial_safety.py, tests/test_financial_safety_provenance.py, tests/test_dart_filing_provenance.py.
- [ ] Capture actual official OpenDART BS ZIP/hash and publicDART filing responses. Record current thresholds before modifications.
- [ ] RED tests: stock/name mapping, exact integer units, mixed periods/scope, conflicting account duplicates, missing vs0, actual filing dates/latest amendments, unchanged ratio boundaries.
- [ ] Implement strict collected facts/provenance; keep unavailable reasons and all2649 identities. Collect financial-sector raw facts while preservingN/A policy.
- [ ] GREEN all financial Python tests and real full-universe collection, compare displayed source fields and expose coverage/reasons.

## Task2: Independent publish and Android receipt/evidence
Files: .github/workflows/stage4567-v2-refresh.yml, app/src/main/java/com/krstock/v3/data/{model/Stage45Models.kt,stage/Stage45Data.kt,stage/FinancialSafetySnapshotUpdater.kt}, ui/components/Stage45Components.kt, regression tests.
- [ ] RED tests: separate-file receive, dates/identities/raw ratio validation, badStage4 does not alter quant ranks, changedrisk alone leaves all scores/combinations invariant.
- [ ] Publish evidence/financial_safety_snapshot.json independently; wire manual, schedule and post-quant workflow triggers; optional final candidates cannot block factual publication or quant.
- [ ] Android reads independent artifact and cache fail-closed, shows exact amounts/period/date/scope/source and clickable status explanations; preserve originalcolors and discoverable universe.
- [ ] GREEN JVM/Python, actual GitHub publication→Android receive→screen comparison.

## Task3: Chart adjustment honesty
Files: data/price/* and ui/components/PriceChartPanel.kt, associated tests, official-source evidence report.
- [ ] Investigate actual split/reverse-split discontinuities and official adjustment semantics. RED test current missing adjustment label.
- [ ] Apply verified adjustments consistently toOHLC only if independently supported, never twice; otherwise preserve actual rawOHLC and show수정주가 미확인. No M04/ranking changes.
- [ ] GREEN source parsing and real daily/week/month aggregation tests.

## Task4: Complete actual UI evidence and gallery
Files: Android instrumentation, scripts/build_ui_gallery.py, .github/workflows/android-build.yml and device evidence.
- [ ] Exercise home/search/all2649/15metric combinations/detail5tabs/risk4states,24chartcombinations,source5states,background/relaunch; verify light/dark and phone/tablet onAndroid14.
- [ ] Save original uneditedPNG plus metadata. Separate actual market data from controlled fixtures; mobileHTML gallery andZIP contain originals and a manifest.
- [ ] Fix actual observed clipping/overlap/scroll/touch issues with regression assertions; record physical-device availability and never infer a physical run.
- [ ] Extend stable-upgrade gate to preserve settings and verify latestfacts, allstock access and daily/week/month controls in installedrelease.

## Task5: Full regression and actual automation
- [ ] Run existing Python/JVM/Android suites and new tests, record pass/fail/skip individually; retain failing tests and originalquality floors.
- [ ] Run actual manual collection and inspect source coverage/reasons; distinguish latest observed scheduled event from manual/rerun. Never label a dispatch as schedule.
- [ ] Fresh independent whole-branch review, resolve important findings byRED→GREEN. PushPR, run actualAndroid14CI and inspect originalPNG/galleries; repair failures and rerun.

## Task6: Main integration and fixed-sign release
- [ ] Merge after required checks; run mainCI and Android14 existing4.3.208→higher fixed-sign upgrade withoutuninstall, withUID/firstInstall/private-data/settings/relaunch/currentdata/chart checks.
- [ ] Download actualpublicAPK; verifypackage/version/originalcert/public=installed=build=manifestSHA256.
- [ ] Deploy/download PNG/HTML/ZIP evidence; report PR/maincommits,CI,financialcoverage/riskreasons,adjustmentstatus,exacttestcounts,upgradeproof,manual-vs-scheduled and anyunverifieditems.
