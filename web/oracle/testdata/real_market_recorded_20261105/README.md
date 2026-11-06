# REAL_MARKET_RECORDED fixture

The two gzip files preserve the exact, unmodified JSON bytes from the source commit and paths in `provenance.json`. These are recorded actual market observations, not LIVE data. No dates, financial values, source URLs, verification flags, thresholds or quant records were changed to create a passing pair. Gzip compression is deterministic (`mtime=0`).

Both source dates are2026-11-05. Their real matching quant-record fingerprint lets the original financial parser exercise its valid path even when the independently refreshed current quant and Stage4 files legitimately differ. Tests verify the source-byte hashes before decompression/export and compare exact nullable Long strings to the recorded raw amounts, including verified facts. Separate tests require missing/current-invalid Stage4 to produce HOLD without stopping valid quant.
