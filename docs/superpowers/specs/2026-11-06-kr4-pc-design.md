# KR4 PC full parity and senior UI

Task: KR4_PC_FULL_PARITY_SENIOR_UI_20261106. The user's detailed specification and four approved images authorize implementation and public free deployment. Baseline is actual main a1676ea, Android 4.3.210; Android source, package, signing, financial policy and quant formulas stay unchanged.

## Approach and decisions

Use a separate React/TypeScript/Vite static application under `web/`, deployed anonymously on free GitHub Pages. This is the implementation choice, not a new design proposal. Reuse the original Kotlin classes in a separate JVM exporter (`web/oracle`) so rules, reports, peer comparisons, interpretations, quarterly enrichment and candidate validation are executed by the Android source itself. Publish original-engine JSON per stock. An independent TypeScript subset ranker is exhaustively compared against original Kotlin for all 2,649 stocks × 15 masks; market/search/safety filtering occurs after global ranking.

Browser-only direct Naver prices is ruled out by measured HTTP403 CORS; pageSize200 also fails HTTP400. A separate bounded server-side collector requests pageSize60, validates genuine OHLCV, completed cutoff and duplicate consistency, and publishes verified daily histories. Browsers fetch these from the same Pages origin. Such price data is explicitly CACHE with provider/check/cutoff dates, not a fresh browser-provider LIVE claim. No paid accounts, new keys, login or alternative price provider.

The approved four JPEGs govern navy/blue styling and hierarchy. Desktop adaptation uses readable maximum widths, meaningful two-column groups, 20–22px body, >=18px explanatory text, 22–26px companies, 28–36px values and >=44px controls. Large text defaults on, further enlargement persists locally. Four metric selectors are expanded in normal document flow; no sticky/fixed selector or hidden menu. Five detail tabs and all original supplemental information remain accessible. Add keyboard-accessible local per-browser favorites and original more/source/refresh functions.

## Data and error contract

Export a versioned manifest with source commit, input SHA-256 values, snapshot/price dates and independent coverage counts. `stocks.json` contains original StockSummary[], `rankings.json` all fifteen original result maps, and `details/{code}.json` original StockDetail plus analysis, evidence, history, financial and candidate objects. Null remains null. Export explicit original schema fields and derived coverage properties; no synthesized sources, quarters or numbers.

Browser receiver validates manifest and content hashes; maintains current/previous verified IndexedDB caches and bundled initial manifest. LIVE means newly fetched published snapshot, clearly identified as GitHub-published data; CACHE/BACKUP/BUNDLED/failure remain distinct with last success/attempt timestamps. A separate failed or date-mismatched financial/candidate publication becomes HOLD and must not stop valid quant reception. Generation-bound URLs prevent mixing data sets during deployment. Original source dates are displayed separately from HTTP receive time.

Context evidence is collected server-side using the original repository or parsed official observations. Unavailable/partial evidence and stale checks are visible. Original rule-based analysis is exported after actual evidence/history enrichment; unconfirmed news/IR is never marked confirmed. Initial and scheduled builds preserve raw observation provenance and do not fabricate missing history.

## Verification and completion

1. Audit all original screens/actions/fields, map each to a visible web implementation and test.
2. Execute original Kotlin oracle, validate input schemas and whole-universe parity, preserve protected source hashes.
3. Collect actual prices and evidence with response logs; verify raw/parsed data and independent calendar aggregation.
4. Run original regression and new TypeScript/browser tests, including 15 masks, five tabs, all 24 chart modes, source failures/recovery, keyboard, persistent preferences, scroll-away selectors and no clipped core information.
5. Deploy Pages and operate actual public website. Capture original screenshots for requested physical-resolution/scaling-derived CSS viewports. Linux browser emulation cannot be reported as actual Windows/LG physical testing; report that limitation honestly.
6. Independently compare approved images with actual public-site captures using transparent rubric and deductions. No invented automated 95 score.

Only actual executed checks may PASS. Failures retain evidence, receive minimum fixes and repeat affected checks. No final whole-task PASS with required unverified conditions. Maintain a public feature parity report, Android differences, collected-data provenance, gallery and free hosting/refresh limits.
