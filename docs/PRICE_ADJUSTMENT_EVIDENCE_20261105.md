# Chart adjustment evidence — 2026-11-05

The chart retains the provider's original OHLCV and displays **수정주가 미확인**. No split/reverse-split coefficient is applied. This is an explicit **UNVERIFIED** state, not a claim that adjusted prices passed verification or that the provider is definitively unadjusted.

## Actual event examples investigated

| Issuer | Actual provider notice | Last valid pre-event close | First valid post-event close | Provider reference price |
|---|---|---:|---:|---:|
| 미원화학 134380 | 액면분할; effective 2026-04-13 | 118,600 (2026-03-30) | 11,000 (2026-04-13) | 11,860 |
| 밸로프 331520 | 액면병합; effective 2026-04-30; par 100→500 | 524 (2026-04-07) | 2,615 (2026-04-30) | 2,620 |

These values come from the actual 2026 responses, not the synthetic M04 fixtures. The split fixture's 2026-04-17 and reverse-split fixture's 2026-05-04 are **not** the actual effective dates found in these provider notices. M04 code, tests, formulas and ranking are unchanged.

Price responses:

- https://m.stock.naver.com/api/stock/134380/price?pageSize=60&page=3 — SHA256 `6dc38450fbd9246f1e7e1216568ee4809111dac1b0b85577729783323d9dba59`
- https://m.stock.naver.com/api/stock/331520/price?pageSize=60&page=3 — SHA256 `b4f7748f58fc5f6f28264b4d60adbac652ea15ca51a0478dcba54b4a8c86b6bd`
- https://m.stock.naver.com/api/stock/134380/disclosure/1629227 — provider notice reference price/effective date.
- https://m.stock.naver.com/api/stock/331520/disclosure/1635989 — provider notice reference price/effective date/par values.

The notice author is KOSCOM; these Naver retransmissions are investigative leads, **not independent official DART/KRX event confirmation**. Large raw returns and provider `fluctuationsRatio` are not adjustment evidence. For example, the reverse-split row's reported daily change is −0.19% against reference 2,620 while its previous reported close is 524. Applying that comparator to all historical OHLC would be an unsupported assumption.

## Official sources actually inspected

- [OpenDART DS005 catalogue](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS005), actual HTTP200 response, SHA256 `a70516778e5cf1e2ece684d9cecf030c12713e073f03195c675e98bee614a608`.
- [OpenDART 감자 결정](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020026), actual HTTP200 response, SHA256 `0318607852c7a18f3399f20fb5969f61ae11f3cc0adc4662ee424f556063e7d7`. `crDecsn` identifies corporation/receipt, reduction ratio, reference date and planned listing dates. It is not a general 액면병합 price-adjustment feed and does not certify Naver OHLC's existing adjustment basis.
- [OpenDART 회사분할 결정](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020051), actual HTTP200 response, SHA256 `5506b6e7a7608403a6d279919f5d1fe0c8f0ff5c9206f79c51e6d0db7e930949`. `cmpDvDecsn.dv_rt` describes a **company/business division**, not a stock's 액면분할 coefficient. It cannot be reused as such.
- [KRX KIND official search](https://kind.krx.co.kr/disclosure/searchtotalinfo.do?method=searchTotalInfoMain) returned HTTP200. Search requests returned empty fragments; attempted viewer links based on provider identifiers returned a blank shell. Those attempts did not retrieve official event documents. A provider `bizDateSeq` is not assumed to equal a KRX receipt number.
- [KRX data](https://data.krx.co.kr/contents/MDC/MAIN/main/index.cmd) returned HTTP403; [KRX rules](https://law.krx.co.kr/las/main.jsp) timed out. Neither failure is evidence about event absence.

No official event document was independently corroborated in this investigation. The public OpenDART API guides require an authentication key; no credential was configured or changed for this task. No complete official issue-specific event chain, authoritative OHLC adjustment semantics, rounding policy or proof against prior adjustment was obtained. Even one confirmed par-value ratio would not establish complete factors for every chart window or prove that a second adjustment is safe. Raw fallback is therefore mandatory.

## Implementation and verification

`PriceAdjustmentProvenance` exposes UNVERIFIED separately from LIVE/CACHE/MISSING. The same explicit label, reason, treatment and source appear for every interval/window/chart mode. The app reports “선택 기간 원본 가격 변동”, making no adjusted investment-return claim. Prices and volume are not transformed; no verified state is available without a future independently verified data path.

RED: the new independent-provenance contract assertion failed before production edits. GREEN: `testDebugUnitTest` ran **131 tests, 0 failures, 0 skipped**, including **22 price tests**. Actual response hashes are asserted in JVM tests; every retained OHLCV field matches the source. Suspended dates with zero O/H/L are excluded, not fabricated. Existing exact calendar week/month/year-boundary, volume, pagination, missing-data and geometry tests remain green. Actual split/reverse-split responses also aggregate with first open/max high/min low/last close/exact volume and preserve discontinuities.

Raw downloaded responses, failed-attempt metadata, per-response URLs/retrieval timestamps/SHA256, RED/GREEN logs and `investigation-proof.json` are saved under `/workspace/kr4-completion/prices`. The two original price responses are also permanent JVM resources in `app/src/test/resources/prices_20261105/`. Initial exploratory guide URLs that resolved to unrelated API pages are retained as attempts; only the correctly identified official documents linked above support this report. Device/UI verification is owned by the independent UI evidence task; this report makes no device-run claim.
