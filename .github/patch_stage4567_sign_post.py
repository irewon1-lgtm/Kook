from pathlib import Path
import sys

path = Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/stage4567_direct_release.sh')
text = path.read_text(encoding='utf-8')
old = '''  curl -fsS --get -H "Authorization: Bearer ${OIDC_TOKEN}" --data-urlencode "transport_key=${TRANSPORT_KEY}" "https://kr4-signing-vault-5yh3dd.v2.appdeploy.ai/api/signing-material" -o "$RUNNER_TEMP/kr4-encrypted-signing.json"
  test -s "$RUNNER_TEMP/kr4-encrypted-signing.json"'''
new = '''  OIDC_TOKEN="$OIDC_TOKEN" python3 - <<'PYCLAIMS'
import base64, json, os
token=os.environ['OIDC_TOKEN']
parts=token.split('.')
assert len(parts)==3
p=parts[1].replace('-','+').replace('_','/')
p += '='*((4-len(p)%4)%4)
claims=json.loads(base64.b64decode(p).decode('utf-8'))
safe={k:claims.get(k) for k in ('iss','aud','sub','repository','repository_id','repository_owner','repository_owner_id','ref','event_name','workflow','workflow_ref','job_workflow_ref')}
print('OIDC_SAFE_CLAIMS', json.dumps(safe, ensure_ascii=False, sort_keys=True))
PYCLAIMS
  RELAY_URL_FILE="$RUNNER_TEMP/kr4-relay-url.txt"
  OIDC_TOKEN="$OIDC_TOKEN" TRANSPORT_KEY="$TRANSPORT_KEY" RELAY_URL_FILE="$RELAY_URL_FILE" python3 - <<'PYURL'
import os, urllib.parse
fragment=urllib.parse.urlencode(
    {'relay_token':os.environ['OIDC_TOKEN'], 'transport_key':os.environ['TRANSPORT_KEY']},
    quote_via=urllib.parse.quote,
)
url='https://kr4-signing-vault-5yh3dd.v2.appdeploy.ai/#'+fragment
with open(os.environ['RELAY_URL_FILE'],'w',encoding='utf-8') as f:
    f.write(url)
os.chmod(os.environ['RELAY_URL_FILE'],0o600)
print('SIGNING_BROWSER_RELAY_URL_READY')
PYURL
  CHROME=$(command -v google-chrome || command -v chromium || command -v chromium-browser || true)
  test -n "$CHROME"
  RELAY_URL=$(cat "$RELAY_URL_FILE")
  "$CHROME" --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --virtual-time-budget=15000 --dump-dom "$RELAY_URL" > "$RUNNER_TEMP/kr4-relay.html"
  rm -f "$RELAY_URL_FILE"
  RELAY_HTML="$RUNNER_TEMP/kr4-relay.html" RELAY_JSON="$RUNNER_TEMP/kr4-encrypted-signing.json" python3 - <<'PYRELAY'
import html as html_lib, json, os, re
src=open(os.environ['RELAY_HTML'],encoding='utf-8',errors='replace').read()
m=re.search(r'<pre[^>]*id=["\\\']relay-output["\\\'][^>]*>(.*?)</pre>',src,re.I|re.S)
assert m, 'relay_output_missing'
text=re.sub(r'<[^>]+>','',m.group(1))
text=html_lib.unescape(text).strip()
assert text and text != 'WORKING', 'relay_not_completed'
doc=json.loads(text)
assert not doc.get('error'), doc
for key in ('algorithm','ciphertextChunks','repository','ref'):
    assert key in doc, (key, doc.keys())
assert doc['algorithm']=='RSA-OAEP-SHA256'
assert doc['repository']=='irewon1-lgtm/Kook'
assert doc['ref']=='refs/heads/main'
chunks=doc['ciphertextChunks']
assert isinstance(chunks,list) and chunks and all(isinstance(x,str) and x for x in chunks)
with open(os.environ['RELAY_JSON'],'w',encoding='utf-8') as f:
    json.dump(doc,f,separators=(',',':'))
    f.write('\\n')
print('SIGNING_BROWSER_RELAY_PASS', len(chunks))
PYRELAY
  rm -f "$RUNNER_TEMP/kr4-relay.html"
  test -s "$RUNNER_TEMP/kr4-encrypted-signing.json"'''
count = text.count(old)
assert count == 1, f'expected exactly one GET signing block, found {count}'
text = text.replace(old, new)
assert '--get -H "Authorization: Bearer ${OIDC_TOKEN}"' not in text
assert 'SIGNING_BROWSER_RELAY_PASS' in text
path.write_text(text, encoding='utf-8')
print('SIGNING_BROWSER_RELAY_PATCH_PASS')
