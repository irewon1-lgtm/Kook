"""Controlled fixtures only; these amounts are regression cases, never market data."""
import io,json,sys,unittest,zipfile
from unittest.mock import patch
from types import SimpleNamespace
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import collect_financial_safety_verified as v

FIELDS=['재무제표종류','종목코드','회사명','결산기준일','보고서종류','통화','항목코드','항목명','당기 반기말']
KINDS=[('ifrs-full_Assets','자산총계',100),('ifrs-full_Liabilities','부채총계',50),('ifrs-full_Equity','자본총계',50),('ifrs-full_CurrentAssets','유동자산',80),('ifrs-full_CurrentLiabilities','유동부채',50)]
def rows():
 return [dict(zip(FIELDS,['재무상태표, 유동/비유동법 - 연결','[000001]','검증회사','2026-06-30','반기보고서','KRW',code,name,str(value)])) for code,name,value in KINDS]
def archive(data):
 stream=io.BytesIO()
 with zipfile.ZipFile(stream,'w') as z:
  text='\t'.join(FIELDS)+'\n'+'\n'.join('\t'.join(r.get(k,'') for k in FIELDS) for r in data)
  z.writestr('controlled_bs.txt',text.encode('cp949'))
 return stream.getvalue()
def parse(data):
 return v.parse_bulk_sources({'000001':{'name':'검증회사','sector':'제조업'}},[('2026_2Q_BS_20261105030346.zip',archive(data))],'2026-11-05')['000001']
def provenance():
 return {'verified':True,'corp_code':'00123456','receipt_no':'20260814000001','filing_date':'2026-08-14','report_period':'2026-06-30','report_name':'반기보고서 (2026.06)','source_url':'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814000001','correction_status':'ORIGINAL','correction_check':'LATEST_FINAL_REPORT_CONFIRMED'}
class FinancialFacts(unittest.TestCase):
 def test_exact_amounts_keep_zero_distinct_from_missing(self):
  r=rows();r[3]['당기 반기말']='0';self.assertEqual(parse(r)['current_assets'],0)
  r[3]['당기 반기말']='-';self.assertIsNone(parse(r)['current_assets'])
 def test_conflicting_duplicate_account_is_not_arbitrarily_chosen(self):
  r=rows();r.append(dict(r[0],**{'당기 반기말':'999'}));self.assertIn('CONFLICTING_ACCOUNT_ASSETS',parse(r)['validation_issues'])
 def test_currency_must_be_known_and_consistent(self):
  r=rows();r[0]['통화']='USD';self.assertIn('MIXED_CURRENCY',parse(r)['validation_issues'])
 def test_one_complete_foreign_currency_is_preserved_without_fx_guess(self):
  r=rows()
  for x in r:x['통화']='CNY'
  p=parse(r);self.assertEqual(p['currency'],'CNY');self.assertEqual(p['assets'],100)
 def test_mixed_dates_cannot_mix_balance_sheet_values(self):
  r=rows();r[2]['결산기준일']='2026-03-31';self.assertIn('MIXED_PERIOD',parse(r)['validation_issues'])
 def test_company_identity_mismatch_is_quarantined(self):
  r=rows();r[0]['회사명']='다른회사';self.assertIn('ISSUER_NAME_MISMATCH',parse(r)['validation_issues'])
 def test_fractional_or_unmarked_scaled_amount_cannot_become_integer(self):
  r=rows();r[0]['당기 반기말']='100.5';self.assertIn('INVALID_AMOUNT_ASSETS',parse(r)['validation_issues'])
 def test_exact_long_amount_is_not_rounded_via_float(self):
  r=rows();r[0]['당기 반기말']='9,007,199,254,740,993';self.assertEqual(parse(r)['assets'],9007199254740993)
 def test_newest_actual_period_wins_without_combining_scopes(self):
  older=rows();new=rows()
  for x in new:x['결산기준일']='2026-09-30';x['보고서종류']='분기보고서'
  sources=[('2026_2Q_BS_20261105030346.zip',archive(older)),('2026_3Q_BS_20261105032754.zip',archive(new))]
  p=v.parse_bulk_sources({'000001':{'name':'검증회사','sector':'제조업'}},sources,'2026-11-05')['000001']
  self.assertEqual(p['report_period'],'2026-09-30');self.assertEqual(p['scope'],'CFS')
 def test_unverified_filing_never_promotes_raw_facts(self):
  p=v.validate_record(parse(rows()),{'verified':False,'error':'DART_IDENTITY_UNCONFIRMED'},'2026-11-05')
  self.assertEqual((p['status'],p['reason']),('HOLD','DART_IDENTITY_UNCONFIRMED'))
 def test_actual_provenance_and_original_thresholds_are_required(self):
  p=v.validate_record(parse(rows()),provenance(),'2026-11-05')
  self.assertEqual(p['status'],'PASS');self.assertEqual(p['current_ratio_pct'],160.0)
 def test_newer_amendment_not_tied_to_bulk_values_remains_hold(self):
  f=provenance();f['correction_status']='LATEST_CORRECTION'
  p=v.validate_record(parse(rows()),f,'2026-11-05');self.assertEqual(p['status'],'HOLD');self.assertIn('CORRECTION',p['reason'])
 def test_future_or_mismatched_filing_period_is_rejected(self):
  f=provenance();f['report_period']='2026-03-31';self.assertEqual(v.validate_record(parse(rows()),f,'2026-11-05')['status'],'HOLD')
 def test_financial_sector_keeps_raw_facts_while_not_comparable(self):
  p=parse(rows());p['sector']='은행';p=v.validate_record(p,provenance(),'2026-11-05')
  self.assertEqual(p['status'],'NOT_APPLICABLE');self.assertEqual(p['assets'],100)
 def test_missing_issuer_stays_present_with_explicit_reason(self):
  p=v.parse_bulk_sources({'000002':{'name':'자료없는회사','sector':'제조'}},[('2026_2Q_BS_20261105030346.zip',archive(rows()))],'2026-11-05')
  self.assertEqual(set(p),{'000002'});self.assertEqual(p['000002']['reason'],'DART_NO_BALANCE_SHEET')
class AmendmentProof(unittest.TestCase):
 def amended(self):
  f=provenance();f['correction_status']='LATEST_CORRECTION';f['values_reconciled']=True
  f['correction_reconciliation']={'receipt_no':f['receipt_no'],'corp_code':f['corp_code'],'report_period':'2026-06-30','scope':'CFS','currency':'KRW','amounts':{'assets':100,'liabilities':50,'equity':50,'current_assets':80,'current_liabilities':50},'source_api_sha256':'a'*64,'source_api_url':'https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json?corp_code=00123456&bsns_year=2026&reprt_code=11012&fs_div=CFS'}
  f['correction_reconciliation'].update(bsns_year='2026',reprt_code='11012',observed_receipt_nos=[f['receipt_no']],observed_corp_codes=[f['corp_code']],observed_currencies=['KRW'],source_api_params={'corp_code':f['corp_code'],'bsns_year':'2026','reprt_code':'11012','fs_div':'CFS'},bulk_amounts=dict(f['correction_reconciliation']['amounts']),mismatches={})
  return f
 def test_boolean_without_actual_matching_proof_cannot_promote_amendment(self):
  f=self.amended();f.pop('correction_reconciliation')
  self.assertEqual(v.validate_record(parse(rows()),f,'2026-11-05')['status'],'HOLD')
 def test_all_five_actual_matching_values_can_validate_corrected_filing(self):
  p=v.validate_record(parse(rows()),self.amended(),'2026-11-05')
  self.assertEqual(p['status'],'PASS');self.assertTrue(p['values_reconciled']);self.assertEqual(p['assets'],100)
 def test_different_corrected_value_never_replaces_bulk_or_promotes_risk(self):
  f=self.amended();f['correction_reconciliation']['amounts']['assets']=999
  p=v.validate_record(parse(rows()),f,'2026-11-05');self.assertEqual(p['status'],'HOLD');self.assertEqual(p['assets'],100)
 def test_wrong_receipt_scope_or_credential_bearing_source_rejects_proof(self):
  for field,value in [('receipt_no','20260814000002'),('scope','OFS'),('source_api_url','https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json?crtfc_key=CONTROLLED')]:
   f=self.amended();f['correction_reconciliation'][field]=value
   self.assertEqual(v.validate_record(parse(rows()),f,'2026-11-05')['status'],'HOLD')
 def test_internal_counterevidence_cannot_be_overridden_by_reconciled_boolean(self):
  for field,value in [('validation_error','RECEIPT_MISMATCH'),('observed_receipt_nos',['20260814009999']),('observed_corp_codes',['99999999']),('observed_currencies',['USD']),('source_api_params',{'corp_code':'00123456','bsns_year':'2026','reprt_code':'11012','fs_div':'OFS'}),('bsns_year','2025'),('reprt_code','11013'),('mismatches',{'assets':{'bulk':100,'api':101}})]:
   with self.subTest(field=field):
    f=self.amended();f['correction_reconciliation'][field]=value
    p=v.validate_record(parse(rows()),f,'2026-11-05')
    self.assertEqual(p['status'],'HOLD');self.assertFalse(p['values_reconciled'])
class OfficialSourceRetry(unittest.TestCase):
 def test_transient_source_failure_is_retried_without_marking_missing_evidence_valid(self):
  seen=[]
  def fetch(*args,**kwargs):
   seen.append(1)
   return {'000001':{'verified':len(seen)>1,'error':None if len(seen)>1 else 'SOURCE_UNAVAILABLE'}}
  result=v.collect_with_retry({'000001':{}},Path('/tmp/kr4-controlled-retry'),1,'2026-11-05',collector=fetch,pause=lambda _:None)
  self.assertEqual(len(seen),2);self.assertTrue(result['000001']['verified'])
 def test_persistent_failure_stays_explicit_after_bounded_retries(self):
  seen=[]
  def fetch(*args,**kwargs):
   seen.append(1);return {'000001':{'verified':False,'error':'COLLECTION_ERROR'}}
  result=v.collect_with_retry({'000001':{}},Path('/tmp/kr4-controlled-retry'),1,'2026-11-05',collector=fetch,pause=lambda _:None)
  self.assertEqual(len(seen),3);self.assertEqual(result['000001']['error'],'COLLECTION_ERROR');self.assertFalse(result['000001']['verified'])
 def test_known_absence_is_not_hammered_as_transient_network_failure(self):
  seen=[]
  def fetch(*args,**kwargs):
   seen.append(1);return {'000001':{'verified':False,'error':'REPORT_PERIOD_NOT_FOUND'}}
  v.collect_with_retry({'000001':{}},Path('/tmp/kr4-controlled-retry'),1,'2026-11-05',collector=fetch,pause=lambda _:None)
  self.assertEqual(len(seen),1)
class AuthenticatedCollectionCli(unittest.TestCase):
 def test_cli_reconciles_actual_correction_before_classification_without_mutating_quant(self):
  import tempfile
  import reconcile_financial_corrections as reconciliation
  import opendart_filing_provenance as authenticated
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory); q=root/'quant.json'; out=root/'facts.json'; key=root/'private-key'
   codes=[f'{number:06d}' for number in range(2649)]
   quant={'snapshot_date_kst':'2026-11-05','records':{code:{} for code in codes}}
   q.write_text(json.dumps(quant)); before=q.read_bytes(); key.write_text('CONTROLLED_CLI_SECRET'); (root/'cache').mkdir()
   raw={code:dict(parse(rows()),issuer_code=code) for code in codes}
   filings={code:provenance() for code in codes}
   filings['000001']['correction_status']='LATEST_CORRECTION'
   corrected=json.loads(json.dumps(filings)); corrected['000001']=AmendmentProof().amended()
   def reconcile(actual_raw,actual_filings,cache,credential):
    self.assertEqual(actual_raw,raw); self.assertEqual(actual_filings,filings)
    self.assertEqual(credential,'CONTROLLED_CLI_SECRET')
    return corrected
   issuers=[SimpleNamespace(code=code,name='검증회사',sector='제조업') for code in codes]
   argv=['collector','--quant',str(q),'--out',str(out),'--cache',str(root/'cache'),'--api-key-file',str(key)]
   with patch.object(sys,'argv',argv),patch.object(v.base,'load_issuers',return_value=issuers),patch.object(v,'download_sources',return_value=[]),patch.object(v,'parse_bulk_sources',return_value=raw),patch.object(authenticated,'collect_authenticated_filing_provenance',return_value=filings),patch.object(reconciliation,'reconcile_corrections',side_effect=reconcile) as reconcile_call:
    v.main()
   reconcile_call.assert_called_once()
   result=json.loads(out.read_text())
   self.assertEqual(result['records']['000001']['status'],'PASS')
   self.assertTrue(result['records']['000001']['values_reconciled'])
   self.assertEqual(q.read_bytes(),before)
   self.assertNotIn('CONTROLLED_CLI_SECRET',out.read_text())

if __name__=='__main__':unittest.main(verbosity=2)
