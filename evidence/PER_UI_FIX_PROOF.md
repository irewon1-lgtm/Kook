# KR4 PER + UI fix proof

Snapshot date: 2026-09-15
Price cutoff: 2026-09-14
Universe: 2,649 KOSPI/KOSDAQ issue codes

## PER diagnosis

- Numeric trailing PER available: 1,677
- Non-positive EPS / loss-making, so positive PER is not applicable: 959
- True unresolved Naver EPS/provider gap: 13
- Six-month price available: 2,611

The collector now prefers Naver's reported positive trailing PER and falls back to last completed-session close divided by Naver actual/non-consensus EPS. Consensus PER/EPS is not used. A negative or zero EPS is not converted into a fake PER.

## UI changes

- Coverage counts are computed from the current snapshot instead of hard-coded UI numbers.
- PER is explicitly separated into numeric, loss-making/not-applicable, and unresolved states.
- Stock cards use a fixed 2x2 metric grid and a separate score/rank area.
- Home/list/detail screens use a compact professional hierarchy, consistent spacing, borders, typography, and status badges.
- Metric detail shows value, relative score, interpretation, calculation basis, source, date, and caution in a fixed reading order.

## Validation policy

CI uses regression floors rather than frozen exact coverage counts, so improved source coverage is accepted while regressions are rejected. Android validation includes Python stress tests, JVM unit tests, Debug APK build, and Android 14 emulator end-to-end navigation/search/filter/detail checks.
