import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { rankStocks } from '../src/ranking';
import { METRIC_IDS, type RankResult, type Stock } from '../src/types';
const root = process.env.KR4_ORACLE_DIR || '/workspace/kr4-pc-work/oracle-v2';
const stocks = JSON.parse(readFileSync(resolve(root, 'stocks.json'), 'utf8')) as Stock[];
const original = JSON.parse(readFileSync(resolve(root, 'rankings.json'), 'utf8')) as Record<string, Record<string, RankResult>>;
describe('All 2,649 actual stocks × 15 original Kotlin ranking masks', () => {
  for (let mask = 1; mask <= 15; mask++) {
    it(`matches original unrounded score, global rank, completeness and tie result for mask ${mask}`, () => {
      const selected = METRIC_IDS.filter((_, index) => mask & 1 << index);
      const actual = rankStocks(stocks, selected);
      expect(actual.size).toBe(2649);
      for (const stock of stocks) expect(actual.get(stock.issuerId), `mask ${mask}: ${stock.issuerId}`).toEqual(original[String(mask)][stock.issuerId]);
    });
  }
  it('is unchanged when every financial risk status changes independently', () => {
    for (const status of ['PASS', 'FAIL', 'HOLD', 'NOT_APPLICABLE']) {
      const changed = stocks.map(stock => ({ ...stock, financialStatus: status }));
      for (let mask = 1; mask <= 15; mask++) {
        const selected = METRIC_IDS.filter((_, index) => mask & 1 << index);
        expect([...rankStocks(changed, selected)]).toEqual([...rankStocks(stocks, selected)]);
      }
    }
  });
});
