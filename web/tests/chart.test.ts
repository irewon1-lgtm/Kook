import { describe, expect, it, vi } from 'vitest';
import {createElement} from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import ChartView from '../src/chart/ChartView';
import { aggregate, latestQuote, normalizeDaily, visible } from '../src/chart/model';
import { createPriceStore } from '../src/chart/store';
import {DEFAULT_CHART_PREFERENCES,readChartPreferences,saveChartPreferences} from '../src/chart/preferences';
import type { DailyBar, PriceData } from '../src/types';

const bar = (date: string, open = 100, high = 110, low = 95, close = 105, volume: number | null = 10): DailyBar => ({date, open, high, low, close, volume});
const boundary = [bar('2025-12-29'), bar('2025-12-30',105,112,101,108,20), bar('2026-01-02',110,120,104,115,30), bar('2026-01-05',120,130,118,125,40)];
const price = (bars = boundary): PriceData => ({schemaVersion:1,issuerId:'005930',source:'CACHE',sourceUrl:'https://m.stock.naver.com/api/stock/005930/price',requestedCutoffDate:'2026-11-05',lastTradingDate:bars.at(-1)?.date ?? '',collectedAt:'2026-11-05T20:00:00Z',lastAttemptAt:'2026-11-05T20:00:00Z',adjustment:'UNVERIFIED',bars,error:null});

describe('original calendar price semantics', () => {
  it('uses Monday buckets across New Year and holidays, with actual first open and last close', () => {
    const result = aggregate([...boundary].reverse(), 'WEEK');
    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({periodStart:'2025-12-29',periodEnd:'2026-01-02',firstTradingDate:'2025-12-29',lastTradingDate:'2026-01-02',open:100,high:120,low:95,close:115,volume:60,tradingDays:3});
  });
  it('separates calendar months and leap February', () => {
    const result = aggregate([bar('2024-02-28'),bar('2024-02-29',106,120,100,115),bar('2024-03-04'),bar('2025-02-03')], 'MONTH');
    expect(result.map(x=>[x.periodStart,x.periodEnd,x.tradingDays])).toEqual([['2024-02-01','2024-02-29',2],['2024-03-01','2024-03-31',1],['2025-02-01','2025-02-28',1]]);
  });
  it('does not fill gaps, weekends or empty periods', () => {
    expect(aggregate([bar('2026-01-02'),bar('2026-01-03'),bar('2026-01-19')], 'WEEK').map(x=>x.tradingDays)).toEqual([1,1]);
    expect(aggregate([], 'DAY')).toEqual([]);
  });
  it('keeps complete weekly and monthly periods at viewport boundary', () => {
    const rows = [bar('2026-09-07'),bar('2026-09-09',110,120,100,115),bar('2026-09-11',120,130,110,125),bar('2026-10-09',130,140,120,135)];
    for (const interval of ['WEEK','MONTH'] as const) expect(visible(rows,interval,1)[0]).toMatchObject({firstTradingDate:'2026-09-07',open:100});
  });
  it('clamps month subtraction to February rather than overflowing into March', () => {
    const result = visible([bar('2024-02-28'),bar('2024-02-29'),bar('2024-03-29')], 'DAY', 1);
    expect(result.map(x=>x.lastTradingDate)).toEqual(['2024-02-29','2024-03-29']);
  });
  it('deduplicates equal rows and rejects conflicting same-day prices', () => {
    const b = bar('2026-11-04');
    expect(aggregate([b,b], 'DAY')).toHaveLength(1);
    expect(()=>aggregate([b,{...b,close:106}], 'DAY')).toThrow(/Conflicting/);
  });
  it('preserves unavailable total volume and separately labels observed partial sums', () => {
    const partial=aggregate([bar('2026-11-02'),bar('2026-11-04',100,110,95,105,null)],'WEEK')[0];
    expect(partial).toMatchObject({volume:null,observedVolume:10,volumeCoverage:'PARTIAL'});
    expect(aggregate([bar('2026-11-04',100,110,95,105,null)],'DAY')[0]).toMatchObject({volume:null,observedVolume:null,volumeCoverage:'MISSING'});
    expect(aggregate([bar('2026-11-04',100,110,95,105,0)],'DAY')[0]).toMatchObject({volume:0,volumeCoverage:'COMPLETE'});
  });
  it('filters invalid OHLC, impossible dates and future data without inventing prices or volume', () => {
    const good=bar('2026-11-04');
    expect(normalizeDaily([good,bar('2026-11-06'),bar('2026-02-30'),bar('2026-11-03',100,99,95,105),bar('2026-11-02',0),bar('2026-10-30',NaN)],'2026-11-05')).toEqual([good]);
    expect(normalizeDaily([bar('2026-11-04',100,110,95,105,-1)],'2026-11-05')[0].volume).toBeNull();
  });
  it('calculates latest daily change from actual previous observed trading day', () => {
    expect(latestQuote(boundary)).toMatchObject({date:'2026-01-05',close:125,change:10,changePct:125/115*100-100});
    expect(latestQuote([boundary[0]])).toMatchObject({change:null,changePct:null});
    expect(latestQuote([])).toBeNull();
  });
});

describe('shared actual-price reception', () => {
  it('shares one request across quote and chart, then retains verified CACHE on failed retry', async () => {
    let calls=0;
    const store=createPriceStore(async()=> {calls++; if(calls>1) throw new Error('offline'); return price();});
    await Promise.all([store.load('005930','2026-11-05'),store.load('005930','2026-11-05')]);
    expect(calls).toBe(1);
    expect(store.get('005930','2026-11-05').data?.bars).toHaveLength(4);
    await store.load('005930','2026-11-05',true);
    expect(store.get('005930','2026-11-05')).toMatchObject({loading:false,error:'offline',data:{source:'CACHE',bars:boundary}});
  });
  it('rejects a different issuer and never shows future rows from a published file', async () => {
    const wrong=createPriceStore(async()=>({...price(),issuerId:'000660'}));
    await wrong.load('005930','2026-11-05');
    expect(wrong.get('005930','2026-11-05').error).toMatch(/issuer/i);
    const future=createPriceStore(async()=>price([bar('2026-11-04'),bar('2026-11-06')]));
    await future.load('005930','2026-11-05');
    expect(future.get('005930','2026-11-05').data?.bars.map(x=>x.date)).toEqual(['2026-11-04']);
  });
});

describe('chart states and chart selection', () => {
  it('accepts only original range options', () => {
    expect(()=>visible(boundary,'DAY',2)).toThrow(/Unsupported/);
  });
  it('never wraps an unsafe volume sum into a displayed complete value', () => {
    const candle=aggregate([bar('2026-11-02',100,110,95,105,Number.MAX_SAFE_INTEGER),bar('2026-11-03',100,110,95,105,1)],'WEEK')[0];
    expect(candle.volume).toBeNull();
    expect(candle.observedVolume).toBeNull();
  });
  it('does not discard a previous verified history when a later publication is MISSING', async () => {
    let count=0;
    const store=createPriceStore(async()=>count++ ? {...price([]),source:'MISSING',error:'수집 실패'} : price());
    await store.load('005930','2026-11-05');
    await store.load('005930','2026-11-05',true);
    expect(store.get('005930','2026-11-05')).toMatchObject({data:{bars:boundary,source:'CACHE'},error:'수집 실패',loading:false});
  });
});


describe('accessible actual-price chart presentation', () => {
  const render=(data:PriceData|null=price(), error:string|null=null)=>renderToStaticMarkup(createElement(ChartView,{data,error,loading:false,cutoffDate:'2026-11-05',onReload:()=>{}}));
  it('starts at six months, daily candles, with an accessible real SVG and actual OHLCV', () => {
    const html=render();
    expect(html).toContain('aria-label="조회 기간"');
    expect(html).toContain('data-interval="DAY"');
    expect(html).toContain('data-months="6"');
    expect(html).toContain('data-chart-mode="candle"');
    expect(html).toContain('<svg');
    expect(html).toContain('2026-01-05');
    expect(html).toContain('실제 1거래일');
    expect(html).toContain('40주');
  });
  it('labels CACHE, collection date, actual trading date and unverified adjustment', () => {
    const html=render();
    expect(html).toContain('CACHE');
    expect(html).toContain('2026-11-05T20:00:00Z');
    expect(html).toContain('실제 최근 거래일');
    expect(html).toContain('수정주가 미확인');
    expect(html).toContain('제공 원본 OHLC 유지');
    expect(html).not.toContain('LIVE');
  });
  it('keeps errors and cutoff mismatches visible with a retry control', () => {
    const html=render({...price(),requestedCutoffDate:'2026-11-04'},'offline');
    expect(html).toContain('role="alert"');
    expect(html).toContain('offline');
    expect(html).toContain('요청 기준일 불일치');
    expect(html).toContain('다시 불러오기');
    const missing=render(null,'수집 실패');
    expect(missing).toContain('과거 종가 자료 미확보');
    expect(missing).not.toContain('<svg');
  });
});


it('preserves original range change and corporate-action caveats without implying rank inputs', () => {
  const html=renderToStaticMarkup(createElement(ChartView,{data:price(),error:null,loading:false,cutoffDate:'2026-11-05',onReload:()=>{}}));
  expect(html).toContain('선택 기간 원본 가격 변동 +19.05%');
  expect(html).toContain('분할·병합');
  expect(html).toContain('결측 거래일은 임의로 채우지 않습니다');
  expect(html).toContain('4지표 순위와 별도 참고자료');
});


it('renders a separate latest daily quote with change from the actual previous trading day', () => {
 const html=renderToStaticMarkup(createElement(ChartView,{data:price(),error:null,loading:false,cutoffDate:'2026-11-05',onReload:()=>{}}));
 expect(html).toContain('data-testid="chart_latest_quote"');
 expect(html).toContain('125원');
 expect(html).toContain('+10원 (+8.70%)');
 expect(html).toContain('data-testid="chart_latest_volume"');
});

describe('published price revision changes on the same cutoff', () => {
  it('loads a new revision for the same issuer and cutoff without changing source dates', async () => {
    const original=price();
    const corrected=price([...boundary.slice(0,-1),bar('2026-01-05',120,130,118,126,40)]);
    let published=original;
    let requests=0;
    const store=createPriceStore(async()=>{requests++;return published;});
    await store.load('005930','2026-11-05',false,'sha-original');
    published=corrected;
    await store.load('005930','2026-11-05',false,'sha-corrected');
    expect(requests).toBe(2);
    expect(store.get('005930','2026-11-05','sha-corrected').data).toEqual(corrected);
    expect(store.get('005930','2026-11-05','sha-original').data).toEqual(original);
    expect(store.get('005930','2026-11-05','sha-corrected').data).toMatchObject({source:'CACHE',requestedCutoffDate:original.requestedCutoffDate,lastTradingDate:original.lastTradingDate,collectedAt:original.collectedAt});
  });
  it('coalesces quote and chart loads on a new revision and keeps reopened unchanged revisions cached', async () => {
    const original=price();
    const corrected=price([...boundary.slice(0,-1),bar('2026-01-05',120,130,118,126,40)]);
    let requests=0;
    const store=createPriceStore(async()=>++requests===1?original:corrected);
    await store.load('005930','2026-11-05',false,'sha-original');
    let quoteUpdates=0,chartUpdates=0;
    const unsubscribeQuote=store.subscribe('005930','2026-11-05',()=>{quoteUpdates++;},'sha-corrected');
    const unsubscribeChart=store.subscribe('005930','2026-11-05',()=>{chartUpdates++;},'sha-corrected');
    await Promise.all([store.load('005930','2026-11-05',false,'sha-corrected'),store.load('005930','2026-11-05',false,'sha-corrected')]);
    expect(store.get('005930','2026-11-05','sha-corrected').data).toEqual(corrected);
    expect(requests).toBe(2);
    expect(quoteUpdates).toBeGreaterThan(0);expect(chartUpdates).toBe(quoteUpdates);
    unsubscribeQuote();unsubscribeChart();
    await store.load('005930','2026-11-05',false,'sha-corrected');
    expect(requests).toBe(2);
  });
  it('isolates an older revision request that finishes after the current revision', async () => {
    const original=price();
    const corrected=price([...boundary.slice(0,-1),bar('2026-01-05',120,130,118,126,40)]);
    let finishOriginal!:(data:PriceData)=>void;
    let requests=0;
    const store=createPriceStore(async()=>++requests===1?new Promise<PriceData>(resolve=>{finishOriginal=resolve;}):corrected);
    const pendingOld=store.load('005930','2026-11-05',false,'sha-original');
    const pendingNew=store.load('005930','2026-11-05',false,'sha-corrected');
    await Promise.resolve();
    expect(requests).toBe(2);
    await pendingNew;
    expect(store.get('005930','2026-11-05','sha-corrected').data).toEqual(corrected);
    finishOriginal(original);await pendingOld;
    expect(store.get('005930','2026-11-05','sha-corrected').data).toEqual(corrected);
  });
});


describe('per-stock chart preferences', () => {
  function storage() {
    const values=new Map<string,string>();
    return {getItem:(key:string)=>values.get(key)??null,setItem:(key:string,value:string)=>{values.set(key,value);},values};
  }
  it('restores each stock independently without sharing another stock choices', () => {
    const local=storage();
    const samsung={interval:'MONTH' as const,months:12 as const,candleMode:false};
    const hynix={interval:'WEEK' as const,months:3 as const,candleMode:true};
    saveChartPreferences('005930',samsung,local);
    expect(readChartPreferences('005930',local)).toEqual(samsung);
    expect(readChartPreferences('000660',local)).toEqual(DEFAULT_CHART_PREFERENCES);
    saveChartPreferences('000660',hynix,local);
    expect(readChartPreferences('000660',local)).toEqual(hynix);
    expect(readChartPreferences('005930',local)).toEqual(samsung);
  });
  it('rejects malformed, unsupported and partially invalid stored values', () => {
    const local=storage();
    for(const value of ['{', 'null', '[]', JSON.stringify({schemaVersion:2,interval:'MONTH',months:12,candleMode:false}), JSON.stringify({schemaVersion:1,interval:'YEAR',months:12,candleMode:false}), JSON.stringify({schemaVersion:1,interval:'MONTH',months:2,candleMode:false}), JSON.stringify({schemaVersion:1,interval:'MONTH',months:12,candleMode:'false'})]) {
      local.setItem('kr4-pc-chart:v1:005930',value);
      expect(readChartPreferences('005930',local)).toEqual(DEFAULT_CHART_PREFERENCES);
    }
    expect(()=>saveChartPreferences('../005930',{interval:'DAY',months:6,candleMode:true},local)).not.toThrow();
    expect(local.values.has('kr4-pc-chart:v1:../005930')).toBe(false);
  });
  it('uses defaults and remains operable when browser storage is denied', () => {
    const denied={getItem:()=>{throw Error('denied');},setItem:()=>{throw Error('denied');}};
    expect(readChartPreferences('005930',denied)).toEqual(DEFAULT_CHART_PREFERENCES);
    expect(()=>saveChartPreferences('005930',{interval:'MONTH',months:12,candleMode:false},denied)).not.toThrow();
  });
  it('restores from the known issuer before asynchronous price data arrives', () => {
    const local=storage();
    local.setItem('kr4-pc-chart:v1:005930',JSON.stringify({schemaVersion:1,interval:'MONTH',months:12,candleMode:false}));
    vi.stubGlobal('localStorage',local);
    try {
      const html=renderToStaticMarkup(createElement(ChartView,{data:null,error:null,loading:true,cutoffDate:'2026-11-05',onReload:()=>{},preferenceKey:'005930'}));
      expect(html).toContain('data-interval="MONTH"');
      expect(html).toContain('data-months="12"');
      expect(html).toContain('data-chart-mode="line"');
      expect(html).toContain('가격 자료 대기');
    } finally {vi.unstubAllGlobals();}
  });
});
