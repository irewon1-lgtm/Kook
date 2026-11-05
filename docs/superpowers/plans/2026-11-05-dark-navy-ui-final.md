# Approved Dark Navy UI Implementation Plan

> Execute the already-approved user design directly. The user explicitly excludes new proposals and approval loops and authorizes implementation, actual validation, main integration and original-signed APK publication.

**Goal:** KR4_DARK_NAVY_UI_FINAL_20261105 reproduces the four approved screens on current main5569726 / official4.3.209 while preserving real data and all investment behavior.
**Architecture:** Update existing Compose presentation and navigation only. Reuse repositories, ranking, financial validation, OHLCV/aggregation, background updates and original signing vault unchanged. Always render the approved navy palette even under a light system setting.
**Spec:** The user's final B-design directive and original JPEGs under docs/design/approved-dark-navy. Attachment content mapping is home16403, ranking16404, detail16402, chart16401; labels/data in the JPEGs are examples.

## Global Constraints

- 2649 visible, all15 combinations and M01–M04 calculations/ranks unchanged; missing reasons visible.
- Existing source states, financial criteria/colors, rawOHLCV aggregation and unverified-adjustment warning preserved.
- All existing detail tabs, filters, searches, source evidence and controls remain reachable.
- com.krstock.v3 / original certificate1070e82beee574cd29ffd2dc3e693fd7f7862374f16d1606567db303ab152477; baseline official209, release strictly newer.
- Existing app data/cache/settings preserved; added navigation must perform real actions.

## Review Focus

- Dense rows on narrow screens and large fonts: grow/wrap rather than overlap; full company identity remains accessible.
- Distinguish raw composite values from percentile scores and example quotes from actual quotes.
- Financial HOLD/FAIL cannot hide/reorder quant ranks; no unavailable quote values fabricated.
- Keep real-market screenshots separate from controlled conditions; comparisons must use untouched originals.
- Design score is an evidence-based independent assessment, never an automatic95 assertion.

## Tasks

- [ ] Theme/navigation: RED palette tests, always-navy semantic colors, functional five-item bottom menu, existing back/pager preserved; GREEN JVM/build.
- [ ] Home/ranking: compact data tables, blue rank/metric bars, truthful source/count panel, all filters/combos/search reachable; retain semantic assertions.
- [ ] Detail/finance: compact actual score/metric/financial summary, every existing detail below/in tabs, exact source dialog preserved.
- [ ] Chart: large raw-data canvas with legible axes, compact quote/controls/warning and complete provenance, unchanged geometry/data/aggregation.
- [ ] Actual validation: add four LIVE main-activity captures, run all Python/JVM/Android and four API34 cells, compare original reference and actual screenshots side by side. Fix mismatches; independent rubric4×25 with critical defects0, target95.
- [ ] Integration/delivery: whole-branch review and CI, then main original-key build; actual209→new install-r identity/data/cache/LIVE/UI checks, public redownload hashes, originalPNG/HTML/ZIP/comparison/report assets.
