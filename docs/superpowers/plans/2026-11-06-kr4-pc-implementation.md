# KR4 PC full parity implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task by task. Steps use checkbox syntax.

**Goal:** Publish an anonymous free senior-readable PC web port with original Android features, exact quant ranking and genuine charts.

**Architecture:** Separate `web/` frontend plus original-source Kotlin JVM exporter. Server-side actual-price/context collection and immutable hashed publications are deployed to GitHub Pages; browser validates and caches data independently of supplementary safety failures.

**Tech Stack:** React, TypeScript, Vite, Vitest, Playwright Chromium; Kotlin/JVM and org.json/Gson; Python requests; GitHub Pages/Actions.

**Spec:** ../specs/2026-11-06-kr4-pc-design.md and complete user task KR4_PC_FULL_PARITY_SENIOR_UI_20261106.

## Global constraints

- No Android source, original calculation, signing, financial thresholds or existing test deletion/weakening.
- All2,649 stocks; all15 nonempty masks; risk state never enters ranking.
- All five detail tabs and original report/interpretation/evidence/quarterly/source fields visible.
- Four metric buttons start selected, expanded, >=44px; selector position is static and scrolls away.
- Senior text defaults on/persists; no login/tokens in client; no paid hosting.
- No synthetic OHLCV/quarters; pageSize60, completed cutoff, Monday weeks/calendar months; unverified adjustment remains explicit.

## Review focus

- Same-day corrected snapshots and date-mismatched financial/candidate sources cannot silently mix generations.
- Alphanumeric codes, long company names, null/zero financial values, long Korean evidence URLs and 320%-equivalent text space constraints retain usable controls.
- Missing selected metrics must remain visible and explain absence; market/search filters must not renumber global ranks.
- Browser refresh failure, cache corruption and quota denial preserve last verified data and show honest status.
- Partial latest weeks/months, missing volume, conflicting duplicate days and pre-open next-day rows never become fabricated bars.

## Task 1: Original-source oracle and feature audit

Files: `web/oracle/**`, `docs/pc/feature-parity.*`, original Android files READ ONLY.

- [ ] Audit screens/engine/model inputs and enumerate visible web mapping.
- [ ] Compile original Kotlin classes in independent JVM project, adapt only Android I/O with explicit thin adapters.
- [ ] Export original models/reports/rank maps/analysis enriched by original quarterly/evidence engines and validate all input hashes/dates.
- [ ] Test complete original schema, no omitted fields, all stocks/masks, explicit missing/partial evidence; preserve raw sources.

## Task 2: Actual chart collection and calendar port

Files: `scripts/collect_pc_prices.py`, `tests/test_pc_prices.py`, `web/src/chart/**`, original collectors READ ONLY.

- [ ] Pin observed60/200/CORS errors with bounded original response logs.
- [ ] Test strict OHLCV/null volume/duplicates/cutoff/calendar boundary and cached failure recovery before implementing collector.
- [ ] Collect all universe, save verified source responses/hashes and checkpoint; never fill missing prices.
- [ ] Compare independent TS day/week/month OHLCV with original Kotlin aggregations and actual raw representative stocks.

## Task 3: Receiver, ranking and accessible UI

Files: `web/package*.json`, `web/src/{types,data,ranking,App,components,screens,styles}*`, `web/tests/**`.

- [ ] Implement runtime data contracts and independent ranker; compare every issuer/mask with Kotlin, including changed safety states/ties/missing metrics.
- [ ] Implement generation/hash validation, five source states, independently failed safety/candidates, auto/explicit refresh and persistent settings/favorites.
- [ ] Build approved navy home/ranking/detail/chart, five primary navigation routes and all original supplemental information.
- [ ] Add nonfixed expanded selectors, visible large controls, accessible keyboard focus, wrap-safe text and responsive two-column sections.
- [ ] Browser-test real source data and separately labelled controlled error fixtures, all24 chart modes/15 masks/five tabs and original actions.

## Task 4: Free deployment, public checks and review

Files: `.github/workflows/pc-web.yml`, `scripts/build_pc_publication.py`, `docs/pc/**`.

- [ ] Build versioned publication, run Python/JVM/web/Android checks appropriate to protected-source changes and new data; record exact versions/commands/counts.
- [ ] Configure free Pages, deploy actual website from reviewed main; run scheduled/source-triggered collection with checkpoint/cache and no secrets in frontend.
- [ ] Verify anonymous public HTTP/Chrome/Edge-engine interactions, original source hashes, real prices, failure/recovery and viewport/zoom matrix.
- [ ] Capture PNGs/HTML/ZIP and independent rubric with itemized deductions; fix genuine failures without lowering gates.
- [ ] Merge reviewed PR after required checks, verify final public deployment and submit URL, commit, parity matrix, API evidence, screenshots, operating limits and explicit unverified physical-Windows conditions.
