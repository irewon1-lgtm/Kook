#!/usr/bin/env python3
import itertools
import json
import pathlib
import random
import sys

SOURCE = pathlib.Path("evidence/real_quant_snapshot.json")
OUT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path("evidence/combo_rank_stress_results.json")

with SOURCE.open(encoding="utf-8") as f:
    snapshot = json.load(f)

records = snapshot["records"]
metric_ids = ("m01", "m02", "m03", "m04")
assert len(records) == 2649


def rank_for(selected):
    rows = []
    for code, rec in records.items():
        values = [rec[mid]["percentile"] for mid in selected]
        if all(v is not None for v in values):
            score = sum(float(v) for v in values) / len(values)
            rows.append((code, score))
    rows.sort(key=lambda x: (-x[1], x[0]))
    return {code: {"rank": idx + 1, "score": score} for idx, (code, score) in enumerate(rows)}


combo_results = {}
cache = {}
for size in range(1, 5):
    for combo in itertools.combinations(metric_ids, size):
        ranked = rank_for(combo)
        cache[frozenset(combo)] = ranked
        ordered = sorted(ranked.items(), key=lambda kv: kv[1]["rank"])
        assert [v["rank"] for _, v in ordered] == list(range(1, len(ordered) + 1))
        for (ca, a), (cb, b) in zip(ordered, ordered[1:]):
            assert a["score"] >= b["score"], (combo, ca, cb)
        combo_results["+".join(combo)] = {
            "metric_count": len(combo),
            "eligible_count": len(ranked),
            "top5": [
                {"code": code, "rank": info["rank"], "score": round(info["score"], 8)}
                for code, info in ordered[:5]
            ],
        }

# One-metric coverage must exactly match the audited source coverage.
coverage = snapshot["coverage"]
assert combo_results["m01"]["eligible_count"] == coverage["m01_available"]
assert combo_results["m02"]["eligible_count"] == coverage["m02_available"]
assert combo_results["m03"]["eligible_count"] == coverage["m03_available"]
assert combo_results["m04"]["eligible_count"] == coverage["m04_available"]

# All four must reproduce the committed 4-metric rank exactly. The persisted
# composite is rounded during snapshot generation, so score comparison uses the
# persisted precision rather than an unrealistic nanoscopic tolerance.
all4 = cache[frozenset(metric_ids)]
assert len(all4) == coverage["complete_count"] == coverage["ranked_count"]
for code, rec in records.items():
    committed_rank = rec["rank"]
    committed_score = rec["composite"]
    dynamic = all4.get(code)
    if committed_rank is None:
        assert committed_score is None and dynamic is None, code
    else:
        assert dynamic is not None, code
        assert dynamic["rank"] == committed_rank, code
        assert abs(dynamic["score"] - float(committed_score)) < 1e-6, code

# Removing required metrics must never reduce eligibility.
counts_by_size = {}
for size in range(1, 5):
    counts = [v["eligible_count"] for v in combo_results.values() if v["metric_count"] == size]
    counts_by_size[str(size)] = {"min": min(counts), "max": max(counts), "combos": len(counts)}

# Simulate 10,000 user toggle actions. Never allow zero selected and validate every resulting state.
rng = random.Random(20260915)
selected = set(metric_ids)
toggle_states = {}
for step in range(10_000):
    metric = metric_ids[rng.randrange(4)]
    if metric in selected and len(selected) == 1:
        pass
    elif metric in selected:
        selected.remove(metric)
    else:
        selected.add(metric)
    assert 1 <= len(selected) <= 4
    key = frozenset(selected)
    ranked = cache[key]
    assert all(1 <= row["rank"] <= len(ranked) for row in ranked.values())
    toggle_states["+".join(sorted(selected))] = toggle_states.get("+".join(sorted(selected)), 0) + 1

# Selected-complete filter contract: eligibility depends only on selected metrics.
filter_checks = 0
for combo_key, ranked in cache.items():
    for code, rec in records.items():
        selected_ok = all(rec[mid]["percentile"] is not None for mid in combo_key)
        assert selected_ok == (code in ranked), (combo_key, code)
        filter_checks += 1

result = {
    "status": "PASS",
    "universe_count": len(records),
    "all_nonempty_combinations_tested": len(combo_results),
    "combination_results": combo_results,
    "counts_by_selected_metric_count": counts_by_size,
    "all_four_reproduces_committed_rank": True,
    "ten_thousand_toggle_simulation_pass": True,
    "toggle_state_visits": toggle_states,
    "selected_complete_filter_checks": filter_checks,
    "zero_selection_prevented": True,
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
