# Context refresh retention and category provenance

Scope: wrapper-only `web/oracle/src/main/kotlin/oracle/Export.kt`, new `ContextCachePolicy.kt`, oracle tests/test-only Java helper/recorded context fixture, and oracle README. Original Android engine, repositories, models, thresholds, raw `app`/`evidence` inputs and actual context checkpoints were not edited. No commits/push.

## Reproduced failures

The previous exporter unconditionally wrote the latest original repository result over the checkpoint. A regression used the exact unchanged recorded000020 cache and the actual original repository through a local controlled HTTP503 CONNECT proxy. It observed all23 genuine receipts and both actual hydrated body fields disappear. The legacy cache with `loaded:true,error:null` also produced universal COMPLETE despite zero IR and zero official items. Both failed as expected in `oracle-context-retention-red.log` (2 FAIL,18.685s).

The source fixture is explicitly **REAL_MARKET_RECORDED**, not LIVE. `web/oracle/testdata/context_real_market_recorded_000020/provenance.json` identifies original collector, source path, original timestamp, raw SHA256 and gzip SHA256. It preserves all source bytes, URLs, dates, receipts and body text. The controlled503 response is a regression transport; it is not represented as a newly observed provider outage.

## Final behavior

- Read and validate the prior observation before source collection. Failed source categories retain actual older rows. Canonical DART retention requires the original primary tier plus a real receipt and exact official canonical DART URL; Naver IDs are not promoted to receipts.
- Merge by canonical receipt identity or original kind/id/URL/title identity. Preserve an earlier hydrated body if the same receipt loses its body even when the original collector suppresses that hydration failure. Healthy rolling categories use the new rows rather than blindly unioning older lists. A successful fresh news category remains independent of DART/official failure.
- Recompute the disclosure comparison using the unchanged original `DisclosureDiffEngine` on actual merged rows. No comparison text, source row, receipt, quarter or source date is fabricated.
- Keep retained row observation timestamps. A refresh carrying older rows preserves the older aggregate `collectedAt`, records new `lastAttemptAt` and original failure classes (or explicit wrapper evidence-loss diagnostic), and marks retained provenance. Repeated failed attempts cannot move the original body/receipt observation timestamps forward.
- Promote cache checkpoints atomically through a separate synced temporary file. Failure before replacement leaves the old checkpoint available.
- Export independent `sourceStates.context.categories.{news,disclosures,ir,official}` with loaded/count/status/source/checkedAt/lastAttemptAt/lastAttemptError/retainedCount/itemObservedAt. `itemObservedAt` is identity-key→original observation timestamp. Empty IR or official is always MISSING; loaded means collection attempted, not source confirmation.
- Overall status uses AVAILABLE/PARTIAL/PARTIAL_OR_CACHE/ERROR/MISSING/NOT_COLLECTED, never universal COMPLETE from error==null. Retained/mixed source is CACHE or MIXED; category timestamps distinguish fresh news from old disclosures. Saved metadata is validated before use so malformed optional cache provenance does not block valid quant publication.
- Original repository often exposes only IOException/IllegalStateException classes. Those are preserved rather than inventing unavailable HTTP diagnostic details.

## Executed verification

`python3 -m unittest discover -s web/oracle/tests -v`: **14 tests PASS in83.743s**, `oracle-context-retention-full-green.log` (existing10 exporter tests plus4 new tests).

New coverage:

1. Actual original repository + controlled503 transport: retains all23 receipts, exact two body fields, original observation date and original disclosure-comparison scope; explicit new failure date/error, PARTIAL_OR_CACHE, IR MISSING.
2. Partial controlled input reuses genuine recorded source rows and proves new news remains independent of retained failed DART; a second daily failed attempt preserves original disclosure observation time.
3. Body loss without original error preserves only the two lost bodies, marks category and aggregate evidence-loss diagnostics, does not mark successful news failed, and subsequent valid recorded-input recovery clears retained state.
4. Legacy original cache independently reports news/disclosures CACHE and zero IR/official MISSING, with honest original times.

The body-loss diagnostic initially lacked category error metadata; its explicit additional regression failed in `oracle-context-body-loss-red.log`, was fixed and included in final14 PASS. Controlled payloads change availability only and never introduce invented source content; the helper is test-only and is not an exporter input mechanism.

## Concrete artifacts

- Actual existing observations with final wrapper metadata for all2649 stocks: `/workspace/kr4-pc-work/oracle-context-provenance`. This is an offline export of actual cache, not a fresh source collection. Coverage: contextComplete0/contextPartial2649/contextError0/contextNotCollected0. Every legacy zero-IR row is independently MISSING.
- New-attempt UI regression artifact: `/workspace/kr4-pc-work/context-CONTROLLED-503/publication/details/000020.json`, separately labelled in `classification.json`. Old checkedAt2026-11-05T23:49:15.152482134Z; actual controlled-failure attempt2026-11-06T00:26:48.453090627Z; source CACHE; retained actual receipts/body fields; no fresh source success claimed.

This preserves earlier valid observations during failure. It does not repair current DART access, invent evidence for issuers without prior receipts/body rows, or turn an empty company IR array into available IR. Scheduled all-universe refresh can now retain known genuine evidence while exposing each new attempt honestly.
