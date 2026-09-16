from pathlib import Path

p = Path("scripts/stage4567_ci.sh")
s = p.read_text(encoding="utf-8")
anchor = s.find("collect_financial_safety.py")
if anchor < 0:
    raise SystemExit("financial-safety collector anchor not found")

candidates = []
for marker in ("python3 - <<'PY'", "python - <<'PY'"):
    pos = s.find(marker, anchor)
    if pos >= 0:
        candidates.append(pos)
if not candidates:
    raise SystemExit("financial-safety post-collector python gate not found")
py_start = min(candidates)
line_start = s.rfind("\n", 0, py_start) + 1
indent = s[line_start:py_start]
end = s.find("\nPY\n", py_start)
if end < 0:
    raise SystemExit("financial-safety gate terminator not found")
end += len("\nPY\n")

gate = r'''import json
from pathlib import Path

def normalize_counts(d):
    if not isinstance(d, dict):
        return None
    lower = {str(k).lower(): v for k, v in d.items()}
    aliases = {
        "pass": ("pass",),
        "fail": ("fail", "reject"),
        "hold": ("hold",),
        "not_applicable": ("not_applicable", "not applicable", "n/a", "na"),
    }
    out = {}
    for dest, names in aliases.items():
        hit = next((lower[n] for n in names if n in lower), None)
        if hit is None:
            return None
        try:
            out[dest] = int(hit)
        except Exception:
            return None
    total_hit = lower.get("total")
    out["total"] = int(total_hit) if total_hit is not None else sum(out.values())
    return out

def walk(obj):
    if isinstance(obj, dict):
        hit = normalize_counts(obj)
        if hit:
            yield hit
        for v in obj.values():
            yield from walk(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from walk(v)

paths = [
    Path("/tmp/financial_safety.json"),
    Path("/tmp/financial_safety_snapshot.json"),
    Path("evidence/financial_stability_snapshot.json"),
    Path("evidence/financial_safety.json"),
    Path("evidence/financial_safety_snapshot.json"),
]
paths.extend(sorted(Path("/tmp").glob("*financial*safety*.json")))
paths.extend(sorted(Path("evidence").glob("*financial*stability*.json")))
paths.extend(sorted(Path("evidence").glob("*financial*safety*.json")))

seen = set()
selected = None
selected_path = None
for path in paths:
    if path in seen or not path.is_file():
        continue
    seen.add(path)
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        continue
    for counts in walk(doc):
        if counts["total"] == 2649:
            selected = counts
            selected_path = path
            break
    if selected:
        break

assert selected is not None, "financial-stability status summary not found"
counts = selected
assert sum(counts[k] for k in ("pass", "fail", "hold", "not_applicable")) == counts["total"], counts

q = json.loads(Path("evidence/real_quant_snapshot.json").read_text(encoding="utf-8"))
expected_na = int(q["reason_counts"]["m02"]["FINANCIAL_SECTOR_EXCLUDED"])
assert counts["not_applicable"] == expected_na, (counts, expected_na)
non_financial = counts["total"] - counts["not_applicable"]
assert counts["pass"] + counts["fail"] >= int(non_financial * 0.95), counts
assert counts["hold"] <= max(50, int(non_financial * 0.05)), counts
assert counts["pass"] > 0 and counts["fail"] > 0, counts
print("FINANCIAL_STABILITY_STRUCTURAL_GATE_PASS", str(selected_path), counts)
'''

replacement = indent + "python3 - <<'PY'\n" + gate + "\nPY\n"
s2 = s[:line_start] + replacement + s[end:]
p.write_text(s2, encoding="utf-8")
print("FINANCIAL_STABILITY_GATE_PATCHED")
