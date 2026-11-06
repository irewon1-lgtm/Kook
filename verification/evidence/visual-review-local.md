# Independent visual review — fresh local browser evidence

Status: local visual review meets the target under the rubric below. Actual PUBLIC-site acceptance is still NOT_RUN. This is not a whole-task PASS.

Four approved original JPEGs were compared with actual post-density Linux Chromium captures at1365×853 and1920×1200 CSS px, plus six ordinary-scroll1365×853 captures. Default font22px. Actual sources/values were retained. No image generation or original-image editing was used.

## Rubric and actual result

Each screen has25 possible points: palette/status contrast5; information hierarchy6; card/table geometry/spacing5; typography/readability5; controls/discoverability4. These are independent reviewer judgments, not an automatic pixel-similarity percentage. Intentional PC accessibility changes and actual newer data are not themselves penalized.

| Screen | Palette /5 | Hierarchy /6 | Geometry /5 | Typography /5 | Controls /4 | Total /25 |
|---|---:|---:|---:|---:|---:|---:|
|home|5|5.5|4.25|5|4|23.75|
|ranking|5|5.5|5|5|4|24.5|
|detail|5|6|4.5|5|4|24.5|
|chart|4.75|6|5|4.75|3.5|24.0|

Total: **96.75/100**. Critical visual defects observed in the inspected captures: **0**. This observation does not substitute for the full viewport matrix, public-site operation, native Windows, or physical LG Gram testing.

## Evidence and explicit deductions

### home

- information_hierarchy: −0.5. At1365x853 the top-five table starts at the fold, so reading actual ranks requires ordinary scrolling; approved mobile reference gives ranks more immediate prominence. Evidence: `canonical-home-1365x853.png`.
- geometry_spacing: −0.75. Hero preserves navy/blue language but has more unused vertical space around the title/counts and uses simple bars instead of the approved subtle candle motif. Evidence: `canonical-home-1365x853.png`.

### ranking

- information_hierarchy: −0.5. Default expanded metrics and visible source/search controls push actual rank rows below the first fold. This is readable and nonfixed, but a minor difference from approved table-first density. Evidence: `canonical-ranking-1365x853.png`.

### detail

- geometry_spacing: −0.5. The score-card middle fact label wraps to two lines (기존4지표순위), so rank22 has a lower baseline than adjacent total/count numbers; all text remains readable and uncut. Evidence: `canonical-detail-1365x853.png`.

### chart

- palette_status_contrast: −0.25. Rising candles use a softer pink-red than the vivid red in the approved reference, while up/down semantics remain correct. Evidence: `focus-chart-1365x853-chart-controls-and-plot.png`.
- typography_readability: −0.25. Automatically calculated price ticks include half-won decimals (.5), adding minor reading noise compared with the approved integer labels; these are axis ticks, not fabricated transaction prices. Evidence: `focus-chart-1365x853-chart-controls-and-plot.png`.
- controls_discoverability: −0.5. Daily/weekly/monthly and date-range buttons share one row with limited group separation; both selected states are visible but the approved separate-row grouping is more immediately distinct. Evidence: `focus-chart-1365x853-chart-controls-and-plot.png`.

## Resolved findings

- Earlier detail first-fold captures showed no meaningful four-metric content. Fresh desktop score+metrics two-column layout now shows all four bars next to the score at1365px.
- Earlier chart first-fold capture showed no actual plot. Header/quote spacing was reduced without shrinking controls or explanatory text; the fresh first fold now shows real candles. Normal scrolling shows the full plot, volume and OHLC summary.
- Shared white-on-blue normal22px button contrast improved from4.20:1 to4.911:1 by changing only the primary background. Other measured token contrasts: body15.78, secondary8.79, risk6.77, missing8.21, neutral11.30:1.

## Artifacts

- Original captures and per-image metadata: `/workspace/kr4-pc-work/layout-matrix-post-density`.
- Side-by-side comparison HTML, untouched copied originals, SHA manifest and four labeled browser comparison PNGs: `/workspace/kr4-pc-work/visual-comparison-local`.
- Four comparison pages opened in a real browser and all images decoded; JavaScript errors0. Comparison browser execution evidence: `comparison-browser-evidence.json`.
- These comparison PNGs are explicitly labeled composites rendered from comparison HTML. They are not original product screenshots.
- JSON scoring and file checksums: `/workspace/kr4-pc-work/visual-review-local.json`.

## Remaining scope

Repeat against actual PUBLIC-site captures before claiming public visual acceptance. Native Windows/Chrome/Edge and physical LG Gram execution remain separate evidence scopes. No public or physical PASS is asserted here.
