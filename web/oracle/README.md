# Original-source KR4 JVM oracle

This independent Gradle/JDK17 application compiles the **original** Android data models, repositories, generated identity/bundled-reference data, ranking, peer, interpretation, quarterly, evidence, safety and candidate sources directly from `app/src/main/java`. It does not copy or port the analysis engine. The two `android.content` adapters provide only a files directory and asset input streams. Android files remain unchanged.

The original `SnapshotAutoUpdater.parseAndValidate` validates the selected current quant JSON; its parsed rows and metadata replace the old generated bundled runtime state before any repository is read. Independent original financial parsing and candidate parsing/runtime installation follow. A safety date mismatch, invalid safety, missing source or mismatched `quant_records_sha256` yields HOLD and never aborts quant. The fingerprint uses the original Python collector's sorted compact JSON convention. Candidate validation also checks active metrics/ranks/price cutoff and current financial PASS.

## Run

```bash
python3 scripts/export_pc_oracle.py --output /absolute/new-generation
python3 scripts/export_pc_oracle.py --output /absolute/new-generation \
  --context-cache /absolute/checkpoints --collect-context \
  --context-limit 2649 --context-concurrency 8
python3 -m unittest discover -s web/oracle/tests -v
```

Inputs default to the four original `evidence/*.json` sources. Override with `--quant`, `--financial`, `--candidates`, `--history`. Output must be absent or empty, preventing accidental overwrite of a published generation. `--skip-build` executes the previous distribution. `GRADLE` may select Gradle; otherwise PATH or the managed toolchain is used. The wrapper preserves Java proxy and CA trust for Gradle **and** the exporting JVM, and serializes concurrent Gradle builds with a filesystem lock. It does not invoke the unrelated Android Gradle wrapper.

Context selection: `--context-codes 005930,000660,000020`, `--context-limit N` (0..2649, default10), `--context-concurrency N` (1..16, default4). `--collect-context` requires `--context-cache`; existing schema-valid loaded checkpoints are reused, including actual partial/error responses. `--context-refresh` forces collection again. Original `ContextEvidenceRepository.load` performs actual server requests; no synthetic news, IR or confirmation is added. Refresh writes checkpoints atomically. A failed category retains its earlier actual rows; canonical DART receipts/body rows are retained only for that failed source, and a previously hydrated body is retained if the same receipt loses its body. Healthy rolling categories use the fresh rows, so fresh news does not inherit an unrelated DART failure. The original disclosure comparison is recomputed on the resulting actual rows. Each observation records original collector identity and complete original evidence/error fields. When refresh retains earlier evidence, `collectedAt` remains the earliest retained observation time and `lastAttemptAt` / `lastAttemptError` describe the new attempt separately. `categoryStates` retains each category's original check times and per-item `itemObservedAt` map. This prevents a scheduled failed refresh from relabelling old bodies as newly fetched. A checkpoint directory without `--collect-context` performs an offline export from observations. Missing observations remain NOT_COLLECTED; this is distinct from a provider ERROR. The original repository exposes parsed observations rather than raw HTTP bodies, so checkpoint provenance retains those observations and original URLs/errors.

## Publication contract

All JSON uses original Kotlin property names. Gson retains nulls. `stocks.json` is the complete original `StockSummary[]`. `rankings.json` maps string masks `1`..`15` to issuer-code maps of original `DynamicRankResult`; bit0=M01 through bit3=M04. Ranking is computed on the complete universe with the original engine.

`details/{issuerId}.json` retains all original `StockDetail` constructor fields (`summary`, `report`, `news`, `filings`) and adds:

- `analysis`: original `IntegratedInterpretationEngine.analyze`, enriched by original `QuarterlyTrendAnalyzer.enrich`.
- `evidence`: original `EvidenceBundle` constructor fields, plus original computed `all` and `primarySourceCount`. Context items remain here in their original complete schema; the original `StockDetail.news/filings` empty arrays are preserved.
- `history`: original `QuarterlyHistoryRepository.parseIssuer` result plus original computed `latestTargetPoint`, `comparisonScope`, `comparablePoints`, `availableQuarterCount`, `directQuarterCount`, `reconstructedQuarterCount`, `hasFourQuarters`, `hasEightQuarters`, `hasTrailingGap`.
- `financial`: original `FinancialSafetyRecord`; invalid publication is an explicit adapter HOLD record with null ratios and original rejection in `basis`. `facts.amountsExact` additionally maps exact original integer amounts to nullable decimal strings for JavaScript BigInt; original `facts.amounts` is retained.
- `candidate`: complete original `FinalCandidateRecord`, or null.
- `valuation`: direct original `ValuationBandPolicy.classify(summary.m03Per)` result: `{code,labelKo,shortLabelKo,percentile,changesRank}`, or null. It is descriptive, never changes ranking and is independent of Stage4 validation.
- `sourceStates`: `context` has `status` (AVAILABLE/PARTIAL/PARTIAL_OR_CACHE/ERROR/MISSING/NOT_COLLECTED), `source` (COLLECTED/CACHE/MIXED/NOT_COLLECTED), `checkedAt`, `error`, `lastAttemptAt`, `lastAttemptError`, and independent `categories` metadata; `financial` and `candidate` have VALID/HOLD and original rejection text.

`financial.json` provides `{snapshotDate,policyVersion,source,status,error,records}` for list screens. Valid `records` maps issuer code to the same financial objects as details. Invalid publication produces HOLD, the original failure reason, and `{}` records.

`valuations.json` provides `{snapshotDate,records}` with all2649 issuer keys mapped to the same original ValuationBand objects/nulls as details. Null means the original classifier returned unavailable for missing, nonpositive or invalid PER/percentile; it is not Stage4 HOLD. No TypeScript threshold implementation is needed.

`candidates.json` provides `{snapshotDate,priceCutoffDate,status,error,records}`. `records` is the original `FinalCandidateRepository.getFinalCandidateMap()` after original validation/runtime installation. Valid publication has VALID and nullable error; unavailable/mismatched validation has HOLD, the original rejection explanation and an empty record map. It never blocks quant or Stage5 publication.

`sources/{quant,financial,candidates,history}.json` retains the exact selected raw input bytes. `sources/context/{issuerId}.json` retains actual checkpoint observations used. No assets or evidence sources are modified.

`manifest.json` has `schemaVersion:1`, UTC `generatedAt`, exact Git `sourceCommit`, original `snapshotDate` and `priceCutoffDate`, `inputHashes` (named source SHA256, original compiled Kotlin source paths/hashes and used context observation hashes), `coverage`, `financial:{status,error}`, and `files`. Each file entry is a relative path mapped to `{sha256,bytes}`. The manifest does not hash itself. Consumers should bind one generation and validate files before installation. Coverage independently counts quant metrics, complete stocks, all details/masks, valid financial/candidate state and records, original comparable4/8-quarter histories, and each actual context state. HTTP receive time does not replace source dates or context observation time.

## Per-category context provenance

`sourceStates.context.categories` has keys `news`, `disclosures`, `ir`, `official`. Each value contains `loaded` (collection attempted, not source confirmation), `count`, `status`, `source`, `checkedAt`, `lastAttemptAt`, `lastAttemptError`, `retainedCount`, and `itemObservedAt` (identity-key to original observation timestamp). Saved refresh metadata also has `retainedIds`. A canonical DART row identity is `receipt:{receiptNo}`; other identities contain the original kind/id/URL/title. No Naver ID is converted to a DART receipt.

A category with zero rows is always MISSING, even when the original bundle has `loaded:true,error:null`. A cached category is CACHE, a fresh nonempty error-free category is AVAILABLE, and retained/mixed failed observations are PARTIAL_OR_CACHE. `checkedAt` is the oldest included row observation when a category mixes dates; `itemObservedAt` keeps the finer row timestamps. `lastAttemptAt` records the latest collection start separately. Overall context never becomes COMPLETE solely because the bundle error is null. `coverage.contextComplete` counts only observations with all four actual categories freshly AVAILABLE. Cached/partial observations count in `contextPartial`.

Regression fixtures are explicitly REAL_MARKET_RECORDED; the local HTTP503 proxy and controlled availability tests are test scenarios, not fresh market source evidence. See `tests/test_context_cache.py`.
