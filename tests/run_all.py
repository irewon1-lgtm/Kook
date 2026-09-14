import sys,json,time,unittest,platform,hashlib
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
class Recorder(unittest.TextTestResult):
    def __init__(self,*a,**k): super().__init__(*a,**k); self.records=[]; self.starts={}
    def startTest(self,test): self.starts[test.id()]=time.perf_counter();super().startTest(test)
    def record(self,t,status,detail=''): self.records.append({'id':t.id(),'domain':t.__class__.__name__,'status':status,'seconds':round(time.perf_counter()-self.starts[t.id()],6),'detail':detail})
    def addSuccess(self,t):super().addSuccess(t);self.record(t,'PASS')
    def addFailure(self,t,e):super().addFailure(t,e);self.record(t,'FAIL',self._exc_info_to_string(e,t))
    def addError(self,t,e):super().addError(t,e);self.record(t,'ERROR',self._exc_info_to_string(e,t))
if __name__=='__main__':
    out=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'evidence'/'clean_pass_results.json'
    suite=unittest.defaultTestLoader.discover(str(ROOT/'tests'),pattern='test_core.py',top_level_dir=str(ROOT))
    result=unittest.TextTestRunner(verbosity=2,resultclass=Recorder).run(suite)
    domains={}
    for r in result.records:
        d=domains.setdefault(r['domain'],{'tests':0,'pass':0,'failed':0})
        d['tests']+=1;d['pass']+=r['status']=='PASS';d['failed']+=r['status']!='PASS'
    payload={'scope':'Offline reference engine; synthetic inputs; NOT Android, provider integration, live operations, human expert quality, or backtest proof','time_utc':datetime.now(timezone.utc).isoformat(),'python':platform.python_version(),'source_sha256':hashlib.sha256((ROOT/'engine/core.py').read_bytes()).hexdigest(),'tests_run':result.testsRun,'success':result.wasSuccessful(),'domains':domains,'records':result.records}
    out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(payload,ensure_ascii=False,indent=2))
    sys.exit(0 if result.wasSuccessful() else 1)
