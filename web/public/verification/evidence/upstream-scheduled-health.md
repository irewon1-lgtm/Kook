# Upstream scheduled data health — read-only evidence

Observed 2026-11-06 approximately 01:43–01:52 UTC. Repository: `irewon1-lgtm/Kook`. GitHub API confirmed main remained `a1676eab7bfe6b0d3765e4340504f8ff2a2d2a3f` during this check. No repository source, tests, engine, Android data, evidence or workflow was modified.

## Actual scheduled runs

| Workflow/run | Trigger | Actual result | Publication consequence |
| --- | --- | --- | --- |
| [Collect Real KR4 Quant Snapshot / 42100942826](https://github.com/irewon1-lgtm/Kook/actions/runs/42100942826) | `schedule`, 2026-11-05 20:10:50 UTC | FAILURE, completed 20:38:30 UTC | Collection and data quality gates passed; Android regression step failed; commit and proof-upload steps were skipped. New staged quant/history was not published. |
| [KR4 Independent Financial Facts / 42107102489](https://github.com/irewon1-lgtm/Kook/actions/runs/42107102489) | `schedule`, 2026-11-05 20:50:27 UTC | SUCCESS, completed 21:02:14 UTC | Actual financial publication committed `28d51f2`, then original-policy final candidates committed `a1676ea`. Both remain tied to committed November 5 quant. |
| [KR4 PC Web / 42145573441](https://github.com/irewon1-lgtm/Kook/actions/runs/42145573441) | `workflow_dispatch`, 2026-11-06 01:42:06 UTC | PENDING when sampled, no jobs yet; head `e3b0d190d6c95dde304110aa78b51db435280915` | A manually requested PC run, not evidence that a scheduled PC run or public deployment passed. Owner must update this transient status from final run evidence. |

The PC row is a timestamped observation, not a claim about its eventual result. Superseded/cancelled PC runs likewise must not be counted as successful scheduled operation.

## Quant failure cause established from real logs and original code

The failing scheduled run began on `8b1b45671072636dff59f1ced48aa311204fe2ff`. Its log records:

- `AUTO_UPDATE_EXTREME_GATE_PASS` with staged snapshot `2026-11-06`, price cutoff `2026-11-05`, M01 2,561 / M02 2,410 / M03 2,626 / M04 2,619 / complete and ranked 2,376; source_errors 0. Full universe remained 2,649.
- Quarterly history gate PASS: four-quarter coverage 2,467 and eight-quarter coverage 2,214.
- Rebase reported already up to date. The workflow copied only staged quant and history into `evidence/`, leaving the existing committed financial snapshot in place.
- `CommittedFinancialSnapshotTest.publishedFinancialFactsParseCompletelyAndPreserveExactSourceAmounts` failed with `IllegalArgumentException` at `CommittedFinancialSnapshotTest.kt:17`. The Gradle total was **152 tests completed, 1 failed**, then `:app:testDebugUnitTest` failed. The workflow's commit step did not run.

The exact failing test source at the run SHA was retrieved through GitHub. Line 17 calls the original `FinancialSafetySnapshotParser.parse(raw, quant.snapshot_date_kst, codes)`. At that run's base commit the committed financial file was dated **2026-11-05**; the newly staged quant file was dated **2026-11-06**. Original `Stage45Data.kt:32` requires date equality and throws `financial-safety snapshot mismatch`.

This is a cross-stage date alignment failure in the existing upstream regression gate. It is not an OHLCV fabrication, failed quant coverage gate, Android compilation error, or the local PC context-DART HTTP503. The source parser is correctly rejecting stale financial data; the test incorrectly requires that independently collected financial data already be valid for the newly staged quant date before the quant commit can occur.

Relevant existing locations: `.github/workflows/collect-real-quant.yml:238` promotes staged quant/history in the job; line 257 runs the complete Android regression/build; line 262 commits only after every gate passes. `app/src/test/java/com/krstock/v3/data/stage/CommittedFinancialSnapshotTest.kt:17` is the cross-date parse. `app/src/main/java/com/krstock/v3/data/stage/Stage45Data.kt:32` enforces the correct parser contract.

## Successful financial schedule does not repair the unpublished quant date

The independent financial run genuinely collected and published official-source observations: 2,649 total records, 2,109 PASS, 275 FAIL, 39 HOLD, 226 NOT_APPLICABLE; 2,586 verified facts, 2,621 verified filings. Its log records official filing source_errors 0 at that run's time. The candidate job produced 10 candidates from 1,371 eligible rows using the existing policy and pushed `a1676ea`.

At `a1676ea`, committed quant snapshot, price cutoff, financial snapshot and candidate snapshot are all **2026-11-05**. This matched pair remains usable by the PC exporter and its independent validation. The financial scheduled success is real, but it does not mean the separate quant schedule succeeded or published the November 6 staged snapshot.

The environment/time-specific PC context-DART HTTP503 remains a separate observed limitation. A successful upstream official financial collection does not demonstrate that every PC news/disclosure/IR/context endpoint is available now; conversely, local context HTTP503 does not negate the recorded upstream financial success.

## PC publication and freshness impact

The new PC pipeline consumes committed validated quant, with price collection cutoff derived from that committed source. It can publish the matching November 5 data and expose genuine stored prices, source dates, last attempts and HOLD/CACHE status. This upstream failure does not by itself show a defect in the PC exporter/receiver or invalidate the currently committed matching pair.

However, an unpublished next-date quant snapshot cannot become fresh PC quant through a frontend refresh or a successful PC build. If the upstream date-alignment gate continues blocking future commits, PC quant and its committed-source price cutoff will stop advancing even if PC builds run successfully. Thus **“all scheduled/server auto-refresh is healthy” is unsupported**. Current price cutoff November 5 is also the completed-day cutoff shown in the failed run; distinguish the missing November 6 quant publication date from an assertion that a later completed-price day was already available.

The PC `workflow_run` path requires successful upstream completion, so a failed quant run does not produce a successful quant-triggered publication. Manual PC runs, PC cron runs, successful financial-triggered runs, verified cached data and actual upstream quant success are separate statuses and must be reported separately. This review did not wait for or certify a scheduled PC cycle or deployment.

## External original-source date-isolation experiment

Executed an isolated Java harness against the already compiled, unchanged original Kotlin classes. Harness and output live outside git:

- `/workspace/kr4-pc-work/UpstreamDateIsolationHarness.java`
- `/workspace/kr4-pc-work/upstream-scheduled-health.date-isolation.json`

Unchanged actual inputs: quant SHA-256 `a7e296f069387674d52c9fdf39bd18088dc331c68127e153417f3447b4119745`; financial SHA-256 `15ed6fa759d0da700408e74d411ec5250186342f122731fadc37f1d470224ad7`. These match the existing authenticated November 5 recorded fixtures.

Observed assertions:

1. RED reproduction of the incorrect “financial must already be VALID at newly expected November 6” assumption: the original parser rejects the untouched November 5 financial input with exactly `financial-safety snapshot mismatch`.
2. GREEN independent-artifact validity: parse the same untouched financial file at its real November 5 date; validate all 2,649 records/statuses and **13,245 exact nullable Long amount comparisons**; PASS/FAIL rows retain verified facts and official DART URLs.
3. GREEN date isolation: install that valid original financial snapshot; runtime lookup at expected November 6 returns null (HOLD), while lookup at November 5 remains available.
4. GREEN quant independence: parse actual committed quant through the original parser, install its unchanged original rows, then compare all 2,649 stock summaries and **all 15 ranking maps** before/after financial installation. All remained identical. Both source files remained byte-identical.

This is controlled expected-date boundary evidence with authentic unchanged financial/quant source bytes. **It is not a replay of the failed run's exact full staged November 6 quant JSON**: GitHub returned zero artifacts for that failed run, and its proof upload was skipped. We preserve that limitation rather than inventing its unpublished data.

## Minimal upstream recommendation — deferred, no edits authorized/applied here

A separate reviewed upstream task could correct only the test contract: retain every current full-universe/status/exact-amount/source/immutability check while validating the independent financial artifact at its own explicit date; explicitly test both successful matching-date reception and rejected differing-date/HOLD reception, with core quant unchanged. Keep an authenticated genuine recorded matching pair so positive-path validation always executes even when current committed dates differ. A current-pair integration test must assert the expected outcome for both alignment states, without an early-return/skip and without weakening source validity or date rejection. The existing genuine fixture provenance is at `web/oracle/testdata/real_market_recorded_20261105/provenance.json`.

**Do not apply this within the present PC-only scope:** touching `app/src/test/**` can trigger the existing `app/**` Android release workflow and an unrequested APK even with production Android unchanged. No failing test was deleted, bypassed or weakened. No threshold, policy, ranking formula, production parser, financial data or release workflow changed. The operational issue is reported for a separately scoped fix.

## Retained evidence

All evidence paths use `/workspace/kr4-pc-work/upstream-scheduled-health.*`: `quant.json`, `financial.json`, `pc-manual.json` hold actual GitHub run metadata; `quant.log` and `financial.log` contain downloaded real archived job logs; `quant-failed.log` contains the failure excerpt; `quant-artifacts.json` records zero artifacts; `failure-test.kt` is the exact test fetched at the failing run SHA; `committed-dates.json` records the original and current committed source metadata; `date-isolation.json` contains the independent original-source assertions. Raw downloaded archives are retained alongside these. Initial `gh run view --log` emitted no content, so actual logs were retrieved from the GitHub Actions archive API instead.

### Additional exact failing-financial-input check

Repeated the external harness with the **exact financial bytes from the failed run's base commit `8b1b456`**, extracted read-only via `git show`; financial SHA-256 is `bdb7fe532250c6d516454de822bef2700dded37c36caab88cb8d7e3c1ffbd358`. The expected date is the actual November 6 date printed by that failed run, and its universe identity gate confirms the same 2,649 issuer set. This reproduces the failing parser's raw financial input/date condition directly: exact rejection message, own-date full 2,649/13,245 validation, HOLD-null date isolation and unchanged 15 rank maps all passed. Result: `upstream-scheduled-health.failed-run-date-isolation.json`; extracted inputs: `upstream-scheduled-health.failed-run-financial.json` and `upstream-scheduled-health.failed-run-baseline-quant.json`. The baseline quant hash equals the current committed quant hash. The unavailable full staged November 6 quant/history remains explicitly outside this replay.
