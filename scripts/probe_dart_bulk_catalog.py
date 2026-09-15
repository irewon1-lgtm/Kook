#!/usr/bin/env python3
from collections import Counter, defaultdict
import json, re, requests
import collect_real_quant as base

s=requests.Session(); s.headers.update(base.DART_HEADERS)
s.get(base.DART_BASE,timeout=30).raise_for_status()
r=s.post(base.DART_LIST,timeout=60); r.raise_for_status()
raw=base.DART_ENTRY_RE.findall(r.text)
print('RAW_MATCH_COUNT',len(raw))
print('PERIOD_CODES',json.dumps(Counter(x[1] for x in raw),ensure_ascii=False,sort_keys=True))
print('STATEMENT_CODES',json.dumps(Counter(x[2] for x in raw),ensure_ascii=False,sort_keys=True))
by_year=defaultdict(list)
for y,p,st,f in raw:
    if st=='PL': by_year[y].append((p,f))
for y in sorted(by_year)[-8:]:
    print('YEAR',y,json.dumps(sorted(by_year[y]),ensure_ascii=False))
# Show nearby unmatched download_ext002 calls to detect regex/code drift.
calls=re.findall(r"download_ext002\(([^\n;]+)\)",r.text)
print('CALL_COUNT',len(calls))
for c in calls[-40:]: print('CALL',c[:240])
