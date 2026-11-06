#!/usr/bin/env python3
"""Build and execute original Android Kotlin sources in a separate JVM project."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True)
    for name, default in [('quant','real_quant_snapshot'),('financial','financial_safety_snapshot'),('candidates','final_candidates'),('history','quarterly_history')]:
        p.add_argument('--'+name,default=str(ROOT/'evidence'/f'{default}.json'))
    p.add_argument('--context-cache')
    p.add_argument('--collect-context',action='store_true')
    p.add_argument('--context-codes',default='')
    p.add_argument('--context-limit',type=int,default=10)
    p.add_argument('--context-concurrency',type=int,default=4)
    p.add_argument('--skip-build',action='store_true')
    p.add_argument('--context-refresh',action='store_true')
    args=p.parse_args()
    env=os.environ.copy()
    local=Path('/workspace/toolchains')
    if (local/'jdk-17.0.17+10').is_dir(): env['JAVA_HOME']=str(local/'jdk-17.0.17+10')
    if (local/'gradle-home').is_dir(): env['GRADLE_USER_HOME']=str(local/'gradle-home')
    gradle=os.environ.get('GRADLE') or shutil.which('gradle') or str(local/'gradle-8.7/bin/gradle')
    proxy=urlparse(env.get('HTTPS_PROXY') or env.get('HTTP_PROXY',''))
    java_options=[]
    if Path('/etc/ssl/certs/java/cacerts').exists(): java_options += ['-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
    if proxy.hostname:
        for scheme in ('https','http'):
            java_options += [f'-D{scheme}.proxyHost={proxy.hostname}',f'-D{scheme}.proxyPort={proxy.port or 80}']
    if not args.skip_build:
        with (ROOT/'web/oracle/.gradle-build.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            subprocess.run([gradle,*java_options,'--no-daemon','--max-workers=2','installDist'],cwd=ROOT/'web/oracle',env=env,check=True)
    # installDist starts a separate JVM; keep session proxy and CA trust there too.
    env['JAVA_OPTS']=' '.join(java_options)
    command=[str(ROOT/'web/oracle/build/install/kr4-original-source-oracle/bin/kr4-original-source-oracle'),'--root',str(ROOT),'--output',str(Path(args.output).resolve())]
    for key in ('quant','financial','candidates','history','context_cache','context_codes','context_limit','context_concurrency'):
        value=getattr(args,key)
        if value is not None: command += ['--'+key.replace('_','-'),str(value)]
    quant=json.loads(Path(args.quant).read_text())
    records_fingerprint=hashlib.sha256(json.dumps(quant['records'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
    command += ['--quant-records-sha256',records_fingerprint]
    if args.collect_context: command += ['--collect-context']
    if args.context_refresh: command += ['--context-refresh']
    subprocess.run(command,cwd=ROOT,env=env,check=True)

if __name__=='__main__': main()
