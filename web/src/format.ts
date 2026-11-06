import type { Metric } from './types';
export function formatNumber(value: number | null | undefined, digits = 0): string {
  return value == null || !Number.isFinite(value) ? '미확보' : new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits: digits, minimumFractionDigits: 0,
  }).format(value);
}
export function formatExactWon(value: number | string | null | undefined): string {
  if (value == null || value === '') return '미확보';
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '정확한 원 단위 미확인';
  try { return BigInt(value).toLocaleString('ko-KR') + '원'; } catch { return '미확보'; }
}
export function safeExternalUrl(value: string): string | null {
  try {
    const url = new URL(value);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}
export function formatDate(value: string): string {
  if (!value) return '미확인';
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit',
    day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date) + ' KST';
}
export function formatMetric(metric: Metric): string {
  if (metric.rawValue == null) return '미확보';
  const digits = metric.id === 'M03' ? 2 : 1;
  return new Intl.NumberFormat('ko-KR', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(metric.rawValue) + metric.unit;
}
