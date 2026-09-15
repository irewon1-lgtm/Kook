# KR4 incomplete 267 classification

- Snapshot: `2026-09-15`
- Universe: **2649**
- Complete: **2382**
- Incomplete: **267**

## Missing by metric

| Metric | Available | Missing |
|---|---:|---:|
| M01 | 2504 | 145 |
| M02 | 2409 | 240 |
| M03 | 2639 | 10 |
| M04 | 2611 | 38 |

## Issuer buckets

- **RECOVERABLE**: 151
- **STRUCTURAL_ONLY**: 116

## Missing combinations

- `M01+M02`: 124
- `M02`: 98
- `M04`: 23
- `M01+M02+M04`: 7
- `M01+M02+M03+M04`: 6
- `M01+M02+M03`: 4
- `M01`: 3
- `M02+M04`: 1
- `M01+M04`: 1

## Metric classes

- `STRUCTURAL_NA_FINANCIAL_M02`: 226
- `RECOVERABLE_ACCOUNTING_SOURCE`: 159
- `STRUCTURAL_NA_NEW_LISTING_M04`: 38
- `RECOVERABLE_EPS_SOURCE`: 9
- `STRUCTURAL_NA_ZERO_EPS`: 1

## Actual missing reasons

### M01
- `DART_NO_COMPARABLE_PRIOR_REVENUE`: 93
- `DART_NO_REVENUE`: 52

### M02
- `FINANCIAL_SECTOR_EXCLUDED`: 226
- `DART_NO_OPERATING_INCOME`: 7
- `DART_NO_COMPARABLE_OPERATING_MARGIN`: 5
- `DART_OPERATING_MARGIN_OUTLIER_GUARD`: 2

### M03
- `NAVER_EPS_MISSING`: 9
- `ZERO_EPS`: 1

### M04
- `PRICE_HISTORY_SHORTER_THAN_6M`: 38

## Policy

- Structural N/A: financial-sector M02, zero-EPS M03, and M04 for issuers listed after the six-calendar-month target date.
- Recoverable: DART accounting-source gaps, Naver EPS missing, and price-history gaps in issuers old enough to have six-month history.
- This diagnostic never changes existing numeric values.
