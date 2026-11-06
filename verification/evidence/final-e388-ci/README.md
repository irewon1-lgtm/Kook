# Final e388 CI evidence

Source commit: **e388ea2b7aa52843af5c0d32861cc0493bfe5f5a**.

- PC PR run [42157893694](https://github.com/irewon1-lgtm/Kook/actions/runs/42157893694): **SUCCESS**.
- PC deployment run [42157888715](https://github.com/irewon1-lgtm/Kook/actions/runs/42157888715): **SUCCESS**, all five jobs including build, publish, Pages and both native Windows browsers.
- Android PR run [42157893754](https://github.com/irewon1-lgtm/Kook/actions/runs/42157893754): **SUCCESS**, all six jobs.

| Verification | Fresh actual result |
|---|---|
| Python full suite, each PC build | 366 passed + 103 subtests passed |
| JVM original exporter contracts | 12 passed |
| Recorded actual OHLCV oracle | 229 passed |
| Frontend unit suite | 319 passed |
| Linux browser, PR and deployment | 53 passed each; 0 skipped/flaky/unexpected |
| Native Windows Chrome 156.0.8078.80 | 53 passed; 0 skipped/flaky/unexpected |
| Native Windows Edge 156.0.4314.56 | 53 passed; 0 skipped/flaky/unexpected |
| Android14 emulator matrix | 24 each across phone light/dark, tablet light/dark; 96 total |

Publication/corruption subset 44 tests +35 subtests overlaps the full Python suite and is not an additional unique count. Native Windows used actual win32 runners and branded headless browsers. These results do not claim a physical LG Gram, physical laptop, Windows11 desktop, or physical display-scaling test.

The e388 Android `extreme-cleanpass` job detected `APP_SOURCE_CHANGED=false`; its JVM153 step and APK-evidence upload were **SKIPPED**. Earlier JVM153 evidence retains its earlier execution scope and is not relabelled as a fresh e388 run. Android96 is confirmed by the four fresh instrumented job logs.

Live Pages: **https://irewon1-lgtm.github.io/Kook/**. Published site commit: **2d56fc60fe0373cac6ba2d7d3d43cd7d9f76962d**. Stable data: **https://raw.githubusercontent.com/irewon1-lgtm/Kook/kr4-pc-site/data/**. Both native browser public-capture JSON sets assert sourceCommit exactly e388.

`summary.json` is the compact machine-readable report. `verification-excerpts.log` contains precise executed test-count lines. `manual/` and `pr/` contain complete workflow logs, downloaded verification/browser/publication artifacts, and the deployed Pages artifact. `android-run.json`, `android-logs.zip` and `android-logs/` preserve the fresh Android run scope and 96-test evidence. A range-only index of the large Android gallery was read without downloading its APK/screenshots; no JVM report artifact exists in this skipped scope.
