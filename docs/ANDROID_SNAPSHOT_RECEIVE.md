# Android snapshot receive and display

M01–M04 and Stage4 are separate validation domains. `SnapshotAutoUpdater`
validates collector provenance, identities, values, percentiles, accounting of
coverage, contiguous ranks, dates and all existing quality floors before any
quantitative data is promoted. Optional `financial_safety` cannot reject an
otherwise valid four-metric document.

`FinancialSafetySnapshotUpdater` separately validates the safety policy,
identity set, ratios and exact active snapshot date. Authoritative absence or
invalidity clears Stage4, its cache and final candidates. Only transport failure
allows a revalidated, matching safety cache. Missing safety is HOLD, never PASS.
Final candidates require matching current safety and quantitative values; no
ranking is recalculated or weakened by this receive path.

Foreground bootstrap and the background worker share the same serialized
receive/install/publish path. Both update memory immediately. The repository
cache follows the immutable runtime rows and metadata, including corrections
with unchanged dates. A StateFlow revision causes Home, ranking and an open
detail screen to reread the current snapshot. Screens use a captured generation
for counts, dates and ranks rather than combining independently changing values.

Home reports LIVE, CACHE, BACKUP or BUNDLED as the data source, plus the last
successful refresh, latest attempt and any refresh failure. A failed request
keeps valid data visible and explicitly reports failure. There is no fixed
connection-success label.

The test-only fixture manifest records original Git commits/URLs and SHA-256
hashes for real October 7 corrections and the November 5 public document
(price November 4, complete 2367/2649, no Stage4 section). They are not bundled
production replacements. Instrumented tests cover HTTP startup/recreation and
background refresh, screen counts/dates/ranks, same-date corrections, cache and
backup recovery, and rejection of degraded/downgraded input. Screenshots and
JSON evidence are exported through AGP additional test outputs.

The release workflow keeps `com.krstock.v3` and the existing stable signing
identity. Before publication it installs the previous official signed APK on an
Android 14 emulator, upgrades with `adb install -r`, checks preserved UID,
first-install time and private app data, verifies the installed signature, and
checks the updated app's displayed current snapshot. This proves Android
PackageManager accepts an in-place update. Android's user-confirmed installation
dialog remains part of the in-app update flow.
