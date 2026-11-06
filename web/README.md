# KR4 PC web

Separate, anonymous PC application for the existing KR4 Android source and verified publications. The Android package, signing, formulas and original engine files are not modified.

- Default body text22px, persistent20–32px controls, keyboard navigation, browser-local favorites.
- Four ranking selectors start expanded and selected, in normal document flow. No sticky/fixed selector.
- Original Kotlin exports all2,649 stocks, fifteen ranking maps, five-tab detail data, all analysis/report fields, quarterly history, verified financial facts and original candidate/PER classifications.
- Browser ranking is exhaustively compared with the original Kotlin engine. Financial status never enters the ranking comparator.
- Naver daily requests use60 rows. Browser direct access is CORS-blocked, so Actions collects actual OHLCV. The website labels these histories CACHE with source observation/cutoff/last-trade dates. Missing prices and quarters remain missing.
- LIVE means newly received verified GitHub-published snapshot, not live exchange quotes. Explicit recorded observations are CACHE even after a successful HTTP request.

## Development and verification

Node24, Python3.12 with requests/pytest, JDK17 and Gradle8.7 are used by the workflow.

From the repository root:

    python scripts/export_pc_oracle.py --output .pc-work/oracle --context-cache .pc-cache/context
    python scripts/collect_pc_prices.py --universe .pc-work/oracle/stocks.json --cutoff YYYY-MM-DD --output .pc-cache/prices --workers 8
    python scripts/build_pc_publication.py --oracle .pc-work/oracle --prices .pc-cache/prices --output web/public/data --bundle web/src/generated/bundled.json
    npm ci --prefix web

The workflow authenticates and unpacks the committed real price/context seeds before these steps. It separately authenticates recorded raw price observations and executes the original Kotlin price parser/projector before TypeScript parity tests. See data/README.md and .github/workflows/pc-web.yml for the complete reproducible sequence.

From web/:

    KR4_ORACLE_DIR=/absolute/path/to/.pc-work/oracle KR4_PRICE_ORACLE_DIR=/absolute/path/to/.pc-work/price-oracle npm test
    npm run build
    npm run dev
    npm run test:e2e

Public-site checks accept KR4_PUBLIC_URL as the exact deployed index.html URL. Actual Windows jobs use KR4_BROWSER_CHANNEL=chrome or msedge. CSS viewport/DPR profiles approximate requested display scale/zoom, while physical LG Gram testing is reported separately.

Generated public/data, src/generated, Gradle output, node_modules and test artifacts are ignored. Only authenticated genuine recorded fixtures are committed for regression; their provenance is explicitly separate from current market captures.

## Free publication

The reviewed workflow publishes compiled output only to kr4-pc-site. A pinned free raw.githack CDN URL loads the UI; a stable anonymous GitHub raw endpoint supplies current immutable, SHA-verified generations. No ChatGPT/GitHub account or token is required in the browser. GitHub Pages is additionally deployed when the repository has an existing Actions-configured Pages site.

Daily source refresh, independent source-triggered publication, current/previous verified IndexedDB cache and in-memory recovery preserve honest dates and errors. A failed optional financial/candidate transaction never blocks valid quant reception. Public errors preserve verified context rows with original dates; new failed attempts are not relabeled as fresh verification.

See data/README.md for free service limits, pinned UI update behavior,300-second raw endpoint caching, history growth and lack of third-party CDN uptime guarantees. No paid plan or payment registration is used.

## Evidence and differences

docs/pc/feature-parity.md and the public verification page map original Android information/actions to PC code and actual evidence. Source-specific failures, controlled tests, public browser runs, native Windows browser runs and physical-device checks have separate statuses. No independent visual score or whole-task PASS is implied by a successful build.
