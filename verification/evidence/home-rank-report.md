# Home / Ranking UI report

Owned implementation files:
- `web/src/screens/Home.tsx`
- `web/src/screens/Ranking.tsx`
- `web/src/components/ui.tsx`
- `web/src/styles.css`

Implemented approved navy/blue Home hero, source/receive/date/status panel, real completeness ring/counts, top-five original-engine rankings with actual four metric bars, neutral PASS/NA, orange HOLD, red FAIL, stock actions and full-universe CTA. No sample numbers or synthetic LIVE state are embedded in production components. NOT_APPLICABLE label is exactly `비교제외`.

Ranking delegates global rank computation to root's `rankStocks` before original-compatible name/code/sector/market query, market, selected-complete-only and ALL/HOLD/FAIL filters. HOLD includes absent records. Combination/code/name sorting preserves global ranks. All nonempty masks are selectable with minimum one. Four selectors remain expanded in ordinary document flow. Missing rows remain visible, with each missing selected metric and its original reason. 50/100 pagination exposes all 2,649 rows, total counts, shown ranges, first/previous/next/last actions. No sticky/fixed positioning occurs in owned styles. Added optional shared export `UniverseCounts`; otherwise requested component signatures unchanged.

Typography defaults to 20px (21px wide desktop), auxiliary text >=18px, company names24px, scores32–34px, control height >=44px. Names and reasons wrap; scores and their units remain together. Responsive layouts include 1024 CSS fallback and narrow mobile layouts. Navy theme is explicit for all OS color modes.

## Executed verification

`node /tmp/kr4-home-rank-check/verify.cjs` passes using actual `/workspace/kr4-pc-work/oracle-v2` stocks, manifest, financial records and Kotlin rankings, rendered in real system Chromium with Playwright. Harness-only receive time/source and refresh failure are explicitly controlled fixtures; production components use their `state` props. Evidence: `home-rank-browser-checks.json`, `home-rank-browser-checks.log`.

Checks executed:
- All 15 metric masks: first visible stock matches the original Kotlin oracle for that mask.
- Minimum-one-selection guard.
- KOSPI filter preserves original global third place; FAIL filter preserves original 17th place.
- Last page includes unranked stocks with selected-metric reasons.
- Page sizes50 and100.
- Samsung code search retains original global22 rank; row callback yields005930.
- Empty-result reset restores all rows.
- Controlled refresh failure shows CACHE, error and last-good state, with no green LIVE dot.
- Home and Ranking document horizontal overflow equals zero at CSS widths1536,1366,1365,1280,1024,850,550,390 for both20px and25px root font sizes.
- Selector position computes `static`; observed auxiliary text minimum18px; no browser page errors.

Original captures: `home-1365.png`, `ranking-1365.png`, `home-1024-large.png`, `ranking-1024-large.png`. These are isolated component harness captures, not public deployment/physical LG/Windows evidence. Global app-header/favorites/more/detail/chart layout verification belongs to integration checks.

`npm test` run during parallel integration:30 passed /9 failed. Failures all from receiver.test.ts while root was still implementing receiver: complete universe acceptance; duplicate/missing universe validation; invalid metric/reason validation; future cutoff/coverage validation; optional safety isolation; cache/backup/bundled recovery; OHLCV validation. None are hidden here or claimed passing. `npm run build` initially blocked by chart ES2022 toReversed use, then by PriceChart.tsx missing root receiver loadPrice export. Root was informed. Standalone esbuild bundle plus browser execution of the owned files passes.

No Android/data edits, commits, pushes, new agents or test deletion occurred. No repository test files/config changed; temporary harness and test runner are under `/tmp/kr4-home-rank-check`.

Final scoped TypeScript check: `tsc --noEmit --target ES2022 --module ESNext --moduleResolution Bundler --jsx react-jsx --strict --skipLibCheck src/screens/Home.tsx src/screens/Ranking.tsx src/components/ui.tsx` exited0. Final browser verification rerun exited0 after latest edits. Full integration build is now blocked by receiver.ts TS2775 assertion-helper declarations and resulting nullable narrowing; parent informed.
