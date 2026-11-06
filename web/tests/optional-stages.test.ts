import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';
import { MarketReceiver, MemoryStore } from '../src/data/receiver';

const root = process.env.KR4_ORACLE_DIR || '/workspace/kr4-pc-work/oracle-stage5-candidates';
const text = (name: string) => readFileSync(resolve(root, name), 'utf8');
const manifest = JSON.parse(text('manifest.json'));
const make = (brokenFinancial = false) => new MarketReceiver({
  bundled: { manifest, stocksText: text('stocks.json') }, store: new MemoryStore(), baseUrl: '/data/',
  fetcher: async url => {
    const name = url.replace('/data/', '');
    if (name === 'financial.json' && brokenFinancial) return new Response('{}');
    try { return new Response(text(name)); } catch { return new Response('', { status: 404 }); }
  },
});
it('receives the exact original Stage5 and candidate maps without client-side thresholds', async () => {
  const receiver = make(); await receiver.refresh();
  expect(receiver.getState().valuationsByCode).toEqual(JSON.parse(text('valuations.json')).records);
  const candidate = JSON.parse(text('candidates.json'));
  expect(receiver.getState().candidatesByCode).toEqual(candidate.status === 'VALID' && !receiver.getState().financialError ? candidate.records : {});
  expect(receiver.getState().source).toBe('LIVE');
});
it('keeps original PER classifications independent when Stage4 fails and holds dependent final candidates', async () => {
  const receiver = make(true); await receiver.refresh();
  expect(receiver.getState().stocks).toHaveLength(2649); expect(receiver.getState().source).toBe('LIVE');
  expect(receiver.getState().financialByCode).toEqual({});
  expect(receiver.getState().valuationsByCode).toEqual(JSON.parse(text('valuations.json')).records);
  expect(receiver.getState().candidatesByCode).toEqual({}); expect(receiver.getState().candidatesError).toBeTruthy();
});
