# Chart implementation report

Owned files: `web/src/chart/{PriceChart.tsx,ChartView.tsx,model.ts,store.ts,chart.css}`, `web/tests/chart.test.ts`. No app/engine changes, commits or pushes.

## Integration API

```ts
import PriceChart, { usePriceData, latestQuote } from './chart/PriceChart';
<PriceChart issuerId="000020" cutoffDate="2026-11-05" />
const {data, error, loading, reload} = usePriceData(issuerId, cutoffDate);
// data: PriceData|null; error:string|null; loading:boolean; reload:()=>void
const quote = data ? latestQuote(data.bars) : null;
// {date:string,close:number,change:number|null,changePct:number|null}|null
```

`PriceChart` imports root's `loadPrice(issuerId,cutoffDate):Promise<PriceData>` from `../data/receiver`. A shared store deduplicates concurrent requests from quote/chart, retains verified CACHE on failed retries and exposes errors. Root loader remains responsible for persisted previous-cache and published file hash verification. The chart rejects different-issuer data and malformed source/schema, drops invalid OHLC/weekend/future rows, rejects conflicting duplicates and never generates missing rows or prices.

`model.ts` exports `normalizeDaily(daily,cutoff?)`, `aggregate(daily,interval)`, `visible(daily,interval,months)`, `latestQuote(daily)`; Interval is DAY|WEEK|MONTH. Candle contains original fields `firstTradingDate,lastTradingDate,periodStart,periodEnd,open,high,low,close,volume,tradingDays` plus `date=lastTradingDate`, `observedVolume`, `volumeCoverage=COMPLETE|PARTIAL|MISSING`. Oracle comparison should project original fields. Weekly Monday–Friday, calendar month end, chronological first open/last close, max/min, exact observed complete volume, month-end-clamped viewport cutoff, and aggregation-before-window preserve original rules. If any daily volume is unavailable the original aggregate `volume` remains null. A known partial subtotal is separately shown as partial and hatched; unsafe JS integer sums remain unavailable.

Default six months/DAY/candle. Four windows × three intervals × two modes give all 24 combinations. Genuine OHLC and actual volume are SVG, with right price axis and observed dates; line plots close only. Click/left/right/Home/End select a bar and the accessible live OHLCV summary updates; explicit Previous/Next buttons also work. All component styling scoped under .pc-chart/.pc-chart-quote/.pc-chart-stack, no fixed/sticky. A separate latest daily quote card precedes the plot and remains daily in all 24 modes, matching the original chart tab; Detail hides its duplicate quote only on tab4. Price plot 376px high plus volume and date axis; plot resize uses actual element width, with .9rem SVG axes and >=.9rem explanation labels (18px at root20px). Typography uses rem and responds to root20/25/30px preferences. Axis margins and date-label density respond to root typography changes. Source shows CACHE, requested/file cutoffs, actual last trading date, provider collection/attempt timestamps, source link, modification warning, visible mismatches/errors and retry. Original range change/corporate-action/ranking-independence notices are retained.

## Executed evidence

- TDD initial model/store skeleton: 11 meaningful failures, followed by 11 passes after implementation. Presentation stub: 3 meaningful failures, followed by pass. Original range/caveat missing behavior: 1 failure, followed by pass. Separate latest daily quote card: 1 failure, followed by pass.
- `npm test -- tests/chart.test.ts`: **19 tests PASS**, no warnings (latest 2026-11-05 23:57 UTC).
- Isolated Playwright/Chromium 142 browser harness uses the genuine 299-bar collected `000020.json`, not fixture prices: **24/24 modes rendered**, correct latest close 5,270; keyboard previous/End and click selection pass; all SVG axis text lies within chart bounds at 1440×1100; no browser JS errors; CACHE and actual dates shown. This is local browser integration evidence, not final public deployment evidence.
- JSON: `/workspace/kr4-pc-work/chart-browser-result.json`; screenshot viewed: `/workspace/kr4-pc-work/chart-actual-desktop.png`; reproducible harness: `/workspace/kr4-pc-work/chart-harness/check.mjs` (Vite port 5183, system `/usr/bin/chromium`).
- Whole `npm test` first run at 23:53 UTC exposed 9 receiver failures while that implementation was in progress. Repeated after root integration at 23:57 UTC: **40/40 tests PASS** across chart19, format3, ranking8, receiver10.
- `npx tsc --noEmit` at 23:57 UTC: **PASS**, no output/errors.
- Typography/viewport browser matrix: widths960/1280/1440/1920/2560 × root20/25/30px = **15/15 PASS**. All axis labels inside SVG and no document horizontal overflow; labels scale18/22.5/27px. Evidence `/workspace/kr4-pc-work/chart-enlargement-result.json`.

## Remaining root verification

Final root integration/build/global suite; exact original Kotlin oracle fixture comparison on collected prices; public deployment browser source failures/recovery and requested viewport matrix; physical Windows/LG testing remains outside this Linux browser run. The chart's local source proof must not be reported as public site proof.
