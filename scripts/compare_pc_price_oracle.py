#!/usr/bin/env python3
"""Run unmodified Android Kotlin price methods and exact TypeScript projection parity."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/krstock/v3/data/price'
CODES = ['005930', '000660', '000020', '0001A0', '000300', '005030']


def digest(data):
    return hashlib.sha256(data).hexdigest()


def controlled(case_id, dates, volumes):
    # Explicitly synthetic unit inputs. Never mixed into actual collector records.
    bars = [dict(date=date, open=100.125 + i, high=103.625 + i,
                 low=99.0625 + i, close=102.375 + i, volume=volume)
            for i, (date, volume) in enumerate(zip(dates, volumes))]
    raw = [dict(localTradedAt=b['date'], openPrice=b['open'], highPrice=b['high'],
                lowPrice=b['low'], closePrice=b['close'], accumulatedTradingVolume=b['volume']) for b in bars]
    return dict(id=case_id, kind='controlled', source='CONTROLLED_TEST_INPUT',
                cutoff=max(dates), inputBars=bars, rawRows=raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=Path('/workspace/kr4-pc-work/price-cache'))
    parser.add_argument('--output', type=Path, default=Path('/workspace/kr4-pc-work/price-oracle'))
    parser.add_argument('--skip-build', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    cases = []
    for code in CODES:
        source_path = args.cache / 'prices' / f'{code}.json'
        source_bytes = source_path.read_bytes()
        record = json.loads(source_bytes)
        rows = []
        for page in record['pages']:
            raw_path = args.cache / 'raw' / code / f"{page['sha256']}.json"
            raw_bytes = raw_path.read_bytes()
            if digest(raw_bytes) != page['sha256']:
                raise ValueError(f'Raw source digest mismatch: {raw_path}')
            rows.extend(json.loads(raw_bytes))
        cases.append(dict(id=code, kind='actual', source=record['source'],
                          cutoff=record['requestedCutoffDate'], inputBars=record['bars'], rawRows=rows,
                          collectorRecordPath=str(source_path), collectorRecordSha256=digest(source_bytes),
                          collectorMetadata={k: v for k, v in record.items() if k != 'bars'}))
    cases.extend([
        controlled('missing-volume', ['2025-12-30', '2025-12-31', '2026-01-02'], [10, None, 10]),
        controlled('year-boundary', ['2025-12-01', '2025-12-31', '2026-01-02', '2026-01-30'], [11, 12, 13, 14]),
        controlled('calendar-gaps', ['2025-12-24', '2025-12-29', '2026-01-02'], [None, None, None]),
    ])
    manifest = dict(schemaVersion=1, generatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    originalSourceSha256={name: digest((SOURCE / name).read_bytes()) for name in
                                          ['PriceHistoryRepository.kt', 'PriceHistoryAggregation.kt', 'PriceAdjustmentProvenance.kt']},
                    oracleMethods=['PriceHistoryRepository.parseBars', 'PriceHistoryAggregation.visible'],
                    modeMeaning='24 rendering selections; 12 distinct interval/window OHLCV projections per case',
                    cases=cases)
    request = args.output / 'price-oracle-input.json'
    request.write_text(json.dumps(manifest, ensure_ascii=False, separators=(',', ':')) + '\n')
    env = os.environ.copy()
    local = Path('/workspace/toolchains')
    if (local / 'jdk-17.0.17+10').is_dir():
        env['JAVA_HOME'] = str(local / 'jdk-17.0.17+10')
    if (local / 'gradle-home').is_dir():
        env['GRADLE_USER_HOME'] = str(local / 'gradle-home')
    gradle = env.get('GRADLE') or shutil.which('gradle') or str(local / 'gradle-8.7/bin/gradle')
    java_opts = ['-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
    proxy = urlparse(env.get('HTTPS_PROXY') or env.get('HTTP_PROXY', ''))
    if proxy.hostname:
        for scheme in ['http', 'https']:
            java_opts.extend([f'-D{scheme}.proxyHost={proxy.hostname}', f'-D{scheme}.proxyPort={proxy.port or 80}'])
    if not args.skip_build:
        with (ROOT / 'web/oracle/.gradle-build.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            subprocess.run([gradle, *java_opts, '--no-daemon', '--max-workers=2', 'installDist'],
                           cwd=ROOT / 'web/oracle', env=env, check=True)
    projection = args.output / 'original-kotlin-price-projections.json'
    java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
    subprocess.run([java, '-cp', str(ROOT / 'web/oracle/build/install/kr4-original-source-oracle/lib/*'),
                    'oracle.price.PriceOracleKt', str(request), str(projection)], env=env, check=True)
    env['KR4_PRICE_ORACLE_DIR'] = str(args.output)
    env['KR4_PRICE_PARITY_REPORT'] = str(args.output / 'price-parity-comparisons.json')
    result = subprocess.run(['npm', 'test', '--', '--run', 'tests/price-oracle-parity.test.ts',
                             '--reporter=default', '--reporter=json',
                             f'--outputFile={args.output / "vitest-results.json"}'], cwd=ROOT / 'web', env=env)
    tests = json.loads((args.output / 'vitest-results.json').read_text())
    report = json.loads((args.output / 'price-parity-comparisons.json').read_text())
    report.update(status='PASS' if result.returncode == 0 and tests['success'] else 'FAIL',
                  testsPassed=tests['numPassedTests'], testsFailed=tests['numFailedTests'],
                  originalSourceSha256=manifest['originalSourceSha256'], projectionArtifact=str(projection),
                  actualCasesSummary=[dict(id=c['id'], source=c['source'], bars=len(c['inputBars']),
                                           lastTradingDate=c['collectorMetadata']['lastTradingDate'])
                                      for c in cases if c['kind'] == 'actual'])
    (args.output / 'price-oracle-parity-report.json').write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
