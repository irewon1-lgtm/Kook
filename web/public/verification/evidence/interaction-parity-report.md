# Independent interaction parity journeys

Only repository file changed: `web/e2e/interaction-parity.spec.ts`. Product source remains frozen. Exactly four browser journeys were added:

1. `CONTROLLED_LOCAL_PREF` browser-only stale unknown favorite ZZZZZZ, independent stale removal, original company-name picker ordering, explicit cancellation, zero-result search, backdrop cancellation, multi-stock save and independent known-stock removal without detail navigation.
2. Actual published alphanumeric issuer0001A0 deep link, keyboard-accessible detail update dialog, actual manifest request on refresh, exact unknown ZZZZZZ alert with no substitute stock, and search recovery value/focus.
3. Original four-metric Home top five remains unchanged when global Ranking selects growth only; original inline four raw values/relative scores/reasons, financial record reason/receipt/hash, original exported Stage5 label and candidate FINAL rank; independent expansion/collapse and detail opening.
4. Full equal-weight/current-selection/15-combination/missing-disclosure/financial-independent ranking explanation, selected-complete-only count and last-page exclusion, company-name sort with original global ranks, and restoring all rows.

Helpers preserve `KR4_PUBLIC_URL` query/pinned URL when changing hash routes, discover the actual `data-data-base` and generation prefix, and SHA-256 check published artifact bytes against that generation's manifest. No stock/source fixtures replace real publication data. The only seeded state is explicitly labeled `CONTROLLED_LOCAL_PREF` favorite storage. The spec uses the existing Playwright config, so Chromium/native Windows Chrome/Edge channels are supported without platform-specific filesystem assumptions.

Executed scoped TypeScript check: PASS.

Local actual-app run:3PASS/1FAIL. The real defect is search focus after unknown-code recovery: `toHaveValue('ZZZZZZ')` passes but `toBeFocused()` receives inactive. Existing Ranking remains mounted and changing the `autoFocus` prop on route transition does not focus the input. This assertion is preserved. Root was notified; no product fix or threshold weakening was made by this agent.

Authoritative isolated-output rerun command:
`npx playwright test e2e/interaction-parity.spec.ts --reporter=list --trace=off --output=/workspace/kr4-pc-work/interaction-parity-focused-run`
Log: `/workspace/kr4-pc-work/interaction-parity-focused.log`; screenshot/context under the explicit output directory. Trace is disabled for this rerun only because concurrent independent default-output runs deleted each other's trace temporary files; screenshot retention and every behavior assertion remain unchanged. CI configuration is unmodified.

The first standard-config run also reached3PASS/1FAIL, but concurrent output directory cleanup caused additional trace ENOENT/teardown timeout after the same real focus failure; see `interaction-parity-local.log`. Separately running journeys3–4 passed2/2 in11s (`interaction-parity-remaining.log`).

Test source is frozen, pending root's decision on the reported product focus defect. No commits/PRs or product changes.

## Final unchanged regression after root's focus repair

Root fixed search-entry focus with an input ref/effect and re-froze product source. The test file and assertions remained unchanged. Standard-config tracing was restored, with an isolated output directory to avoid concurrent artifact cleanup.

Command: `npx playwright test e2e/interaction-parity.spec.ts --reporter=list --output=/workspace/kr4-pc-work/interaction-parity-green-run`

Result: **4 passed in17.0s**, exit0. This includes the formerly failing unknown-code recovery focus assertion. Authoritative final log: `/workspace/kr4-pc-work/interaction-parity-green.log`. All four journey sources are frozen. No product files were edited by this agent.
