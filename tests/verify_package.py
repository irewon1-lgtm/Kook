"""Artifact consistency checks; not a production application acceptance test.
Optional QA dependencies: jsonschema and PyMuPDF. Core engine tests use only stdlib.
"""
from pathlib import Path
from datetime import datetime,timezone
import copy,json,hashlib,sys,re
import jsonschema,fitz
R=Path(__file__).resolve().parents[1];sys.path.insert(0,str(R))
from engine.core import IDS,WEIGHTS,LABELS,UNITS
records=[]
def check(name,fn):
 try: detail=fn();records.append({'check':name,'status':'PASS','detail':detail})
 except Exception as e:records.append({'check':name,'status':'FAIL','detail':repr(e)})
def require(cond):
 if not cond:raise AssertionError('condition not met')
 return True
def read(p):return json.loads((R/p).read_text())
registry=read('docs/metric_registry.json');schema=read('docs/company_bundle.schema.json');fixture=read('docs/synthetic_schema_fixture.json');req=read('docs/requirements.json')
check('all specification JSON files parse',lambda:[json.loads(p.read_text()) is not None for p in (R/'docs').glob('*.json')])
check('original 8 plus 2 with stable IDs',lambda:require(registry['original_metric_ids']==list(IDS[:8]) and registry['additional_metric_ids']==list(IDS[8:]) and len(registry['metrics'])==10))
check('weights and unit labels match tested engine',lambda:require(sum(x['default_weight'] for x in registry['metrics'])==100 and all(x['default_weight']==WEIGHTS[x['id']] and x['unit']==UNITS[x['id']] and x['name_ko']==LABELS[x['id']] for x in registry['metrics'])))
check('68 requirements unique and four user goals covered',lambda:require(req['count']==68 and len({x['id'] for x in req['requirements']})==68 and {n for x in req['requirements'] for n in x['user_requirements']}=={1,2,3,4}))
check('requirement status does not assert live completion',lambda:require(all('TEST' in x['current_status'] for x in req['requirements'])))
check('draft 2020-12 schema is well formed',lambda:jsonschema.Draft202012Validator.check_schema(schema))
check('explicitly synthetic missing-data fixture valid',lambda:jsonschema.validate(fixture,schema))
def bad_fixture(mut):
 f=copy.deepcopy(fixture);mut(f)
 try:jsonschema.validate(f,schema)
 except jsonschema.ValidationError:return 'REJECTED_AS_EXPECTED'
 raise AssertionError('bad fixture accepted')
check('wrong metric label id rejected',lambda:bad_fixture(lambda f:f['metrics']['Q01'].update(id='Q02')))
check('wrong metric unit rejected',lambda:bad_fixture(lambda f:f['metrics']['Q05'].update(unit='USD')))
check('missing metric slot rejected',lambda:bad_fixture(lambda f:f['metrics'].pop('Q10')))
check('score outside 0..100 rejected',lambda:bad_fixture(lambda f:f['decision'].update(buy_lower=101)))
check('probability mislabel rejected',lambda:bad_fixture(lambda f:f['decision'].update(is_probability=True)))
check('reference test result belongs to shipped source',lambda:require(read('evidence/final_recheck.json')['success'] and read('evidence/final_recheck.json')['tests_run']==138 and read('evidence/final_recheck.json')['source_sha256']==hashlib.sha256((R/'engine/core.py').read_bytes()).hexdigest()))
check('all32 mutation logs/source hashes present',lambda:require(read('evidence/mutation_results.json')['complete'] and read('evidence/mutation_results.json')['killed']==32 and all((R/'evidence/mutations'/f'{x["id"]}.log').exists() and hashlib.sha256((R/'evidence/mutations'/f'{x["id"]}.py.txt').read_bytes()).hexdigest()==x['mutant_sha256'] for x in read('evidence/mutation_results.json')['results'])))
check('8 stress cases all recorded',lambda:require(read('evidence/stress_results.json')['total']==8 and read('evidence/stress_results.json')['passed']==8))
check('rights are unverified not preapproved',lambda:require(all(x['entitlement_status']=='NOT_VERIFIED' and not x['allowed_redistribution'] for x in read('docs/data_rights_matrix.json')['providers'])))
check('copy order is same as ZIP start order',lambda:require((R/'00_START_HERE.txt').read_text()==(R/'docs/KR_Stock_V3_Paste_Order_20260913.txt').read_text()))
def pdf_checks():
 d=fitz.open(R/'docs/KR_Stock_V3_Research_Report_20260913.pdf')
 require(len(d)==31)
 for i,p in enumerate(d):
  require(f'{i+1} / 31' in p.get_text());require(len(p.get_text())>250)
  require(not any(w[0]<24 or w[2]>p.rect.width-24 or w[1]<14 or w[3]>p.rect.height-14 for w in p.get_text('words')))
 return {'pages':31,'footer_sequence':True,'outside_page_words':0,'visual_qa_record':'evidence/visual_review.json (separate manual image inspection)'}
check('PDF 31 pages, page numbers and text bounds',pdf_checks)
payload={'scope':'Artifact consistency / schema guards only, not live app acceptance','time_utc':datetime.now(timezone.utc).isoformat(),'total':len(records),'passed':sum(x['status']=='PASS' for x in records),'results':records}
(R/'evidence/package_checks.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2))
print(json.dumps({'total':payload['total'],'passed':payload['passed'],'failures':[x for x in records if x['status']!='PASS']},ensure_ascii=False))
sys.exit(0 if payload['total']==payload['passed'] else 1)
