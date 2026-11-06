# PC feature/information parity independent audit

Scope: only `docs/pc/**` and `web/public/verification/**` edited. No product, Android, engine, tests, configuration, commit or push changes. Android reference commit `a1676eab7bfe6b0d3765e4340504f8ff2a2d2a3f`.

## Reviewable artifacts

- `docs/pc/feature-parity.md` and `.json`: 548 granular mappings, source path:line → web path:line/visible location → evidence → scoped status. Includes 84 original inventory behavior requirements plus all 20 analysis fields, 14 report fields, 4×14 metric fields, 4×9 peer fields, all finance/facts/source amounts, 27 candidate fields + 4×6 nested metric fields, quarterly source and derived fields + all 8 visible columns, 24 chart modes and all 15 ranking masks.
- `docs/pc/{android-inventory,detail-inventory}.md`: original full read-only source audits preserved verbatim as appendices. Suggested/unexecuted checkbox assertions are explicitly not PASS.
- `web/public/verification/index.html`: actual readable navy report, 22px default, wrapping responsive table/cards, source GitHub/main/baseline links, downloadable matching Markdown/JSON, original inventories and copied local execution records. The App More URL resolves after Vite copies public files; previous missing verification path is supplied.
- `web/public/verification/evidence/*`: actual scoped worker reports/JSON/logs, not invented aggregate results. Explicit LOCAL_BROWSER, ORIGINAL_KOTLIN_TEST, controlled-regression, upstream BLOCKED and NOT_DEPLOYED boundaries.

## Concrete findings delivered early and repair observations

Eight concrete omissions were delivered to root with original/current path evidence: independent Home and Ranking expanders and inline four-metric/Stage4/5 content; Home metric coverage counts/percentages; Home education; unknown-issuer identity/no-substitution instructions; favorite picker original sorting, explicit empty result guidance and backdrop cancellation. Root subsequently restored these in product files. The audit re-read code and moved these rows from FAIL to NOT_RUN after fix; code presence alone was not turned into functional PASS.

Root also restored cross-tab storage synchronization and original Kotlin valuation publication/usage during the audit. These are kept separate from original Android-required behavior. No product files were changed by this worker.

## Remaining critical / important behavior

- **Critical deployment boundary:** public site remains NOT_DEPLOYED (initial Pages setup HTTP403 previously observed). All local browser assertions must retain LOCAL_BROWSER and cannot become a public-site PASS. Physical Windows/LG checks remain NOT_RUN.
- **Important original behavior still requiring follow-up:** original `StockListScreen.kt:225` ranking explanation access. At last static read Ranking ends after pagination; existing introductory fragments do not provide the full selected-weight/missing-retention/safety-independence explanation in one equivalent accessible place. Reported to root; current row FAIL, to be updated if root restores it with evidence.
- **Important state retention check:** original `PriceChartPanel.kt:53-55` uses rememberSaveable for months/mode/interval. Current Detail conditionally mounts PriceChart only on chart tab; ChartView controls use local state. Tab-away/tab-back can reset controls. Reported to root, explicitly NOT_RUN pending actual roundtrip verification; 24-mode render PASS does not verify preservation.
- **Information-only follow-up:** original valuation short label/code now exists through root's original-Kotlin valuation publication; exact UI placement and all boundary labels need final root evidence. Do not replace original valuation classification with an unaudited independent calculation.
- **Source availability, not an implementation success:** fresh DART access remains BLOCKED by independently observed HTTP503. Existing genuine receipts/two body fields/original dates and per-category errors are retained. Empty IR/official must remain MISSING. Real prices are CACHE2636 + explicit MISSING13, never full-price availability PASS.

## Actual verification of this report

Linux Chromium155 via `/tmp/check_pc_verification.cjs`, served only local static `web/public` on127.0.0.1:5198. 6 widths390/768/1024/1366/1920/2560 × font22/26/30 =18 layout cases: no horizontal document overflow, all actual anchor boxes at least44px high. All report-local download/evidence links HTTP200, no browser pageerrors. Screenshot viewed at `/workspace/kr4-pc-work/verification-report-1366.png`. JSON `/workspace/kr4-pc-work/verification-report-browser-result.json` (latest rerun after548-row update), copied into public evidence after the run.

`git diff --check -- docs/pc web/public/verification` exit0. JSON successfully parsed; category count checks exactly analysis20, report14, metrics56, peers36, candidate27+nested24, chart24, rank15. No framework is needed to read HTML and all rendered text is escaped. Table rows remain complete at narrow widths via stacked cells, no horizontal table-only escape hatch.

Document statuses deliberately retain many NOT_RUN entries because existing harness reports only assert particular values/branches. Cross-index rows themselves are NOT_RUN; status row counts are not completion percentages. No whole-project PASS, public-site PASS, fresh DART success, fabricated future data, APK runtime equivalence or95-point score is asserted. Root's latest global rerun and restored-behavior evidence may update statuses before final integration.
