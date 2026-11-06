import { expect, it } from 'vitest';
import { MarketReceiver, MemoryStore } from '../src/data/receiver';
import { recordedTar } from './recorded-fixture';

const positive = recordedTar('tests/fixtures/positive-candidate-recorded-20261105.tar.gz', 'f37f825a02c33251e713316f79cb40a11add85051882afbba85aba3858e4d679');
const before = recordedTar('e2e/fixtures/background-recorded-pair.tar.gz', 'b2a38438881bfc9306da3773c0bded9abe58d3a84d21319179966ae6b432a4c9');
const manifest = { ...JSON.parse(positive.get('manifest.json')!), dataKind: 'REAL_MARKET_RECORDED' as const };
const code = Object.keys(JSON.parse(positive.get('candidates.json')!).records).sort()[0];
it('R1 candidate reception HOLD applies to detail and recovers only after a verified candidate transaction', async () => {
  let unavailable = true;
  const receiver = new MarketReceiver({ bundled: { manifest, stocksText: positive.get('stocks.json')! }, store: new MemoryStore(), baseUrl: '/recorded/', fetcher: async url => {
    const name = url.replace('/recorded/', '');
    if (name === 'manifest.json') return new Response(JSON.stringify(manifest));
    if (name === 'candidates.json' && unavailable) return new Response('', { status: 503 });
    return positive.has(name) ? new Response(positive.get(name)) : new Response('', { status: 404 });
  } });
  await receiver.refresh();
  expect(receiver.getState().financialError).toBeNull();
  expect(receiver.getState().candidatesError).toBeTruthy();
  const held = await receiver.getDetail(code);
  expect(held.candidate).toBeNull();
  expect(held.sourceStates?.candidate).toMatchObject({ status: 'HOLD' });
  unavailable = false; await receiver.refresh();
  expect(receiver.getState().candidatesError).toBeNull();
  expect((await receiver.getDetail(code)).candidate?.issuerId).toBe(code);
});
it('R2 blocked storage plus offline refresh retains the newer genuinely received in-memory core', async () => {
  let offline = false;
  const oldManifest = { ...JSON.parse(before.get('before/manifest.json')!), dataKind: 'REAL_MARKET_RECORDED' as const };
  const receiver = new MarketReceiver({ bundled: { manifest: oldManifest, stocksText: before.get('before/stocks.json')! },
    store: { read: async () => { throw Error('CONTROLLED_STORAGE_DENIED'); }, write: async () => { throw Error('CONTROLLED_STORAGE_DENIED'); } }, baseUrl: '/recorded/', fetcher: async url => {
      if (offline) throw Error('CONTROLLED_OFFLINE');
      const name = url.replace('/recorded/', '');
      if (name === 'manifest.json') return new Response(JSON.stringify(manifest));
      return positive.has(name) ? new Response(positive.get(name)) : new Response('', { status: 404 });
    } });
  await receiver.refresh(); const received = receiver.getState();
  expect(received.manifest?.snapshotDate).not.toBe(oldManifest.snapshotDate);
  offline = true; await receiver.refresh();
  expect(receiver.getState().source).toBe('CACHE');
  expect(receiver.getState().manifest?.snapshotDate).toBe(received.manifest?.snapshotDate);
  expect(receiver.getState().lastSuccessAt).toBe(received.lastSuccessAt);
  expect(receiver.getState().stocks).toEqual(received.stocks);
});
