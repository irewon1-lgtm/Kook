# Quarterly history collection window

An OpenDART PL bulk link is not proof that a reporting cycle is complete.
At the beginning of November 2026, the collector advanced its eight-quarter
window as soon as the new Q3 link appeared. Issuers without an early Q3 filing
then lost complete-eight-quarter coverage. The six runs 41901867493,
41868044698, 41705580701, 41674531363, 41532076530 and 41503690022 all failed
at the eight-quarter history gate. Their public annotations confirm the failed
step; authenticated logs are needed to independently inspect their counts.

Both collectors now use the same eligibility filter in `list_pl_entries()`:

- Count 45 days from the quarter end, or 90 days from the annual year end.
- Extend a Saturday/Sunday boundary to Monday and wait through that day.
- Select the latest contiguous 12 eligible support quarters and derive the
  visible eight quarters exactly as before.

For a 2026-11-05 collection this keeps the visible window at 2024Q3–2026Q2.
The nominal Q3 boundary is Saturday, November 14; Q3 becomes eligible on
November 17 after the Monday boundary. Logs identify deferred periods and the
JSON policy describes selection. These dates are a conservative collection
policy, not a guarantee of complete filings: public holidays, filing extensions
or source delays can still leave insufficient data. The existing quality gate
must then fail. Do not fall back to older periods because a mature period has
bad coverage.

No quality thresholds are lowered. In particular, `int(2649 * 0.45) == 1192`
still applies to stocks with eight revenue quarters. No missing values are
invented or copied from the previous snapshot. Revenue and operating-income
parsing, same-scope cumulative subtraction, all four investment metrics and APK
signing remain unchanged.

`python tests/test_quarterly_history.py` includes a synthetic 2,649-issuer,
eight-early-filer reproduction, future-period filtering, quarterly/annual
boundaries, leap years and weekend extensions. It also proves that sparse Q3
data remains sparse once Q3 is eligible, rather than silently rolling back.
