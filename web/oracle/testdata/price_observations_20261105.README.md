# Recorded original-source price parity fixture

`price_observations_20261105.tar.gz` is a deterministic `REAL_MARKET_RECORDED` fixture for the original Kotlin price parser and chart projection regression tests. Its storage source is `CACHE`. Five original collector records retain `source: CACHE`; 005030 retains `source: MISSING`, empty bars and no successful observation timestamp. The fixture is never represented as current LIVE production data.

The archive contains the exact six original `prices/CODE.json` byte streams and all 27 referenced raw provider pages under `raw/CODE/SHA256.json`, for 33 members and 1,088 normalized daily bars. Codes: 005930, 000660, 000020, 0001A0, 000300, 005030. No fields, prices, bars, observation dates, provenance or adjustments were estimated or changed. The requested cutoff remains 2026-11-05. Successful observations were recorded between 2026-11-05T23:46:37.966687Z and 2026-11-05T23:49:08.695306Z; each exact `observedAt` and the missing case's `lastAttemptAt` are retained in the sidecar.

`price_observations_20261105.provenance.json` records:

- `archive.path`, `archive.sha256`, `archive.bytes`: compressed archive authentication.
- `files`: the exact member allowlist, with each original member's SHA-256 and bytes.
- `counts`: six issuers, six normalized records, 27 raw pages, 33 members, 1,088 bars, five CACHE records and one MISSING record.
- `cases`: per-code classification, source, observedAt, lastAttemptAt, cutoff, last trading date and counts.

Archive SHA-256: `de5cc3f9b8edf5fc41dbfd0299f258a60afbadb47bd58fa95d47e552ec39d032` (54,608 bytes).

The gzip header uses an empty filename, mtime 0 and compression level 9. Its USTAR payload sorts member paths and fixes each regular file's mtime/uid/gid to 0, mode to 0644 and owner/group names to empty. Repacking the same exact members with these settings reproduces the archive byte for byte; two independent encodings were compared during creation.

Authenticate the archive against the sidecar before extraction, require the exact regular-file allowlist, and authenticate every member after extraction. Unpack to a separate recorded-source directory. Do not merge these observations into a fresh production collector cache or relabel them as LIVE.

From the repository root, after authenticated extraction to `.pc-work/recorded-price-source`, run:

```bash
python scripts/compare_pc_price_oracle.py \
  --cache "$PWD/.pc-work/recorded-price-source" \
  --output "$PWD/.pc-work/price-oracle"
```

Use absolute paths because the runner invokes Vitest from `web/`. `--skip-build` may be appended after the current original-source JVM oracle distribution has already been built. Subsequent full Vitest runs should set `KR4_PRICE_ORACLE_DIR="$PWD/.pc-work/price-oracle"`.

Validation authenticated the archive and all 33 members, verified every raw-page reference and original source/count/date fields, then executed the current compiled original Kotlin parser/aggregator and all 229 parity tests successfully against a separate extraction. The 144 actual rendering selections cover 72 unique source windows; the three separately labeled controlled fixtures add 72 selections. Fresh production price/E2E checks remain independent of this pinned regression fixture.
