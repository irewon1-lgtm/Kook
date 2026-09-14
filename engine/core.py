"""KR Stock V3 offline reference model - 4 Quantitative Metrics Core Engine.
Not a production collector, Android app, investment adviser or live-trading engine.
All numeric functions are deterministic; the test suite uses synthetic fixtures.
"""
from __future__ import annotations
import calendar, hashlib, json, math, sqlite3, time
from bisect import bisect_left, bisect_right
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Mapping, Sequence, Any
from urllib.parse import urlsplit

IDS = ('M01', 'M02', 'M03', 'M04')
LABELS = dict(zip(IDS, ['매출 증가율', '영업이익률', '실적 기준 PER', '최근 6개월 주가 상승률']))
UNITS = dict(zip(IDS, ['%', '%', '배', '%']))
DIRECTIONS = dict(zip(IDS, [1, 1, -1, 1]))
WEIGHTS = dict(zip(IDS, [25, 25, 25, 25]))

class DataError(ValueError): pass
class PolicyError(ValueError): pass
class IntegrityError(ValueError): pass
class InjectedCrash(RuntimeError): pass

def number(x: Any) -> float:
    if isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x):
        raise DataError('FINITE_NUMBER_REQUIRED')
    return float(x)

def positive(x: Any) -> float:
    x = number(x)
    if x <= 0: raise DataError('POSITIVE_DENOMINATOR_REQUIRED')
    return x

def canonical(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)

def digest(obj: Any) -> str:
    return hashlib.sha256(canonical(obj).encode()).hexdigest()

def security_code(code: str) -> str:
    if not isinstance(code, str) or len(code) != 6 or any(c not in '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ' for c in code):
        raise DataError('SECURITY_CODE_STRING_REQUIRED')
    return code

def parse_money(text: str, scale: int = 1) -> int | None:
    if not isinstance(text, str) or type(scale) is not int or scale <= 0: raise DataError('MONEY_FORMAT')
    t = text.strip().replace(',', '')
    if t in ('', '-', '—'): return None
    if t.startswith('(') and t.endswith(')'): t = '-' + t[1:-1]
    try: return int(t) * scale
    except ValueError as exc: raise DataError('MONEY_FORMAT') from exc

@dataclass(frozen=True)
class Fact:
    issuer: str
    value: float
    start: date
    end: date
    published_at: datetime
    scope: str = 'CFS'
    currency: str = 'KRW'
    account: str = 'revenue'
    cumulative: bool = False
    receipt: str = 'R001'

def compatible(a: Fact, b: Fact, *, exact_period: bool = True) -> None:
    if a.issuer != b.issuer: raise DataError('ISSUER_MISMATCH')
    if a.scope != b.scope: raise DataError('SCOPE_MISMATCH')
    if a.currency != b.currency: raise DataError('CURRENCY_MISMATCH')
    if exact_period and (a.start, a.end) != (b.start, b.end): raise DataError('PERIOD_MISMATCH')

def available(facts: Sequence[Fact], at: datetime) -> list[Fact]:
    if at.tzinfo is None: raise DataError('TIMEZONE_REQUIRED')
    out = []
    for f in facts:
        if f.published_at.tzinfo is None: raise DataError('TIMEZONE_REQUIRED')
        if f.published_at <= at: out.append(f)
    return out

def latest_revision(facts: Sequence[Fact], at: datetime) -> Fact | None:
    if facts:
        for f in facts:
            compatible(facts[0], f)
            if f.account != facts[0].account: raise DataError('REVISION_ACCOUNT_MISMATCH')
    fs = available(facts, at)
    return max(fs, key=lambda x: (x.published_at, x.receipt)) if fs else None

def quarter_value(current: Fact, previous: Fact | None = None) -> float:
    if not current.cumulative: return number(current.value)
    if previous is None:
        if (current.end - current.start).days > 100: raise DataError('PREVIOUS_YTD_REQUIRED')
        return number(current.value)
    compatible(current, previous, exact_period=False)
    if current.account != previous.account or not previous.cumulative or current.start != previous.start or previous.end >= current.end:
        raise DataError('YTD_NOT_COMPARABLE')
    if not 60 <= (current.end - previous.end).days <= 120: raise DataError('QUARTER_GAP')
    return number(current.value) - number(previous.value)

def ttm_quarters(qs: Sequence[Fact]) -> float:
    if len(qs) != 4: raise DataError('FOUR_QUARTERS_REQUIRED')
    fs = sorted(qs, key=lambda q: q.start)
    for f in fs:
        compatible(fs[0], f, exact_period=False)
        if f.account != fs[0].account or f.cumulative or not 60 <= (f.end - f.start).days <= 120: raise DataError('NOT_QUARTER')
    for a, b in zip(fs, fs[1:]):
        if (b.start - a.end).days != 1: raise DataError('GAP_OR_OVERLAP')
    return sum(number(f.value) for f in fs)

def day_weighted_quarter(avg_ytd: float, days_ytd: int, avg_previous: float, days_previous: int) -> float:
    if not (0 < days_previous < days_ytd): raise DataError('BAD_DAY_COUNTS')
    return (number(avg_ytd) * days_ytd - number(avg_previous) * days_previous) / (days_ytd - days_previous)

def yoy(cur: float, old: float) -> float: return (number(cur) / positive(old) - 1) * 100

def margin(profit: float, revenue: float) -> float: return number(profit) / positive(revenue) * 100

def trailing_per(price: float, eps: float, *, is_financial: bool = False, is_loss_making: bool = False) -> float | None:
    """Calculates PER for profitable non-financial companies only. Returns None (N/A) for loss-making/financial/uncalculable."""
    if is_financial or is_loss_making:
        return None
    p = positive(price)
    e = number(eps)
    if e <= 0:
        return None
    return p / e

def price_return_6m(current_price: float, price_6m_ago: float) -> float:
    """Calculates 6-month price return based on adjusted close price."""
    p_cur = positive(current_price)
    p_old = positive(price_6m_ago)
    return (p_cur / p_old - 1) * 100

def scoring_value(metric: str, value: float) -> float:
    x = number(value)
    return x

def months_before(d: date, months: int) -> date:
    yy, mm = divmod(d.year * 12 + d.month - 1 - months, 12)
    return date(yy, mm + 1, min(d.day, calendar.monthrange(yy, mm + 1)[1]))

def normal_weights(weights: Mapping[str, float]) -> dict[str, float]:
    if not weights or any(k not in IDS for k in weights): raise PolicyError('UNKNOWN_OR_EMPTY_METRICS')
    w = {k: number(v) for k, v in weights.items()}
    if any(v < 0 for v in w.values()) or sum(w.values()) <= 0: raise PolicyError('BAD_WEIGHTS')
    total = sum(w.values())
    return {k: v / total for k, v in w.items() if v > 0}

class RankSnapshot:
    def __init__(self, raw: Mapping[str, Mapping[str, float | None]], weights: Mapping[str, float] = WEIGHTS, *, min_n: int = 2, cohort_id: str = 'nonfinancial-sector-v1'):
        self.weights = normal_weights(weights)
        self.raw = {i: dict(m) for i, m in raw.items()}
        self.cohort_id = cohort_id
        self.points: dict[str, dict[str, float]] = {i: {} for i in raw}
        for m in self.weights:
            values = {i: scoring_value(m, r[m]) for i, r in raw.items() if r.get(m) is not None}
            if len(values) < max(2, min_n): continue
            arr = sorted(DIRECTIONS[m] * x for x in values.values())
            n = len(arr)
            for issuer, v in values.items():
                x = DIRECTIONS[m] * v
                lo, hi = bisect_left(arr, x), bisect_right(arr, x)
                self.points[issuer][m] = 100 * (lo + (hi - lo - 1) / 2) / (n - 1)
        self.snapshot_id = digest({'raw': self.raw, 'weights': self.weights, 'cohort': cohort_id, 'min_n': min_n})

    def score(self, issuer: str) -> dict[str, Any]:
        p = self.points[issuer]
        coverage = sum(w for m, w in self.weights.items() if m in p)
        lower = sum(w * p[m] for m, w in self.weights.items() if m in p)
        upper = min(100.0, lower + 100 * (1 - coverage))
        complete = len(p) == len(self.weights)
        return {'issuer': issuer, 'score': lower if complete else None, 'lower': lower, 'upper': upper, 'coverage': coverage, 'status': 'COMPLETE' if complete else 'PARTIAL', 'snapshot_id': self.snapshot_id}

    def visible(self, issuers: Sequence[str] | None = None) -> list[dict[str, Any]]:
        ids = list(self.raw) if issuers is None else list(issuers)
        out = [self.score(i) for i in ids]
        return sorted(out, key=lambda r: (r['score'] is None, -round(r['score'] or 0, 6), r['issuer']))

def score_bounds(scores: Mapping[str, float | None], weights: Mapping[str, float]) -> tuple[float, float, float]:
    if set(scores) - set(weights): raise DataError('UNKNOWN_AXIS')
    ww = {k: number(v) for k, v in weights.items()}
    if not ww or any(v < 0 for v in ww.values()) or sum(ww.values()) <= 0: raise PolicyError('BAD_AXIS_WEIGHTS')
    tot = sum(ww.values()); lower = coverage = 0.0
    for k, w in ww.items():
        x = scores.get(k)
        if x is None: continue
        x = number(x)
        if not 0 <= x <= 100: raise DataError('AXIS_OUT_OF_RANGE')
        lower += w / tot * x; coverage += w / tot
    return lower, min(100, lower + 100 * (1 - coverage)), coverage

BUY_W = {'quant': 40, 'business': 25, 'valuation': 20, 'catalyst': 15}
SELL_W = {'thesis_break': 35, 'financing': 25, 'overvaluation': 20, 'governance': 20}

def decision(buy: Mapping[str, float | None], sell: Mapping[str, float | None], *, evidence_ok: bool, fresh: bool, critical: bool = False) -> dict[str, Any]:
    b = score_bounds(buy, BUY_W); s = score_bounds(sell, SELL_W)
    if critical: label = 'CRITICAL_RISK'
    elif not fresh: label = 'REVIEW_PENDING'
    elif not evidence_ok: label = 'INSUFFICIENT_EVIDENCE'
    elif b[0] >= 70 and s[0] >= 60: label = 'CONFLICT'
    elif s[0] >= 70: label = 'SELL_REVIEW'
    elif b[2] >= 0.999999 and b[0] >= 70 and s[1] <= 40: label = 'BUY_REVIEW'
    else: label = 'WATCH'
    return {'buy': b, 'sell': s, 'label': label, 'is_probability': False, 'performance_validated': False}

def instant(text: str) -> datetime:
    try:
        d = datetime.fromisoformat(text.replace('Z', '+00:00'))
        if d.tzinfo is None: raise ValueError('timezone missing')
        return d.astimezone(timezone.utc)
    except (ValueError, AttributeError) as exc: raise IntegrityError('INVALID_INSTANT') from exc

def verify_claim(claim: Mapping[str, Any], source: Mapping[str, Any], *, issuer: str, asof: str) -> None:
    if claim.get('issuer') != issuer or source.get('issuer') != issuer: raise IntegrityError('WRONG_ISSUER')
    if instant(source.get('published_at', '')) > instant(asof): raise IntegrityError('FUTURE_SOURCE')
    if claim.get('source_id') != source.get('id'): raise IntegrityError('SOURCE_ID')
    quote = claim.get('quote', '')
    if not quote or quote not in source.get('text', ''): raise IntegrityError('QUOTE_NOT_FOUND')
    if claim.get('kind') == 'ACTUAL' and source.get('kind') == 'FORECAST': raise IntegrityError('FORECAST_AS_ACTUAL')
    if source.get('read_level') != 'FULL' and claim.get('kind') != 'METADATA': raise IntegrityError('FULLTEXT_NOT_READ')
    if claim.get('period') != source.get('period'): raise IntegrityError('CLAIM_PERIOD')

DEPENDENCIES = {
 'PRICE': {'M04', 'RANK', 'VALUATION', 'DECISION'},
 'FINANCIAL': {'M01', 'M02', 'M03', 'RANK', 'REPORT_REVIEW', 'DECISION'},
 'CORRECTION': {'M01', 'M02', 'M03', 'RANK', 'REPORT_REVIEW', 'DECISION', 'FILINGS'},
 'SPLIT': {'M04', 'RANK', 'CHART', 'DECISION'},
 'MATERIAL_NEWS': {'NEWS', 'REPORT_REVIEW', 'DECISION'},
 'MINOR_NEWS': {'NEWS', 'CHECK_LOG'},
 'CANCELLATION': {'NEWS', 'FILINGS', 'REPORT_REVIEW', 'DECISION'},
 'HALT': {'TRADABILITY', 'RISK_BANNER', 'DECISION'},
 'MASTER': {'MASTER', 'RANK'},
}

def affected(kind: str) -> set[str]:
    if kind not in DEPENDENCIES: raise DataError('UNKNOWN_EVENT')
    return set(DEPENDENCIES[kind])

def report_status(report_watermark: int, source_watermark: int, checked_at: str) -> dict[str, Any]:
    return {'state': 'CURRENT' if report_watermark >= source_watermark else 'REVIEW_PENDING', 'checked_at': checked_at, 'report_watermark': report_watermark, 'source_watermark': source_watermark}

def render_metrics(raw: Mapping[str, Any]) -> list[dict[str, Any]]:
    if set(raw) - set(IDS): raise DataError('UNKNOWN_METRIC_ID')
    return [{'id': m, 'label': LABELS[m], 'unit': UNITS[m], 'value': raw.get(m)} for m in IDS]

def safe_url(url: str, allowed_hosts: set[str]) -> bool:
    try:
        p = urlsplit(url)
        return p.scheme == 'https' and p.hostname in allowed_hosts and p.username is None and p.password is None and p.port in (None, 443)
    except (TypeError, ValueError): return False

def entitlement(license_doc: Mapping[str, Any], *, purpose: str, now: str) -> bool:
    return license_doc.get('status') == 'APPROVED' and purpose in license_doc.get('purposes', []) and license_doc.get('expiry', '') >= now

class Store:
    def __init__(self, path: Path | str):
        self.db = sqlite3.connect(str(path), timeout=30, isolation_level=None)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA busy_timeout=30000')
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS jobs (k TEXT PRIMARY KEY, issuer TEXT, status TEXT, lease_until REAL DEFAULT 0, token INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS bundles (issuer TEXT PRIMARY KEY, seq INTEGER, payload TEXT, hash TEXT);
        CREATE TABLE IF NOT EXISTS outbox (k TEXT PRIMARY KEY, issuer TEXT, seq INTEGER);
        CREATE TABLE IF NOT EXISTS reservations (k TEXT PRIMARY KEY, units INTEGER);
        ''')
    def close(self): self.db.close()
    def enqueue(self, issuer: str, event_id: str, policy: str = 'v3') -> bool:
        k = digest([issuer, event_id, policy])
        c = self.db.execute('INSERT OR IGNORE INTO jobs(k,issuer,status) VALUES(?,?,?)', (k, issuer, 'QUEUED'))
        return c.rowcount == 1
    def claim(self, now: float, ttl: float = 60) -> tuple[str, str, int] | None:
        if ttl <= 0: raise PolicyError('BAD_LEASE')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            row = self.db.execute("SELECT k,issuer,token FROM jobs WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?) ORDER BY rowid LIMIT 1", (now,)).fetchone()
            if row:
                k, issuer, token = row; token += 1
                self.db.execute("UPDATE jobs SET status='RUNNING',lease_until=?,token=? WHERE k=?", (now + ttl, token, k))
                row = (k, issuer, token)
            self.db.execute('COMMIT'); return row
        except BaseException:
            self.db.execute('ROLLBACK'); raise
    def publish(self, job: tuple[str, str, int], seq: int, payload: Mapping[str, Any], now: float, crash: bool = False) -> None:
        k, issuer, token = job
        text = canonical(payload)
        if payload.get('issuer') != issuer: raise IntegrityError('BUNDLE_ISSUER')
        wm = payload.get('report_watermark', 0); src = payload.get('source_watermark', 0)
        if payload.get('analysis_status') == 'READY' and wm < src: raise IntegrityError('STALE_REPORT_AS_READY')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            row = self.db.execute('SELECT status,token,lease_until FROM jobs WHERE k=?', (k,)).fetchone()
            if not row or row[0] != 'RUNNING' or row[1] != token or row[2] <= now: raise IntegrityError('STALE_WORKER')
            old = self.db.execute('SELECT seq FROM bundles WHERE issuer=?', (issuer,)).fetchone()
            if old and old[0] >= seq: raise IntegrityError('OUT_OF_ORDER')
            self.db.execute('INSERT INTO bundles VALUES(?,?,?,?) ON CONFLICT(issuer) DO UPDATE SET seq=excluded.seq,payload=excluded.payload,hash=excluded.hash', (issuer, seq, text, hashlib.sha256(text.encode()).hexdigest()))
            if crash: raise InjectedCrash('CRASH_BETWEEN_BUNDLE_AND_OUTBOX')
            self.db.execute('INSERT INTO outbox VALUES(?,?,?)', (digest([issuer, seq]), issuer, seq))
            self.db.execute("UPDATE jobs SET status='DONE' WHERE k=?", (k,))
            self.db.execute('COMMIT')
        except BaseException:
            self.db.execute('ROLLBACK'); raise
    def read(self, issuer: str) -> dict[str, Any] | None:
        r = self.db.execute('SELECT payload,hash FROM bundles WHERE issuer=?', (issuer,)).fetchone()
        if not r: return None
        if hashlib.sha256(r[0].encode()).hexdigest() != r[1]: raise IntegrityError('HASH_MISMATCH')
        return json.loads(r[0])
    def reserve(self, k: str, units: int, limit: int) -> bool:
        if type(units) is not int or type(limit) is not int or units < 0 or limit < 0: raise PolicyError('BAD_BUDGET')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            if self.db.execute('SELECT 1 FROM reservations WHERE k=?', (k,)).fetchone():
                self.db.execute('COMMIT'); return False
            used = self.db.execute('SELECT COALESCE(SUM(units),0) FROM reservations').fetchone()[0]
            if used + units > limit:
                self.db.execute('COMMIT'); return False
            self.db.execute('INSERT INTO reservations VALUES(?,?)', (k, units)); self.db.execute('COMMIT'); return True
        except BaseException:
            self.db.execute('ROLLBACK'); raise

def retry_delay(status: int, attempt: int, retry_after: int | None = None) -> int | None:
    if status not in (429, 500, 502, 503, 504) or not 0 <= attempt < 3: return None
    base = min(300, 2 ** attempt * 5)
    return max(base, retry_after or 0)

def cost_estimate(issuers: int, changed: int, days: int, in_tokens: int, out_tokens: int, in_per_million: float, out_per_million: float, passes: int = 2) -> dict[str, float]:
    for n in (issuers, changed, days, in_tokens, out_tokens, passes):
        if type(n) is not int or n < 0: raise DataError('BAD_COST_INPUT')
    if changed > issuers: raise DataError('CHANGED_EXCEEDS_UNIVERSE')
    cost_per = (in_tokens * number(in_per_million) + out_tokens * number(out_per_million)) / 1_000_000 * passes
    if cost_per < 0: raise DataError('NEGATIVE_PRICE')
    return {'initial': issuers * cost_per, 'incremental': changed * days * cost_per, 'full_daily': issuers * days * cost_per}

def resolve_peer_ids(issuer: str, metadata: Mapping[str, Mapping[str, str]], valid_ids: set[str], min_n: int = 2) -> tuple[list[str], str]:
    me = metadata[issuer]
    for label, field in [('INDUSTRY', 'industry'), ('SECTOR', 'sector'), ('MODEL_WIDE', 'model')]:
        ids = sorted(i for i, m in metadata.items() if i in valid_ids and m['model'] == me['model'] and m[field] == me[field])
        if len(ids) >= min_n: return ids, label
    return [], 'INSUFFICIENT_PEERS'
