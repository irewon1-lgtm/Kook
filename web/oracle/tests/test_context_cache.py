"""Retain genuine recorded observations through controlled transport failures."""
import copy
import gzip
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest

ROOT=Path(__file__).resolve().parents[3]
FIXTURE=ROOT/'web/oracle/testdata/context_real_market_recorded_000020'

class Failure503Proxy(BaseHTTPRequestHandler):
    requests=0
    def do_CONNECT(self):
        type(self).requests+=1
        self.send_error(503,'CONTROLLED_FAILURE_REGRESSION')
    do_GET=do_CONNECT
    do_POST=do_CONNECT
    def log_message(self,*args): pass

class ContextCacheRetentionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        cls.directory=Path(cls.temp.name)
        metadata=json.loads((FIXTURE/'provenance.json').read_text())
        raw=gzip.decompress((FIXTURE/'observation.json.gz').read_bytes())
        if metadata['classification']!='REAL_MARKET_RECORDED' or hashlib.sha256(raw).hexdigest()!=metadata['sha256']:
            raise AssertionError('Recorded actual context provenance changed')
        cls.previous=json.loads(raw)
        # Build outside the controlled proxy. All later calls reuse this JVM dist.
        cls.run_export(cls.directory/'build-check',skip_build=False)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    @classmethod
    def run_export(cls,output,*arguments,skip_build=True,env=None):
        command=['python3',str(ROOT/'scripts/export_pc_oracle.py'),'--output',str(output)]
        if skip_build: command+=['--skip-build']
        result=subprocess.run(command+list(arguments),capture_output=True,text=True,env=env)
        if result.returncode: raise AssertionError(result.stdout+result.stderr)

    def make_cache(self,name):
        cache=self.directory/name;cache.mkdir()
        (cache/'000020.json').write_text(json.dumps(self.previous,ensure_ascii=False))
        return cache

    def test_503_refresh_preserves_actual_bodies_receipts_and_observation_time(self):
        cache=self.make_cache('failure-cache')
        proxy=ThreadingHTTPServer(('127.0.0.1',0),Failure503Proxy)
        server=threading.Thread(target=proxy.serve_forever,daemon=True);server.start()
        environment=os.environ.copy()
        environment['HTTPS_PROXY']=environment['HTTP_PROXY']=f'http://127.0.0.1:{proxy.server_port}'
        output=self.directory/'failure-publication'
        try:
            self.run_export(output,'--context-cache',str(cache),'--collect-context','--context-refresh','--context-codes','000020','--context-limit','1','--context-concurrency','1',env=environment)
        finally:
            proxy.shutdown();server.join();proxy.server_close()
        self.assertGreater(Failure503Proxy.requests,0,'Actual original repository must encounter the controlled HTTP503 transport')
        observation=json.loads((cache/'000020.json').read_text())
        detail=json.loads((output/'details/000020.json').read_text())
        previous=self.previous['evidence']['disclosures']
        previous_bodies={item['receiptNo']:item['bodyText'] for item in previous if item['bodyText']}
        previous_receipts={item['receiptNo'] for item in previous if item['receiptNo']}
        self.assertEqual(len(previous_bodies),2)
        self.assertEqual(len(previous_receipts),23)
        retained=detail['evidence']['disclosures']
        self.assertLessEqual(previous_receipts,{item['receiptNo'] for item in retained})
        self.assertEqual(previous_bodies,{item['receiptNo']:item['bodyText'] for item in retained if item['receiptNo'] in previous_bodies})
        self.assertEqual(observation['collectedAt'],self.previous['collectedAt'])
        self.assertGreater(observation['lastAttemptAt'],observation['collectedAt'])
        self.assertTrue(observation['lastAttemptError'])
        context=detail['sourceStates']['context']
        self.assertEqual(context['status'],'PARTIAL_OR_CACHE')
        self.assertEqual(context['checkedAt'],self.previous['collectedAt'])
        self.assertEqual(context['lastAttemptAt'],observation['lastAttemptAt'])
        self.assertTrue(context['lastAttemptError'])
        self.assertEqual(context['categories']['ir']['status'],'MISSING')
        disclosures=context['categories']['disclosures']
        self.assertEqual(disclosures['checkedAt'],self.previous['collectedAt'])
        self.assertEqual(disclosures['source'],'CACHE')
        self.assertGreater(disclosures['retainedCount'],0)
        self.assertEqual(detail['evidence']['disclosureDiff']['scope'],self.previous['evidence']['disclosureDiff']['scope'])

    def controlled_policy_refresh(self,previous,incoming,attempted_at,name):
        # Reuse actual recorded rows; mutate only scenario availability, never
        # introduce new source content or pass a test payload through publication.
        prior_file=self.directory/(name+'-previous.json')
        incoming_file=self.directory/(name+'-CONTROLLED_FAILURE.json')
        prior_file.write_text(json.dumps(previous,ensure_ascii=False))
        incoming_file.write_text(json.dumps(incoming,ensure_ascii=False))
        java=Path('/workspace/toolchains/jdk-17.0.17+10/bin/java')
        if not java.is_file(): java=Path('java')
        libraries=ROOT/'web/oracle/build/install/kr4-original-source-oracle/lib'
        result=subprocess.run([str(java),'--class-path',str(libraries/'*'),str(ROOT/'web/oracle/tests/java/ContextRetentionHarness.java'),str(prior_file),str(incoming_file),attempted_at],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        return json.loads(result.stdout)

    def test_fresh_news_is_independent_of_failed_dart_and_older_retained_rows(self):
        incoming=copy.deepcopy(self.previous['evidence'])
        incoming['news']=incoming['news'][:2]
        incoming['disclosures']=[]
        incoming['disclosureDiff']=None
        incoming['error']='dart=CONTROLLED_HTTP_503, naverDisclosure=CONTROLLED_HTTP_503, official=CONTROLLED_HTTP_503'
        attempt='2026-11-06T00:00:00Z'
        result=self.controlled_policy_refresh(self.previous,incoming,attempt,'mixed')
        observation=result['observation'];state=result['state']
        self.assertEqual(observation['evidence']['news'],incoming['news'])
        self.assertEqual(state['categories']['news']['source'],'COLLECTED')
        self.assertEqual(state['categories']['news']['status'],'AVAILABLE')
        self.assertEqual(state['categories']['news']['checkedAt'],attempt)
        self.assertEqual(state['categories']['news']['retainedCount'],0)
        self.assertEqual(state['categories']['disclosures']['source'],'CACHE')
        self.assertEqual(state['categories']['disclosures']['checkedAt'],self.previous['collectedAt'])
        self.assertEqual(observation['collectedAt'],self.previous['collectedAt'])
        self.assertEqual(len([item for item in observation['evidence']['disclosures'] if item['bodyText']]),2)
        self.assertEqual(state['status'],'PARTIAL_OR_CACHE')
        self.assertEqual(state['source'],'MIXED')
        # Repeated failed daily attempts never advance the retained observation.
        again=self.controlled_policy_refresh(observation,incoming,'2026-11-07T00:00:00Z','mixed-again')
        self.assertEqual(again['observation']['collectedAt'],self.previous['collectedAt'])
        self.assertEqual(again['state']['categories']['disclosures']['checkedAt'],self.previous['collectedAt'])
        self.assertEqual(again['observation']['lastAttemptAt'],'2026-11-07T00:00:00Z')

    def test_body_loss_without_original_error_retains_only_lost_bodies(self):
        incoming=copy.deepcopy(self.previous['evidence'])
        for item in incoming['disclosures']:item['bodyText']=''
        result=self.controlled_policy_refresh(self.previous,incoming,'2026-11-06T00:00:00Z','body-loss')
        disclosures=result['observation']['evidence']['disclosures']
        self.assertEqual(len(disclosures),len(incoming['disclosures']))
        self.assertEqual([item for item in disclosures if item['bodyText']],[item for item in self.previous['evidence']['disclosures'] if item['bodyText']])
        state=result['state']['categories']['disclosures']
        self.assertEqual(state['retainedCount'],2)
        self.assertEqual(state['source'],'MIXED')
        self.assertTrue(state.get('lastAttemptError'))
        self.assertTrue(result['observation']['lastAttemptError'])
        self.assertEqual(result['state']['categories']['news']['status'],'AVAILABLE')
        self.assertIsNone(result['state']['categories']['news'].get('lastAttemptError'))
        recovered=self.controlled_policy_refresh(result['observation'],self.previous['evidence'],'2026-11-07T00:00:00Z','recovery')
        self.assertFalse(recovered['observation']['retainedPrevious'])
        self.assertEqual(recovered['observation']['collectedAt'],'2026-11-07T00:00:00Z')
        self.assertIsNone(recovered['observation'].get('lastAttemptError'))
        self.assertEqual(recovered['state']['categories']['ir']['status'],'MISSING')

    def test_legacy_cache_has_independent_missing_ir_and_official_categories(self):
        cache=self.make_cache('legacy-cache');output=self.directory/'legacy-publication'
        self.run_export(output,'--context-cache',str(cache))
        detail=json.loads((output/'details/000020.json').read_text())
        state=detail['sourceStates']['context']
        self.assertNotEqual(state['status'],'COMPLETE')
        for category in ('news','disclosures','ir','official'):
            item=state['categories'][category]
            self.assertEqual(item['count'],len(detail['evidence'][category]))
            self.assertTrue(item['loaded'])
            self.assertEqual(item['source'],'CACHE')
            self.assertEqual(item['checkedAt'],self.previous['collectedAt'])
        self.assertEqual(state['categories']['ir']['status'],'MISSING')
        self.assertEqual(state['categories']['official']['status'],'MISSING')

if __name__=='__main__':unittest.main()
