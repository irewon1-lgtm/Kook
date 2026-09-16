from pathlib import Path
import sys

path = Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/stage4567_direct_release.sh')
text = path.read_text(encoding='utf-8')
start = text.find('promote() {\n')
end = text.find('\nmanifest() {', start)
assert start >= 0 and end > start, (start, end)
new_promote = r'''promote() {
  test -s dist/KR4.apk; test -s evidence/real_quant_snapshot.json; test -s evidence/final_candidates.json
  git fetch origin main --quiet
  test "$(git rev-parse origin/main)" = "$GITHUB_SHA"
  git cat-file -e "${STABLE_BASE_SHA}^{commit}"
  git show "${STABLE_BASE_SHA}:.github/workflows/android-build.yml" > .github/workflows/android-build.yml
  rm -f .github/stage4567_payload.part* .github/patch_stage4567_*.py .github/stage4567_trigger_v2 .github/stage4567_direct_release.sh scripts/stage4567_ci.sh
  git restore --source=HEAD --worktree --staged -- evidence/stress_results.json 2>/dev/null || true
  python3 - <<'PYGATE'
import subprocess
from pathlib import Path
manifest=Path('/tmp/stage4567_payload_changed_paths.txt')
assert manifest.is_file(), 'payload path manifest missing'
payload_allowed={x.strip() for x in manifest.read_text(encoding='utf-8').splitlines() if x.strip()}
allowed_exact={
    'evidence/real_quant_snapshot.json',
    'evidence/final_candidates.json',
    '.github/workflows/android-build.yml',
}
bootstrap_prefixes=(
    '.github/stage4567_',
    '.github/patch_stage4567_',
)
ephemeral_prefixes=(
    '.gradle/',
    'app/build/',
    'dist/',
    'evidence/ci/stage4567/',
)
ephemeral_exact={'evidence/stress_results.json'}
out=subprocess.check_output(['git','status','--porcelain=v1','-uall'],text=True)
paths=[]
for line in out.splitlines():
    raw=line[3:]
    if ' -> ' in raw:
        raw=raw.split(' -> ',1)[1]
    paths.append(raw.strip('"'))
bad=[]
for p in paths:
    if p in payload_allowed or p in allowed_exact or p in ephemeral_exact:
        continue
    if p.startswith(bootstrap_prefixes) or p.startswith(ephemeral_prefixes):
        continue
    bad.append(p)
assert not bad, ('UNEXPECTED_PROMOTION_PATHS',bad,paths,sorted(payload_allowed))
print('PROMOTION_PATH_GATE_PASS', {'status_paths':len(paths),'payload_allowed_count':len(payload_allowed),'ephemeral_ignored':sum(p in ephemeral_exact or p.startswith(ephemeral_prefixes) for p in paths)})
PYGATE
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  python3 - <<'PYSTAGE'
import subprocess
from pathlib import Path
manifest=Path('/tmp/stage4567_payload_changed_paths.txt')
payload_allowed={x.strip() for x in manifest.read_text(encoding='utf-8').splitlines() if x.strip()}
required={
    'evidence/real_quant_snapshot.json',
    'evidence/final_candidates.json',
    '.github/workflows/android-build.yml',
}
tracked=set(subprocess.check_output(['git','ls-files'],text=True).splitlines())
bootstrap={p for p in tracked if p.startswith('.github/stage4567_') or p.startswith('.github/patch_stage4567_')}
bootstrap.add('scripts/stage4567_ci.sh')
stage_candidates=payload_allowed | required | bootstrap
for p in sorted(stage_candidates):
    exists=Path(p).exists()
    is_tracked=p in tracked
    if exists or is_tracked:
        subprocess.run(['git','add','-A','--',p],check=True)
staged=set(subprocess.check_output(['git','diff','--cached','--name-only'],text=True).splitlines())
forbidden=[p for p in staged if p.startswith(('.gradle/','app/build/','dist/','evidence/ci/stage4567/')) or p=='evidence/stress_results.json']
assert not forbidden, ('FORBIDDEN_STAGED_PATHS',forbidden)
unexpected=staged-stage_candidates
assert not unexpected, ('UNEXPECTED_STAGED_PATHS',sorted(unexpected),sorted(stage_candidates))
for p in required:
    assert p in staged, ('REQUIRED_PROMOTION_PATH_NOT_STAGED',p,sorted(staged))
remaining_bootstrap=[p for p in subprocess.check_output(['git','ls-files'],text=True).splitlines() if p.startswith('.github/stage4567_') or p.startswith('.github/patch_stage4567_')]
assert not remaining_bootstrap, ('BOOTSTRAP_FILES_REMAIN_IN_INDEX',remaining_bootstrap)
print('PROMOTION_EXPLICIT_STAGE_PASS', {'staged':sorted(staged),'count':len(staged)})
PYSTAGE
  git diff --cached --check
  test -n "$(git diff --cached --name-only)"
  git commit -m "[skip ci] Promote validated Stage4567 stability and valuation pipeline"
  PROMOTED_SHA=$(git rev-parse HEAD)
  git push origin HEAD:main
  echo "PROMOTED_SHA=$PROMOTED_SHA" >> "$GITHUB_ENV"
  echo "DIRECT_MAIN_PROMOTION_PASS sha=$PROMOTED_SHA"
}
'''
text = text[:start] + new_promote + text[end:]
assert text.count('promote() {') == 1
assert 'PROMOTION_EXPLICIT_STAGE_PASS' in text
assert 'git add -A\n' not in text
path.write_text(text, encoding='utf-8')
print('PROMOTION_SAFE_STAGE_PATCH_PASS')
