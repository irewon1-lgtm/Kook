"""Deterministic synthetic stress/fault scenarios for 4-metric KR Stock V3 engine. No network, API billing or app execution."""
import sys, json, time, tempfile, random, sqlite3
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from fractions import Fraction
ROOT = Path(__file__).resolve().parents[1]; sys.path.insert(0, str(ROOT))
from engine.core import *

def b(i, seq=1): return {'issuer': i, 'report_watermark': seq, 'source_watermark': seq, 'analysis_status': 'READY', 'body': 'SYNTHETIC - 실제기업 자료 아님', 'metrics': {m: 1 for m in IDS}}
results = []
def run(ident, title, fn):
    start = time.perf_counter()
    try: detail = fn(); status = 'PASS'
    except Exception as e: detail = {'error': repr(e)}; status = 'FAIL'
    results.append({'id': ident, 'scenario': title, 'status': status, 'seconds': round(time.perf_counter() - start, 6), 'detail': detail})

def ranking_3500():
    rng = random.Random(301)
    raw = {f'{i:06}': {m: rng.uniform(0, 200) for m in IDS} for i in range(3500)}
    r = RankSnapshot(raw); v = r.visible()
    assert len(v) == 3500 and all(0 <= x['score'] <= 100 for x in v)
    subset = [v[i]['issuer'] for i in (0, 50, 1000, 3499)]
    assert {x['issuer']: x['score'] for x in r.visible(subset)} == {i: r.score(i)['score'] for i in subset}
    return {'synthetic_issuers': 3500, 'synthetic_metric_values': 14000, 'visible': len(v), 'filter_invariance': True, 'snapshot_hash': r.snapshot_id}

def independent_oracle():
    rng = random.Random(303); raw = {f'{i:06}': {m: rng.randrange(10) for m in IDS} for i in range(60)}
    r = RankSnapshot(raw)
    ws = [25, 25, 25, 25]; dirs = [1, 1, -1, 1]
    for i, vals in raw.items():
        target = Fraction(0)
        for k, (w, d) in enumerate(zip(ws, dirs), 1):
            m = f'M{k:02}'; x = vals[m]; others = [v[m] for j, v in raw.items() if j != i]
            less = sum(d * y < d * x for y in others); ties = sum(y == x for y in others)
            p = Fraction(less * 2 + ties, 2 * len(others)) * 100
            target += Fraction(w, 100) * p
        assert abs(r.score(i)['score'] - float(target)) < 1e-10
    return {'independent_pairwise_oracle': True, 'issuers': 60, 'metrics': 240, 'arithmetic': 'fractions.Fraction', 'max_tolerance': 1e-10}

def crash_resume_500():
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / 's.db'; s = Store(p)
        for i in range(500): s.enqueue(f'{i:06}', 'NEW')
        for n in range(136):
            j = s.claim(0); s.publish(j, 1, b(j[1]), 1)
        doomed = s.claim(0)
        try: s.publish(doomed, 1, b(doomed[1]), 1, crash=True)
        except InjectedCrash: pass
        before = s.db.execute('SELECT COUNT(*) FROM bundles').fetchone()[0]; assert before == 136
        s.close(); s = Store(p); resumed = 0
        while j := s.claim(100): s.publish(j, 1, b(j[1]), 101); resumed += 1
        after = s.db.execute('SELECT COUNT(*) FROM bundles').fetchone()[0]
        outbox = s.db.execute('SELECT COUNT(*) FROM outbox').fetchone()[0]
        assert (resumed, after, outbox) == (364, 500, 500)
        s.close()
        return {'failure_at': 137, 'before': before, 'resumed_only': resumed, 'after': after, 'unique_outbox': outbox}

def one_bad_of500():
    with tempfile.TemporaryDirectory() as td:
        s = Store(Path(td) / 's.db')
        for i in range(500): s.enqueue(f'{i:06}', 'NEW')
        failures = []
        while j := s.claim(0):
            try:
                payload = b(j[1]); payload['issuer'] = 'WRONG' if j[1] == '000137' else j[1]
                s.publish(j, 1, payload, 1)
            except IntegrityError: failures.append(j[1])
        count = s.db.execute('SELECT COUNT(*) FROM bundles').fetchone()[0]
        assert count == 499 and failures == ['000137']; s.close()
        return {'valid_published': 499, 'isolated_failure': failures, 'whole_universe_blocked': False}

def flood10000():
    with tempfile.TemporaryDirectory() as td:
        s = Store(Path(td) / 's.db'); created = 0
        for i in range(10000): created += s.enqueue(f'{i%500:06}', 'E1')
        count = s.db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]
        assert count == 500 and created == 500; s.close()
        return {'deliveries': 10000, 'unique_jobs': 500, 'duplicates_suppressed': 9500}

def parallel_budget():
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / 's.db'; s = Store(p); s.close()
        def spend(i):
            store = Store(p)
            try: return store.reserve(str(i), 100, 1000)
            finally: store.close()
        with ThreadPoolExecutor(max_workers=16) as ex: res = list(ex.map(spend, range(100)))
        s = Store(p); used = s.db.execute('SELECT SUM(units) FROM reservations').fetchone()[0]; s.close()
        assert sum(res) == 10 and used == 1000
        return {'parallel_threads': 16, 'requests': 100, 'accepted': sum(res), 'budget_limit_units': 1000, 'used_units': used, 'money_spent': 0}

def revision_flood():
    with tempfile.TemporaryDirectory() as td:
        s = Store(Path(td) / 's.db'); accepted = 0; rejected = 0
        for seq in (1, 3, 2, 5, 4, 9, 8, 10, 7, 6):
            s.enqueue('A', f'E{seq}'); j = s.claim(0)
            try: s.publish(j, seq, b('A', seq), 1); accepted += 1
            except IntegrityError: rejected += 1
        final = s.read('A')['source_watermark']; assert final == 10; s.close()
        return {'delivery_order': [1, 3, 2, 5, 4, 9, 8, 10, 7, 6], 'published_versions': accepted, 'outdated_rejected': rejected, 'final_version': final}

def cost_stress():
    x = cost_estimate(3000, 150, 30, 8000, 2000, 1, 4, 2)
    assert x['full_daily'] / x['incremental'] == 20
    return {'synthetic_inputs_only': True, 'not_provider_price_quote': True, 'assumed_input_USD_per_million': 1, 'assumed_output_USD_per_million': 4, 'initial_USD': x['initial'], 'monthly_incremental_USD': x['incremental'], 'monthly_full_daily_USD': x['full_daily'], 'relative_reduction': 20, 'other_costs_excluded': True}

if __name__ == '__main__':
    run('S01', '3,500 synthetic stocks / 35,000 metric ranking', ranking_3500)
    run('S02', 'Independent exact rational arithmetic oracle', independent_oracle)
    run('S03', 'Forced crash at 137/500 and process reopening', crash_resume_500)
    run('S04', 'One invalid issuer among 500', one_bad_of500)
    run('S05', '10,000 repeated event deliveries', flood10000)
    run('S06', 'Concurrent budget reservation', parallel_budget)
    run('S07', 'Out-of-order revisions cannot overwrite newest', revision_flood)
    run('S08', 'Synthetic cost differential calculation', cost_stress)
    obj = {'time_utc': datetime.now(timezone.utc).isoformat(), 'scope': 'Synthetic local reference model, not current market data, production throughput, model quality or investment returns', 'total': len(results), 'passed': sum(r['status'] == 'PASS' for r in results), 'results': results}
    (ROOT / 'evidence/stress_results.json').write_text(json.dumps(obj, ensure_ascii=False, indent=2)); print(json.dumps(obj, ensure_ascii=False, indent=2))
    sys.exit(0 if obj['passed'] == obj['total'] else 1)
