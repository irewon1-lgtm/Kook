import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, afterAll } from 'vitest';
import { normalizeDaily, visible, type Candle, type Interval } from '../src/chart/model';
import type { DailyBar } from '../src/types';

const root = process.env.KR4_PRICE_ORACLE_DIR || '/workspace/kr4-pc-work/price-oracle';
interface OracleCandle {
  firstTradingDate:string; lastTradingDate:string; periodStart:string; periodEnd:string;
  open:number; high:number; low:number; close:number; volume:number|null; tradingDays:number;
}
interface PriceCase {
  id:string; kind:'actual'|'controlled'; source:string; cutoff:string; inputBars:DailyBar[];
  parsedDaily:DailyBar[]; projections:Record<string,OracleCandle[]>;
}
const artifact = JSON.parse(readFileSync(resolve(root, 'original-kotlin-price-projections.json'), 'utf8')) as {
  cases:PriceCase[]; originalSourceSha256:Record<string,string>;
};
const comparisons: {id:string; kind:string; interval:Interval; months:number; mode:string; bars:number; status:string}[] = [];
function canonical(candle:Candle):OracleCandle {
  const {firstTradingDate,lastTradingDate,periodStart,periodEnd,open,high,low,close,volume,tradingDays}=candle;
  return {firstTradingDate,lastTradingDate,periodStart,periodEnd,open,high,low,close,volume,tradingDays};
}
describe('Original Android Kotlin price parser and chart projection parity', () => {
  it('includes actual large cap, small cap, alphanumeric, stale and missing source cases separately from controlled fixtures', () => {
    expect(artifact.cases.filter(row=>row.kind==='actual').map(row=>row.id)).toEqual(['005930','000660','000020','0001A0','000300','005030']);
    expect(artifact.cases.filter(row=>row.kind==='controlled').map(row=>row.id)).toEqual(['missing-volume','year-boundary','calendar-gaps']);
    expect(Object.keys(artifact.originalSourceSha256).sort()).toEqual(['PriceAdjustmentProvenance.kt','PriceHistoryAggregation.kt','PriceHistoryRepository.kt']);
  });
  for (const sample of artifact.cases) {
    it(`${sample.kind} ${sample.id}: original parseBars agrees exactly with normalized collector input`, () => {
      expect(sample.parsedDaily).toEqual(normalizeDaily(sample.inputBars, sample.cutoff));
      if(sample.kind==='actual' && sample.id==='005030') {
        expect(sample.source).toBe('MISSING'); expect(sample.parsedDaily).toEqual([]);
      }
      if(sample.kind==='actual' && sample.id==='000300') {
        expect(sample.source).toBe('CACHE'); expect(sample.parsedDaily.at(-1)?.date).toBe('2025-03-06');
      }
    });
    for (const interval of ['DAY','WEEK','MONTH'] as const) for (const months of [1,3,6,12]) for (const mode of ['candle','line']) {
      it(`${sample.kind} ${sample.id} ${interval}/${months}/${mode}: full precision OHLCV and dates equal original visible()`, () => {
        const actual = visible(sample.inputBars, interval, months).map(canonical);
        expect(actual).toEqual(sample.projections[`${interval}/${months}/${mode}`]);
        comparisons.push({id:sample.id,kind:sample.kind,interval,months,mode,bars:actual.length,status:'PASS'});
      });
    }
  }
  it('treats candle and line as two rendering modes over the same original OHLCV source window', () => {
    for (const sample of artifact.cases) for (const interval of ['DAY','WEEK','MONTH']) for(const months of [1,3,6,12])
      expect(sample.projections[`${interval}/${months}/candle`]).toEqual(sample.projections[`${interval}/${months}/line`]);
  });
  it('retains partial missing-volume buckets without inventing volume', () => {
    const sample=artifact.cases.find(row=>row.id==='missing-volume')!;
    const candle=visible(sample.inputBars,'WEEK',1)[0];
    expect(candle.volume).toBeNull(); expect(candle.observedVolume).toBe(20); expect(candle.volumeCoverage).toBe('PARTIAL');
    expect(candle.periodStart).toBe('2025-12-29'); expect(candle.periodEnd).toBe('2026-01-02');
    expect(candle.firstTradingDate).toBe('2025-12-30'); expect(candle.lastTradingDate).toBe('2026-01-02');
  });
  it('keeps entire boundary buckets and never fills absent calendar dates', () => {
    const year=artifact.cases.find(row=>row.id==='year-boundary')!;
    const month=visible(year.inputBars,'MONTH',1)[0];
    expect(month.firstTradingDate).toBe('2025-12-01'); expect(month.lastTradingDate).toBe('2025-12-31');
    const gaps=artifact.cases.find(row=>row.id==='calendar-gaps')!;
    expect(visible(gaps.inputBars,'DAY',12).map(row=>row.date)).toEqual(['2025-12-24','2025-12-29','2026-01-02']);
  });
});
afterAll(() => {
  if(process.env.KR4_PRICE_PARITY_REPORT) writeFileSync(process.env.KR4_PRICE_PARITY_REPORT, JSON.stringify({
    numericComparison:'exact IEEE-754 values; no tolerance or rounding',
    modeMeaning:'24 rendering selections share 12 unique interval/window OHLCV projections per case',
    actualCases:6,controlledCases:3,actualRenderingSelections:144,actualUniqueProjections:72,
    controlledRenderingSelections:72,controlledUniqueProjections:36,comparisons,
  },null,2)+'\n');
});
