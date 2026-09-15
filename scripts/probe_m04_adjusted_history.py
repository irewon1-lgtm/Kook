#!/usr/bin/env python3
from __future__ import annotations

import json
import math
from pathlib import Path

from pykrx import stock

CASES={
    '006380':'카프로',
    '008290':'원풍물산',
    '046070':'코다코',
    '082660':'코스나인',
    '134380':'미원화학',
    '331520':'밸로프',
}
START='20260315'
END='20260915'
OUT=Path('evidence/m04_adjusted_history_probe.json')


def frame_summary(df):
    if df is None or df.empty:
        return {'empty':True}
    rows=[]
    for idx,row in df.iterrows():
        rows.append({
            'date':idx.strftime('%Y-%m-%d'),
            'open':float(row.get('시가',0)),
            'high':float(row.get('고가',0)),
            'low':float(row.get('저가',0)),
            'close':float(row.get('종가',0)),
            'volume':float(row.get('거래량',0)),
            'change_pct':float(row.get('등락률',0)) if '등락률' in row.index else None,
        })
    positive=[r for r in rows if r['close']>0]
    start=positive[0] if positive else None
    end=positive[-1] if positive else None
    ret=None
    if start and end and start['close']>0:
        ret=(end['close']/start['close']-1.0)*100.0
    jumps=[]
    for a,b in zip(positive,positive[1:]):
        f=b['close']/a['close'] if a['close'] else None
        if f is not None and (f<0.69 or f>1.31):
            jumps.append({'from':a['date'],'to':b['date'],'from_close':a['close'],'to_close':b['close'],'factor':f,
                          'reported_change_pct':b['change_pct']})
    return {
        'empty':False,'row_count':len(rows),'positive_row_count':len(positive),
        'start':start,'end':end,'simple_return_pct':ret,'large_close_jumps':jumps,
        'tail':rows[-8:],
    }


def main():
    out={'start':START,'end':END,'records':{},'errors':{}}
    for code,name in CASES.items():
        rec={'name':name}
        for adjusted in (True,False):
            key='adjusted' if adjusted else 'unadjusted'
            try:
                df=stock.get_market_ohlcv_by_date(START,END,code,adjusted=adjusted)
                rec[key]=frame_summary(df)
            except Exception as exc:
                rec[key]={'error':f'{type(exc).__name__}:{exc}'}
                out['errors'][f'{code}:{key}']=rec[key]['error']
        out['records'][code]=rec
    out['error_count']=len(out['errors'])
    OUT.write_text(json.dumps(out,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    print(json.dumps({
        code:{
            'name':rec['name'],
            'adj_return':rec.get('adjusted',{}).get('simple_return_pct'),
            'adj_jumps':rec.get('adjusted',{}).get('large_close_jumps'),
            'raw_return':rec.get('unadjusted',{}).get('simple_return_pct'),
            'raw_jumps':rec.get('unadjusted',{}).get('large_close_jumps'),
        } for code,rec in out['records'].items()
    },ensure_ascii=False,indent=2))
    print('errors',out['errors'])

if __name__=='__main__': main()
