# Final main independent visual review

**95.25/100. Critical visual defects observed: 0.** The four fresh canonical main captures meet the visual target. No blocking visual revision is identified.

Reviewed commit: `8b1b45671072636dff59f1ced48aa311204fe2ff` (merged main). Capture run: `42070717369`. I freshly viewed all four original Android PNGs, all four complete browser pairs and all four approved JPEGs. The score is independently reconfirmed from these images, rather than inherited from CI6 or inferred from passing tests. Different actual companies, dates, ranks, quote values, financial states, PER units and candle shapes receive no deductions.

Main phone-light and phone-dark jobs were reported successful at 24 tests each when assigned. Tablet/full-suite/sign-install/public-release gates remain outside this review; this is not an overall release PASS.

## Category scores

| Screen | Color /5 | Layout /5 | Type /4 | Spacing /4 | Readability /4 | Information /3 | Total /25 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Home | 4.75 | 4.5 | 3.75 | 4 | 4 | 3 | **24** |
| Ranking | 4.75 | 4.75 | 3.75 | 3.75 | 4 | 3 | **24** |
| Detail | 4.75 | 4.5 | 3.75 | 3.5 | 4 | 3 | **23.5** |
| Chart | 4.75 | 4.5 | 3.75 | 3.75 | 4 | 3 | **23.75** |
| **Total** | | | | | | | **95.25/100** |

## Actual observations and residual deductions

**Home — 24/25:** All five preview rows, four raw metrics, real safety badges, dates/source/counts and the complete ranking action are readable. The compact menu follows the approved footprint. Residuals remain: metric bars are shorter relative to the identity/value columns; some type and outlines are slightly stronger; a tiny top border of secondary coverage content appears near the bottom viewport. The quarter-point color/type and half-point layout deductions account for these small fidelity differences.

**Ranking — 24/25:** Ten rows and the full ranking action fit with a clean gap above navigation. No eleventh-row fragment appears. The full-image browser pair confirms that the app header is present; the tall raw-image viewer can crop its preview. Residuals: table separators and score-heading information treatment are simplified, and some header/numeric type is slightly stronger. The quarter-point color/layout/type/spacing deductions cover these differences. No required rank, identity, score or action is clipped.

**Detail — 23.5/25:** The completed quote, five single-line tabs, financial date, all four metrics/scores, financial ratios/equity, evidence entry and financial-sector note fit. Actual ratio-comparison wording is preserved. Residuals: the quote uses a line sparkline rather than faint candle decoration; small composite/metric icon and chevron treatments are simplified; the financial note leaves a short orphaned ending on its second line. Deductions are color/type 0.25 each and layout/spacing 0.5 each. These are minor fidelity issues, not missing data.

**Chart — 23.75/25:** The actual day/six-month/candle state, adjustment warning, plot, latest observed-bar OHLCV and complete source footer are visible. The selected tab underline and compact lower summary match the intended hierarchy. Residuals: the price-axis grid density and short date-label format differ; a supplemental range/bar-count line begins at the bottom viewport boundary. Required quote/OHLCV/source information is complete and extra content remains scrollable. Deductions are color/type/spacing 0.25 each and layout 0.5.

These residuals are unchanged in kind from CI6 after fresh main-image inspection. The result is not a perfect-match claim and was not rounded up to reach 95.

## Supplemental font 1.3 check

Fresh main phone-dark detail and chart originals were also viewed at 320dp / font 1.3. Detail retains the complete **38,429,384주**, including the unit and a visible label/value gap; price and change stack cleanly. All five 52dp navigation icons and labels are visible without detected clipping in the chart image. These checks are supplemental and unscored. The longer content scrolls on this smaller view.

## Evidence and integrity

- Approved references: `/workspace/Kook/docs/design/approved-dark-navy/{home,ranking,detail,chart}.jpg`.
- Canonical originals and adjacent JSON: `/workspace/kr4-navy-final/main-8b1/KR4-UI-phone-light/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 14/snapshot-e2e/navy-approved-{home,ranking,detail,chart}.png`.
- Complete browser pairs: `/workspace/kr4-navy-final/main-8b1-comparison/browser-pair-{home,ranking,detail,chart}.png`.
- CI comparison: `/workspace/kr4-navy-final/main-8b1/KR4-UI-phone-light/evidence/navy-comparison/index.html` and adjacent `manifest.json`.
- Supplemental originals use the same relative paths under `main-8b1/KR4-UI-phone-dark`.
- [Machine-readable scores, residuals, full commit, paths and SHA-256 values](/workspace/kr4-navy-final/visual-review-main-8b1-evidence.json).

Independently recomputed PNG/JPEG/JSON hashes match the main CI manifest for all four canonical sets; the packaged copies also match. Both supplemental PNG/JSON sets match the main dark manifest. Browser-pair, comparison HTML and manifest hashes are recorded separately. Every reviewed original was left unchanged. Canonical metadata identifies API 34, 360dp / font 1.0, LIGHT system setting, MainActivity, successful LIVE receive and uncontrolled real-market state.

No unreadable overlap, clipped required canonical control or visibly covered essential control was observed. Visual inspection alone does not prove touch behavior or hidden data correctness. No app source edit, build or test was performed by this reviewer.

## Original-file SHA-256

| Screen | Actual Android PNG | Approved JPEG |
|---|---|---|
| Home | `ad6fa28ecb2cfa26f0a7366738d0783e5579b10f902835fb1970c21b3908cffe` | `e0c407f25aca3d888e579aee5bf583606c65dc23e90fbe5b6705643ad5d4f906` |
| Ranking | `ff8df3ff628e66b85ceeaee236f7b13a7ccb118718951eeb2e1ef56f1865ae18` | `c89d0e6b640bed0b7ce6305a355a9d364cc14cbdd4fd4f12bd8143aa40afd26f` |
| Detail | `e60a0cf9a94f258b2d902b7fea5f272ec9f78089a040bb6f8eb49249bd24e288` | `6ae973d5a809ecbb3255d2734643bb585d433485e317138d91e70b14ffab78c1` |
| Chart | `1c067fe1c0bb5a5a13ad33ff4c12999961bf5e08727a256c7b9cda01bf162c5a` | `4f6f09806da3146629d9474c941d77b9e04f01b4209a24260260472b44ba395d` |
