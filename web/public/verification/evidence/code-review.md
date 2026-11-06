# Independent PC source review — 2026-11-06

Review scope: `/workspace/kr4-pc-web`, against the approved PC design and implementation plan and the original Android baseline `a1676eab7bfe6b0d3765e4340504f8ff2a2d2a3f`. Production changes were untracked at review time. This is a source/recovery review, not a public deployment or physical-device PASS.

## Verdicts

**Code quality: REQUEST CHANGES.** Two Important (P2) correctness/recovery defects were reproduced. No Critical issue was established. Resolve these and run the affected focused regressions before merge. The findings below describe the reviewed code before any owner fixes.

**Specification compliance: NOT COMPLETE / cannot certify full parity.** The architecture and inspected UI broadly implement the requested original-engine reuse, 2,649-stock ranking universe, separate financial gate, genuine cached OHLCV, static expanded selectors, five detail tabs and browser-local settings. However, the two reproduced defects violate candidate failure isolation and last-verified-data recovery. The current feature matrix reports 548 rows with 206 PASS, 340 NOT_RUN, 1 BLOCKED and 1 FAIL; overall `NOT_COMPLETE`, public deployment `NOT_DEPLOYED`, physical Windows/LG `NOT_RUN`. Those remaining verification/deployment states must stay explicit. DART upstream HTTP503 is a source availability block, not evidence of a code fix or completed full-context parity.

## Important findings

### R1 — P2: Failed candidate reception still exposes a VALID final candidate in detail

Location: `web/src/data/receiver.ts:243` (`getDetail`, especially lines 248–254), with rendering at `web/src/screens/Detail.tsx:325` and load dependencies at `web/src/App.tsx:76`.

`refreshOnce()` correctly rejects a failed `candidates.json` and sets `candidatesByCode={}` plus `candidatesError`. But `getDetail()` only clears the candidate if financial data is unavailable. When Stage4 remains valid, it returns the candidate embedded in the detail file and its exported `sourceStates.candidate.status='VALID'`, regardless of the failed candidate transaction. The summary then renders that object as a final candidate. Thus the list transaction is on HOLD while the detail claims the same candidate is valid.

Executed reproduction uses the actual `oracle-stage5-candidates` dataset and an empty `MemoryStore`, with only `candidates.json` returning HTTP503. After refresh, `getDetail('142210')` produced:

```json
{"candidatesInState":0,"candidateError":"자료 수신 HTTP 503","detailCandidate":"142210","detailCandidateState":{"status":"VALID","error":null}}
```

This conflicts with the design contract that an independently failed candidate publication becomes HOLD. Bind the detail candidate and its visible source state to the accepted candidate transaction, while preserving the verified quant/detail content. Update the detail receiver/UI when the optional transaction completes, including its failure and recovery; merely clearing the object once can leave it permanently missing because App's effect currently does not depend on candidate state. A same-generation hash-verified artifact cache can remain a valid explicit recovery path.

Focused regression required: healthy financial + candidate HTTP/hash failure hides final-candidate status everywhere and exposes HOLD, then candidate recovery restores the exact original candidate without needing navigation or a second unrelated refresh.

### R2 — P2: Storage denial makes a failed refresh discard the current verified in-memory snapshot

Location: `web/src/data/receiver.ts:193` (fallback selection, lines 195–199).

Saving genuine network data deliberately tolerates IndexedDB errors, so the receiver can successfully display a new snapshot when storage is denied/full. On the next network failure, recovery considers only persistent `core:current`, persistent `core:backup`, and the original bundle. It does not retain the already verified core in memory. Consequently a background/focus refresh rolls the user back to the bundle (or an older persisted generation) and erases the successful-reception timestamp, even though the current verified data is still available in the running application.

Executed reproduction uses the real full-universe fixture, a newer manifest metadata revision, a store whose read/write both throw, one successful network refresh followed by an offline refresh. The result was:

```json
{"before":{"source":"LIVE","generatedAt":"2026-11-06T10:00:00Z","lastSuccessAt":"2026-11-06T01:02:32.525Z"},"after":{"source":"BUNDLED","generatedAt":"2026-11-06T00:34:19.145970259Z","lastSuccessAt":""}}
```

The metadata revision in this controlled reproduction distinguishes the genuinely accepted network core from the older bundled core; stock payloads were the actual 2,649 original-engine rows. The same branch drops corrected stock content whenever its network generation differs from the bundle. Retain the validated core bundle in memory (with original authenticated bytes and success time) and prefer that valid latest reception on transport/storage failure. Preserve a truthful CACHE/error label and keep optional data bound to the selected core.

Focused regression required: denied and quota-full storage, successful reception of a distinct validated generation, then network failure; retain that generation, stocks, lastSuccessAt, and useful error. Also cover a stale readable persistent current whose attempted newer write failed.

## Review evidence and boundaries

Newly executed review evidence: `/workspace/kr4-pc-work/review-receiver-reproductions.json`. The production receiver was bundled unchanged with the installed esbuild into `/workspace/kr4-pc-work/review-receiver.mjs` and executed with actual original-engine fixture data plus explicitly controlled transport/storage failures. No Android, production, test, or workflow source was edited during review.

Inspected the approved design/plan; receiver/runtime validators; app routing/favorites/settings; ranking and shared UI flow; detail analysis/facts/provenance/candidates; chart aggregation/store/preferences; original-source Kotlin exporter, source inclusion and context-retention adapter; Python price collector; publication allowlist/hash/generation retention and latest valuation/candidate validation; and the complete PC workflow's build/publish/Pages/native-Windows stages. Read relevant original candidate/parser/store sources read-only. `git diff --stat a1676ea -- app engine evidence` returned no protected-tree differences.

Observed favorable controls, without re-running previously logged full suites:

- Original Kotlin classes are compiled from the existing Android tree; engine reports, analyses, quarterly history and classifications are exported rather than rewritten in the browser. The TS ranker uses equal-weight selected percentile means with the original code tie-break; financial filters apply after ranking.
- File hashes and sizes authenticate generation-bound payloads; publisher limits the path set, rejects unsafe paths/symlinks and validates retained generation contents. Current strict validation covers the newly added original valuation and candidate outputs.
- Price collection requests bounded pageSize60 histories, excludes future cutoff rows, rejects conflicting observations, retains explicit CACHE/MISSING and unverified adjustment status, and preserves successful source dates on a failed collection.
- Context refresh preserves observed bodies/items and their original per-item dates; partial/unavailable classifications remain visible. The review did not treat observed DART failure as confirmed context.
- The publisher runs after the build, downloads the current run's compiled artifact, rejects repository internals and symlinks, and publishes the dedicated site branch with explicit contents write. `workflow_run` builds check out current main instead of executing a triggering run's artifacts. No concrete credential leak or privilege escalation was established in the inspected path.
- Defaults are 22px browser text and expanded static selectors, with accessible 44px+ control styling and five detail tabs in the inspected implementation. Actual final viewport and physical display conclusions require their own recorded executions.

Previously supplied full-suite totals were not re-labelled as reviewer executions or repeated gratuitously. Public CDN/raw CORS operation, final CI, final restored UI browser matrix, native Windows jobs and physical LG verification remain outside this review's executed evidence. The localhost Playwright configuration runs Vite dev; the workflow separately defines postpublication checks for the actual compiled public URL. No completed public/runtime PASS is claimed here.

## Focused R1/R2 fix re-review — 2026-11-06 01:12 UTC

**R1: CLOSED. R2: CLOSED. Focused code verdict: APPROVED for the reviewed fixes.** This supersedes the initial request-changes verdict for these two findings. No additional Critical/Important finding was established in the fix scope. It does not certify public deployment, whole-feature parity, the complete final browser matrix, native Windows or a physical LG device.

Reviewed `/workspace/kr4-pc-work/review-r1-r2-fix.diff`, current receiver/App source and `web/tests/review-regressions.test.ts` with its authenticated recorded-fixture loader. R1 now resets candidate reception to pending per accepted core, treats pending/failure as HOLD in detail, and takes valid candidates only from the accepted global candidate map. App's detail loading effect observes candidate map/error and valuation map changes, so completion/recovery can update a mounted detail. R2 now retains authenticated stock bytes, manifest and lastSuccessAt in memory and validates that bundle ahead of persistent/bundled fallbacks; failed persistence cannot replace the current verified generation with an older stored one.

Independently executed from `web/`:

```text
npm test -- --run tests/review-regressions.test.ts
Test Files 1 passed (1)
Tests 2 passed (2)
Start at 01:11:43; Duration 1.39s
```

These use genuine authenticated November 5 and October 29 full-universe records, plus original positive candidates, with controlled transport/storage errors. They verify candidate HTTP503 HOLD followed by verified recovery and blocked-storage/offline preservation of the newer received stock data and success time.

Two additional independent checks used the unchanged fixed production receiver and the same hash-authenticated fixtures, without editing product/tests:

- An old readable October 29 persistent current, failed writes (quota simulation), successful November 5 reception and subsequent offline refresh retained November 5, the exact lastSuccessAt, CACHE and the transport error.
- A deliberately pending candidate request with already-valid financial data returned a null candidate/HOLD; releasing the genuine candidate response changed the same receiver's detail to its exact original candidate/VALID.

Both assertions passed. Evidence: `/workspace/kr4-pc-work/review-fix-extra-checks.json`. Protected-tree `git diff --stat a1676ea -- app engine evidence` remained empty. No production, test or original Android edits were made by the reviewer. Specification-completion verdict remains NOT COMPLETE until its outstanding execution/deployment evidence is resolved.
