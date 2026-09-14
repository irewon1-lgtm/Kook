"""Modify real reference source, run unchanged tests, retain every mutant and log."""
import json,os,shutil,subprocess,sys,tempfile,time,hashlib
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1]
# id, failure class, exact source replacement
MUTATIONS=[
('M01','성장률 음수 분모 허용','number(cur)/positive(old)','number(cur)/abs(number(old))'),
('M02','낙폭 부호 역전','return (h-c)/h*100','return (c-h)/h*100'),
('M03','CAPEX를 차감 대신 가산','(number(cfo)-p-i)','(number(cfo)+p+i)'),
('M04','마진 변화 방향 역전','return margin(p,r)-margin(op,orr)','return margin(op,orr)-margin(p,r)'),
('M05','음수 EBITDA 우량 판정','positive(number(op)+da)','abs(number(op)+da)'),
('M06','ROIC 세후 계산 오류','op*(1-rate)/base*100','op*(1+rate)/base*100'),
('M07','연결 별도 혼합',"if a.scope!=b.scope:","if False and a.scope!=b.scope:"),
('M08','통화 혼합',"if a.currency!=b.currency:","if False and a.currency!=b.currency:"),
('M09','미래 자료 사용','if f.published_at<=at: out.append(f)','if True: out.append(f)'),
('M10','직접 분기값 이중 차감','if not current.cumulative: return number(current.value)','if not current.cumulative: return number(current.value)-(number(previous.value) if previous else 0)'),
('M11','희석 평균주식수 기간 비가중','return (number(avg_ytd)*days_ytd-number(avg_previous)*days_previous)/(days_ytd-days_previous)','return number(avg_ytd)-number(avg_previous)'),
('M12','ID 대신 값 위치 사용',"'value':raw.get(m)","'value':list(raw.values())[idx] if idx<len(raw) else None"),
('M13','점수 방향 뒤집힘','arr=sorted(DIRECTIONS[m]*x for x in values.values())','arr=sorted(-DIRECTIONS[m]*x for x in values.values())'),
('M14','결측 개별 재가중',"'score':lower if complete else None", "'score':lower/coverage if coverage else None"),
('M15','검색마다 점수 재계산','out=[self.score(i) for i in ids]','out=[RankSnapshot({j:self.raw[j] for j in ids},self.weights,min_n=2).score(i) for i in ids]'),
('M16','없는 값도 백분위 분모 포함','n=len(arr)','n=len(raw)'),
('M17','오래된 분석 매수표시','elif not fresh:','elif False and not fresh:'),
('M18','중대 위험 신호 무시',"if critical: label='CRITICAL_RISK'","if False and critical: label='CRITICAL_RISK'"),
('M19','가짜 도메인 허용','p.hostname in allowed_hosts','any(host in (p.hostname or "") for host in allowed_hosts)'),
('M20','승인 금액 상한 무시','if used+units>limit:','if False and used+units>limit:'),
('M21','이전 버전이 새 버전 덮어씀','if old and old[0]>=seq:','if False and old and old[0]>=seq:'),
('M22','만료 작업자 펜싱 제거',"or row[1]!=token or row[2]<=now",''),
('M23','원자적 저장 롤백 제거',"self.db.execute('ROLLBACK'); raise","self.db.execute('COMMIT'); raise"),
('M24','사용권 미승인 허용',"license_doc.get('status')=='APPROVED' and ",''),
('M25','정정공시 보고서 갱신 빠짐',"'CORRECTION':{'Q03','Q04','Q05','Q06','Q07','Q08','Q09','Q10','RANK','REPORT_REVIEW','DECISION','FILINGS'}", "'CORRECTION':{'FILINGS'}"),
('M26','순현금 초과 가점',"return max(x,0.0) if metric=='Q10' else x",'return x'),
('M27','영문 종목코드 거부',"any(c not in '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ' for c in code)","not code.isdigit()"),
('M28','기사 기간 대조 제거',"if claim.get('period')!=source.get('period'):","if False and claim.get('period')!=source.get('period'):"),
('M29','본문 미열람 숨김',"if source.get('read_level')!='FULL' and claim.get('kind')!='METADATA':","if False and source.get('read_level')!='FULL' and claim.get('kind')!='METADATA':"),
('M30','시차 문자열 비교 회귀',"if instant(source.get('published_at',''))>instant(asof):", "if source.get('published_at','9999')>asof:"),
('M31','정정공시 회사 혼합 회귀',"            compatible(facts[0],f)\n",''),
('M32','금융사와 제조업 비교 혼합',"and m['model']==me['model'] and m[field]==me[field]","and (field=='model' or m[field]==me[field])"),
]
if __name__=='__main__':
    source=(ROOT/'engine/core.py').read_text();folder=ROOT/'evidence/mutations';folder.mkdir(exist_ok=True)
    checkpoint=ROOT/'evidence/mutation_checkpoint.json'
    results=json.loads(checkpoint.read_text()) if checkpoint.exists() else []
    done={r['id'] for r in results}
    batch_limit=int(sys.argv[1]) if len(sys.argv)>1 else 100
    started=0
    for ident,label,old,new in MUTATIONS:
        if ident in done: continue
        if started>=batch_limit: break
        started+=1
        assert old in source,(ident,'mutation target missing')
        mutant=source.replace(old,new)
        if ident=='M12': mutant=mutant.replace('for m in IDS]\n','for idx,m in enumerate(IDS)]\n')
        (folder/f'{ident}.py.txt').write_text(mutant)
        try: compile(mutant,ident,'exec')
        except SyntaxError as exc:
            results.append({'id':ident,'failure_type':label,'status':'INVALID_MUTANT','detail':str(exc)});continue
        with tempfile.TemporaryDirectory() as td:
            t=Path(td);shutil.copytree(ROOT/'engine',t/'engine',ignore=shutil.ignore_patterns('__pycache__'));shutil.copytree(ROOT/'tests',t/'tests',ignore=shutil.ignore_patterns('__pycache__'))
            (t/'engine/core.py').write_text(mutant)
            proc=subprocess.run([sys.executable,'tests/run_all.py','result.json'],cwd=t,capture_output=True,text=True,timeout=30)
            (folder/f'{ident}.log').write_text(proc.stdout+proc.stderr)
            rr=json.loads((t/'result.json').read_text()) if (t/'result.json').exists() else {}
            failures=[r['id'] for r in rr.get('records',[]) if r['status']!='PASS']
            status='KILLED' if proc.returncode!=0 and rr.get('tests_run')==138 and failures else ('SURVIVED' if proc.returncode==0 else 'INVALID_RUN')
            results.append({'id':ident,'failure_type':label,'status':status,'tests_run':rr.get('tests_run'),'failed_tests':failures,'mutant_sha256':hashlib.sha256(mutant.encode()).hexdigest()})
        checkpoint.write_text(json.dumps(results,ensure_ascii=False,indent=2))
    out={'time_utc':datetime.now(timezone.utc).isoformat(),'scope':'32 intentionally corrupted OFFLINE reference implementations; original test suite unchanged','planned':len(MUTATIONS),'complete':len(results)==len(MUTATIONS),'total':len(results),'killed':sum(r['status']=='KILLED' for r in results),'survived':sum(r['status']=='SURVIVED' for r in results),'invalid':sum(r['status'].startswith('INVALID') for r in results),'results':results}
    (ROOT/'evidence/mutation_results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
    print(json.dumps({k:v for k,v in out.items() if k!='results'},ensure_ascii=False,indent=2))
    for r in results:
        if r['status']!='KILLED':print(r)
    sys.exit(0 if out['killed']==out['total'] else 1)
