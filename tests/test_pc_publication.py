"""Small, explicitly synthetic contract fixtures; never used as published market data."""
import base64
import copy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.build_pc_publication import build_publication


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()


def digest(value):
    return dict(sha256=hashlib.sha256(value).hexdigest(), bytes=len(value))


class PublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.source = Path(cls.fixture.name)
        cls.codes = [f'{i:06}' for i in range(2648)] + ['0004Y0']
        cls.stocks = [dict(issuerId=c, asOfDate='2026-11-05', name='계약 시험', market='KOSPI', sector='계약 시험', rankOrder=None, compositeScore=None, isCompositeComplete=False, m03Per=dict(id='M03', rawValue=None, percentileScore=None, isAvailable=False)) for c in cls.codes]
        files = {'stocks.json': encoded(cls.stocks),
                 'financial.json': encoded(dict(snapshotDate='2026-11-05', status='HOLD', records={}, error='Independent financial mismatch')),
                 'valuations.json': encoded(dict(snapshotDate='2026-11-05', records={c: None for c in cls.codes})),
                 'candidates.json': encoded(dict(snapshotDate='2026-11-05', priceCutoffDate='2026-11-05', status='HOLD', records={}, error='Independent candidate mismatch')),
                 'rankings.json': encoded({str(mask): {c: dict(issuerId=c, selectedMetricCount=mask.bit_count(),
                     isEligible=False, rank=None, score=None) for c in cls.codes} for mask in range(1, 16)}),
                 'sources/quant.json': encoded(dict(records={c: {} for c in cls.codes})),
                 'sources/history.json': encoded(dict(records={})),
                 **{f'details/{s["issuerId"]}.json': encoded(dict(summary=s, financial=dict(status='HOLD'), candidate=None, valuation=None)) for s in cls.stocks}}
        manifest = dict(schemaVersion=1, generatedAt='2026-11-05T17:00:00Z', sourceCommit='a'*40,
            snapshotDate='2026-11-05', priceCutoffDate='2026-11-05', coverage=dict(stocks=2649, details=2649,
            rankingMasks=15, financialValid=False, candidateValid=False, candidates=0), financial=dict(status='HOLD'),
            inputHashes={k.split('/')[1].split('.')[0]: digest(v)['sha256'] for k, v in files.items() if k.startswith('sources/')},
            files={k: digest(v) for k, v in files.items()})
        for name, content in {**files, 'manifest.json': encoded(manifest)}.items():
            path=cls.source/'oracle'/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
        prices={}
        for code in cls.codes:
            missing=code=='0004Y0'
            value=dict(schemaVersion=1, issuerId=code, source='MISSING' if missing else 'CACHE',
                sourceUrl=f'https://m.stock.naver.com/api/stock/{code}/price', adjustment='UNVERIFIED',
                requestedCutoffDate='2026-11-05', collectedAt=None if missing else '2026-11-05T17:00:00Z',
                lastAttemptAt='2026-11-05T17:00:00Z', lastTradingDate=None if missing else '2026-11-05',
                error='HTTP 404' if missing else None,
                pages=[] if missing else [dict(url=f'https://m.stock.naver.com/api/stock/{code}/price?pageSize=60&page=1',
                    sha256='a'*64, bytes=100, status=200)],
                bars=[] if missing else [dict(date='2026-11-05',open=10,high=12,low=9,close=11,volume=None)])
            content=encoded(value); path=cls.source/'prices'/'prices'/f'{code}.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(content)
            prices[code]=dict(path=f'prices/{code}.json', **digest(content), source=value['source'], error=value['error'],
                lastTradingDate=value['lastTradingDate'],collectedAt=value['collectedAt'],lastAttemptAt=value['lastAttemptAt'],bars=len(value['bars']))
        index=dict(schemaVersion=1, source='CACHE',provider='Naver Finance',adjustment='UNVERIFIED',
            requestedCutoffDate='2026-11-05', universe=cls.codes, prices=prices,
            coverage=dict(universe=2649,withBars=2648,missing=1,errors=1,throughCutoff=2648,historyBoundaryReached=0))
        (cls.source/'prices/index.json').write_bytes(encoded(index))

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        import shutil
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        shutil.copytree(self.source/'oracle',self.root/'oracle')
        shutil.copytree(self.source/'prices',self.root/'prices')
        self.oracle=self.root/'oracle';self.prices=self.root/'prices';self.output=self.root/'output';self.bundle=self.root/'bundled.json'

    def tearDown(self):
        self.temp.cleanup()

    def build(self):
        return build_publication(self.oracle,self.prices,self.output,self.bundle)

    def oracle_edit(self,path,value):
        content=encoded(value);(self.oracle/path).write_bytes(content)
        m=json.loads((self.oracle/'manifest.json').read_bytes());m['files'][path]=digest(content)
        (self.oracle/'manifest.json').write_bytes(encoded(m))

    def read_oracle(self, name):
        return json.loads((self.oracle/name).read_bytes())

    def set_valuation(self, code='000000'):
        value=dict(code='BELOW_MEDIAN_PER', labelKo='시장 중앙값보다 낮은 PER 구간',
            shortLabelKo='중앙값↓ PER', percentile=75.0, changesRank=False)
        stocks=self.read_oracle('stocks.json')
        stock=next(s for s in stocks if s['issuerId']==code)
        stock['m03Per']=dict(id='M03',rawValue=10.0,percentileScore=75.0,isAvailable=True)
        self.oracle_edit('stocks.json',stocks)
        table=self.read_oracle('valuations.json');table['records'][code]=value
        self.oracle_edit('valuations.json',table)
        detail=self.read_oracle(f'details/{code}.json');detail.update(summary=stock,valuation=value)
        self.oracle_edit(f'details/{code}.json',detail)
        return value

    def set_valid_candidate(self, code='000000'):
        valuation=self.set_valuation(code)
        stocks=self.read_oracle('stocks.json');stock=next(s for s in stocks if s['issuerId']==code)
        metrics={f'm0{i}':dict(id=f'M0{i}',rawValue=10.0,percentileScore=75.0,
            basis='CONTROLLED_CONTRACT_INPUT',asOfDate='2026-11-05',source='KR4 validated remote snapshot') for i in range(1,5)}
        for i,key in enumerate(('m01RevGrowth','m02OpMargin','m03Per','m04Price6m'),1): stock[key]=dict(metrics[f'm0{i}'],source='CONTROLLED_TEST_PROVIDER',isAvailable=True)
        stock.update(rankOrder=1,compositeScore=75.0,isCompositeComplete=True)
        self.oracle_edit('stocks.json',stocks)
        ranks=self.read_oracle('rankings.json')
        for mask in range(1,16): ranks[str(mask)][code].update(isEligible=True,rank=1,score=75.0)
        self.oracle_edit('rankings.json',ranks)
        financial=dict(issuerId=code,status='PASS',reasonCode='PASS',basis='CONTROLLED_CONTRACT_INPUT',
            debtToEquityPct=50.0,currentRatioPct=200.0,accountingIdentityGapPct=0.0)
        self.oracle_edit('financial.json',dict(snapshotDate='2026-11-05',status='VALID',error=None,
            records={c:financial if c==code else dict(issuerId=c,status='HOLD') for c in self.codes}))
        candidate=dict(schemaVersion='KR4_FINAL_CANDIDATE_V2',policyVersion='STAGE4567_SAFETY_AND_RELATIVE_PER_BAND_V1',
            candidateRank=1,sourceRank=1,issuerId=code,name=stock['name'],market=stock['market'],sector=stock['sector'],
            compositeScore=75.0,financialSnapshotDate='2026-11-05',priceCutoffDate='2026-11-05',**metrics,
            selectionFlags=['FOUR_METRICS_COMPLETE','POSITIVE_TRAILING_PER','FINANCIAL_SAFETY_PASS','VALUATION_BAND_CLASSIFIED','EXISTING_COMPOSITE_RANK_REUSED'],
            researchStatus='FINAL_RESEARCH_CANDIDATE',selectionReasonKo='명시적 계약 시험 후보',
            financialSafetyStatus='PASS',financialSafetyReason='PASS',financialSafetyBasis=financial['basis'],
            debtToEquityPct=50.0,currentRatioPct=200.0,accountingIdentityGapPct=0.0,
            valuationBand=valuation['code'],valuationBandKo=valuation['labelKo'],valuationPercentile=valuation['percentile'])
        self.oracle_edit('candidates.json',dict(snapshotDate='2026-11-05',priceCutoffDate='2026-11-05',status='VALID',error=None,records={code:candidate}))
        detail=self.read_oracle(f'details/{code}.json');detail.update(summary=stock,financial=financial,candidate=candidate)
        self.oracle_edit(f'details/{code}.json',detail)
        manifest=self.read_oracle('manifest.json');manifest['coverage'].update(financialValid=True,candidateValid=True,candidates=1)
        manifest['financial']=dict(status='VALID',error=None)
        (self.oracle/'manifest.json').write_bytes(encoded(manifest))
        return candidate

    def price_edit(self,code,edit):
        path=f'prices/{code}.json';value=json.loads((self.prices/path).read_bytes());edit(value)
        content=encoded(value);(self.prices/path).write_bytes(content)
        index=json.loads((self.prices/'index.json').read_bytes());index['prices'][code].update(digest(content))
        (self.prices/'index.json').write_bytes(encoded(index))

    def test_binds_every_original_file_and_exact_bundle_and_honest_missing(self):
        original=(self.oracle/'manifest.json').read_bytes();manifest=self.build()
        self.assertEqual((self.oracle/'manifest.json').read_bytes(),original)
        generation=self.output/manifest['basePath']
        self.assertEqual((generation/'oracle-manifest.json').read_bytes(),original)
        for name,expected in manifest['files'].items():
            self.assertEqual(digest((generation/name).read_bytes()),expected,name)
        bundle=json.loads(self.bundle.read_bytes())
        self.assertEqual(bundle['manifest'],manifest)
        self.assertEqual(gzip.decompress(base64.b64decode(bundle['stocksGzip'])),(self.oracle/'stocks.json').read_bytes())
        self.assertNotIn('stocks',bundle)
        self.assertEqual(manifest['priceCoverage']['missing'],1)
        self.assertEqual(json.loads((generation/'prices/0004Y0.json').read_bytes())['bars'],[])
        self.assertEqual(manifest['financial']['status'],'HOLD')
        self.assertEqual(self.build()['generationId'],manifest['generationId'])

    def test_generation_changes_for_any_payload_and_retains_previous_two(self):
        generations=[]
        for i in range(4):
            self.oracle_edit('financial.json',dict(status='HOLD', records={}, error=f'failure {i}'))
            generations.append(self.build()['generationId'])
        self.assertEqual(len(set(generations)),4)
        self.assertEqual({p.name for p in (self.output/'generations').iterdir()},set(generations[-3:]))

    def test_rejects_bad_oracle_hash_without_replacing_last_manifest(self):
        self.build();original=(self.output/'manifest.json').read_bytes()
        (self.oracle/'stocks.json').write_text('[]')
        with self.assertRaisesRegex(ValueError,'hash|Hash'): self.build()
        self.assertEqual((self.output/'manifest.json').read_bytes(),original)

    def test_rejects_bad_price_hash(self):
        (self.prices/'prices/000000.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'hash|Hash'): self.build()

    def test_rejects_path_traversal_and_private_files(self):
        m=json.loads((self.oracle/'manifest.json').read_bytes())
        for path in ('../secret.json','sources/.env','sources/tokens.json','/etc/passwd','sources/context/../../secret.json'):
            with self.subTest(path=path):
                modified=copy.deepcopy(m);modified['files'][path]=dict(sha256='a'*64,bytes=1)
                (self.oracle/'manifest.json').write_bytes(encoded(modified))
                with self.assertRaisesRegex(ValueError,'path|Path|allow|Allow'): self.build()
        (self.oracle/'manifest.json').write_bytes(encoded(m))

    def test_rejects_lost_stock_detail_and_mask_universe(self):
        self.oracle_edit('stocks.json',self.stocks[:-1])
        with self.assertRaisesRegex(ValueError,'2649|universe'): self.build()
        self.oracle_edit('stocks.json',self.stocks)
        ranks=json.loads((self.oracle/'rankings.json').read_bytes());ranks['15'].pop('0004Y0')
        self.oracle_edit('rankings.json',ranks)
        with self.assertRaisesRegex(ValueError,'rank|universe'): self.build()

    def test_rejects_rehashed_invalid_or_future_ohlcv_and_fake_live(self):
        path=self.prices/'prices/000000.json';original=path.read_bytes()
        index=(self.prices/'index.json').read_bytes()
        for edit in (lambda p:p.update(source='LIVE'), lambda p:p['bars'][0].update(high=1),
                     lambda p:p['bars'][0].update(date='2026-11-06'),lambda p:p['bars'][0].update(volume=-1),
                     lambda p:p.update(pages=[]),lambda p:p.update(source='MISSING')):
            with self.subTest(edit=edit):
                path.write_bytes(original);(self.prices/'index.json').write_bytes(index)
                self.price_edit('000000',edit)
                with self.assertRaises(ValueError): self.build()

    def test_missing_empty_http_error_response_preserves_valid_quant(self):
        self.price_edit('0004Y0', lambda p:p.update(pages=[dict(
            url='https://m.stock.naver.com/api/stock/0004Y0/price?pageSize=60&page=1',
            status=503, bytes=0, sha256=hashlib.sha256(b'').hexdigest())]))
        manifest=self.build()
        self.assertEqual(manifest['priceCoverage']['missing'],1)
        self.assertEqual(manifest['coverage']['stocks'],2649)

    def test_rejects_source_hash_mismatch_even_with_valid_outer_hash(self):
        self.oracle_edit('sources/quant.json',dict(records={},tampered=True))
        with self.assertRaisesRegex(ValueError,'source|input'): self.build()

    def test_rejects_symlink_and_manifest_missing_detail(self):
        path=self.oracle/'details/000000.json';saved=self.root/'outside.json';path.rename(saved);path.symlink_to(saved)
        with self.assertRaisesRegex(ValueError,'symlink|path'): self.build()

    def test_rejects_missing_detail_and_price_code_even_with_valid_remaining_hashes(self):
        m=json.loads((self.oracle/'manifest.json').read_bytes())
        m['files'].pop('details/0004Y0.json')
        (self.oracle/'manifest.json').write_bytes(encoded(m))
        with self.assertRaisesRegex(ValueError,'Detail universe'): self.build()
        m['files']['details/0004Y0.json']=digest((self.oracle/'details/0004Y0.json').read_bytes())
        (self.oracle/'manifest.json').write_bytes(encoded(m))
        index=json.loads((self.prices/'index.json').read_bytes());index['prices'].pop('0004Y0')
        (self.prices/'index.json').write_bytes(encoded(index))
        with self.assertRaisesRegex(ValueError,'Price universe'): self.build()

    def test_rejects_corrupt_previous_generation_before_switching_current(self):
        manifest=self.build();original=(self.output/'manifest.json').read_bytes()
        (self.output/manifest['basePath']/'stocks.json').write_text('[]')
        self.oracle_edit('financial.json',dict(status='HOLD',records={},error='new failure'))
        with self.assertRaisesRegex(ValueError,'hash'): self.build()
        self.assertEqual((self.output/'manifest.json').read_bytes(),original)

    def test_publishes_required_valuation_and_reasoned_hold_candidate_exports(self):
        self.set_valuation()
        manifest=self.build();generation=self.output/manifest['basePath']
        self.assertIn('valuations.json',manifest['files']);self.assertIn('candidates.json',manifest['files'])
        valuations=json.loads((generation/'valuations.json').read_bytes())
        self.assertEqual(set(valuations['records']),set(self.codes));self.assertIsNone(valuations['records']['0004Y0'])
        self.assertEqual(valuations['records']['000000']['changesRank'],False)
        self.assertEqual(json.loads((generation/'candidates.json').read_bytes())['status'],'HOLD')
        self.assertEqual(manifest['coverage']['stocks'],2649)

    def test_requires_both_new_root_exports(self):
        original=self.read_oracle('manifest.json')
        for name in ('valuations.json','candidates.json'):
            with self.subTest(name=name):
                manifest=copy.deepcopy(original);manifest['files'].pop(name)
                (self.oracle/'manifest.json').write_bytes(encoded(manifest))
                with self.assertRaisesRegex(ValueError,'Missing original'): self.build()

    def test_rejects_valuation_universe_date_and_constructor_corruption(self):
        self.set_valuation();original=self.read_oracle('valuations.json')
        edits=[lambda table:table.update(snapshotDate='2026-11-04'),
               lambda table:table['records'].pop('0004Y0'),
               lambda table:table['records'].update({'999999':None}),
               lambda table:table['records']['000000'].pop('shortLabelKo'),
               lambda table:table['records']['000000'].update(changesRank=True),
               lambda table:table['records']['000000'].update(code='UNVERIFIED'),
               lambda table:table['records']['000000'].update(percentile=101),
               lambda table:table['records']['000000'].update(labelKo='')]
        for edit in edits:
            with self.subTest(edit=edit):
                table=copy.deepcopy(original);edit(table);self.oracle_edit('valuations.json',table)
                with self.assertRaisesRegex(ValueError,'Valuation'): self.build()

    def test_rejects_valuation_detail_mismatch_and_false_missing_band(self):
        self.set_valuation();detail=self.read_oracle('details/000000.json');detail['valuation']=None
        self.oracle_edit('details/000000.json',detail)
        with self.assertRaisesRegex(ValueError,'valuation'): self.build()
        detail['valuation']=self.read_oracle('valuations.json')['records']['000000'];self.oracle_edit('details/000000.json',detail)
        table=self.read_oracle('valuations.json');table['records']['000000']=None;self.oracle_edit('valuations.json',table)
        detail['valuation']=None;self.oracle_edit('details/000000.json',detail)
        with self.assertRaisesRegex(ValueError,'Valuation'): self.build()

    def test_valid_candidate_preserves_original_fields_and_financial_pass(self):
        expected=self.set_valid_candidate();manifest=self.build()
        published=json.loads((self.output/manifest['basePath']/'candidates.json').read_bytes())
        self.assertEqual(published['records'],{'000000':expected})

    def test_rejects_candidate_identity_dates_and_missing_constructor_field(self):
        self.set_valid_candidate();original=self.read_oracle('candidates.json')
        edits=[lambda table:table.update(snapshotDate='2026-11-04'),
               lambda table:table.update(priceCutoffDate='2026-11-04'),
               lambda table:table.update(status='LIVE'),
               lambda table:table.update(error='Unexpected VALID error'),
               lambda table:table['records'].update({'999999':copy.deepcopy(table['records']['000000'])}),
               lambda table:table['records']['000000'].update(issuerId='999999'),
               lambda table:table['records']['000000'].update(financialSnapshotDate='2026-11-04'),
               lambda table:table['records']['000000'].pop('valuationPercentile'),
               lambda table:table['records']['000000']['m01'].pop('source'),
               lambda table:table['records']['000000']['m01'].update(source='FAKE_LIVE'),
               lambda table:table['records']['000000'].update(financialSafetyStatus='HOLD'),
               lambda table:table['records']['000000'].update(valuationBand='HIGH_RELATIVE_PER')]
        for edit in edits:
            with self.subTest(edit=edit):
                table=copy.deepcopy(original);edit(table);self.oracle_edit('candidates.json',table)
                with self.assertRaisesRegex(ValueError,'Candidate'): self.build()

    def test_rejects_candidate_detail_mismatch_and_nonpass_current_financial(self):
        self.set_valid_candidate();detail=self.read_oracle('details/000000.json');detail['candidate']=None
        self.oracle_edit('details/000000.json',detail)
        with self.assertRaisesRegex(ValueError,'candidate'): self.build()
        detail['candidate']=self.read_oracle('candidates.json')['records']['000000'];self.oracle_edit('details/000000.json',detail)
        financial=self.read_oracle('financial.json');financial['records']['000000']['status']='FAIL'
        self.oracle_edit('financial.json',financial)
        with self.assertRaisesRegex(ValueError,'Candidate'): self.build()

    def test_candidate_hold_requires_empty_map_and_reason_without_blocking_quant(self):
        self.build();original=self.read_oracle('candidates.json')
        for edit in (lambda table:table.update(error=''),lambda table:table.update(records={'000000':{}})):
            with self.subTest(edit=edit):
                table=copy.deepcopy(original);edit(table);self.oracle_edit('candidates.json',table)
                with self.assertRaisesRegex(ValueError,'Candidate'): self.build()
        self.oracle_edit('candidates.json',original)
        self.assertEqual(self.build()['coverage']['stocks'],2649)

    def test_size_limit_failure_does_not_replace_manifest(self):
        with self.assertRaisesRegex(ValueError,'size|limit'): build_publication(self.oracle,self.prices,self.output,self.bundle,max_bytes=1)
        self.assertFalse((self.output/'manifest.json').exists())


if __name__=='__main__': unittest.main()
