"""Behavioral contract against the actual original-source JVM publication."""
import gzip
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]

class ExporterAvailabilityTest(unittest.TestCase):
    def test_original_source_exporter_is_available(self):
        self.assertTrue((ROOT/'scripts/export_pc_oracle.py').is_file(), 'Missing original-source JVM exporter')

class OriginalSourceExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.output = Path(cls.temp.name) / 'publication'
        result = subprocess.run(['python3', str(ROOT / 'scripts/export_pc_oracle.py'), '--output', str(cls.output)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError('Original-source exporter must run successfully:\n' + result.stdout + result.stderr)
        cls.manifest = json.loads((cls.output/'manifest.json').read_text())
        cls.stocks = json.loads((cls.output/'stocks.json').read_text())
        cls.rankings = json.loads((cls.output/'rankings.json').read_text())
        # The latest optional Stage4 can legitimately lag quant. Keep a separate
        # immutable real source pair for the positive exact-amount regression.
        fixture=ROOT/'web/oracle/testdata/real_market_recorded_20261105'
        provenance=json.loads((fixture/'provenance.json').read_text())
        if provenance['classification']!='REAL_MARKET_RECORDED':
            raise AssertionError('Positive fixture must be explicitly recorded, never LIVE')
        cls.recorded_inputs={}
        for name,metadata in provenance['files'].items():
            compressed=(fixture/metadata['path']).read_bytes()
            raw=gzip.decompress(compressed)
            if hashlib.sha256(compressed).hexdigest()!=metadata['gzipSha256'] or hashlib.sha256(raw).hexdigest()!=metadata['sha256']:
                raise AssertionError('Recorded real source bytes changed: '+name)
            if len(raw)!=metadata['bytes'] or len(compressed)!=metadata['gzipBytes']:
                raise AssertionError('Recorded real source length changed: '+name)
            destination=Path(cls.temp.name)/name
            destination.write_bytes(raw)
            cls.recorded_inputs[name]=destination
        cls.recorded_financial_source=json.loads(cls.recorded_inputs['financial_safety_snapshot.json'].read_text())
        cls.recorded_output=Path(cls.temp.name)/'recorded-publication'
        cls._export(cls.recorded_output, '--quant', str(cls.recorded_inputs['real_quant_snapshot.json']),
                    '--financial', str(cls.recorded_inputs['financial_safety_snapshot.json']))

    @classmethod
    def _export(cls, destination, *extra):
        result=subprocess.run(['python3',str(ROOT/'scripts/export_pc_oracle.py'),'--skip-build','--output',str(destination),*extra],capture_output=True,text=True)
        if result.returncode:
            raise AssertionError('Original-source exporter must run successfully:\n'+result.stdout+result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_manifest_authenticates_every_published_file(self):
        m = self.manifest
        self.assertEqual(m['schemaVersion'], 1)
        self.assertEqual(m['snapshotDate'], json.loads((ROOT/'evidence/real_quant_snapshot.json').read_text())['snapshot_date_kst'])
        self.assertEqual(len(m['sourceCommit']), 40)
        for path, expected in m['files'].items():
            data=(self.output/path).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), expected['sha256'], path)
            self.assertEqual(len(data), expected['bytes'], path)
        self.assertEqual(len(list((self.output/'details').glob('*.json'))), 2649)
        self.assertTrue(m['inputHashes'])

    def test_all_original_rank_masks_preserve_null_and_universe(self):
        self.assertEqual(len(self.stocks), 2649)
        codes={s['issuerId'] for s in self.stocks}
        self.assertEqual(set(self.rankings), {str(n) for n in range(1,16)})
        for mask, ranks in self.rankings.items():
            self.assertEqual(set(ranks), codes)
            selected=int(mask).bit_count()
            eligible=sorted((r for r in ranks.values() if r['isEligible']), key=lambda r:r['rank'])
            self.assertEqual([r['rank'] for r in eligible],list(range(1,len(eligible)+1)))
            for r in ranks.values():
                self.assertEqual(r['selectedMetricCount'], selected)
                if not r['isEligible']:
                    self.assertIsNone(r['rank']); self.assertIsNone(r['score'])

    def test_original_validated_candidate_list_matches_detail_records(self):
        path=self.output/'candidates.json'
        self.assertTrue(path.is_file(),'Missing original final-candidate list publication')
        table=json.loads(path.read_text())
        self.assertEqual(table['snapshotDate'],self.manifest['snapshotDate'])
        self.assertEqual(table['priceCutoffDate'],self.manifest['priceCutoffDate'])
        self.assertIn('candidates.json',self.manifest['files'])
        if self.manifest['coverage']['candidateValid']:
            self.assertEqual(table['status'],'VALID')
            self.assertIsNone(table['error'])
            self.assertEqual(len(table['records']),self.manifest['coverage']['candidates'])
        else:
            self.assertEqual(table['status'],'HOLD')
            self.assertTrue(table['error'])
            self.assertEqual(table['records'],{})
        for stock in self.stocks:
            code=stock['issuerId']
            detail=json.loads((self.output/'details'/f'{code}.json').read_text())
            self.assertEqual(detail['candidate'],table['records'].get(code))

    def test_original_stage5_valuation_table_and_non_mutating_classification(self):
        path=self.output/'valuations.json'
        self.assertTrue(path.is_file(),'Missing original Stage5 valuation publication')
        table=json.loads(path.read_text())
        self.assertEqual(table['snapshotDate'],self.manifest['snapshotDate'])
        self.assertEqual(set(table['records']),{stock['issuerId'] for stock in self.stocks})
        self.assertIn('valuations.json',self.manifest['files'])
        java=Path('/workspace/toolchains/jdk-17.0.17+10/bin/java')
        if not java.is_file():java=Path('java')
        result=subprocess.run([str(java),'--class-path',str(ROOT/'web/oracle/build/install/kr4-original-source-oracle/lib/*'),str(ROOT/'web/oracle/tests/java/ValuationOracleHarness.java'),str(self.output/'stocks.json')],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        original=json.loads(result.stdout)
        self.assertEqual(table['records'],original['records'])
        self.assertTrue(original['rankingsUnchanged'],'Original Stage5 classification must not mutate any of15 global rankings')
        for code,valuation in table['records'].items():
            detail=json.loads((self.output/'details'/f'{code}.json').read_text())
            self.assertIn('valuation',detail)
            self.assertEqual(detail['valuation'],valuation)
            if valuation is not None:
                self.assertEqual(set(valuation),{'code','labelKo','shortLabelKo','percentile','changesRank'})
                self.assertFalse(valuation['changesRank'])
        recorded=json.loads((self.recorded_output/'valuations.json').read_text())
        self.assertEqual(recorded['records']['005930'],{'code':'MID_PER','labelKo':'시장 중간 PER 구간','shortLabelKo':'중간 PER','percentile':52.219162,'changesRank':False})
        self.assertIsNone(recorded['records']['003060'],'Actual negative PER must retain original unavailable/null classification')

    def test_financial_table_preserves_exact_amount_strings(self):
        # Positive contract always executes against an unmodified, verified real
        # recorded pair. It must exercise amounts even when current data is HOLD.
        table=json.loads((self.recorded_output/'financial.json').read_text())
        self.assertEqual(table['status'],'VALID')
        self.assertEqual(len(table['records']),2649)
        verified=amounts=nulls=zeros=negatives=0
        for code,record in table['records'].items():
            facts=record['facts']
            self.assertIsNotNone(facts,code)
            verified+=facts['verified']
            raw=self.recorded_financial_source['records'][code]
            self.assertEqual(facts['amounts'],{key:raw.get(key) for key in facts['amounts']},code)
            self.assertEqual(facts['amountsExact'],{key:None if value is None else str(value) for key,value in facts['amounts'].items()},code)
            for value in facts['amounts'].values():
                amounts+=1
                nulls+=value is None
                zeros+=value==0
                negatives+=value is not None and value<0
        self.assertGreater(verified,0,'Recorded positive regression must include verified facts')
        self.assertGreater(amounts,0,'Exact-amount assertions must not be vacuous')
        self.assertGreater(nulls,0); self.assertGreater(zeros,0); self.assertGreater(negatives,0)

    def test_current_financial_publication_is_valid_or_explains_hold(self):
        table=json.loads((self.output/'financial.json').read_text())
        coverage=self.manifest['coverage']
        self.assertEqual(coverage['details'],2649)
        self.assertEqual(table['snapshotDate'],self.manifest['snapshotDate'])
        if coverage['financialValid']:
            self.assertEqual(table['status'],'VALID')
            self.assertEqual(set(table['records']),{stock['issuerId'] for stock in self.stocks})
            self.assertEqual(coverage['financialRecords'],2649)
        else:
            self.assertEqual(table['status'],'HOLD')
            self.assertEqual(table['records'],{})
            self.assertTrue(table['error'])
            self.assertEqual(self.manifest['financial']['status'],'HOLD')
            self.assertEqual(coverage['financialRecords'],0)
            self.assertFalse(coverage['candidateValid'])
            self.assertEqual(coverage['candidates'],0)

    def test_missing_optional_stage4_preserves_current_quant(self):
        destination=Path(self.temp.name)/'missing-stage4'
        missing=Path(self.temp.name)/'absent-financial-snapshot.json'
        self.assertFalse(missing.exists())
        self._export(destination,'--financial',str(missing))
        table=json.loads((destination/'financial.json').read_text())
        manifest=json.loads((destination/'manifest.json').read_text())
        self.assertEqual(table['status'],'HOLD')
        self.assertEqual(table['records'],{})
        self.assertTrue(table['error'])
        self.assertFalse(manifest['coverage']['financialValid'])
        self.assertFalse(manifest['coverage']['candidateValid'])
        candidates=json.loads((destination/'candidates.json').read_text())
        self.assertEqual(candidates['status'],'HOLD')
        self.assertEqual(candidates['records'],{})
        self.assertTrue(candidates['error'])
        self.assertEqual(manifest['coverage']['details'],2649)
        for name in ('stocks.json','rankings.json','valuations.json'):
            self.assertEqual((destination/name).read_bytes(),(self.output/name).read_bytes())

    def test_every_original_constructor_field_is_published(self):
        definitions={}
        for path in (ROOT/'app/src/main/java/com/krstock/v3/data/model').glob('*.kt'):
            for name,body in re.findall(r'data class (\w+)\(([\s\S]*?)\n\)',path.read_text()):
                definitions[name]=set(re.findall(r'val (\w+)\s*:',body))
        def check(name,value):
            self.assertLessEqual(definitions[name],set(value),name)
        for stock in self.stocks:
            detail=json.loads((self.output/'details'/f"{stock['issuerId']}.json").read_text())
            check('StockDetail',detail);check('StockSummary',stock)
            check('CompanyReport',detail['report']);check('IntegratedAnalysis',detail['analysis'])
            check('QuarterlyHistory',detail['history']);check('EvidenceBundle',detail['evidence'])
            check('FinancialSafetyRecord',detail['financial'])
            self.assertIn('valuation',detail)
            if detail['valuation'] is not None:check('ValuationBand',detail['valuation'])
            for point in detail['history']['points']:check('QuarterlyPoint',point)
            for evidence in detail['evidence']['all']:check('ContextEvidence',evidence)
            if detail['financial']['facts']:check('FinancialSafetyFacts',detail['financial']['facts'])
            if detail['candidate']:
                check('FinalCandidateRecord',detail['candidate'])
                for metric in ['m01','m02','m03','m04']:check('CandidateMetricSnapshot',detail['candidate'][metric])
            for metric in ['m01RevGrowth','m02OpMargin','m03Per','m04Price6m']:
                check('MetricValue',stock[metric])
                if stock[metric]['peerComparison']:check('PeerComparison',stock[metric]['peerComparison'])

    def test_same_day_wrong_quant_fingerprint_is_hold(self):
        safety=json.loads(self.recorded_inputs['financial_safety_snapshot.json'].read_text())
        safety['quant_records_sha256']='0'*64
        bad=Path(self.temp.name)/'wrong-fingerprint.json'
        bad.write_text(json.dumps(safety))
        destination=Path(self.temp.name)/'wrong-fingerprint'
        result=subprocess.run(['python3',str(ROOT/'scripts/export_pc_oracle.py'),'--skip-build','--output',str(destination),'--financial',str(bad),'--quant',str(self.recorded_inputs['real_quant_snapshot.json'])],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        manifest=json.loads((destination/'manifest.json').read_text())
        self.assertEqual(json.loads((destination/'financial.json').read_text())['records'],{})
        self.assertFalse(manifest['coverage']['financialValid'])
        self.assertFalse(manifest['coverage']['candidateValid'])

    def test_date_mismatched_safety_is_hold_without_blocking_quant(self):
        bad=Path(self.temp.name)/'bad-financial.json'
        safety=json.loads(self.recorded_inputs['financial_safety_snapshot.json'].read_text())
        safety['snapshot_date_kst']='2026-01-01'
        bad.write_text(json.dumps(safety))
        destination=Path(self.temp.name)/'mismatched'
        result=subprocess.run(['python3',str(ROOT/'scripts/export_pc_oracle.py'),'--skip-build','--output',str(destination),'--financial',str(bad),'--quant',str(self.recorded_inputs['real_quant_snapshot.json'])],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        manifest=json.loads((destination/'manifest.json').read_text())
        self.assertEqual(json.loads((destination/'financial.json').read_text())['records'],{})
        self.assertFalse(manifest['coverage']['financialValid'])
        self.assertFalse(manifest['coverage']['candidateValid'])
        candidates=json.loads((destination/'candidates.json').read_text())
        self.assertEqual(candidates['status'],'HOLD')
        self.assertEqual(candidates['records'],{})
        self.assertTrue(candidates['error'])
        self.assertEqual(manifest['coverage']['details'],2649)
        for name in ('stocks.json','rankings.json','valuations.json'):
            self.assertEqual((destination/name).read_bytes(),(self.recorded_output/name).read_bytes())
        for path in (destination/'details').glob('*.json'):
            detail=json.loads(path.read_text())
            self.assertEqual(detail['financial']['status'],'HOLD')
            self.assertIsNone(detail['candidate'])
            self.assertEqual(detail['sourceStates']['candidate']['status'],'HOLD')

    def test_details_include_every_original_and_computed_field(self):
        for stock in self.stocks:
            d=json.loads((self.output/'details'/f"{stock['issuerId']}.json").read_text())
            self.assertEqual(d['summary'], stock)
            self.assertTrue({'report','news','filings','analysis','history','evidence','financial','candidate','valuation'} <= d.keys())
            self.assertTrue({'regimeTitle','thesis','industryContext','combinationMeaning','causeInvestigation','consequence','falsifiers','confidenceNote','quarterlyTrend','quarterlySignal','quarterlyCoverage'} <= d['analysis'].keys())
            self.assertTrue({'availableQuarterCount','directQuarterCount','reconstructedQuarterCount','comparablePoints','latestTargetPoint','comparisonScope','hasFourQuarters','hasEightQuarters','hasTrailingGap'} <= d['history'].keys())
            self.assertTrue({'all','primarySourceCount','loaded','error'} <= d['evidence'].keys())
            if not d['evidence']['loaded']:
                self.assertEqual(d['sourceStates']['context']['status'], 'NOT_COLLECTED')
                self.assertEqual(d['evidence']['all'], [])
                self.assertIsNotNone(d['evidence']['error'])

if __name__ == '__main__': unittest.main()
