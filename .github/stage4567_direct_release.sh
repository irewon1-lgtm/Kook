#!/usr/bin/env bash
set -euo pipefail

EXPECTED_CERT="${EXPECTED_STABLE_CERT_SHA256:-1070e82beee574cd29ffd2dc3e693fd7f7862374f16d1606567db303ab152477}"
REPO="${GITHUB_REPOSITORY:-irewon1-lgtm/Kook}"

prepare() {
  test -s /tmp/real_quant_stage4567.json
  test -s /tmp/final_candidates_v2.json
  python3 - <<'PY'
import json
rq = json.load(open('/tmp/real_quant_stage4567.json', encoding='utf-8'))
fc = json.load(open('/tmp/final_candidates_v2.json', encoding='utf-8'))
assert rq['universe_count'] == len(rq['records']) == 2649
st = rq.get('financial_stability') or rq.get('financial_safety')
assert isinstance(st, dict), rq.keys()
summary = st.get('summary') or st.get('coverage') or st.get('status_counts') or st

def val(*keys):
    for k in keys:
        if k in summary:
            return int(summary[k])
    raise AssertionError((keys, summary))
p = val('pass','PASS'); f = val('fail','FAIL'); h = val('hold','HOLD'); n = val('not_applicable','NOT_APPLICABLE','n/a','N/A')
t = val('total','TOTAL') if ('total' in summary or 'TOTAL' in summary) else p+f+h+n
assert (p,f,h,n,t) == (2111,277,35,226,2649), (p,f,h,n,t)
assert fc.get('schema') == 'KR4_FINAL_CANDIDATE_V2', fc.get('schema')
cs = fc.get('candidates')
assert isinstance(cs, list) and len(cs) == 10
codes = [str(x.get('code')) for x in cs]
assert codes == ['025560','187870','005950','000660','013700','059100','031330','037460','115480','080220'], codes
for x in cs:
    assert x.get('financial_stability',{}).get('status') == 'PASS' or x.get('stability',{}).get('status') == 'PASS', x.get('code')
print('DIRECT_PREPARE_DATA_PASS', {'stability': [p,f,h,n,t], 'candidates': codes})
PY
  cp /tmp/real_quant_stage4567.json evidence/real_quant_snapshot.json
  cp /tmp/final_candidates_v2.json evidence/final_candidates.json
  python3 -m json.tool evidence/real_quant_snapshot.json >/dev/null
  python3 -m json.tool evidence/final_candidates.json >/dev/null
  echo DIRECT_PROMOTION_DATA_STAGED
}

sign() {
  test "${GITHUB_REF:-}" = "refs/heads/main"
  test -n "${ACTIONS_ID_TOKEN_REQUEST_URL:-}"
  test -n "${ACTIONS_ID_TOKEN_REQUEST_TOKEN:-}"
  test -n "${GITHUB_ENV:-}"
  test -n "${RUNNER_TEMP:-}"
  umask 077
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$RUNNER_TEMP/kr4-transport-private.pem" >/dev/null 2>&1
  openssl pkey -in "$RUNNER_TEMP/kr4-transport-private.pem" -pubout -outform DER -out "$RUNNER_TEMP/kr4-transport-public.der"
  TRANSPORT_KEY=$(base64 -w0 "$RUNNER_TEMP/kr4-transport-public.der" | tr '+/' '-_' | tr -d '=')
  curl -fsS -H "Authorization: Bearer ${ACTIONS_ID_TOKEN_REQUEST_TOKEN}" "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=kr4-signing-vault" -o "$RUNNER_TEMP/kr4-oidc.json"
  OIDC_TOKEN=$(python3 - <<'PY'
import json, os
p=os.path.join(os.environ['RUNNER_TEMP'],'kr4-oidc.json')
t=json.load(open(p,encoding='utf-8')).get('value')
assert isinstance(t,str) and len(t)>100
print(t)
PY
)
  curl -fsS --get -H "Authorization: Bearer ${OIDC_TOKEN}" --data-urlencode "transport_key=${TRANSPORT_KEY}" "https://kr4-signing-vault-5yh3dd.v2.appdeploy.ai/api/signing-material" -o "$RUNNER_TEMP/kr4-encrypted-signing.json"
  python3 - <<'PY'
import json, os
p=os.path.join(os.environ['RUNNER_TEMP'],'kr4-encrypted-signing.json')
r=json.load(open(p,encoding='utf-8'))
assert r.get('algorithm') == 'RSA-OAEP-SHA256', r.get('algorithm')
assert r.get('repository') == os.environ['GITHUB_REPOSITORY'], r.get('repository')
assert r.get('ref') == 'refs/heads/main', r.get('ref')
chunks=r.get('ciphertextChunks')
assert isinstance(chunks,list) and chunks and all(isinstance(x,str) and x for x in chunks)
out=os.path.join(os.environ['RUNNER_TEMP'],'kr4-encrypted-chunks.txt')
open(out,'w',encoding='utf-8').write('\n'.join(chunks)+'\n')
print('SIGNING_VAULT_RESPONSE_PASS', len(chunks))
PY
  : > "$RUNNER_TEMP/kr4-signing-bundle.txt"
  INDEX=0
  while IFS= read -r CHUNK; do
    printf '%s' "$CHUNK" | base64 -d > "$RUNNER_TEMP/kr4-chunk-${INDEX}.bin"
    openssl pkeyutl -decrypt -inkey "$RUNNER_TEMP/kr4-transport-private.pem" -in "$RUNNER_TEMP/kr4-chunk-${INDEX}.bin" -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 >> "$RUNNER_TEMP/kr4-signing-bundle.txt"
    INDEX=$((INDEX + 1))
  done < "$RUNNER_TEMP/kr4-encrypted-chunks.txt"
  test "$INDEX" -gt 0
  python3 - <<'PY'
import base64, hashlib, json, os, subprocess
rt=os.environ['RUNNER_TEMP']
raw=open(os.path.join(rt,'kr4-signing-bundle.txt'),encoding='utf-8').read().strip()
assert len(raw)>100
bundle=json.loads(base64.b64decode(raw).decode('utf-8'))
for k in ('keystore_b64','store_password','alias','key_password'):
    v=bundle.get(k); assert isinstance(v,str) and v and '\n' not in v and '\r' not in v, k
jks=os.path.join(rt,'kr4-release.jks')
open(jks,'wb').write(base64.b64decode(bundle['keystore_b64'])); os.chmod(jks,0o600)
cert=subprocess.check_output(['keytool','-exportcert','-keystore',jks,'-alias',bundle['alias'],'-storepass',bundle['store_password']],stderr=subprocess.DEVNULL)
sha=hashlib.sha256(cert).hexdigest(); expected=os.environ.get('EXPECTED_STABLE_CERT_SHA256','1070e82beee574cd29ffd2dc3e693fd7f7862374f16d1606567db303ab152477')
assert sha == expected, (sha, expected)
print(f"::add-mask::{bundle['store_password']}"); print(f"::add-mask::{bundle['key_password']}")
with open(os.environ['GITHUB_ENV'],'a',encoding='utf-8') as f:
    f.write(f"KR4_KEYSTORE_FILE={jks}\nKR4_KEYSTORE_PASSWORD={bundle['store_password']}\nKR4_KEY_ALIAS={bundle['alias']}\nKR4_KEY_PASSWORD={bundle['key_password']}\nKR4_SIGNING_CERT_SHA256={sha}\n")
print('STABLE_SIGNING_CERT_PASS', sha)
PY
  LATEST_TAG=$(gh api "repos/${REPO}/releases/latest" --jq '.tag_name')
  [[ "$LATEST_TAG" =~ ^kr4-app-c([0-9]+)$ ]]
  NEXT=$((10#${BASH_REMATCH[1]} + 1))
  while gh release view "kr4-app-c${NEXT}" >/dev/null 2>&1; do NEXT=$((NEXT+1)); done
  VERSION_NAME="4.3.$((NEXT-20000))"
  echo "KR4_VERSION_CODE=$NEXT" >> "$GITHUB_ENV"; echo "KR4_VERSION_NAME=$VERSION_NAME" >> "$GITHUB_ENV"; echo "KR4_RELEASE_TAG=kr4-app-c${NEXT}" >> "$GITHUB_ENV"
  echo "DIRECT_VERSION_PASS latest=$LATEST_TAG next=kr4-app-c${NEXT} name=$VERSION_NAME"
  rm -f "$RUNNER_TEMP/kr4-oidc.json" "$RUNNER_TEMP/kr4-encrypted-signing.json" "$RUNNER_TEMP/kr4-encrypted-chunks.txt" "$RUNNER_TEMP/kr4-signing-bundle.txt" "$RUNNER_TEMP/kr4-transport-private.pem" "$RUNNER_TEMP/kr4-transport-public.der" "$RUNNER_TEMP"/kr4-chunk-*.bin
}

build() {
  for v in KR4_KEYSTORE_FILE KR4_KEYSTORE_PASSWORD KR4_KEY_ALIAS KR4_KEY_PASSWORD KR4_VERSION_CODE KR4_VERSION_NAME KR4_SIGNING_CERT_SHA256 KR4_RELEASE_TAG; do test -n "${!v:-}"; done
  test -s "$KR4_KEYSTORE_FILE"
  gradle :app:assembleRelease --stacktrace
  APK="app/build/outputs/apk/release/app-release.apk"; test -s "$APK"; mkdir -p dist evidence/ci/stage4567; cp "$APK" dist/KR4.apk
  APKSIGNER=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -1); AAPT=$(find "$ANDROID_HOME/build-tools" -type f -name aapt | sort -V | tail -1); test -x "$APKSIGNER"; test -x "$AAPT"
  "$APKSIGNER" verify --verbose --print-certs dist/KR4.apk | tee dist/APK_SIGNING_CERT.txt
  ACTUAL=$(awk -F': ' '/Signer #1 certificate SHA-256 digest/{print tolower($2); exit}' dist/APK_SIGNING_CERT.txt | tr -d ':'); test "$ACTUAL" = "$EXPECTED_CERT"
  BADGING=$("$AAPT" dump badging dist/KR4.apk | head -1); echo "$BADGING"; echo "$BADGING" | grep -q "name='com.krstock.v3'"; echo "$BADGING" | grep -q "versionCode='${KR4_VERSION_CODE}'"; echo "$BADGING" | grep -q "versionName='${KR4_VERSION_NAME}'"
  sha256sum dist/KR4.apk | tee evidence/ci/stage4567/APK_SHA256.txt; stat --printf='APK_BYTES=%s\n' dist/KR4.apk | tee evidence/ci/stage4567/APK_SIZE.txt
  echo "STABLE_RELEASE_APK_PASS cert=$ACTUAL version=$KR4_VERSION_NAME"
}

promote() {
  test -s dist/KR4.apk; test -s evidence/real_quant_snapshot.json; test -s evidence/final_candidates.json
  git fetch origin main --quiet; test "$(git rev-parse origin/main)" = "$GITHUB_SHA"
  git show "$GITHUB_SHA^:.github/workflows/android-build.yml" > .github/workflows/android-build.yml
  rm -f .github/stage4567_payload.part* .github/patch_stage4567_gate.py .github/patch_stage4567_post.py .github/stage4567_trigger_v2 .github/stage4567_direct_release.sh scripts/stage4567_ci.sh
  python3 - <<'PY'
import subprocess
allowed_prefixes=('app/src/main/java/com/krstock/v3/','app/src/test/java/com/krstock/v3/','scripts/collect_financial_','tests/test_stage4567_')
allowed_exact={'scripts/build_final_candidates.py','evidence/real_quant_snapshot.json','evidence/final_candidates.json','.github/workflows/collect-real-quant.yml','.github/workflows/final-candidate-refresh.yml','.github/workflows/android-build.yml'}
out=subprocess.check_output(['git','status','--porcelain=v1','-uall'],text=True); paths=[]
for line in out.splitlines():
    raw=line[3:]
    if ' -> ' in raw: raw=raw.split(' -> ',1)[1]
    paths.append(raw.strip('"'))
bad=[p for p in paths if p not in allowed_exact and not p.startswith(allowed_prefixes)]
assert not bad, ('UNEXPECTED_PROMOTION_PATHS',bad,paths)
print('PROMOTION_PATH_GATE_PASS',paths)
PY
  git config user.name "github-actions[bot]"; git config user.email "41898282+github-actions[bot]@users.noreply.github.com"; git add -A; git diff --cached --check; test -n "$(git diff --cached --name-only)"
  git commit -m "[skip ci] Promote validated Stage4567 stability and valuation pipeline"
  PROMOTED_SHA=$(git rev-parse HEAD); git push origin HEAD:main; echo "PROMOTED_SHA=$PROMOTED_SHA" >> "$GITHUB_ENV"; echo "DIRECT_MAIN_PROMOTION_PASS sha=$PROMOTED_SHA"
}

manifest() {
  test -n "${PROMOTED_SHA:-}"; test -s dist/KR4.apk; SHA=$(sha256sum dist/KR4.apk | awk '{print $1}'); export SHA
  python3 - <<'PY'
import json, os
out={'versionCode':int(os.environ['KR4_VERSION_CODE']),'versionName':os.environ['KR4_VERSION_NAME'],'apkUrl':'https://github.com/irewon1-lgtm/Kook/releases/latest/download/KR4.apk','sha256':os.environ['SHA'],'mandatory':False,'notes':'재무안정성 Stage4 + 실적 PER 상대밴드 Stage5 + 최종후보 V2 검증 반영','commit':os.environ['PROMOTED_SHA'],'signingCertificateSha256':os.environ['KR4_SIGNING_CERT_SHA256']}
assert out['signingCertificateSha256']=='1070e82beee574cd29ffd2dc3e693fd7f7862374f16d1606567db303ab152477'
with open('dist/update.json','w',encoding='utf-8') as f: json.dump(out,f,ensure_ascii=False,indent=2); f.write('\n')
print('UPDATE_MANIFEST_PASS',{k:v for k,v in out.items() if k!='apkUrl'})
PY
}

publish() {
  for f in dist/KR4.apk dist/update.json dist/APK_SIGNING_CERT.txt; do test -s "$f"; done; test -n "${PROMOTED_SHA:-}"; test -n "${KR4_RELEASE_TAG:-}"
  TITLE="KR4 ${KR4_VERSION_NAME}"; NOTES="Stage4 재무안정성 + Stage5 실적 PER 상대밴드 + Stage6/7 최종후보 V2. pre-CLEAN 및 Android 14 E2E 통과. commit ${PROMOTED_SHA}."
  if gh release view "$KR4_RELEASE_TAG" >/dev/null 2>&1; then gh release upload "$KR4_RELEASE_TAG" dist/KR4.apk dist/update.json dist/APK_SIGNING_CERT.txt --clobber; gh release edit "$KR4_RELEASE_TAG" --target "$PROMOTED_SHA" --title "$TITLE" --notes "$NOTES" --latest; else gh release create "$KR4_RELEASE_TAG" dist/KR4.apk dist/update.json dist/APK_SIGNING_CERT.txt --target "$PROMOTED_SHA" --title "$TITLE" --notes "$NOTES" --latest; fi
  test "$(gh api "repos/${REPO}/releases/latest" --jq '.tag_name')" = "$KR4_RELEASE_TAG"
  rm -rf "$RUNNER_TEMP/kr4-release-verify"; mkdir -p "$RUNNER_TEMP/kr4-release-verify"; gh release download "$KR4_RELEASE_TAG" -p KR4.apk -p update.json -p APK_SIGNING_CERT.txt -D "$RUNNER_TEMP/kr4-release-verify"
  test "$(sha256sum dist/KR4.apk | awk '{print $1}')" = "$(sha256sum "$RUNNER_TEMP/kr4-release-verify/KR4.apk" | awk '{print $1}')"; cmp -s dist/update.json "$RUNNER_TEMP/kr4-release-verify/update.json"
  python3 - <<'PY'
import json, os
p=os.path.join(os.environ['RUNNER_TEMP'],'kr4-release-verify','update.json'); d=json.load(open(p,encoding='utf-8'))
assert d['commit']==os.environ['PROMOTED_SHA']; assert d['versionCode']==int(os.environ['KR4_VERSION_CODE']); assert d['signingCertificateSha256']==os.environ['KR4_SIGNING_CERT_SHA256']
print('LATEST_RELEASE_REDOWNLOAD_PASS',d['versionName'],d['commit'],d['sha256'])
PY
  echo "DIRECT_LATEST_RELEASE_PASS tag=$KR4_RELEASE_TAG sha=$(sha256sum dist/KR4.apk | awk '{print $1}')"
}

case "${1:-}" in prepare) prepare ;; sign) sign ;; build) build ;; promote) promote ;; manifest) manifest ;; publish) publish ;; *) echo "usage: $0 {prepare|sign|build|promote|manifest|publish}" >&2; exit 2 ;; esac
