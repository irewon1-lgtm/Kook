# Home / Ranking original-behavior restoration

Implemented within owned `Home.tsx`, `Ranking.tsx`, `components/ui.tsx`, `styles.css` only. No App/receiver/data/original Android edits, commits, PRs or agents.

## Restored original interactions and content

- Original HomeScreen.kt:104/432–459 and StockListScreen.kt:203–211: each Home top-five and Ranking row now has two independent keyboard-accessible sibling buttons. The primary row still opens stock detail. Its separate44×48px chevron toggles the same row's inline `한눈 비교`; `aria-expanded`/`aria-controls` identify state/region. There are no nested buttons.
- Expansion shows the original stock status, market/code/industry, current combination score label, all four actual metric values, original market-position wording/relative score, missing metric reasons, and original loss-making/PER fallback. Missing reasons remain visible in the collapsed Ranking row too.
- Original Stage45CompactPanel: source-bound Stage4 global financial record/status, original FINAL candidate badge when present, all three financial ratios in Home, original Kotlin Stage5 short/full band labels and percentile, and unchanged-rank note. The UI consumes `state.valuationsByCode` and `state.candidatesByCode`; there is no TypeScript Stage5 classifier or band threshold.
- Extra inline `재무자료와 판정 근거` discloses actual global financial record amounts using exact-integer strings where supplied, ratios, reporting period/date, CFS/OFS, verification, original Korean reason wording plus raw reason code, original criteria, identity/receipt, correction/reconciliation, validation problems and original source/hash fields. Its source label explicitly identifies the global verified financial map and financial snapshot date; no per-stock detail source is substituted.
- Original HomeScreen.kt:107/503/519: Home below top-five restores M01–M04 availability counts and exact1dp percentages, complete original four-axis education text, and exact source footer `종목 KRX KIND · 재무 OpenDART · PER/가격 네이버증권 공개 데이터`.
- DataStatus and Ranking display independent `financialError` with explicit HOLD and explanation that validated quant data/rankings continue. If given stale financial/candidate records alongside an error, presentation still holds them. Stage5 remains independent from Stage4; optional Stage5/candidate errors are visible inside the expansion.
- Original StockListScreen.kt:225 explanatory dialog is intentionally adapted into a permanent `순위 산출 안내` panel after pagination: current selected names/count, equal weighting,15 nonempty combinations, all missing stocks disclosed with rank pending, financial independence, global-rank preservation, separate detail/expand actions and top Home-menu guidance. This is an intentional PC adaptation preserving all information without a modal.
- Four expanded metric controls are moved immediately after Ranking title, before source-status card, as requested for initial1024×640 senior use. No fixed/sticky behavior added. Existing navy theme, wrap-safe names, original filters/sorts/global ranking, counts and50/100 pagination remain.

## Executed checks on restored source

`node /tmp/kr4-home-rank-check/restoration.cjs` initially failed because the Home expand button was absent. The completed regression now PASSes; persistent runner copy: `/workspace/kr4-pc-work/home-rank-restoration-regression.cjs`. Output: `home-rank-restoration-regression.log`.

The isolated actual-data component harness uses `/workspace/kr4-pc-work/oracle-v2` stocks/financial data and original `/workspace/kr4-pc-work/oracle-stage5-candidates/{valuations,candidates}.json`. Source/receive times and Stage4 failure injection are explicitly controlled harness state. Actual system Chromium with Playwright verifies:

- Separate Home and Ranking expand/collapse versus detail callbacks; expand does not invoke detail callback. Expand target height>=44. No nested buttons. Keyboard Enter expands a missing-stock row.
- All four inline metrics; actual original Stage5 label and FINAL#1; original selected missing reasons; nested financial provenance/source/date/hash.
- Each actual Home M01–M04 availability count/percentage matches the original universe; exact framework/source text remains on Home.
- Permanent ranking guidance contains the original substantive information; selector is above status and visible within initial1024×640 at22px root font.
- Injected Stage4 failure displays HOLD and the error in both status views, keeps global rank1 and all50 current rows available, and shows inline HOLD.
- Expanded evidence with financial provenance OPEN causes no document horizontal overflow at1365/1024/550/390 CSS widths ×20/25/30px fonts (12 cases).

`node /tmp/kr4-home-rank-check/verify.cjs` also reran after restoration: PASS all15 masks versus original Kotlin first-stock outcomes; minimum1; KOSPI global rank3; FAIL global rank17; original Samsung global rank22; missing rows last page;50/100 pagination; source/cache failure; all Home+Ranking width1536/1366/1365/1280/1024/850/550/390 ×20/25px fonts without horizontal overflow; selectors compute static; auxiliary text>=18px. Output: `home-rank-restoration-matrix.log`.

`npm run build` completed successfully on restored source; log `home-rank-restoration-build.log` (existing bundle-size advisory only).

A full `npm test` first passed289/289. The latest rerun while root added optional-stage receiver tests was289PASS/2FAIL, both newly added `optional-stages.test.ts`:
- `receives the exact original Stage5 and candidate maps without client-side thresholds`
- `keeps original PER classifications independent when Stage4 fails and holds dependent final candidates`

These tests failed because in-progress receiver state did not yet include valuations maps; no UI errors were reported. Log `home-rank-restoration-unit.log`. Root informed; final whole-app matrix/receiver verification belongs to root after its integration finishes. Scoped TypeScript check for all three owned TSX files passed.

Evidence is browser emulation/component-harness verification, not deployed-site/physical LG/Windows confirmation. Existing earlier-source screenshots are not presented as restored-source proof. Restored-source capture: `home-restored-1024.png`.

## Final sibling target collision correction

Independent final matrix correctly found that absolute-positioned sibling expand buttons geometrically overlaid the full-width detail button. Fixed `styles.css` only: `.stock-row-header` now uses a real remaining-width/64px two-column grid; the44×48px expand button is static in its dedicated column. Table-heading padding preserves alignment with the detail-button column; obsolete narrow-screen overlay offsets were removed.

Actual full App browser check `node /workspace/kr4-pc-work/home-rank-sibling-collision.cjs` PASS:12 viewport/font profiles (2048/1536/1365/1024 CSS widths ×22/26/30px) across both Home/Ranking, all displayed sibling hit targets have exactly zero geometric intersection, expand controls>=44px, no document overflow, and expansion/collapse still works. Evidence: `home-rank-sibling-collision.json`. No thresholds or test conditions were weakened. Source frozen and final-matrix agent notified before its complete rerun.
