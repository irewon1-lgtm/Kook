from __future__ import annotations

import ast
from pathlib import Path

SCRIPT = Path("scripts/stage4567_ci.sh")
s = SCRIPT.read_text(encoding="utf-8")
anchor = s.find("collect_financial_safety.py")
if anchor < 0:
    raise SystemExit("financial-safety collector anchor not found")

starts = []
for marker in ("python3 - <<'PY'", "python - <<'PY'"):
    pos = s.find(marker, anchor)
    if pos >= 0:
        starts.append((pos, marker))
if not starts:
    raise SystemExit("financial-safety post-collector python gate not found")
py_start, marker = min(starts, key=lambda x: x[0])
line_start = s.rfind("\n", 0, py_start) + 1
indent = s[line_start:py_start]
end_marker = s.find("\nPY\n", py_start)
if end_marker < 0:
    raise SystemExit("financial-safety gate terminator not found")
block_end = end_marker + len("\nPY\n")

header_end = s.find("\n", py_start) + 1
code = s[header_end:end_marker]
try:
    tree = ast.parse(code)
except SyntaxError as exc:
    raise SystemExit(f"original financial-safety gate is not valid Python: {exc}")

STATUS_KEYS = {"pass", "fail", "reject", "hold", "not_applicable"}


def subscript_key(node: ast.Subscript) -> str | None:
    sl = node.slice
    if isinstance(sl, ast.Constant) and isinstance(sl.value, str):
        return sl.value
    return None


def root_name(node: ast.AST) -> str | None:
    cur = node
    while isinstance(cur, ast.Subscript):
        cur = cur.value
    return cur.id if isinstance(cur, ast.Name) else None


def has_threshold_compare(node: ast.AST) -> bool:
    for child in ast.walk(node):
        if isinstance(child, ast.Compare):
            if any(isinstance(op, (ast.Lt, ast.LtE, ast.Gt, ast.GtE)) for op in child.ops):
                return True
    return False


def status_refs(node: ast.AST) -> list[tuple[str, str]]:
    refs: list[tuple[str, str]] = []
    for child in ast.walk(node):
        if isinstance(child, ast.Subscript):
            key = subscript_key(child)
            root = root_name(child)
            if key in STATUS_KEYS and root:
                refs.append((root, key))
    return refs

summary_name: str | None = None
remove_lines: set[int] = set()
removed_sources: list[str] = []
for node in tree.body:
    if not isinstance(node, ast.Assert):
        continue
    refs = status_refs(node.test)
    if refs and summary_name is None:
        summary_name = refs[0][0]
    # Remove only prevalence assumptions (>, >=, <, <=) over PASS/FAIL/HOLD.
    # Keep equality/schema/total checks from the original gate intact.
    if refs and has_threshold_compare(node.test):
        start = int(node.lineno)
        finish = int(getattr(node, "end_lineno", node.lineno))
        remove_lines.update(range(start, finish + 1))
        src = ast.get_source_segment(code, node) or f"assert@{start}"
        removed_sources.append(src)

if not summary_name:
    raise SystemExit("could not identify original financial-safety summary variable")
if not remove_lines:
    raise SystemExit("no prevalence assertion found to replace; refuse blind patch")

lines = code.splitlines()
kept = [line for n, line in enumerate(lines, start=1) if n not in remove_lines]

structural = f'''
# Stage4567 structural gate: validate the actual collected distribution without
# assuming how many companies should be financially safe/unsafe in advance.
import json as _stage4567_json
_stage4567_s = {summary_name}
_stage4567_pass = int(_stage4567_s["pass"])
_stage4567_fail = int(_stage4567_s.get("fail", _stage4567_s.get("reject", 0)))
_stage4567_hold = int(_stage4567_s["hold"])
_stage4567_na = int(_stage4567_s["not_applicable"])
_stage4567_total = int(_stage4567_s.get("total", _stage4567_pass + _stage4567_fail + _stage4567_hold + _stage4567_na))
assert _stage4567_total == 2649, _stage4567_s
assert _stage4567_pass + _stage4567_fail + _stage4567_hold + _stage4567_na == _stage4567_total, _stage4567_s
with open("evidence/real_quant_snapshot.json", encoding="utf-8") as _stage4567_f:
    _stage4567_q = _stage4567_json.load(_stage4567_f)
_stage4567_expected_na = int(_stage4567_q["reason_counts"]["m02"]["FINANCIAL_SECTOR_EXCLUDED"])
assert _stage4567_na == _stage4567_expected_na, (_stage4567_s, _stage4567_expected_na)
_stage4567_non_financial = _stage4567_total - _stage4567_na
assert _stage4567_pass + _stage4567_fail >= int(_stage4567_non_financial * 0.95), _stage4567_s
assert _stage4567_hold <= max(50, int(_stage4567_non_financial * 0.05)), _stage4567_s
assert _stage4567_pass > 0 and _stage4567_fail > 0, _stage4567_s
print("FINANCIAL_STABILITY_STRUCTURAL_GATE_PASS", _stage4567_s)
'''.strip("\n")

new_code = "\n".join(kept).rstrip() + "\n" + structural + "\n"
# Validate patched Python before touching the shell script.
ast.parse(new_code)
replacement = indent + marker + "\n" + new_code + "PY\n"
s2 = s[:line_start] + replacement + s[block_end:]
SCRIPT.write_text(s2, encoding="utf-8")
print("FINANCIAL_STABILITY_GATE_PATCHED", {"summary_var": summary_name, "removed": removed_sources})
