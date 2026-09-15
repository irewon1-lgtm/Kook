# KOSDAQ registration + combined extreme clean-pass

- Snapshot date: 2026-09-15 (KST)
- Source: KRX KIND listed-company master
- KOSPI registered: 832
- KOSDAQ registered: 1,817
- Combined registered universe: 2,649
- KOSDAQ source rows: 1,839
- KOSDAQ exact duplicate issue codes normalized: 22
- KOSDAQ alphanumeric KRX issue codes preserved: 53
- KOSPI normalized master SHA-256: 1f53f7973a05fc559111b23b961033c472cb0168d18732da235805f593a2882b
- KOSDAQ normalized master SHA-256: 9d3bd09cf887c80492d9c7fa2d50d438de2d89f18ee375c5f2502f14cea2d3cb

## Executed validation gates

1. Official KOSPI + KOSDAQ master snapshots validated.
2. Python reference-engine CLEAN tests passed.
3. Python stress scenarios passed.
4. Android repository extreme regression tests passed across the combined 2,649-stock universe.
5. Debug APK assembled successfully.
6. APK installed on a real Android emulator and end-to-end UI smoke completed: home -> all stocks -> KOSDAQ filter -> Samchundang Pharm (000250) detail.
7. APK existence/non-zero check passed.
8. Fake-success/fabricated-investment-data guards passed.
9. Combined machine-readable proof manifest and evidence artifact were produced.

## Deliberate non-features at this stage

The four quantitative metrics, composite score, ranking, current news and filings are not populated yet. Registration status only means the listed-company identity is verified from KRX KIND; it is not a claim that investment data is complete.
