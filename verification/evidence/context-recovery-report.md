# PC context source recovery diagnosis

Observed 2026-11-06 00:01–00:02 UTC. Scope: read-only Android source inspection and bounded public source probes. No Android file, oracle file, existing cache entry, collector process, authentication configuration, or Git state was changed. Raw responses remain outside the repository in `context-recovery-raw/`.

## Finding

**Fresh DART access is currently BLOCKED at the HTTP upstream connection boundary. No Python transport recovery was demonstrated.** The same anonymous DART POST, exact form values, original user agent, Accept, Content-Type and Referer returned HTTP **503**, from **envoy**, with the 95-byte body:

```
upstream connect error or disconnect/reset before headers. reset reason: connection termination
```

Python urllib returned this for 005930, 000660 and 000020. An independent Java 17 `HttpURLConnection` probe using the original method/body/headers and the installed oracle's session proxy and CA trust returned the identical response for 005930. There is no evidence that JVM request semantics are the cause. Both transports used the inherited proxy route; TLS validation remained enabled.

The server reports an upstream connection termination. The evidence does not distinguish destination outage, intermediary failure, throttling or another upstream cause. It would be unsupported to label this a proven DART rate limit or a fixable JVM header error.

Bounded alternate requests to the original DART GET search endpoint with equivalent query parameters, DART public company profiles, and each issuer's exact canonical filing URL from the already verified financial snapshot returned the same 503/95-byte response for all three issuers. No retry loop was run, no destination/proxy bypass was attempted, and no alternate hostname was invented.

## Why the original cache hides the HTTP detail

`ContextEvidenceRepository.fetch` catches Throwable from `fetchDartDisclosureIndex` and records only its class simple name. `postForm` raises `IllegalStateException("HTTP_503")`, but the message is discarded in `dart=IllegalStateException`. `OfficialSourceCollector.collect` repeats the search and similarly returns `DART_SEARCH_IllegalStateException`. The two labels therefore share one failed source; they do not independently establish failures in the DART index parser and company root parser.

The source continues collecting Naver news and Naver disclosures and returns loaded=true. These partial results are valuable and must be retained. The running all-market collection was left untouched as instructed.

## Evidence and provenance

- `context-recovery-raw/005930-jvm.html`: exact 95-byte 503 response from original-equivalent HttpURLConnection POST.
- `context-recovery-raw/{005930,000660,000020}-python.html`: exact 95-byte 503 responses from Python original-equivalent POST.
- `context-recovery-raw/{code}-{search-get,profile,filing}.html`: exact 503 bodies for bounded alternate original endpoints.
- `context-recovery-raw/fallback-probes.json`: complete requested fallback URLs, HTTP status, response header observations, byte counts and SHA256.
- `context-recovery-raw/response-file-hashes.json`: hashes and exact short body for all response files.
- `context-recovery-raw/cache-observation.json`: live read-only cache status at 00:02:09 UTC; 2,074 entries then, 2,013 with shared DART search failures. Collection continued after that observation.

The first cache record showing this shared search failure was issuer 265740 at 2026-11-05T23:49:50.746988927Z. This is a record's collection-start timestamp, not proof of the exact time the destination became unavailable.

## Preserve actual successful earlier evidence

The three requested representative cache files predate the widespread DART transport failure:

| Issuer | Original collectedAt | DART primary items | Actual hydrated body items | IR / official | Original error |
|---|---|---:|---:|---|---|
| 005930 | 2026-11-05T23:47:29.266504602Z | 24 | 0 | 0 / 0 | official=official=IllegalStateException |
| 000660 | 2026-11-05T23:47:29.265549179Z | 24 | 0 | 0 / 0 | official=official=IllegalStateException |
| 000020 | 2026-11-05T23:49:15.152482134Z | 23 | 2 | 0 / 0 | null |

Keep their receipts, full canonical filing URLs, observed filing dates and original body text. The 000020 cache includes two genuine periodic-report bodies and an original full-text comparison. Samsung/SK's recent 24-item lists contain no periodic bodies; do not claim a full-text comparison for them. The original collector can return null error when IR and official arrays are empty: a web availability label must still show those categories MISSING, not infer category success from bundle error==null.

## Dated financial evidence remains usable, but is not a fresh source fetch

Already verified `financial_safety_snapshot.json` associates these issuer identities with DART corp codes and canonical receipts:

| Code | Issuer | Corp code | Receipt | Observed report period | Filing date |
|---|---|---|---|---|---|
| 005930 | 삼성전자 | 00126380 | 20260814003699 | 2026-06-30 | 2026-08-14 |
| 000660 | SK하이닉스 | 00164779 | 20260814003509 | 2026-06-30 | 2026-08-14 |
| 000020 | 동화약품 | 00119195 | 20261002000407 | 2026-06-30 | 2026-10-02 |

The exact requested URLs appear in fallback-probes.json. They can be presented as already verified, dated financial filing references with actual report period and snapshot provenance. A fresh request failed, so do not label them newly collected, latest news, or recovered current DART discovery. A receipt alone cannot establish a company IR root. Do not derive a primary receipt from the Naver disclosureId field.

## Implementation decision

No `collect_pc_context.py` bridge or tests were added because the authorized conditional recovery prerequisite (a verified working Python/server-side route to the same official source) did not hold. Duplicating the failing request in Python would add a second collector without repairing official access. A future bridge should reuse the original Kotlin parsers through an explicit oracle helper, preserve the existing successful cache on refresh failures, write the exact version-1 EvidenceBundle envelope, and retain per-source HTTP detail outside engine inputs.

Existing original generic Naver parser ignores actual mobileNewsUrl/body fields, yielding empty URLs/body even when the source supplies them. A separate web collector enhancement can explicitly retain those exact source values, but that is not a DART recovery and should be validated against the original parser contracts. Public site category availability should remain factual and independent: original Naver news/disclosure cache may be available while DART refresh, IR and official are MISSING/PARTIAL.
