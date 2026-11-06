# PC publication / free deployment implementation evidence

Scope: scripts/build_pc_publication.py, tests/test_pc_publication.py,
.github/workflows/pc-web.yml, web/data/README.md only. No Android source, original
workflow/test, signing, quant rule or financial policy changes. No commits, pushes
or public deployment performed by this worker.

## Builder contract

CLI:

```sh
python3 scripts/build_pc_publication.py \
  --oracle /workspace/kr4-pc-work/oracle-all-context \
  --prices /workspace/kr4-pc-work/price-cache \
  --output /workspace/kr4-pc-work/publication-check/data \
  --bundle /workspace/kr4-pc-work/publication-check/bundled.json
```

Executed against the actual full-context original-Kotlin export and all-code actual
Naver price cache. Output generation:
`b7dc24bfc3c8119d840dc7b58d6d430d8171510f49300582c28bb22fd1964511`.
7,956 authenticated payload files; 238,396,154 payload bytes. Price coverage:
2,649 universe; 2,636 actual bar histories; 13 explicit MISSING/error; 2,528 through
2026-11-05 cutoff; 2,553 reached history boundary. No missing bars filled.
Previous representative generation retained and authenticated:
`b5adc1002782fd9541e687eae85918806a48d4e221106164d2ec86b845a97c2e`.

All original payloads are copied byte-for-byte, including authenticated sources and
verbatim original oracle-manifest.json. Root manifest preserves original inputHashes,
sourceCommit, dates, independent coverage and financial state; adds immutable
basePath, generationId, priceCoverage and all exact price/index payload hashes.
Generation ID is SHA-256 of canonical path→full-SHA/bytes mapping, which includes
the original manifest bytes. Input files are never modified. Files outside the public
payload allowlist and symlink/traversal paths are rejected. Original compiled Kotlin
input hashes are checked against actual repository source files.

Runtime validations cover 2,649 unique stock/detail/price code identities including
alphanumeric codes, all fifteen original rank maps and rank completeness/sequence,
source input bindings, exact original/detail summaries, truthful financial HOLD,
price/index metadata agreement, provider URLs/pageSize60, completed/sorted dates,
OHLC positivity/ranges and null-or-genuine integer volume. Failed empty HTTP responses
remain honest MISSING attempts rather than aborting valid quant publication.

Bundled fallback is {manifest, stocksGzip}; base64-gzip decodes to exact original
stocks.json bytes, without a 12 MB raw stocks array in JavaScript. Existing generation
paths are immutable. Current plus previous two generations are retained and rehashed;
corrupt old data or size overflow fails before replacing the current root manifest.
The builder limits data to 950 MiB and CI limits the complete site to 1,000,000,000 B.

## Executed verification

RED: `python3 -m unittest tests.test_pc_publication -v` first failed with missing
`scripts.build_pc_publication`; implementation then passed the meaningful contract
suite. A later explicit empty-HTTP-error fixture failed with `Invalid observed price
hash: 0004Y0`; accepting actual zero-byte failed responses fixed this independent
supplement case without relaxing bar provenance.

Final command:

```sh
/tmp/kr4-pc-publication-venv/bin/python -m pytest -q \
  tests/test_pc_publication.py tests/test_pc_prices.py \
  --junitxml=/workspace/kr4-pc-work/publication-python-results.xml
```

Final result: **36 passed, 11 subtests passed in 21.64s**. JUnit XML retained at the path above.

The isolated venv installs pytest because the base Python has no pytest. A direct
base-Python pytest attempt failed for that missing dependency; this was an environment
failure, not a passing test. CI explicitly installs pytest and requests.

`/tmp/pc-actionlint .github/workflows/pc-web.yml`: PASS (latest upstream actionlint).
All four embedded workflow Python programs parse with Python AST. The real committed
price/context seed archives were extracted into an isolated temporary workspace:
full manifest SHA/bytes verified before unpack, all 2,649 price payloads/index checked,
2,649 private resume checkpoints reconstructed, and 2,649 context observations
extracted. Deliberately wrong committed seed checksum was rejected before unpack.

Price seed: 13,342,218 B;
SHA-256 dde9e78ae73b0b71a5efc6e1ee7d4f750bd1003f6e084002a71179b54733c08d.
Context seed: 2,059,978 B;
SHA-256 c81a8f0a00897fa9ff53b31988aa554567624213761bd02842ff441aebf213bb.
`git diff --check` for the four owned files: PASS.

## Workflow and hosting limits

Triggers: scoped PR/main push, workflow_dispatch (including authorized feature
branch), 19:40 UTC daily schedule, and successful original collection workflow_run.
Original source workflow names are copied exactly from their actual YAML files.
workflow_run accepts original source branches (the Lite collector runs a refresh
branch while writing main), checks same repository and success, and ALWAYS checks
out current main. It does not execute triggering artifacts. [skip ci]/GITHUB_TOKEN
source commits still cause a PC rebuild through workflow_run.

Python 3.12, JDK17, Gradle8.7, Node24; npm ci; Playwright Chromium. CI runs publication
and price Python tests, original JVM exporter plus mismatch/HOLD tests, all frontend
unit/15-mask parity tests, production build and browser E2E. The original-source
export is reused for browser tests through KR4_ORACLE_DIR. Optional bounded context
refresh is continue-on-error and runs at most thirty issuers/four workers; financial
HOLD is handled by the exporter/builder. Prices increment from actual completed
quant cutoff with eight workers. Cache/seed prevents a complete recollection each run.
Scheduled context rotation revisits the universe in about 89 daily runs.

The workflow restores branch-scoped observation/publication caches and recovers the
last successful Pages artifact across branch cache boundaries with read-only
built-in github.token (actions:read). Publication caches save only AFTER successful
Pages deployment so failed builds cannot evict the active site's generation paths.
Caches may be evicted; the Pages artifact retention is one day. Simultaneous cache
loss plus an expired artifact can lose old generation URLs; current data still
rebuilds from seeds. Reports/screenshots have 14-day retention. No permanent archive,
real-time feed or provider availability is claimed.

Public GitHub Pages has a 1 GB site limit and a soft 100 GB/month bandwidth limit.
Actions scheduling is not guaranteed; repository cache/retention limits apply. No
paid service, browser token, login requirement, new key or purchase was introduced.
Deploy permissions are pages:write/id-token:write; read-only action access permits
cross-branch artifact recovery. Public users require no GitHub account.

Root's external integration currently gets HTTP403 attempting initial Pages creation.
The workflow includes actions/configure-pages@v5 enablement:true so CI can record its
actual result, but upstream action.yml explicitly documents that initial enablement
requires a token OTHER than ordinary GITHUB_TOKEN (GitHub App administration:write
+ pages:write, or eligible PAT). Enabling Settings→Pages→GitHub Actions requires no
new browser/client credential. Already enabled Pages deployment uses ordinary
GITHUB_TOKEN permissions. This worker has NOT claimed CI/public deployment PASS.

References:
https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits
https://github.com/actions/configure-pages/blob/v5/action.yml

Full-context bundle is 2,038,837 B; root manifest 1,166,280 B. The isolated publication
with the preceding representative generation totals 410,005,503 B. Cache and
post-deploy retention steps are nonblocking; successful public deployment remains
a successful workflow if optional cache storage fails.
