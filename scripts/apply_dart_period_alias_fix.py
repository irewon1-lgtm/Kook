#!/usr/bin/env python3
from pathlib import Path


def patch(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


patch(
    "scripts/collect_quarterly_history.py",
    'PERIOD_TO_Q = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}\nQ_TO_PERIOD = {v: k for k, v in PERIOD_TO_Q.items()}\n',
    'PERIOD_TO_Q = {"Q1": 1, "1Q": 1, "HY": 2, "Q3": 3, "3Q": 3, "FY": 4}\nQ_TO_PERIOD = {1: "Q1", 2: "HY", 3: "Q3", 4: "FY"}\n',
    "quarterly period aliases",
)
patch(
    "scripts/collect_quarterly_history.py",
    '            "period": period,\n            "file": fname,\n',
    '            "period": Q_TO_PERIOD[quarter],\n            "file": fname,\n',
    "canonical quarterly period",
)

patch(
    "scripts/collect_real_quant_v3.py",
    'DART_PERIOD_RANK = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}\nDART_PERIOD_LABEL = {"Q1": "1분기", "HY": "반기", "Q3": "3분기", "FY": "사업연도"}\n',
    'DART_PERIOD_CANONICAL = {"Q1": "Q1", "1Q": "Q1", "HY": "HY", "Q3": "Q3", "3Q": "Q3", "FY": "FY"}\nDART_PERIOD_RANK = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}\nDART_PERIOD_LABEL = {"Q1": "1분기", "HY": "반기", "Q3": "3분기", "FY": "사업연도"}\n',
    "v3 canonical map",
)
patch(
    "scripts/collect_real_quant_v3.py",
    '    for year, period, statement, fname in entries:\n        if statement != "PL" or period not in DART_PERIOD_RANK:\n            continue\n        y = int(year)\n',
    '    for year, period, statement, fname in entries:\n        period = DART_PERIOD_CANONICAL.get(period, period)\n        if statement != "PL" or period not in DART_PERIOD_RANK:\n            continue\n        y = int(year)\n',
    "v3 period normalization",
)

print("DART_PERIOD_ALIAS_PATCH_PASS")
