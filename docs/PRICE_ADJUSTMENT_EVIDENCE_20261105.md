# Chart adjustment evidence — 2026-11-05

The chart retains the provider's original OHLCV and displays **수정주가 미확인**. No split/reverse-split coefficient is applied. This is an explicit **UNVERIFIED** state, not a claim that adjusted prices passed verification or that the provider is definitively unadjusted.

## Actual event examples investigated

| Issuer | Actual provider notice | Last valid pre-event close | First valid post-event close | Provider reference price |
|---|---|---:|---:|---:|
| 미원화학 134380 | 액면분할; effective 2026-04-13 | 118,600 (2026-03-30) | 11,000 (2026-04-13) | 11,860 |
| 밸로프 331520 | 액면병합; effective 2026-04-30; par 100→500 | 524 (2026-04-07) | 2,615 (2026-04-30) | 2,620 |

These values come from the actual 2026 responses, not the synthetic M04 fixtures. The split fixture's 2026-04-17 and reverse-split fixture's 2026-05-04 are **not** the trading-resumption dates found in these provider notices. The authenticated official follow-up below independently confirms the corrected split listing schedule and reverse-split trading-resumption date. M04 code, tests, formulas and ranking are unchanged.

Price responses:

- https://m.stock.naver.com/api/stock/134380/price?pageSize=60&page=3 — SHA256 `6dc38450fbd9246f1e7e1216568ee4809111dac1b0b85577729783323d9dba59`
- https://m.stock.naver.com/api/stock/331520/price?pageSize=60&page=3 — SHA256 `b4f7748f58fc5f6f28264b4d60adbac652ea15ca51a0478dcba54b4a8c86b6bd`
- https://m.stock.naver.com/api/stock/134380/disclosure/1629227 — provider notice reference price/effective date.
- https://m.stock.naver.com/api/stock/331520/disclosure/1635989 — provider notice reference price/effective date/par values.

The notice author is KOSCOM; these Naver retransmissions alone are investigative leads, **not independent official DART/KRX event confirmation**. The follow-up official documents below corroborate the events, but do not independently confirm these provider reference prices or the provider's historical OHLC adjustment basis. Large raw returns and provider `fluctuationsRatio` are not adjustment evidence. For example, the reverse-split row's reported daily change is −0.19% against reference 2,620 while its previous reported close is 524. Applying that comparator to all historical OHLC would be an unsupported assumption.

## Official sources actually inspected

- [OpenDART DS005 catalogue](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS005), actual HTTP200 response, SHA256 `a70516778e5cf1e2ece684d9cecf030c12713e073f03195c675e98bee614a608`.
- [OpenDART 감자 결정](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020026), actual HTTP200 response, SHA256 `0318607852c7a18f3399f20fb5969f61ae11f3cc0adc4662ee424f556063e7d7`. `crDecsn` identifies corporation/receipt, reduction ratio, reference date and planned listing dates. It is not a general 액면병합 price-adjustment feed and does not certify Naver OHLC's existing adjustment basis.
- [OpenDART 회사분할 결정](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020051), actual HTTP200 response, SHA256 `5506b6e7a7608403a6d279919f5d1fe0c8f0ff5c9206f79c51e6d0db7e930949`. `cmpDvDecsn.dv_rt` describes a **company/business division**, not a stock's 액면분할 coefficient. It cannot be reused as such.
- [KRX KIND official search](https://kind.krx.co.kr/disclosure/searchtotalinfo.do?method=searchTotalInfoMain) returned HTTP200. Search requests returned empty fragments; attempted viewer links based on provider identifiers returned a blank shell. Those attempts did not retrieve official event documents. A provider `bizDateSeq` is not assumed to equal a KRX receipt number.
- [KRX data](https://data.krx.co.kr/contents/MDC/MAIN/main/index.cmd) returned HTTP403; [KRX rules](https://law.krx.co.kr/las/main.jsp) timed out. Neither failure is evidence about event absence.

The initial investigation above preceded credential availability. The user subsequently provided an OpenDART key, and the authenticated follow-up below retrieved the official event documents. No complete official issue-specific event chain for every chart window, authoritative provider OHLC adjustment semantics, rounding policy or proof against prior adjustment was obtained. Confirmed event/par-value facts do not establish that applying a factor to Naver's historical OHLC is safe. Raw fallback remains mandatory.

## Authenticated official follow-up — 2026-11-05

The supplied key was used only in memory for normal OpenDART authentication. The sanitized `_Client` persisted public request parameters, response bytes and hashes; no key value or authenticated query URL was logged or copied. Corporation identities were resolved using the previously downloaded official `corpCode.xml` ZIP, SHA256 `699963386d2dea6b1219d655b84e326c1bef6b4bf1a085b057438dfb1cbe5ab1`. This follow-up made **10 successful HTTP200 requests**: two authenticated `list.json` requests and eight public DART viewer/main requests, spaced at one request per second within each sequential group. No follow-up request failed or required a retry.

The official list queries used `bgn_de=20260201`, `end_de=20260430`, `last_reprt_at=N`, ascending receipt date and page count 100. Each issuer had exactly one complete response page; dates below are the API's explicit `rcept_dt`, not inferred from receipt-number prefixes.

| Stock / official corporation | List result | Raw response SHA256 |
|---|---:|---|
| 134380 / 00855163 / 미원화학 | 29 filings | `4fdbac29140b832ac0664a71f8261f8bf3b3127d49c9ad311a19a503d0c7a635` |
| 331520 / 01398151 / 밸로프 | 14 filings | `f80ba7b074be891b646af7d054629bc23274cf907b6479e54294c3c2ee48ea3b` |

Official documents actually retrieved:

- [미원화학 주식분할결정, received 2026-02-05](https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260205800935): par value 1,000→100; ordinary shares 2,199,268→21,992,680. The original schedule said new-stock listing **2026-04-17**. Raw viewer-document SHA256 `69a720c70e5b90165a3f88dc741c5b0cec117e18bdf594dcbd268508ac052973`.
- [미원화학 기재정정, received 2026-04-09](https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260409801127): the document explicitly changes the trading-halt end **2026-04-16→2026-04-10** and new-stock listing **2026-04-17→2026-04-13**, with the same par values/share counts. It separately states new-stock legal effectiveness on **2026-04-02**; this is not conflated with the trading/listing date. The list marks the original filing `rm=유정`, while this correction is `rm=유`. Raw viewer-document SHA256 `fdd3e047cbc96f47ba4bbd194c663ad9b16bb985c7657ba4edb876ff6c58e33a`.
- [밸로프 주식병합결정, received 2026-03-09](https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260309900370): par value 100→500; ordinary shares 50,864,390→10,172,878; trading halt planned 2026-04-08 through 2026-04-29; new-stock listing planned **2026-04-30**. The text explicitly says this is a 주식병합 that maintains corporate value, **not a capital reduction (감자)**; it also describes cash treatment of fractional shares. Raw viewer-document SHA256 `8b5f290df6d1ff1cfafde05196d6f9406077f03ae8f2c39340fbc7cd08eb53da`.
- [밸로프 주권매매거래정지해제, received 2026-04-29](https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260429900904): official issuer is 코스닥시장본부; target is 밸로프 ordinary shares; reason is 액면병합 주권 변경상장; resumption is **2026-04-30**. Raw viewer-document SHA256 `07cb6854731b37c70eaa283d92442d4c0298a5ddc839f236e543c7df3dbb25f3`.

**Verified:** official corporation/stock identity, the listed event filings and retrieved event-document contents, including the split schedule correction and reverse-split resumption. **Still unverified:** any filings outside this focused February–April query, complete chart-window event chains, current finality across later amendments, an official adjustment method for Naver OHLC, factor rounding, prior provider adjustment and safe prevention of double adjustment. A 10:1 split or 1:5 share consolidation is an official event fact; neither alone certifies a complete adjusted historical OHLC series. The chart therefore continues to display **수정주가 미확인** and retains provider values unchanged.

New evidence is under `/workspace/kr4-completion/prices/authenticated-20261105/`: `official-event-list-proof.json`, `official-event-observations.json`, original API/main/document bodies, extracted document text and sanitized request metadata. Existing unauthenticated attempts and their failures remain preserved separately. This follow-up changes only this report and adds evidence files; it does not change price/M04/coefficient code or rerun application tests.

## Implementation and verification

`PriceAdjustmentProvenance` exposes UNVERIFIED separately from LIVE/CACHE/MISSING. The same explicit label, reason, treatment and source appear for every interval/window/chart mode. The app reports “선택 기간 원본 가격 변동”, making no adjusted investment-return claim. Prices and volume are not transformed; no verified state is available without a future independently verified data path.

RED: the new independent-provenance contract assertion failed before production edits. GREEN: `testDebugUnitTest` ran **131 tests, 0 failures, 0 skipped**, including **22 price tests**. Actual response hashes are asserted in JVM tests; every retained OHLCV field matches the source. Suspended dates with zero O/H/L are excluded, not fabricated. Existing exact calendar week/month/year-boundary, volume, pagination, missing-data and geometry tests remain green. Actual split/reverse-split responses also aggregate with first open/max high/min low/last close/exact volume and preserve discontinuities.

Raw downloaded responses, failed-attempt metadata, per-response URLs/retrieval timestamps/SHA256, RED/GREEN logs and `investigation-proof.json` are saved under `/workspace/kr4-completion/prices`. The two original price responses are also permanent JVM resources in `app/src/test/resources/prices_20261105/`. Initial exploratory guide URLs that resolved to unrelated API pages are retained as attempts; only the correctly identified official documents linked above support this report. Device/UI verification is owned by the independent UI evidence task; this report makes no device-run claim.
