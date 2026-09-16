from pathlib import Path
import sys

path = Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/stage4567_direct_release.sh')
text = path.read_text(encoding='utf-8')
old = '''  curl -fsS --get -H "Authorization: Bearer ${OIDC_TOKEN}" --data-urlencode "transport_key=${TRANSPORT_KEY}" "https://kr4-signing-vault-5yh3dd.v2.appdeploy.ai/api/signing-material" -o "$RUNNER_TEMP/kr4-encrypted-signing.json"\n  test -s "$RUNNER_TEMP/kr4-encrypted-signing.json"'''
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
  SIGNING_REQUEST="$RUNNER_TEMP/kr4-signing-request.json"
  OIDC_TOKEN="$OIDC_TOKEN" TRANSPORT_KEY="$TRANSPORT_KEY" SIGNING_REQUEST="$SIGNING_REQUEST" python3 - <<'PYREQ'
import json, os
p=os.environ['SIGNING_REQUEST']
with open(p,'w',encoding='utf-8') as f:
    json.dump({'token':os.environ['OIDC_TOKEN'],'transportKey':os.environ['TRANSPORT_KEY']},f,separators=(',',':'))
    f.write('\\n')
os.chmod(p,0o600)
PYREQ
  CURL_META=$(curl -sS -o "$RUNNER_TEMP/kr4-encrypted-signing.json" -w '%{http_code} %{content_type}' -H 'Content-Type: application/json' --data-binary @"$SIGNING_REQUEST" "https://kr4-signing-vault-5yh3dd.v2.appdeploy.ai/api/signing-material")
  HTTP_CODE=${CURL_META%% *}
  CONTENT_TYPE=${CURL_META#* }
  BODY_BYTES=$(wc -c < "$RUNNER_TEMP/kr4-encrypted-signing.json" 2>/dev/null || echo 0)
  BODY_SHA=$(sha256sum "$RUNNER_TEMP/kr4-encrypted-signing.json" 2>/dev/null | awk '{print $1}' || true)
  echo "SIGNING_VAULT_HTTP code=$HTTP_CODE content_type=$CONTENT_TYPE body_bytes=$BODY_BYTES body_sha256=$BODY_SHA"
  if [ "$HTTP_CODE" != '200' ]; then
    echo "SIGNING_VAULT_HTTP_FAIL code=$HTTP_CODE" >&2
    exit 1
  fi
  case "$CONTENT_TYPE" in application/json*) ;; *) echo "signing vault unexpected content-type: $CONTENT_TYPE" >&2; exit 1 ;; esac
  test -s "$RUNNER_TEMP/kr4-encrypted-signing.json"
  rm -f "$SIGNING_REQUEST"'''
count = text.count(old)
assert count == 1, f'expected exactly one GET signing block, found {count}'
text = text.replace(old, new)
assert '--get -H "Authorization: Bearer ${OIDC_TOKEN}"' not in text
assert '--data-binary @"$SIGNING_REQUEST"' in text
path.write_text(text, encoding='utf-8')
print('SIGNING_POST_DIAGNOSTIC_PATCH_PASS')
