# Actual observation seeds and free publication

`pc-price-seed.tar.gz` contains the actual Naver collector's `index.json` and
`prices/{code}.json`; `context-seed.tar.gz` contains flat `{code}.json` observations
from the original Kotlin context repository. These archives are bootstrap caches,
not demonstrations or synthetic market data. Their collection/check dates remain
inside each record. Missing bars and partial/error evidence stay missing or partial.
Raw response folders, HTTP headers, credentials and private checkpoints are excluded.
The build logs each archive's full SHA-256, validates every price/index hash and
reconstructs local resume checkpoints only after authentication. Context input bytes
are preserved and authenticated by the original-source export manifest.

Rebuild locally from verified external working directories:

```sh
python scripts/export_pc_oracle.py --output /tmp/kr4-oracle --context-cache /path/to/context-cache
python scripts/build_pc_publication.py --oracle /tmp/kr4-oracle --prices /path/to/price-cache \
  --output web/public/data --bundle web/src/generated/bundled.json
npm ci --prefix web
python scripts/compare_pc_price_oracle.py --skip-build --cache /path/to/price-cache --output /tmp/kr4-price-oracle
KR4_ORACLE_DIR=/tmp/kr4-oracle KR4_PRICE_ORACLE_DIR=/tmp/kr4-price-oracle npm test --prefix web
npm run build --prefix web
```

The builder verifies all 2,649 stock/detail/price identities, all 15 original ranking
maps, exact source and original compiled Kotlin hashes, genuine OHLCV constraints,
provider provenance and explicit missing states. Independent financial `HOLD` does
not prevent publication of valid quant data. Every immutable generation includes the
verbatim original manifest (`oracle-manifest.json`). The root manifest binds exact
payload bytes using SHA-256 and `generations/{content-id}/`; the initial browser
bundle carries base64 gzip of the exact authenticated stocks text.

The primary free publication uses a dedicated `kr4-pc-site` branch and the public
raw.githack CDN; GitHub Pages is an optional second host when already configured.
Readers need no account or browser API key. The PC workflow uses Python 3.12,
JDK 17, Gradle 8.7 and Node 24, executes Python,
original JVM, complete ranking/frontend and Chromium tests, then uploads `web/dist`.
Before frontend unit tests, CI authenticates and unpacks the separate pinned
`web/oracle/testdata/price_observations_20261105.tar.gz` archive and each of its
33 members using the recorded provenance manifest. Its six real issuer cases
contain five original CACHE records and one explicit MISSING record, together
with 27 actual raw response pages. The original Kotlin price oracle generates
projection artifacts in `KR4_PRICE_ORACLE_DIR`; all 24 rendering selections are
compared with those artifacts. These are recorded market observations dated
2026-11-05, kept separate from current production collection and LIVE reception.
The local comparison command above requires the collector's raw response files;
public price seeds intentionally exclude those private working folders.

PRs build and test with read-only repository permissions. After verified non-PR
builds, a separate job uses only the built-in `GITHUB_TOKEN` with `contents:write`
to publish compiled static files to `kr4-pc-site`; it does not change application
source or main. It rejects symlinks, repository internals, workflow files and files
at or above 100 MB. It creates a new commit only when compiled contents change and
pushes a normal fast-forward, never a force push. Main builds, source-triggered
builds and authorized manual feature-branch dispatches can publish this branch.
Source workflow completion rebuilds current main even when collector commits use
`[skip ci]`.

Each publication returns an immutable UI URL:
`https://rawcdn.githack.com/irewon1-lgtm/Kook/{siteCommit}/index.html`.
The production build uses relative asset paths (`KR4_BASE_PATH=./`) and
`VITE_KR4_DATA_BASE_URL=https://raw.githubusercontent.com/irewon1-lgtm/Kook/kr4-pc-site/data/`.
Thus an existing pinned UI can receive later verified data through the stable
branch endpoint. Updating UI code creates a new commit and a new UI URL; users
must open that new URL to receive new UI code. CDN URLs are cached by their commit
URL and query strings do not force updates. Data always retains original source
and collection dates; a new HTTP reception does not make historical prices LIVE.
GitHub raw responses were observed with `Cache-Control: max-age=300` and permissive
CORS, so a newly published branch manifest may take about five minutes to appear.
Runtime manifest/generation hashes prevent mixing those independently cached files.
Development/browser CI retains local `/Kook/` routing and local data; these production
endpoint variables are scoped only to the production build step.

Pages configuration is inspected through a real `GET /repos/{owner}/{repo}/pages`.
Only HTTP200 with Actions configuration permits the optional Pages deploy job and
its `github-pages` environment policy. HTTP403/404 or other configurations record
the exact status and skip reason; they do not block branch/CDN publication. The
workflow does not attempt to enable Pages or claim an absent site is deployed.
Branch publication evidence, returned URLs and public HTTP observations are saved
separately from browser verification.
After successful branch publication from a main push or manual dispatch, standard
`windows-latest` jobs run the public-site, viewport and controlled-recovery suites
in two genuine stable browser channels: Chrome and Microsoft Edge. They check out
the exact tested source, use Node 24/npm ci and the pinned public UI URL, and need
no local Kotlin/data fixture build. Installed browser paths, product/file versions,
executable hashes, Windows image/OS versions and actual browser protocol versions
are recorded alongside unmodified PNGs, traces and test results in separately
labeled Windows/browser artifacts. A missing selected browser is installed through
Playwright's Chrome/Edge installer; absence or launch failure fails that browser job.
The two browser jobs run independently with a 45-minute limit and read-only
repository permissions. Scheduled/collector-triggered data-only refreshes explicitly
skip the full Windows suite while retaining publisher HTTP observations; the next
main push or manual dispatch runs native Windows again.

This is actual Windows runner/browser execution, typically Windows Server. It is
not a physical LG Gram test or a Windows 11 user-desktop test. Viewport/text profiles
remain CSS-based emulation and do not measure a physical panel, desktop DPI or
physical desktop zoom. GitHub-hosted standard Windows runners are free for this
public repository; no paid larger-runner label, extra account or key is used.

Daily refresh runs at 19:40 UTC (04:40 KST), retries missing price histories and
refreshes all 2,649 original context observations with eight workers. Manual full
context refresh is opt-in; source-triggered builds reuse observations with their
original check dates. The initial full actual collection took about 18 minutes.
CI allows up to 90 minutes for this optional refresh and 120 minutes for the build;
source failures or a partial refresh preserve verified observations and expose
check/attempt dates and errors. Prices follow the quant snapshot's completed
trading cutoff; provider failures retain verified history and never acquire a
browser `LIVE` label.

The current branch tree retains the current generation and two preceding
successful generations for open browser tabs. Each build restores the live
`kr4-pc-site/data` tree and authenticates retained hashes before adding a new
generation, so Actions cache eviction or cross-branch builds do not remove that
protection. An unavailable existing branch fails the restore instead of silently
discarding public generations. Optional caches save after any successful public
branch publication, independently of Pages. The compiled artifact expires after
one day; reports expire after 14 days. Branch history retains old pinned UI commits
and grows over time; future repository maintenance must account for existing URLs.
The builder caps current data at 950 MiB and CI enforces the entire current site
below 1,000,000,000 bytes. Actions caches and artifact storage have repository limits;
older caches may be evicted and there is no permanent archival guarantee.

raw.githack describes itself as a free Cloudflare-backed caching proxy with no
formal uptime or support guarantee. It is an external service with no SLA or
reserved bandwidth; availability, caching policy and service limits can change.
GitHub raw delivery has its own caching, rate and repository constraints. There is
no unlimited-traffic or future-cost guarantee. This workflow purchases no service,
enables no paid quota and introduces no new account, key or client credential.
GitHub documents a 1 GB Pages site limit and a soft 100 GB monthly bandwidth limit
for the optional Pages host. Repository files remain below GitHub's 100 MB file
limit; repository history and stored artifacts must be monitored as they grow.
Actions workflows can be delayed; source providers can fail or change formats;
this static publication is not a real-time feed. GitHub-hosted standard runners
are free for this public repository; the private-repository 2,000-minute allowance
does not constrain the daily full context collection. This workflow does not
purchase storage, compute, bandwidth or another service and uses only the built-in
GitHub token.
Initial optional Pages enablement can be done in repository Settings → Pages →
GitHub Actions. Ordinary `GITHUB_TOKEN` cannot grant the administration permission
needed to create an absent Pages configuration; the branch/CDN host works without
that setting. Deploying to an already enabled Actions Pages site uses `pages:write`
and `id-token:write`.

References: [raw.githack hosting and cache policy](https://raw.githack.com/),
[GitHub file limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github),
[Standard runner billing](https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-actions/about-billing-for-github-actions),
[Pages limits](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits),
[Actions cache limits](https://docs.github.com/en/actions/using-workflows/caching-dependencies-to-speed-up-workflows#usage-limits-and-eviction-policy),
[configure-pages enablement permissions](https://github.com/actions/configure-pages/blob/v5/action.yml).
A local build or accepted artifact is not evidence that public deployment succeeded.
