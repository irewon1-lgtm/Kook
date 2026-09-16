from pathlib import Path
import sys

path = Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/stage4567_direct_release.sh')
text = path.read_text(encoding='utf-8')
old = '''  curl -fsS --get -H "Authorization: Bearer ${OIDC_TOKEN}" --data-urlencode "transport_key=${TRANSPORT_KEY}" "https://kr4-signing-vault-5yh3dd.v2.appdeploy.ai/api/signing-material" -o "$RUNNER_TEMP/kr4-encrypted-signing.json"\n  test -s "$RUNNER_TEMP/kr4-encrypted-signing.json"'''
new = '''  SIGNING_REQUEST="$RUNNER_TEMP/kr4-signing-request.json"\n  OIDC_TOKEN="$OIDC_TOKEN" TRANSPORT_KEY="$TRANSPORT_KEY" SIGNING_REQUEST="$SIGNING_REQUEST" python3 - <<'PYREQ'\nimport json, os\np=os.environ['SIGNING_REQUEST']\nwith open(p,'w',encoding='utf-8') as f:\n    json.dump({'token':os.environ['OIDC_TOKEN'],'transportKey':os.environ['TRANSPORT_KEY']},f,separators=(',',':'))\n    f.write('\\n')\nos.chmod(p,0o600)\nPYREQ\n  CURL_META=$(curl -sS -o "$RUNNER_TEMP/kr4-encrypted-signing.json" -w '%{http_code} %{content_type}' -H 'Content-Type: application/json' --data-binary @"$SIGNING_REQUEST" "https://kr4-signing-vault-5yh3dd.v2.appdeploy.ai/api/signing-material")\n  HTTP_CODE=${CURL_META%% *}\n  CONTENT_TYPE=${CURL_META#* }\n  test "$HTTP_CODE" = '200'\n  case "$CONTENT_TYPE" in application/json*) ;; *) echo "signing vault unexpected content-type: $CONTENT_TYPE" >&2; exit 1 ;; esac\n  test -s "$RUNNER_TEMP/kr4-encrypted-signing.json"\n  rm -f "$SIGNING_REQUEST"'''
count = text.count(old)
assert count == 1, f'expected exactly one GET signing block, found {count}'
text = text.replace(old, new)
assert '--get -H "Authorization: Bearer ${OIDC_TOKEN}"' not in text
assert "--data-binary @\"$SIGNING_REQUEST\"" in text
path.write_text(text, encoding='utf-8')
print('SIGNING_POST_PATCH_PASS')
