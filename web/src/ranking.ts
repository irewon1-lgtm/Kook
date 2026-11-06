import { METRIC_IDS, type Metric, type MetricId, type RankResult, type Stock } from './types';
export function getMetric(stock: Stock, id: MetricId): Metric {
  return ({ M01: stock.m01RevGrowth, M02: stock.m02OpMargin, M03: stock.m03Per, M04: stock.m04Price6m })[id];
}
export function rankStocks(stocks: Stock[], selected: readonly MetricId[]): Map<string, RankResult> {
  const ids = [...new Set(selected)];
  if (!ids.length || ids.some(id => !METRIC_IDS.includes(id))) throw new Error('최소 한 가지 유효 지표를 선택해야 합니다.');
  const scores = new Map<string, number | null>();
  for (const stock of stocks) {
    const metrics = ids.map(id => getMetric(stock, id));
    const eligible = metrics.every(metric => metric.isAvailable && metric.percentileScore !== null);
    const score = eligible ? metrics.reduce((sum, metric) => sum + metric.percentileScore!, 0) / ids.length : null;
    if (score !== null && !Number.isFinite(score)) throw new Error('검증되지 않은 상대점수');
    scores.set(stock.issuerId, score);
  }
  const ordered = [...scores].filter((row): row is [string, number] => row[1] !== null)
    .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  const ranks = new Map(ordered.map(([code], index) => [code, index + 1]));
  return new Map(stocks.map(stock => {
    const score = scores.get(stock.issuerId) ?? null;
    return [stock.issuerId, { issuerId: stock.issuerId, score, rank: ranks.get(stock.issuerId) ?? null,
      selectedMetricCount: ids.length, isEligible: score !== null }];
  }));
}
