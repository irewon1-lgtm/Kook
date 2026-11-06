#!/usr/bin/env python3
"""Collect actual Naver daily OHLCV for the PC publication, outside the source tree.

Default: the complete quant universe, eight workers, resumable same-cutoff checkpoints.
Raw responses and attempt provenance stay private; only prices/ and index.json publish.
Example: python scripts/collect_pc_prices.py --output /workspace/kr4-pc-work/price-cache
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import re
import tarfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://m.stock.naver.com/api/stock'
CODE = re.compile(r'[0-9A-Z]{6}\Z')
MAX_BYTES = 1024 * 1024


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def required_from(cutoff):
    # minusYears clamps Feb 29 like java.time.LocalDate.
    start = date(cutoff.year - 1, cutoff.month, 1)
    return start - timedelta(days=start.weekday())


def _array(payload):
    rows = json.loads(payload) if isinstance(payload, (str, bytes)) else payload
    if not isinstance(rows, list):
        raise ValueError('Price source must be a JSON array')
    return rows


def _number(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(str(value).replace(',', '').strip())
        return number if math.isfinite(number) and number > 0 else None
    except (ValueError, TypeError):
        return None


def _volume(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        number = Decimal(str(value).replace(',', '').strip())
        if number.is_finite() and 0 <= number <= 9223372036854775807 and number == number.to_integral_value():
            return int(number)
    except (ValueError, InvalidOperation):
        pass
    return None


def _add_unique(target, bars):
    for bar in bars:
        previous = target.get(bar['date'])
        if previous is not None and previous != bar:
            raise ValueError('Conflicting daily price at ' + bar['date'])
        target[bar['date']] = bar


def parse_bars(payload, cutoff):
    """Match original Android parseBars: invalid rows skipped, conflicts rejected."""
    parsed = {}
    for row in _array(payload):
        if not isinstance(row, dict):
            continue
        try:
            raw_date = str(row.get('localTradedAt', ''))[:10]
            day = date.fromisoformat(raw_date)
            if day.isoformat() != raw_date or day > cutoff:
                continue
        except ValueError:
            continue
        values = [_number(row.get(key)) for key in ('openPrice','highPrice','lowPrice','closePrice')]
        if any(value is None for value in values):
            continue
        opened, high, low, close = values
        if low > min(opened, close) or high < max(opened, close) or low > high:
            continue
        bar = dict(date=day.isoformat(), open=opened, high=high, low=low, close=close,
                   volume=_volume(row.get('accumulatedTradingVolume')))
        _add_unique(parsed, [bar])
    return sorted(parsed.values(), key=lambda bar: bar['date'])


def fetch_bars(cutoff, request_page, cached_bars=()):
    """Bounded pageSize60 collection; cache merge requires full history and overlap."""
    loaded = {}
    boundary = required_from(cutoff).isoformat()
    can_merge = bool(cached_bars) and cached_bars[0]['date'] <= boundary
    cached_dates = {bar['date'] for bar in cached_bars}
    for page in range(1, 9):
        rows = _array(request_page(page, 60))
        _add_unique(loaded, parse_bars(rows, cutoff))
        if can_merge and cached_dates.intersection(loaded):
            _add_unique(loaded, cached_bars)
            break
        if len(rows) < 60 or loaded and min(loaded) <= boundary:
            break
    if not loaded:
        raise ValueError('No completed daily prices')
    return sorted(loaded.values(), key=lambda bar: bar['date'])


def _encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def _atomic(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_bytes(content)
    temporary.replace(path)


def _sha(content):
    return hashlib.sha256(content).hexdigest()


def _cached(root, code, cutoff):
    try:
        content = (root/'prices'/f'{code}.json').read_bytes()
        checkpoint = json.loads((root/'checkpoints'/f'{code}.json').read_bytes())
        if checkpoint['sha256'] != _sha(content):
            return None
        result = json.loads(content)
        if result['schemaVersion'] != 1 or result['issuerId'] != code or result['source'] != 'CACHE':
            return None
        rows = [dict(localTradedAt=bar['date'], openPrice=bar['open'], highPrice=bar['high'],
                     lowPrice=bar['low'], closePrice=bar['close'], accumulatedTradingVolume=bar['volume'])
                for bar in result['bars']]
        verified = parse_bars(rows, cutoff)
        if not verified or verified != result['bars'] or result['lastTradingDate'] != verified[-1]['date']:
            return None
        return result
    except (OSError, ValueError, KeyError, TypeError):
        return None


def download(url):
    request = Request(url, headers={'Accept':'application/json','Referer':'https://m.stock.naver.com/',
                                    'User-Agent':'KR4-Historical-Chart/1.0'})
    try:
        response = urlopen(request, timeout=20)
    except HTTPError as error:
        response = error
    with response:
        body = response.read(MAX_BYTES + 1)
        return response.status, body, dict(response.headers)


def collect_code(code, cutoff, root, request=None, now=None):
    """Atomic per-code checkpoint. A failed attempt never changes success dates."""
    if not CODE.fullmatch(code):
        raise ValueError('Invalid issue code')
    root = Path(root)
    now = now or utc_now()
    previous = _cached(root, code, cutoff)
    pages = []
    request = request or download
    def request_page(page, size):
        url = f'{BASE}/{code}/price?pageSize={size}&page={page}'
        status, body, headers = request(url)
        checksum = _sha(body)
        metadata = dict(url=url, sha256=checksum, bytes=len(body), status=status)
        pages.append(metadata)
        _atomic(root/'raw'/code/f'{checksum}.json', body)
        observation = dict(metadata, observedAt=now, headers={k:v for k,v in headers.items()
            if k.lower() in ('date','content-type','cache-control','expires','server')})
        _atomic(root/'attempts'/code/f"{now.replace(':','-')}-p{page}.json", _encoded(observation))
        if status != 200:
            raise ValueError(f'Price source HTTP {status}')
        if not 0 < len(body) <= MAX_BYTES:
            raise ValueError('Invalid chart payload size')
        return body
    try:
        bars = fetch_bars(cutoff, request_page, previous['bars'] if previous else ())
        old_pages = []
        if previous:
            observed_dates = {bar['date'] for page in pages for bar in parse_bars(
                (root/'raw'/code/f"{page['sha256']}.json").read_bytes(), cutoff)}
            if any(bar['date'] not in observed_dates for bar in bars):
                old_pages = previous['pages']
        unique_pages = { (p['url'],p['sha256']):p for p in old_pages + pages }
        result = dict(schemaVersion=1, issuerId=code, source='CACHE', sourceUrl=f'{BASE}/{code}/price',
                      requestedCutoffDate=cutoff.isoformat(), lastTradingDate=bars[-1]['date'],
                      collectedAt=now, lastAttemptAt=now, adjustment='UNVERIFIED', bars=bars,
                      pages=list(unique_pages.values()), error=None)
    except Exception as error:
        result = dict(previous) if previous else dict(schemaVersion=1, issuerId=code, source='MISSING',
            sourceUrl=f'{BASE}/{code}/price', requestedCutoffDate=cutoff.isoformat(),
            lastTradingDate=None, collectedAt=None, adjustment='UNVERIFIED', bars=[], pages=pages)
        result.update(lastAttemptAt=now, error=f'{type(error).__name__}: {error}')
    content = _encoded(result)
    _atomic(root/'prices'/f'{code}.json', content)
    _atomic(root/'checkpoints'/f'{code}.json', _encoded(dict(sha256=_sha(content),
        requestedCutoffDate=cutoff.isoformat(),lastAttemptAt=now, error=result['error'])))
    return result


def load_codes(path):
    payload = json.loads(Path(path).read_bytes())
    rows = payload if isinstance(payload, list) else payload.get('stocks',payload.get('records',[]))
    codes = sorted(rows) if isinstance(rows, dict) else sorted({str(row.get('issuerId',row.get('code',''))) for row in rows})
    if not codes or any(not CODE.fullmatch(code) for code in codes):
        raise ValueError('Invalid or empty quant universe')
    return codes


def write_index(root, codes, cutoff):
    root = Path(root)
    prices = {}
    coverage = dict(universe=len(codes),withBars=0,missing=0,errors=0,throughCutoff=0,historyBoundaryReached=0)
    for code in codes:
        path = root/'prices'/f'{code}.json'
        if not path.exists():
            coverage['missing'] += 1
            prices[code] = dict(source='MISSING',error='Not collected',sha256=None)
            continue
        content = path.read_bytes()
        result = json.loads(content)
        bars = result['bars']
        coverage['withBars' if bars else 'missing'] += 1
        coverage['errors'] += bool(result['error'])
        coverage['throughCutoff'] += bool(bars and bars[-1]['date'] == cutoff.isoformat())
        coverage['historyBoundaryReached'] += bool(bars and bars[0]['date'] <= required_from(cutoff).isoformat())
        prices[code] = dict(path=f'prices/{code}.json',sha256=_sha(content),bytes=len(content),
            source=result['source'],error=result['error'],lastTradingDate=result['lastTradingDate'],
            collectedAt=result['collectedAt'],lastAttemptAt=result['lastAttemptAt'],bars=len(bars))
    index = dict(schemaVersion=1,source='CACHE',provider='Naver Finance',adjustment='UNVERIFIED',
        requestedCutoffDate=cutoff.isoformat(),generatedAt=utc_now(),universe=codes,coverage=coverage,prices=prices)
    _atomic(root/'index.json',_encoded(index))
    return index


def seed_archive(root, destination):
    """Deterministic compact initial publication. Never includes private raw responses."""
    root, destination = Path(root), Path(destination)
    destination.parent.mkdir(parents=True,exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.tmp')
    with temporary.open('wb') as output, gzip.GzipFile(fileobj=output,mode='wb',mtime=0,filename='') as compressed:
        with tarfile.open(fileobj=compressed,mode='w') as archive:
            for path in [root/'index.json', *sorted((root/'prices').glob('*.json'))]:
                content=path.read_bytes()
                info=tarfile.TarInfo(path.relative_to(root).as_posix())
                info.size=len(content); info.mode=0o644; info.mtime=0
                archive.addfile(info,io.BytesIO(content))
    temporary.replace(destination)
    return dict(bytes=destination.stat().st_size,sha256=_sha(destination.read_bytes()))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--universe',type=Path,default=ROOT/'evidence/real_quant_snapshot.json')
    parser.add_argument('--cutoff',type=date.fromisoformat,default=date(2026,11,5))
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--workers',type=int,default=8,choices=range(1,9))
    parser.add_argument('--codes',nargs='+',help='Explicit diagnostic subset; default is entire universe')
    parser.add_argument('--refresh',action='store_true',help='Refresh successful checkpoints too')
    parser.add_argument('--seed',type=Path,help='Write deterministic publishable .tar.gz after collection')
    args=parser.parse_args()
    codes=sorted(set(args.codes)) if args.codes else load_codes(args.universe)
    pending=[code for code in codes if args.refresh or not (cached:=_cached(args.output,code,args.cutoff))
             or cached['requestedCutoffDate'] != args.cutoff.isoformat() or cached['error']]
    started=time.monotonic()
    print(json.dumps(dict(event='start',at=utc_now(),universe=len(codes),pending=len(pending),workers=args.workers,
        cutoff=args.cutoff.isoformat(),universeSha256=_sha(args.universe.read_bytes()))),flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures={pool.submit(collect_code,code,args.cutoff,args.output):code for code in pending}
        for number,future in enumerate(as_completed(futures),1):
            result=future.result()
            print(json.dumps(dict(event='collected',n=number,code=futures[future],source=result['source'],
                bars=len(result['bars']),lastTradingDate=result['lastTradingDate'],error=result['error'],
                elapsedSeconds=round(time.monotonic()-started,2))),flush=True)
            if number % 50 == 0:
                write_index(args.output,codes,args.cutoff)
    index=write_index(args.output,codes,args.cutoff)
    final=dict(event='complete',at=utc_now(),elapsedSeconds=round(time.monotonic()-started,2),coverage=index['coverage'])
    if args.seed:
        final['seed']=seed_archive(args.output,args.seed)
    print(json.dumps(final),flush=True)


if __name__ == '__main__':
    main()
