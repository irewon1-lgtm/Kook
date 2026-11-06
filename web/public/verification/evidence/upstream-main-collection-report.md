# Main collection recovery: actual execution evidence

Final observed main: **d377f493fc4cc92fc748d6193e12980e27e21745**. Quant, quarterly history, financial facts and candidates align on **2026-11-06**; quant/candidate price cutoff **2026-11-05**.

## Executed workflows

| Run | Event | Actual result |
|---|---|---|
| [42152787593](https://github.com/irewon1-lgtm/Kook/actions/runs/42152787593) | push, hotfix main merge | SUCCESS, all six jobs; actual log explicitly skipped signed APK release |
| [42152978807](https://github.com/irewon1-lgtm/Kook/actions/runs/42152978807) | workflow_dispatch | SUCCESS, normal quant/history collection with no overrides |
| [42155937715](https://github.com/irewon1-lgtm/Kook/actions/runs/42155937715) | workflow_run | SUCCESS, existing recovery follow-up; all quant records unchanged |
| [42155937716](https://github.com/irewon1-lgtm/Kook/actions/runs/42155937716) | workflow_run | SUCCESS, official financial facts and final candidates, both jobs |

The financial follow-up started automatically after quant succeeded. No manual financial dispatch was needed or issued.

## Real data and gates

- Quant: **2649** issuers, available M01/M02/M03/M04 **2561/2410/2620/2619**, complete/ranked **2370**. Recovery integrity and four-metric coverage gates passed; source errors **0**.
- History: four/eight-quarter coverage **2467/2214**; original eight-quarter floor **1192** retained. Identity gate verified all **2649** issuers.
- The formerly blocked JVM/debug-build gate passed against newly collected Nov6 quant with existing Nov5 independent financial data (`BUILD SUCCESSFUL in 1m 26s`). This is actual upstream collection evidence, separate from controlled regression fixtures.
- Financial: **2109 PASS / 275 FAIL / 39 HOLD / 226 NOT_APPLICABLE**, total **2649**. Raw balance sheets **2641**, verified facts **2586**, filing verified **2621**. Original Android parser passed before publication. Financial `quant_records_sha256` matches final quant records, and issuer sets match exactly.
- Remaining 39 HOLD reasons remain explicit: correction values not reconciled **5**, no balance sheet **3**, identity mismatch **9**, invalid filing date **6**, issuer-name mismatch **8**, mixed period **7**, nonpositive assets **1**. Successful collection does not imply every issuer passed financial safety.
- Candidates: original policy gate passed with **1366 eligible / 10 candidates**. No new ranking formula or threshold override.

## Published commits and Android preservation

- `edb07dbe4dde6b0ac35bfff92f6abfa1993fa00f`: actual quant + eight-quarter history; exactly two remote evidence JSON files.
- `220a2e3f8c09465176fb34f372006bc04c4fd2d8`: existing recovery metadata update; every quant record remains equal to the just-published quant records.
- `7917d752103ec59d4255f4817d8e259e790c5f83`: `[skip ci]` independent verified financial facts.
- `d377f493fc4cc92fc748d6193e12980e27e21745`: `[skip ci]` independently verified final candidates.

From hotfix merge `f341fbd3a06da124ba2372ec7a2f13a427aaf2ec` through final main, only four remote evidence JSON files changed. No Android production, engine, signing, build input or bundled Kotlin file changed. The existing quant workflow intentionally publishes remote JSON only.

Actual main-merge log: `No APK production input change: signed release intentionally skipped.` Official latest release remains **kr4-app-c20210**. Release ID and both APK/update.json asset IDs, sizes, timestamps and SHA256 digests exactly match the pre-dispatch baseline. APK SHA256: `579d25b95a02e5795c31a05526c4ef4bbcdbddb1f0334b843828b7b40a1c0017`.

## Schedule honesty

Historical scheduled quant run **42100942826** remains **FAILED** from the old date-coupled test. Today's quant recovery was **manually dispatched**, followed by observed **automatic workflow_run** recovery and financial refreshes. A subsequent timer-triggered quant run has not yet been observed; this report does not claim scheduled execution has newly passed.

## Evidence

Files with prefix `upstream-main-collection-` retain final workflow/job JSON, actual log archives and extracted logs/gates, before/after/final source hashes and metadata, financial-record fingerprint validation, and before/after/final APK release metadata. `upstream-main-collection-status.json` provides a concise machine-readable outcome.
