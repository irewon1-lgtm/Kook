"""Independent hand-worked examples and invariants for 4-Metric KR Stock V3 model."""
import unittest, math, random, tempfile, json
from pathlib import Path
from datetime import date, datetime, timezone, timedelta
from dataclasses import replace
from engine.core import *

UTC = timezone.utc
P = datetime(2026, 8, 14, 7, tzinfo=UTC)
F = Fact('A', 100, date(2026, 4, 1), date(2026, 6, 30), P)

def bundle(issuer, wm=1, status='READY'):
    return {'issuer': issuer, 'report_watermark': wm, 'source_watermark': wm, 'analysis_status': status, 'body': '가상 검증자료입니다.', 'metrics': {'M01': 10}}

class C01Numbers(unittest.TestCase):
    def test_yoy_hand(self): self.assertAlmostEqual(yoy(120, 100), 20)
    def test_yoy_negative_base(self): self.assertRaises(DataError, yoy, 100, -50)
    def test_yoy_zero_base(self): self.assertRaises(DataError, yoy, 100, 0)
    def test_negative_margin(self): self.assertEqual(margin(-10, 100), -10)
    def test_trailing_per_profitable(self): self.assertAlmostEqual(trailing_per(10000, 500), 20.0)
    def test_trailing_per_loss_making(self): self.assertIsNone(trailing_per(10000, -500, is_loss_making=True))
    def test_trailing_per_financial(self): self.assertIsNone(trailing_per(10000, 500, is_financial=True))
    def test_trailing_per_zero_eps(self): self.assertIsNone(trailing_per(10000, 0))
    def test_price_return_6m_hand(self): self.assertAlmostEqual(price_return_6m(120, 100), 20.0)
    def test_nonfinite_and_bool(self):
        for x in [float('nan'), float('inf'), float('-inf'), True, None, '3']:
            with self.subTest(x=x): self.assertRaises(DataError, number, x)

class C02Accounting(unittest.TestCase):
    def test_direct_quarter_not_double_subtract(self): self.assertEqual(quarter_value(F, replace(F, value=80)), 100)
    def test_ytd_subtraction(self):
        h = replace(F, value=250, start=date(2026, 1, 1), cumulative=True)
        q = replace(h, value=100, end=date(2026, 3, 31))
        self.assertEqual(quarter_value(h, q), 150)
    def test_ytd_needs_previous(self): self.assertRaises(DataError, quarter_value, replace(F, start=date(2026, 1, 1), cumulative=True))
    def test_scope_mismatch(self): self.assertRaises(DataError, compatible, F, replace(F, scope='OFS'))
    def test_currency_mismatch(self): self.assertRaises(DataError, compatible, F, replace(F, currency='USD'))
    def test_issuer_mismatch(self): self.assertRaises(DataError, compatible, F, replace(F, issuer='B'))
    def test_period_mismatch(self): self.assertRaises(DataError, compatible, F, replace(F, end=date(2026, 6, 29)))
    def test_money_leading_signs(self): self.assertEqual(parse_money('(1,234)', 1000), -1234000)
    def test_dash_is_missing_not_zero(self): self.assertIsNone(parse_money('-'))
    def test_zero_real_money(self): self.assertEqual(parse_money('0'), 0)

class C03TimeAndIdentity(unittest.TestCase):
    def test_leading_zero(self): self.assertEqual(security_code('005930'), '005930')
    def test_alphanumeric(self): self.assertEqual(security_code('0009K0'), '0009K0')
    def test_integer_identity_rejected(self): self.assertRaises(DataError, security_code, 5930)
    def test_identity_sql_rejected(self): self.assertRaises(DataError, security_code, 'A;DROP')
    def test_future_information_excluded(self): self.assertEqual(available([F], P - timedelta(seconds=1)), [])
    def test_restatement_point_in_time(self):
        newer = replace(F, value=80, published_at=P + timedelta(days=7), receipt='R002')
        self.assertEqual(latest_revision([F, newer], P).value, 100)
        self.assertEqual(latest_revision([F, newer], P + timedelta(days=8)).value, 80)

class C04RankProperties(unittest.TestCase):
    def test_hand_midrank_tie(self):
        r = RankSnapshot({'A': {'M01': 10}, 'B': {'M01': 10}, 'C': {'M01': 30}}, {'M01': 1}, min_n=2)
        self.assertEqual(r.score('A')['score'], 25); self.assertEqual(r.score('C')['score'], 100)
    def test_all_ties_50(self):
        r = RankSnapshot({i: {'M01': 10} for i in 'ABC'}, {'M01': 1}, min_n=2)
        self.assertEqual(r.score('A')['score'], 50)
    def test_lone_stock_no_fake_50(self):
        self.assertIsNone(RankSnapshot({'A': {'M01': 5}}, {'M01': 1}, min_n=2).score('A')['score'])
    def test_per_low_is_high_score(self):
        r = RankSnapshot({'A': {'M03': 10}, 'B': {'M03': 30}}, {'M03': 1}, min_n=2)
        self.assertEqual(r.score('A')['score'], 100)
    def test_filter_does_not_recompute(self):
        r = RankSnapshot({'A': {'M01': 10}, 'B': {'M01': 20}}, {'M01': 1}, min_n=2)
        self.assertEqual(r.visible(['B'])[0], r.score('B'))
    def test_missing_not_in_denominator(self):
        r = RankSnapshot({'A': {'M01': 1}, 'B': {'M01': 2}, 'C': {'M01': None}}, {'M01': 1}, min_n=2)
        self.assertEqual(r.score('B')['score'], 100)
    def test_no_active_metrics(self): self.assertRaises(PolicyError, normal_weights, {})
    def test_negative_weights(self): self.assertRaises(PolicyError, normal_weights, {'M01': -1})
    def test_all_zero_weights(self): self.assertRaises(PolicyError, normal_weights, {'M01': 0})
    def test_unknown_metric(self): self.assertRaises(PolicyError, normal_weights, {'Q11': 1})
    def test_default_weights_sum100(self): self.assertEqual(sum(WEIGHTS.values()), 100)
    def test_permutation_invariant_200(self):
        rng = random.Random(17)
        base = {str(i): {m: rng.randrange(20) for m in IDS} for i in range(40)}
        ref = RankSnapshot(base).visible()
        for _ in range(200):
            items = list(base.items()); rng.shuffle(items)
            self.assertEqual(RankSnapshot(dict(items)).visible(), ref)

class C05Missingness(unittest.TestCase):
    def test_one_metric_cannot_be_strong_score(self):
        raw = {'A': {'M01': 200}, 'B': {m: 10 for m in IDS}}
        r = RankSnapshot(raw, min_n=2).score('A')
        self.assertIsNone(r['score']); self.assertLessEqual(r['lower'], 25)
    def test_missing_interval_hand(self):
        r = RankSnapshot({'A': {'M01': 2}, 'B': {'M01': 1, 'M02': 10}, 'C': {'M01': 1, 'M02': 20}}, {'M01': 1, 'M02': 1}, min_n=2).score('A')
        self.assertEqual((r['lower'], r['upper'], r['coverage']), (50, 100, 0.5))
    def test_all_missing_0_100(self):
        r = RankSnapshot({'A': {}}, min_n=2).score('A')
        self.assertEqual((r['lower'], r['upper'], r['coverage']), (0, 100, 0))

class C06Decision(unittest.TestCase):
    def setUp(self): self.b = {k: 80 for k in BUY_W}; self.s = {k: 10 for k in SELL_W}
    def test_qualified_buy_review(self): self.assertEqual(decision(self.b, self.s, evidence_ok=True, fresh=True)['label'], 'BUY_REVIEW')
    def test_buy_is_not_probability(self): self.assertFalse(decision(self.b, self.s, evidence_ok=True, fresh=True)['is_probability'])

class C07Evidence(unittest.TestCase):
    def setUp(self):
        self.s = {'id': 'S1', 'issuer': 'A', 'published_at': '2026-08-14T07:00:00Z', 'text': '매출이 120억원이다.', 'kind': 'ACTUAL', 'read_level': 'FULL', 'period': '2026Q2'}
        self.c = {'source_id': 'S1', 'issuer': 'A', 'quote': '매출이 120억원이다.', 'kind': 'ACTUAL', 'period': '2026Q2'}
    def check(self): return verify_claim(self.c, self.s, issuer='A', asof='2026-08-15T00:00:00Z')
    def test_valid_quote_structure(self): self.assertIsNone(self.check())

class C08Updates(unittest.TestCase):
    def test_price_revalues_without_forced_prose(self):
        a = affected('PRICE'); self.assertTrue({'M04', 'VALUATION', 'DECISION'} <= a)

class C09Persistence(unittest.TestCase):
    def setUp(self): self.tmp = tempfile.TemporaryDirectory(); self.path = Path(self.tmp.name) / 'state.sqlite'; self.s = Store(self.path)
    def tearDown(self): self.s.close(); self.tmp.cleanup()
    def take(self, i='A', e='E1', now=0): self.s.enqueue(i, e); return self.s.claim(now)
    def test_duplicate_event_once(self):
        self.assertTrue(self.s.enqueue('A', 'E1'))
        for _ in range(20): self.assertFalse(self.s.enqueue('A', 'E1'))
        self.assertEqual(self.s.db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0], 1)

class C10SecurityAndRights(unittest.TestCase):
    def test_official_https_allowed(self): self.assertTrue(safe_url('https://dart.fss.or.kr/a', {'dart.fss.or.kr'}))

class C11BudgetAndRetry(unittest.TestCase):
    def test_cost_hand_hypothetical(self):
        c = cost_estimate(3000, 150, 30, 8000, 2000, 1, 4, 2)
        self.assertEqual(c, {'initial': 96.0, 'incremental': 144.0, 'full_daily': 2880.0})

class C12PresentationContract(unittest.TestCase):
    def test_metric_order_is_id_based(self):
        raw = {'M02': 99, 'M01': 12}; out = render_metrics(raw)
        self.assertEqual((out[0]['id'], out[0]['value'], out[0]['label']), ('M01', 12, '매출 증가율'))
    def test_exact_four_slots(self): self.assertEqual(len(render_metrics({})), 4)

if __name__ == '__main__': unittest.main(verbosity=2)
