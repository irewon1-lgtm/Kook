import { beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { MarketReceiver, MemoryStore, validateStocks, validateManifest, validatePrice, sha256 } from '../src/data/receiver';
import { recordedTar } from './recorded-fixture';
import type { PublicationManifest, Stock } from '../src/types';

const oracle = process.env.KR4_ORACLE_DIR || '/workspace/kr4-pc-work/oracle-v2';
const manifest = JSON.parse(readFileSync(resolve(oracle, 'manifest.json'), 'utf8')) as PublicationManifest;
const stocksText = readFileSync(resolve(oracle, 'stocks.json'), 'utf8');
const stocks = JSON.parse(stocksText) as Stock[];
const financialText = readFileSync(resolve(oracle, 'financial.json'), 'utf8');
const bundle = { manifest, stocksText };
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));
describe('Publication validation using actual original-engine data', () => {
  it('accepts the complete real universe and actual manifest', () => {
    expect(validateStocks(stocks, manifest).length).toBe(2649);
    expect(validateManifest(manifest).snapshotDate).toBe(manifest.snapshotDate);
  });
  it('rejects duplicate or missing stocks instead of shrinking the universe', () => {
    const duplicate = clone(stocks); duplicate[1] = duplicate[0];
    expect(() => validateStocks(duplicate, manifest)).toThrow();
    expect(() => validateStocks(stocks.slice(1), manifest)).toThrow();
  });
  it('rejects an invalid available metric or missing reason', () => {
    const broken = clone(stocks); broken[0].m01RevGrowth.percentileScore = 101;
    expect(() => validateStocks(broken, manifest)).toThrow();
    const missing = clone(stocks); missing[0].m01RevGrowth = { ...missing[0].m01RevGrowth, rawValue: null, percentileScore: null, isAvailable: false, reason: null };
    expect(() => validateStocks(missing, manifest)).toThrow();
  });
  it('rejects future cutoff and mismatched coverage', () => {
    expect(() => validateManifest({ ...manifest, priceCutoffDate: '2099-01-01' })).toThrow();
    expect(() => validateStocks(stocks, { ...manifest, coverage: { ...manifest.coverage, completeCount: 8 } })).toThrow();
  });
});
describe('Independent optional financial failure and verified cache recovery', () => {
  let cache: MemoryStore;
  beforeEach(() => { cache = new MemoryStore(); });
  const fetcher = (financialFails = false) => async (url: string): Promise<Response> => {
    if (url.includes('manifest.json')) return new Response(JSON.stringify(manifest));
    if (url.endsWith('stocks.json')) return new Response(stocksText);
    if (url.endsWith('financial.json')) return new Response(financialFails ? '{}' : financialText);
    return new Response('missing', { status: 404 });
  };
  it('receives actual complete quant even when safety bytes are invalid', async () => {
    const receiver = new MarketReceiver({ bundled: bundle, store: cache, fetcher: fetcher(true), baseUrl: '/data/' });
    await receiver.refresh();
    expect(receiver.getState().source).toBe('LIVE');
    expect(receiver.getState().stocks).toHaveLength(2649);
    expect(Object.keys(receiver.getState().financialByCode)).toHaveLength(0);
    expect(receiver.getState().financialError).toBeTruthy();
  });
  it('labels explicitly recorded real observations CACHE even when the HTTP request succeeds', async () => {
    const recorded = { ...manifest, dataKind: 'REAL_MARKET_RECORDED' as const };
    const receiver = new MarketReceiver({ bundled: bundle, store: cache, baseUrl: '/data/', fetcher: async url => url.includes('manifest.json') ? new Response(JSON.stringify(recorded)) : fetcher()(url) });
    await receiver.refresh(); expect(receiver.getState().source).toBe('CACHE');
    expect(receiver.getState().stocks).toHaveLength(2649);
  });
  it('uses actual previous verified cache on network failure', async () => {
    const first = new MarketReceiver({ bundled: bundle, store: cache, fetcher: fetcher(), baseUrl: '/data/' });
    await first.refresh();
    const offline = new MarketReceiver({ bundled: bundle, store: cache, fetcher: async () => { throw new Error('CONTROLLED_OFFLINE'); }, baseUrl: '/data/' });
    await offline.refresh();
    expect(offline.getState().source).toBe('CACHE');
    expect(offline.getState().stocks).toHaveLength(2649);
    expect(offline.getState().error).toContain('CONTROLLED_OFFLINE');
  });
  it('does not call cached stock bytes LIVE merely because the manifest request succeeded', async () => {
    const first = new MarketReceiver({ bundled: bundle, store: cache, fetcher: fetcher(), baseUrl: '/data/' });
    await first.refresh();
    const failedData = new MarketReceiver({ bundled: bundle, store: cache, fetcher: async url => url.includes('manifest.json') ? new Response(JSON.stringify(manifest)) : new Response('offline', { status: 503 }), baseUrl: '/data/' });
    await failedData.refresh(); expect(failedData.getState().source).toBe('CACHE');
  });
  it('rejects corrupted current cache and falls back to verified backup', async () => {
    await cache.write('core:current', { ...bundle, stocksText: '[]' });
    await cache.write('core:backup', bundle);
    const receiver = new MarketReceiver({ bundled: bundle, store: cache, fetcher: async () => { throw new Error('CONTROLLED_OFFLINE'); }, baseUrl: '/data/' });
    await receiver.refresh(); expect(receiver.getState().source).toBe('BACKUP');
  });
  it('uses bundled validated data when storage is denied and network fails', async () => {
    const receiver = new MarketReceiver({ bundled: bundle, store: { read: async () => { throw Error('quota'); }, write: async () => { throw Error('quota'); } }, fetcher: async () => { throw new Error('CONTROLLED_OFFLINE'); }, baseUrl: '/data/' });
    await receiver.refresh(); expect(receiver.getState().source).toBe('BUNDLED');
  });
  it('fails visibly if no source including bundled data is valid', async () => {
    const receiver = new MarketReceiver({ bundled: { ...bundle, stocksText: '[]' }, store: cache, fetcher: async () => { throw new Error('CONTROLLED_OFFLINE'); }, baseUrl: '/data/' });
    await receiver.refresh(); expect(receiver.getState().source).toBe('FAILED');
  });
});
it('rejects synthetic or future OHLCV and keeps a genuine older last trade date', () => {
  const price = { schemaVersion: 1, issuerId: '005930', source: 'CACHE', requestedCutoffDate: '2026-11-05', lastTradingDate: '2026-11-04', collectedAt: '2026-11-06T00:00:00Z', lastAttemptAt: '', sourceUrl: 'https://m.stock.naver.com/api/stock/005930/price', adjustment: 'UNVERIFIED', error: null, bars: [{ date: '2026-11-04', open: 10, high: 12, low: 9, close: 11, volume: null }] };
  expect(validatePrice(price, '005930', '2026-11-05').lastTradingDate).toBe('2026-11-04');
  expect(() => validatePrice({ ...price, bars: [{ ...price.bars[0], date: '2026-11-06' }] }, '005930', '2026-11-05')).toThrow();
  expect(() => validatePrice({ ...price, bars: [{ ...price.bars[0], open: null }] }, '005930', '2026-11-05')).toThrow();
});
it('retains null dates and an explicit reason for a genuine empty-source MISSING payload', () => {
  const missing = { schemaVersion: 1, issuerId: '005030', source: 'MISSING', requestedCutoffDate: '2026-11-05', lastTradingDate: null, collectedAt: null, lastAttemptAt: '2026-11-06T00:00:00Z', sourceUrl: 'https://m.stock.naver.com/api/stock/005030/price', adjustment: 'UNVERIFIED', error: 'No completed daily prices', bars: [], pages: [] };
  expect(validatePrice(missing, '005030', '2026-11-05')).toEqual(missing);
});

it('reports a failed price refresh while preserving genuine verified CACHE and clears the error after recovery', async () => {
  const actual = recordedTar('oracle/testdata/price_observations_20261105.tar.gz', 'de5cc3f9b8edf5fc41dbfd0299f258a60afbadb47bd58fa95d47e552ec39d032');
  const text = actual.get('prices/005930.json')!;
  const price = JSON.parse(text);
  const withPrice = { ...manifest, files: { ...manifest.files, 'prices/005930.json': { sha256: await sha256(text), bytes: new TextEncoder().encode(text).length } } };
  let failing = false;
  const receiver = new MarketReceiver({ bundled: { manifest: withPrice, stocksText }, store: new MemoryStore(), baseUrl: '/data/', fetcher: async url => {
    if (url.endsWith('prices/005930.json')) return failing ? new Response('CONTROLLED_PRICE_OUTAGE', { status: 503 }) : new Response(text);
    return new Response('missing', { status: 404 });
  } });
  expect((await receiver.loadPrice('005930', manifest.priceCutoffDate)).bars).toEqual(price.bars);
  failing = true;
  const cached = await receiver.loadPrice('005930', manifest.priceCutoffDate);
  expect(cached.source).toBe('CACHE'); expect(cached.bars).toEqual(price.bars);
  expect(cached.error).toContain('HTTP 503');
  failing = false;
  expect((await receiver.loadPrice('005930', manifest.priceCutoffDate)).error).toBe(price.error);
});
