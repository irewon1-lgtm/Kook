import type { DailyBar } from '../types';

export type Interval = 'DAY' | 'WEEK' | 'MONTH';
export interface Candle extends DailyBar {
  firstTradingDate: string; lastTradingDate: string; periodStart: string; periodEnd: string;
  tradingDays: number; observedVolume: number | null; volumeCoverage: 'COMPLETE' | 'PARTIAL' | 'MISSING';
}

const iso = (date: Date) => date.toISOString().slice(0, 10);
function parseDate(value: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && iso(date) === value ? date : null;
}
function offsetDays(value: string, days: number): string {
  const date = parseDate(value)!;
  date.setUTCDate(date.getUTCDate() + days);
  return iso(date);
}
function periodStart(value: string, interval: Interval): string {
  if (interval === 'DAY') return value;
  if (interval === 'MONTH') return `${value.slice(0, 7)}-01`;
  return offsetDays(value, -((parseDate(value)!.getUTCDay() + 6) % 7));
}
function periodEnd(start: string, interval: Interval): string {
  if (interval === 'DAY') return start;
  if (interval === 'WEEK') return offsetDays(start, 4);
  const date = parseDate(start)!;
  date.setUTCMonth(date.getUTCMonth() + 1, 0);
  return iso(date);
}
function minusMonths(value: string, months: number): string {
  const date = parseDate(value)!;
  const day = date.getUTCDate();
  date.setUTCDate(1);
  date.setUTCMonth(date.getUTCMonth() - months);
  const lastDay = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0)).getUTCDate();
  date.setUTCDate(Math.min(day, lastDay));
  return iso(date);
}

/** Keep observed prices only. No interpolation, calendar filling or split adjustments. */
export function normalizeDaily(daily: DailyBar[], cutoff = '9999-12-31'): DailyBar[] {
  if (!parseDate(cutoff)) throw new Error('Invalid price cutoff date');
  const unique = new Map<string, DailyBar>();
  for (const row of daily) {
    const date = parseDate(row.date);
    if (!date || row.date > cutoff || date.getUTCDay() === 0 || date.getUTCDay() === 6) continue;
    if (![row.open, row.high, row.low, row.close].every(value => Number.isFinite(value) && value > 0)) continue;
    if (row.low > Math.min(row.open, row.close) || row.high < Math.max(row.open, row.close) || row.low > row.high) continue;
    const volume = row.volume !== null && Number.isSafeInteger(row.volume) && row.volume >= 0 ? row.volume : null;
    const bar = {date:row.date, open:row.open, high:row.high, low:row.low, close:row.close, volume};
    const old = unique.get(row.date);
    if (old && (old.open !== bar.open || old.high !== bar.high || old.low !== bar.low || old.close !== bar.close || old.volume !== bar.volume)) throw new Error(`Conflicting price date ${row.date}`);
    unique.set(row.date, bar);
  }
  return [...unique.values()].sort((a,b) => a.date.localeCompare(b.date));
}

/** Matches PriceHistoryAggregation: aggregate whole periods before cutting the viewport. */
export function aggregate(daily: DailyBar[], interval: Interval): Candle[] {
  const groups = new Map<string, DailyBar[]>();
  for (const row of normalizeDaily(daily)) {
    const start = periodStart(row.date, interval);
    const group = groups.get(start) ?? [];
    group.push(row);
    groups.set(start, group);
  }
  return [...groups].map(([start, rows]) => {
    const first = rows[0], last = rows[rows.length - 1];
    const known = rows.filter(row => row.volume !== null);
    const sum = known.reduce((total,row) => total + row.volume!, 0);
    const observedVolume = known.length && Number.isSafeInteger(sum) ? sum : null;
    const complete = known.length === rows.length && observedVolume !== null;
    return {
      date:last.date, firstTradingDate:first.date, lastTradingDate:last.date, periodStart:start,
      periodEnd:periodEnd(start, interval), open:first.open, high:Math.max(...rows.map(row => row.high)),
      low:Math.min(...rows.map(row => row.low)), close:last.close,
      volume:complete ? observedVolume : null, observedVolume,
      volumeCoverage:complete ? 'COMPLETE' as const : observedVolume !== null ? 'PARTIAL' as const : 'MISSING' as const,
      tradingDays:rows.length,
    };
  });
}
export function visible(daily: DailyBar[], interval: Interval, months: number): Candle[] {
  if (![1,3,6,12].includes(months)) throw new Error('Unsupported chart window');
  const all = aggregate(daily, interval);
  if (!all.length) return [];
  const from = minusMonths(all[all.length - 1].lastTradingDate, months);
  return all.filter(candle => candle.lastTradingDate >= from);
}
export function latestQuote(daily: DailyBar[]): {date:string; close:number; change:number|null; changePct:number|null} | null {
  const rows = normalizeDaily(daily);
  const last = rows.at(-1), previous = rows.at(-2);
  if (!last) return null;
  return {date:last.date, close:last.close, change:previous ? last.close - previous.close : null,
    changePct:previous ? last.close / previous.close * 100 - 100 : null};
}
