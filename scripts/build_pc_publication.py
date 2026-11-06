#!/usr/bin/env python3
"""Verify original Kotlin output and actual collector prices; publish immutable static data.

Only allowlisted authenticated public payloads are copied. Private response/checkpoint
folders and undeclared files never enter Pages. This builder does not collect or invent data.
"""
from __future__ import annotations

import argparse
import base64
from datetime import date, datetime, timedelta, timezone
import gzip
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CODE = re.compile(r'[0-9A-Z]{6}\Z')
SHA = re.compile(r'[0-9a-f]{64}\Z')
ORACLE_PATH = re.compile(r'(stocks|rankings|financial|valuations|candidates)\.json\Z|details/[0-9A-Z]{6}\.json\Z|sources/(quant|financial|candidates|history)\.json\Z|sources/context/[0-9A-Z]{6}\.json\Z')
MAX_BYTES = 950 * 1024 * 1024  # Below Pages' 1 GB published-site ceiling.


def require(condition, message):
    if not condition:
        raise ValueError(message)


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def metadata(content):
    return dict(sha256=hashlib.sha256(content).hexdigest(), bytes=len(content))


def load(content):
    def invalid(value):
        raise ValueError('Nonfinite JSON value: ' + value)
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'Duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(content, parse_constant=invalid, object_pairs_hook=pairs)


def day(value):
    require(isinstance(value, str), 'Invalid date')
    parsed = date.fromisoformat(value)
    require(parsed.isoformat() == value, 'Invalid date format')
    return parsed


def safe_path(root, name):
    require(isinstance(name, str) and name and '\\' not in name and ':' not in name and
            not name.startswith('/') and all(p not in ('', '.', '..') for p in name.split('/')),
            'Unsafe payload path: ' + str(name))
    path = root.joinpath(*PurePosixPath(name).parts)
    for ancestor in (path, *path.parents):
        require(not ancestor.is_symlink(), 'Symlink payload path: ' + name)
        if ancestor == root:
            break
    require(path.is_relative_to(root), 'Unsafe payload path')
    return path


def authenticated(root, name, expected):
    path = safe_path(root, name)
    require(isinstance(expected, dict) and SHA.fullmatch(str(expected.get('sha256', ''))) and
            type(expected.get('bytes')) is int and expected['bytes'] > 0, 'Invalid file hash: ' + name)
    content = path.read_bytes()
    require(metadata(content) == expected, 'File hash/size mismatch: ' + name)
    return content


def validate_price(value, code, cutoff):
    require(value.get('schemaVersion') == 1 and value.get('issuerId') == code, 'Price identity mismatch: ' + code)
    require(value.get('source') in ('CACHE', 'MISSING') and value.get('adjustment') == 'UNVERIFIED', 'False price source: ' + code)
    base = f'https://m.stock.naver.com/api/stock/{code}/price'
    require(value.get('sourceUrl') == base, 'Price provider mismatch: ' + code)
    requested = day(value['requestedCutoffDate'])
    require(requested <= cutoff, 'Future price cutoff: ' + code)
    require(value.get('lastAttemptAt'), 'Missing actual price attempt: ' + code)
    bars = value.get('bars')
    require(isinstance(bars, list), 'Invalid price bars: ' + code)
    previous = ''
    for bar in bars:
        parsed = day(bar['date'])
        require(previous < bar['date'] and parsed <= requested, 'Price duplicate/future date: ' + code)
        previous = bar['date']
        values = [bar[k] for k in ('open', 'high', 'low', 'close')]
        require(all(type(v) in (int, float) and math.isfinite(v) and v > 0 for v in values), 'Invalid OHLC: ' + code)
        opened, high, low, close = values
        require(low <= min(opened, close) <= max(opened, close) <= high, 'Inconsistent OHLC: ' + code)
        volume = bar.get('volume')
        require(volume is None or type(volume) is int and 0 <= volume <= 9223372036854775807, 'Invalid volume: ' + code)
    pages = value.get('pages')
    require(isinstance(pages, list), 'Missing price provenance: ' + code)
    success = False
    for page in pages:
        require(re.fullmatch(re.escape(base) + r'\?pageSize=60&page=[1-8]', str(page.get('url', ''))), 'Invalid observed provider URL: ' + code)
        require(SHA.fullmatch(str(page.get('sha256', ''))) and type(page.get('bytes')) is int and 0 <= page['bytes'] <= 1024 * 1024 + 1, 'Invalid observed price hash: ' + code)
        require(type(page.get('status')) is int and 100 <= page['status'] <= 599, 'Invalid provider response status')
        success |= page['status'] == 200 and page['bytes'] > 0
    if bars:
        require(value['source'] == 'CACHE' and success and value.get('collectedAt') and value.get('lastTradingDate') == bars[-1]['date'], 'Unverified price bars: ' + code)
    else:
        require(value['source'] == 'MISSING' and value.get('error') and value.get('lastTradingDate') is None and value.get('collectedAt') is None, 'False price missing state: ' + code)


def constructor_fields(name, filename):
    """Require every original constructor field, including nullable/default fields."""
    source=(ROOT/'app/src/main/java/com/krstock/v3/data/model'/filename).read_text()
    match=re.search(r'data class ' + re.escape(name) + r'\(([\s\S]*?)\n\)', source)
    require(match is not None, 'Original constructor definition missing: ' + name)
    return set(re.findall(r'val (\w+)\s*:', match.group(1)))


def finite_number(value):
    return type(value) in (int, float) and math.isfinite(value)


def same_original_number(received, active):
    # FinalCandidateSnapshotUpdater.sameNumber uses this exact tolerance.
    return finite_number(received) and finite_number(active) and abs(received-active) <= 1e-6


def validate_valuations_and_candidates(payloads, manifest, stocks, details, financial):
    valuations=load(payloads['valuations.json'])
    codes=set(stocks)
    require(isinstance(valuations, dict) and valuations.get('snapshotDate') == manifest['snapshotDate'], 'Valuation snapshot date mismatch')
    bands=valuations.get('records')
    require(isinstance(bands, dict) and set(bands) == codes, 'Valuation universe mismatch')
    valuation_fields=constructor_fields('ValuationBand', 'Stage45Models.kt')
    # These are the original Stage5 display bands; no ranking is recomputed here.
    band_labels={
        'LOW_RELATIVE_PER': ('시장 내 낮은 PER 구간', '낮은 PER'),
        'BELOW_MEDIAN_PER': ('시장 중앙값보다 낮은 PER 구간', '중앙값↓ PER'),
        'MID_PER': ('시장 중간 PER 구간', '중간 PER'),
        'ABOVE_MEDIAN_PER': ('시장 중앙값보다 높은 PER 구간', '중앙값↑ PER'),
        'HIGH_RELATIVE_PER': ('시장 내 높은 PER 구간', '높은 PER'),
    }
    for code, band in bands.items():
        metric=stocks[code].get('m03Per')
        require(isinstance(metric, dict), 'Valuation stock metric missing: ' + code)
        raw, percentile=metric.get('rawValue'), metric.get('percentileScore')
        available=finite_number(raw) and raw > 0 and finite_number(percentile) and 0 <= percentile <= 100
        if not available:
            require(band is None, 'Valuation must remain missing for unavailable/nonpositive PER: ' + code)
        else:
            require(isinstance(band, dict) and valuation_fields <= band.keys(), 'Valuation constructor fields missing: ' + code)
            require(band.get('changesRank') is False and finite_number(band.get('percentile')) and band['percentile'] == percentile, 'Valuation percentile/ranking mutation: ' + code)
            expected=('LOW_RELATIVE_PER' if percentile >= 80 else 'BELOW_MEDIAN_PER' if percentile >= 60 else
                      'MID_PER' if percentile >= 40 else 'ABOVE_MEDIAN_PER' if percentile >= 20 else 'HIGH_RELATIVE_PER')
            require(band.get('code') == expected and (band.get('labelKo'), band.get('shortLabelKo')) == band_labels[expected], 'Valuation original band/labels mismatch: ' + code)
        require('valuation' in details[code] and details[code]['valuation'] == band, 'Original detail/valuation mismatch: ' + code)

    publication=load(payloads['candidates.json'])
    require(isinstance(publication, dict) and publication.get('snapshotDate') == manifest['snapshotDate'] and
            publication.get('priceCutoffDate') == manifest['priceCutoffDate'], 'Candidate publication date mismatch')
    records=publication.get('records');status=publication.get('status')
    require(status in ('VALID', 'HOLD') and isinstance(records, dict), 'Candidate publication status/records mismatch')
    require(set(records) <= codes, 'Candidate universe mismatch')
    if status == 'HOLD':
        require(not records and isinstance(publication.get('error'), str) and publication['error'].strip(), 'Candidate HOLD requires empty records and an explicit reason')
    else:
        require(publication.get('error') is None and 1 <= len(records) <= 100, 'Candidate VALID count/error mismatch')
        require(financial.get('status') == 'VALID' and financial.get('snapshotDate') == manifest['snapshotDate'] and
                manifest['coverage'].get('financialValid') is True, 'Candidate requires valid current financial publication')
    require(manifest['coverage'].get('candidateValid') is (status == 'VALID') and
            manifest['coverage'].get('candidates') == len(records), 'Candidate coverage/status mismatch')
    candidate_fields=constructor_fields('FinalCandidateRecord', 'FinalCandidateModels.kt')
    metric_fields=constructor_fields('CandidateMetricSnapshot', 'FinalCandidateModels.kt')
    for code, record in records.items():
        require(isinstance(record, dict) and candidate_fields <= record.keys(), 'Candidate constructor fields missing: ' + code)
        stock=stocks[code]
        require(record['issuerId'] == code and record['schemaVersion'] == 'KR4_FINAL_CANDIDATE_V2' and
                record['policyVersion'] == 'STAGE4567_SAFETY_AND_RELATIVE_PER_BAND_V1', 'Candidate identity/schema mismatch: ' + code)
        require(record['financialSnapshotDate'] == manifest['snapshotDate'] and record['priceCutoffDate'] == manifest['priceCutoffDate'], 'Candidate record date mismatch: ' + code)
        require(type(record['candidateRank']) is int and record['candidateRank'] > 0 and type(record['sourceRank']) is int and
                record['sourceRank'] == stock.get('rankOrder') and stock.get('isCompositeComplete') is True and
                same_original_number(record['compositeScore'], stock.get('compositeScore')), 'Candidate original rank/score mismatch: ' + code)
        require(all(record[key] == stock.get(key) for key in ('name', 'market', 'sector')), 'Candidate stock identity mismatch: ' + code)
        flags=record['selectionFlags']
        required_flags={'FOUR_METRICS_COMPLETE','POSITIVE_TRAILING_PER','FINANCIAL_SAFETY_PASS','VALUATION_BAND_CLASSIFIED','EXISTING_COMPOSITE_RANK_REUSED'}
        require(isinstance(flags, list) and all(isinstance(flag, str) and flag for flag in flags) and required_flags <= set(flags) and
                record['researchStatus'] == 'FINAL_RESEARCH_CANDIDATE' and isinstance(record['selectionReasonKo'], str) and
                record['selectionReasonKo'].strip(), 'Candidate original selection evidence missing: ' + code)
        safety=financial['records'].get(code)
        require(isinstance(safety, dict) and safety.get('issuerId') == code and safety.get('status') == 'PASS' and
                record['financialSafetyStatus'] == 'PASS' and details[code].get('financial') == safety, 'Candidate requires exact current financial PASS: ' + code)
        require(all(isinstance(record[key], str) for key in ('financialSafetyReason','financialSafetyBasis')) and
                all(record[key] is None or finite_number(record[key]) for key in
                    ('debtToEquityPct','currentRatioPct','accountingIdentityGapPct')), 'Candidate financial constructor values invalid: ' + code)
        band=bands[code]
        require(band is not None and record['valuationBand'] == band['code'] and record['valuationBandKo'] == band['labelKo'] and
                same_original_number(record['valuationPercentile'], band['percentile']), 'Candidate valuation evidence mismatch: ' + code)
        for index, key in enumerate(('m01RevGrowth','m02OpMargin','m03Per','m04Price6m'), 1):
            metric=record[f'm0{index}']; original=stock.get(key)
            require(isinstance(metric, dict) and metric_fields <= metric.keys() and isinstance(original, dict), 'Candidate metric constructor fields missing: ' + code)
            require(metric['id'] == f'M0{index}' and finite_number(metric['rawValue']) and finite_number(metric['percentileScore']) and
                    0 <= metric['percentileScore'] <= 100 and original.get('isAvailable') is True and
                    metric['id'] == original.get('id') and metric['asOfDate'] == original.get('asOfDate') and
                    same_original_number(metric['rawValue'], original.get('rawValue')) and
                    same_original_number(metric['percentileScore'], original.get('percentileScore')) and
                    isinstance(metric['basis'], str) and metric['basis'].strip() and
                    metric['source'] == 'KR4 validated remote snapshot', 'Candidate original metric mismatch: ' + code)
    ranked=sorted(records.values(), key=lambda row:row['candidateRank'])
    require([row['candidateRank'] for row in ranked] == list(range(1,len(records)+1)) and
            all(a['sourceRank'] < b['sourceRank'] for a,b in zip(ranked,ranked[1:])), 'Candidate rank sequence mismatch')
    for code, detail in details.items():
        require('candidate' in detail and detail['candidate'] == records.get(code), 'Original detail/candidate mismatch: ' + code)


def validate_oracle(oracle):
    original = safe_path(oracle, 'manifest.json').read_bytes()
    manifest = load(original)
    require(manifest.get('schemaVersion') == 1 and re.fullmatch(r'[0-9a-f]{40}', manifest.get('sourceCommit', '')), 'Invalid original manifest')
    cutoff = day(manifest['priceCutoffDate']); snapshot = day(manifest['snapshotDate'])
    require(cutoff <= snapshot and (snapshot-cutoff).days <= 10, 'Invalid snapshot/cutoff relationship')
    require(snapshot <= datetime.now(timezone(timedelta(hours=9))).date(), 'Future publication date')
    files = manifest.get('files')
    require(isinstance(files, dict), 'Missing original files')
    payloads = {}
    for name, expected in files.items():
        require(ORACLE_PATH.fullmatch(name), 'Path outside public payload allowlist: ' + name)
        payloads[name] = authenticated(oracle, name, expected)
    require({'stocks.json', 'rankings.json', 'financial.json', 'valuations.json', 'candidates.json', 'sources/quant.json'} <= payloads.keys(), 'Missing original source payloads')
    hashes = manifest.get('inputHashes')
    require(isinstance(hashes, dict) and 'quant' in hashes, 'Missing original input hashes')
    for key, checksum in hashes.items():
        require(SHA.fullmatch(str(checksum)), 'Invalid input hash: ' + key)
        if key.startswith('app/'):
            require(key.startswith('app/src/main/java/com/krstock/v3/data/') and key.endswith('.kt'), 'Invalid original source path')
            source = safe_path(ROOT, key)
            require(source.is_file() and metadata(source.read_bytes())['sha256'] == checksum, 'Original compiled source hash mismatch: ' + key)
        else:
            source_name = 'sources/' + (key if key.startswith('context/') else key + '.json')
            require(source_name in payloads and metadata(payloads[source_name])['sha256'] == checksum, 'Original input source hash mismatch: ' + key)
    for name in payloads:
        if name.startswith('sources/'):
            key=name.removeprefix('sources/').removesuffix('.json')
            if key.startswith('context/'):
                key += '.json'
            require(key in hashes, 'Unbound original source input: ' + name)
    stocks = load(payloads['stocks.json'])
    require(isinstance(stocks, list) and len(stocks) == 2649, 'Expected 2649-stock universe')
    codes = [s.get('issuerId') for s in stocks]
    require(all(isinstance(c, str) and CODE.fullmatch(c) for c in codes) and len(set(codes)) == 2649, 'Invalid stock universe')
    codes = set(codes)
    require(all(s.get('asOfDate') == manifest['snapshotDate'] for s in stocks), 'Stock snapshot mismatch')
    expected_details = {f'details/{c}.json' for c in codes}
    require({p for p in payloads if p.startswith('details/')} == expected_details, 'Detail universe mismatch')
    details={}
    for stock in stocks:
        detail=load(payloads[f'details/{stock["issuerId"]}.json'])
        require(detail.get('summary') == stock, 'Original detail/stock mismatch: ' + stock['issuerId'])
        details[stock['issuerId']]=detail
    rankings=load(payloads['rankings.json'])
    require(set(rankings) == {str(n) for n in range(1, 16)}, 'Expected all 15 ranking masks')
    for mask, ranks in rankings.items():
        require(set(ranks) == codes, 'Ranking universe mismatch: ' + mask)
        eligible=[]
        for code, rank in ranks.items():
            require(rank.get('issuerId') == code and rank.get('selectedMetricCount') == int(mask).bit_count(), 'Ranking identity/mask mismatch')
            if rank.get('isEligible') is True:
                require(type(rank.get('rank')) is int and type(rank.get('score')) in (int, float) and math.isfinite(rank['score']), 'Invalid original ranking')
                eligible.append(rank['rank'])
            else:
                require(rank.get('isEligible') is False and rank.get('rank') is None and rank.get('score') is None, 'Invalid missing rank')
        require(sorted(eligible) == list(range(1, len(eligible)+1)), 'Original rank sequence mismatch')
    coverage=manifest['coverage']
    require(all(coverage.get(k) == v for k, v in dict(stocks=2649, details=2649, rankingMasks=15).items()), 'Original coverage mismatch')
    # Financial HOLD is a valid independently failed supplement, never a quant blocker.
    financial=load(payloads['financial.json'])
    require(financial.get('status') in ('VALID', 'HOLD') and isinstance(financial.get('records'), dict), 'Invalid financial publication')
    if financial['status'] == 'VALID':
        require(set(financial['records']) == codes, 'Financial universe mismatch')
    else:
        require(not financial['records'], 'HOLD must not publish mismatched financial records')
    validate_valuations_and_candidates(payloads, manifest, {stock['issuerId']:stock for stock in stocks}, details, financial)
    payloads['oracle-manifest.json'] = original
    return manifest, payloads, codes, cutoff


def validate_prices(prices, codes, cutoff):
    original = safe_path(prices, 'index.json').read_bytes()
    index = load(original)
    require(index.get('schemaVersion') == 1 and index.get('source') == 'CACHE' and index.get('provider') == 'Naver Finance' and index.get('adjustment') == 'UNVERIFIED', 'Invalid actual price index')
    require(day(index['requestedCutoffDate']) == cutoff, 'Price index cutoff mismatch')
    require(isinstance(index.get('universe'), list) and len(index['universe']) == 2649 and set(index['universe']) == codes and set(index.get('prices', {})) == codes, 'Price universe mismatch')
    payloads = {'prices/index.json': original}
    counts = dict(universe=2649, withBars=0, missing=0, errors=0, throughCutoff=0, historyBoundaryReached=0)
    boundary=date(cutoff.year-1, cutoff.month, 1)
    boundary -= timedelta(days=boundary.weekday())
    for code, expected in index['prices'].items():
        name = f'prices/{code}.json'
        require(expected.get('path') == name, 'Unsafe price index path: ' + code)
        content = authenticated(prices, name, {k: expected.get(k) for k in ('sha256', 'bytes')})
        value = load(content); validate_price(value, code, cutoff)
        for key in ('source', 'error', 'lastTradingDate', 'collectedAt', 'lastAttemptAt'):
            require(expected.get(key) == value.get(key), 'Price index metadata mismatch: ' + code)
        bars=value['bars']
        require(expected.get('bars') == len(bars), 'Price index bar count mismatch')
        counts['withBars' if bars else 'missing'] += 1
        counts['errors'] += bool(value.get('error'))
        counts['throughCutoff'] += bool(bars and bars[-1]['date'] == cutoff.isoformat())
        counts['historyBoundaryReached'] += bool(bars and day(bars[0]['date']) <= boundary)
        payloads[name] = content
    require(index.get('coverage') == counts, 'Actual price coverage mismatch')
    return payloads, counts


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.'+path.name, delete=False) as handle:
        temporary=Path(handle.name)
        handle.write(content)
    temporary.replace(path)


def validate_retained_generation(root, generation):
    target = safe_path(root, 'generations/' + generation)
    manifest = load(safe_path(target, 'manifest.json').read_bytes())
    files = manifest.get('files')
    require(isinstance(files, dict) and manifest.get('generationId') == generation and
            manifest.get('basePath') == f'generations/{generation}/' and
            hashlib.sha256(encoded(files)).hexdigest() == generation, 'Retained generation identity mismatch')
    require({p.relative_to(target).as_posix() for p in target.rglob('*') if p.is_file()} == set(files) | {'manifest.json'},
            'Retained generation payload paths changed')
    for name, expected in files.items():
        require(ORACLE_PATH.fullmatch(name) or name in ('oracle-manifest.json', 'prices/index.json') or
                re.fullmatch(r'prices/[0-9A-Z]{6}\.json', name), 'Retained payload path outside allowlist')
        authenticated(target, name, expected)
    return sum(p.stat().st_size for p in target.rglob('*') if p.is_file())


def build_publication(oracle, prices, output, bundle, *, max_bytes=MAX_BYTES):
    oracle, prices, output, bundle = map(lambda p: Path(p).absolute(), (oracle, prices, output, bundle))
    require(not output.is_relative_to(oracle) and not oracle.is_relative_to(output) and not output.is_relative_to(prices) and not prices.is_relative_to(output), 'Output must be isolated from source inputs')
    manifest, payloads, codes, cutoff = validate_oracle(oracle)
    price_payloads, price_coverage = validate_prices(prices, codes, cutoff)
    payloads.update(price_payloads)
    files = {name: metadata(content) for name, content in sorted(payloads.items())}
    generation = hashlib.sha256(encoded(files)).hexdigest()
    published = dict(manifest, files=files, generationId=generation, basePath=f'generations/{generation}/', priceCoverage=price_coverage)
    root_manifest = encoded(published)
    bundle_content = encoded(dict(manifest=published, stocksGzip=base64.b64encode(gzip.compress(payloads['stocks.json'], mtime=0)).decode('ascii')))
    history_file=output/'publication-history.json'
    history=load(history_file.read_bytes()) if history_file.exists() else []
    require(isinstance(history, list) and all(isinstance(g, str) and SHA.fullmatch(g) for g in history), 'Invalid publication retention history')
    retained = [generation, *[g for g in history if g != generation]][:3]
    previous_size=sum(validate_retained_generation(output, g) for g in retained if g != generation)
    total = sum(len(p) for p in payloads.values()) + len(root_manifest)*2 + len(encoded(retained)) + previous_size
    require(total <= max_bytes, f'Publication size limit exceeded: {total} > {max_bytes}; retain current + previous two generations')
    target=output/'generations'/generation
    if target.exists():
        require((target/'manifest.json').read_bytes() == root_manifest, 'Immutable generation manifest changed')
        require({p.relative_to(target).as_posix() for p in target.rglob('*') if p.is_file()} == set(payloads) | {'manifest.json'}, 'Immutable generation paths changed')
        for name, expected in files.items():
            authenticated(target, name, expected)
    else:
        output.mkdir(parents=True, exist_ok=True)
        temporary=Path(tempfile.mkdtemp(prefix='.generation-', dir=output))
        try:
            for name, content in payloads.items():
                path=temporary/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
            (temporary/'manifest.json').write_bytes(root_manifest)
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary.rename(target)
        finally:
            if temporary.exists(): shutil.rmtree(temporary)
    atomic_write(bundle, bundle_content)
    atomic_write(output/'manifest.json', root_manifest)
    atomic_write(history_file, encoded(retained))
    for path in target.parent.iterdir():
        if path.name not in retained:
            require(SHA.fullmatch(path.name) and path.is_dir() and not path.is_symlink(), 'Unsafe retained generation path')
            shutil.rmtree(path)
    return published


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--oracle', required=True, type=Path)
    parser.add_argument('--prices', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=ROOT/'web/public/data')
    parser.add_argument('--bundle', type=Path, default=ROOT/'web/src/generated/bundled.json')
    args=parser.parse_args()
    manifest=build_publication(args.oracle, args.prices, args.output, args.bundle)
    print(json.dumps(dict(generationId=manifest['generationId'], files=len(manifest['files']),
        payloadBytes=sum(f['bytes'] for f in manifest['files'].values()), priceCoverage=manifest['priceCoverage'],
        retainedGenerations=load((args.output/'publication-history.json').read_bytes()))))


if __name__ == '__main__':
    main()
