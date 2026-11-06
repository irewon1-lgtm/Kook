# Actual E3 PC build CI results

[Run 42145573441](https://github.com/irewon1-lgtm/Kook/actions/runs/42145573441) tested source `e3b0d190d6c95dde304110aa78b51db435280915`. Build and branch publication jobs succeeded. Actual build runner OS: Ubuntu 24.04.5 LTS.

| Actual stage | Passed | Additional scope |
| --- | ---: | --- |
| Selected Python publication/collection tests | 44 | 35 subtests passed |
| Original source export Python tests | 12 | Observed actual count; no assumption of 16 |
| Focused original Kotlin price parity | 229 | Included again in full unit suite; do not add as unique tests |
| Full web unit suite | 300 | 8 test files |
| Linux Chromium browser suite | 53 | 0 skipped, 0 unexpected, 0 flaky |

Stage counts are preserved in actual build logs and selected evidence lines. Browser counts were independently checked against the actual archived `web/test-results/results.json`: all 53 test entries have expected/pass status with no test errors. Its embedded CI metadata pins E3 source and this run.

The 67,270,272-byte verification artifact was read selectively: only 17,462 bytes transferred across 6 HTTP 206 ranges to extract the 63,631-byte browser JSON. No XML members were present in that actual artifact; hidden `.pc-work/` paths were excluded by upload-artifact, so XML counts are not fabricated. No temporary signed URL or token was written.

Linux local-site browser checks include explicitly controlled network sequences. They are separate from real public-site Windows browser checks and physical hardware evidence. Official Pages deployment and new Windows runs against `https://irewon1-lgtm.github.io/Kook/` are tracked separately by root.
