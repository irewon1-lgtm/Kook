# Original Stage5 and candidate list parity restoration

Scope: wrapper Export.kt, oracle tests/test-only original-classifier harness, README. Original Android/model/classifier/formula/financial thresholds and original source inputs were not edited. No commits/push.

## Contracts

`valuations.json`: `{snapshotDate,records:Map<issuerId,ValuationBand|null>}` for all2649 issuer codes. Values are direct original `ValuationBandPolicy.classify(stock.m03Per)` results. Original fields are exactly `code`, `labelKo`, `shortLabelKo`, `percentile`, `changesRank`; every nonnull record preserves `changesRank:false`. Each `details/{code}.json.valuation` is the same original object or explicit null. Null means original classification unavailable, independent of Stage4 HOLD; there is no new web classifier or numeric threshold port.

`candidates.json`: `{snapshotDate,priceCutoffDate,status:'VALID'|'HOLD',error:string|null,records:Map<issuerId,FinalCandidateRecord>}`. The map is read from original `FinalCandidateRepository.getFinalCandidateMap()` after unchanged original candidate parsing/runtime validation. VALID keeps original records and null error; source/date/safety failure produces HOLD, empty records and the original rejection explanation. Existing detail.candidate is preserved and matches the list map. Candidate failure never blocks valid quant or Stage5.

Both new files are included in manifest path→SHA256/byte entries automatically. Existing source-input hashes remain unchanged.

## Executed verification

- Expected RED missing valuation publication: `oracle-stage5-red.log`,1FAIL17.789s.
- Expected RED missing candidate publication: `oracle-candidates-red.log`,1FAIL15.730s.
- Final `python3 -m unittest discover -s web/oracle/tests -v`: **16 tests PASS in75.273s**, `oracle-stage5-candidates-green.log`. Existing14 regressions remain, including context retention, financial invalid/missing/date mismatch, exact nullable Long strings, full original constructor fields, hashes and15masks.
- A test-only JVM harness independently invokes original `ValuationBandPolicy` for all2649 published summaries and checks exact equality with the exported map. It computes original DynamicRankingEngine maps for all15masks before and after classification and proves equality.
- Recorded actual005930 positive PER9.08 yields `{code:'MID_PER',labelKo:'시장 중간 PER 구간',shortLabelKo:'중간 PER',percentile:52.219162,changesRank:false}`. Recorded actual003060 negative PER-0.227865 yields null. Recorded fixture is explicitly REAL_MARKET_RECORDED, not LIVE.
- The original constructor-field coverage now checks all five `ValuationBand` fields. Every nonnull detail value equals its corresponding list record.
- Missing and date-mismatched Stage4 paths retain byte-identical valuations/stocks/rankings while emitting candidate HOLD/empty records with nonempty original error. This executes rather than merely relying on the object's `changesRank:false` flag.

## Reviewable export

`/workspace/kr4-pc-work/oracle-stage5-candidates`: all2649 stocks/details,15rankmaps, all2649 valuation keys,10 valid original candidate records and all2649 actual cached context observations with explicit category metadata. Source snapshot and completed price cutoff2026-11-05. Context is CACHE, not a fresh fetch. Available valuation distribution:

```json
{
  "BELOW_MEDIAN_PER": 336,
  "LOW_RELATIVE_PER": 338,
  "MID_PER": 339,
  "ABOVE_MEDIAN_PER": 337,
  "HIGH_RELATIVE_PER": 321,
  "UNAVAILABLE": 978
}
```

Actual manifest entries:

```json
{
  "valuations.json": {
    "sha256": "695a735fc61164c689018f1ba065e0fd1f154650583b2394dbb09e55d9f8eb8a",
    "bytes": 270413
  },
  "candidates.json": {
    "sha256": "01fd87ab2dfc4214e898e98933d34d8a2614417443bd4d7ee595b890544e7c9c",
    "bytes": 18017
  }
}
```

Receiver/home/detail integration and public browser validation remain parent/UI responsibilities; no unexecuted UI PASS is claimed here.
