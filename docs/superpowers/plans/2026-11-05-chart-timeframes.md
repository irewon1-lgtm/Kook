# KR4 Chart Timeframes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Continue PR #35 with daily/weekly/monthly chart selection, exact real OHLCV aggregation, verified Android 14 behavior and an upgradeable stable release.

**Architecture:** Retain the existing daily Naver price source and the PR's risk badges/full universe. Strictly parse real OHLC data, aggregate complete available daily history into calendar buckets, then apply the existing 1/3/6/12 month display window. No missing day or value is synthesized; current buckets end at the active verified price cutoff.

**Tech Stack:** Kotlin, java.time, Compose Canvas, JUnit, Android 14 instrumentation, existing GitHub signing/release workflow.

**Spec:** User's chart requirements in this chat and PR #35.

## Global Constraints
- Preserve 2,649 discoverable stocks and red financial-risk/orange unavailable flags from PR #35.
- Preserve M01–M04 formulas, ranking, quality floors, com.krstock.v3 and original signing certificate.
- Weeks Monday–Friday; months YearMonth. Open first observed trading day's open, high max, low min, close last observed trading day's close, volume sum only when all inputs are present and representable.
- Preserve 1/3/6/12 month and line/candle controls. No price interpolation or invented OHLC.

## Review Focus
- Holiday gaps and cross-year weeks must group by Monday date, never week number alone.
- Missing OHLC is discarded, not replaced by close; missing volume remains unavailable.
- Full bucket aggregation precedes range clipping; a selected range starting midweek/month cannot change its opening price.
- Partial newest buckets contain only real data through cutoff; single-bar and empty histories still have usable selectors.
- Source duplicate dates/conflicts, unsorted inputs and volume overflow must not silently invent output.

## Task 1: Real daily parsing and OHLCV grouping
Files: PriceHistoryRepository.kt, new PriceHistoryAggregation.kt, new JVM price tests.
- [ ] Write failing tests for missing OHLC, malformed/duplicate input, Monday/Friday and YearMonth boundaries, holiday gaps, volume null/overflow and range boundaries.
- [ ] Run focused tests and capture RED.
- [ ] Implement strict parser and pure aggregation; keep source/download/cache and ranking independent.
- [ ] Run focused GREEN using real captured Naver fixtures and independent literal oracles.

## Task 2: Chart controls and Android proof
Files: PriceChartPanel.kt, PriceChartAndRiskBadgeInstrumentedTest.kt, real Android test assets/provenance.
- [ ] Add daily/weekly/monthly controls and preserve period/style; label observed dates, OHLCV and partial bucket coverage.
- [ ] Test all 24 interval/period/style combinations, actual source parsing and weekly/monthly values; retain full-universe and red/orange risk assertions.
- [ ] Compile and run full JVM/Python regression suites; push the existing PR branch for Android 14 CI, inspect real screens and fix failures.

## Task 3: Integrate and release
Files: PR #35 and existing android-build.yml workflow, no signing changes.
- [ ] Review diff and invariants, update existing PR with concrete behavior/test results and merge only after checks pass.
- [ ] Run main release workflow including real Android 14 old-signed-to-new-signed install and retained-data checks.
- [ ] Verify public APK package/version/certificate/SHA matches the actual installed APK and report immutable download URL, commits and exact outcomes.
