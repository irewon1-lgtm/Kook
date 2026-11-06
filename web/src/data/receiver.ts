import { METRIC_IDS, type Candidate, type Detail, type FinancialRecord, type MarketState, type PublicationManifest, type Stock, type PriceData, type ValuationBand } from '../types';
import { getMetric, rankStocks } from '../ranking';
export interface CacheStore { read(key: string): Promise<unknown>; write(key: string, value: unknown): Promise<void> }
export class MemoryStore implements CacheStore {
  private data = new Map<string, unknown>();
  async read(key: string) { return this.data.get(key); }
  async write(key: string, value: unknown) { this.data.set(key, value); }
}
export class BrowserStore implements CacheStore {
  private database: Promise<IDBDatabase>;
  constructor() {
    this.database = new Promise((resolve, reject) => {
      if (typeof indexedDB === 'undefined') { reject(Error('브라우저 저장소를 사용할 수 없습니다.')); return; }
      const request = indexedDB.open('kr4-pc-verified-v1', 1);
      request.onupgradeneeded = () => request.result.createObjectStore('artifacts');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    void this.database.catch(() => undefined);
  }
  async read(key: string) {
    const database = await this.database;
    return new Promise<unknown>((resolve, reject) => {
      const request = database.transaction('artifacts').objectStore('artifacts').get(key);
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
  }
  async write(key: string, value: unknown) {
    const database = await this.database;
    return new Promise<void>((resolve, reject) => {
      const transaction = database.transaction('artifacts', 'readwrite');
      transaction.objectStore('artifacts').put(value, key);
      transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
  }
}
function requireValue(condition: unknown, message: string): asserts condition { if (!condition) throw Error(message); }
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);
const datePattern = /^\d{4}-\d{2}-\d{2}$/;
const validDay = (value: string): boolean => datePattern.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);
export async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}
export function validateManifest(value: unknown): PublicationManifest {
  requireValue(record(value) && value.schemaVersion === 1, '게시 자료 스키마가 일치하지 않습니다.');
  const manifest = value as unknown as PublicationManifest;
  requireValue(validDay(manifest.snapshotDate) && validDay(manifest.priceCutoffDate), '기준일 형식 오류');
  requireValue(manifest.priceCutoffDate <= manifest.snapshotDate, '주가 기준일이 재무 기준일보다 미래입니다.');
  requireValue(Date.parse(manifest.snapshotDate) - Date.parse(manifest.priceCutoffDate) <= 10 * 86_400_000, '기준일 간격 오류');
  const todayKst = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  requireValue(Date.parse(manifest.snapshotDate) <= Date.parse(todayKst) + 86_400_000, '미래 기준일');
  requireValue(typeof manifest.sourceCommit === 'string' && /^[0-9a-f]{40}$/.test(manifest.sourceCommit), '원본 커밋 정보 오류');
  requireValue(record(manifest.coverage) && record(manifest.inputHashes) && record(manifest.files), '검증 메타데이터 누락');
  requireValue(!manifest.dataKind || ['VERIFIED_PUBLISHED_SNAPSHOT', 'REAL_MARKET_RECORDED'].includes(manifest.dataKind), '게시 자료 종류 오류');
  requireValue(!manifest.basePath || /^generations\/[a-zA-Z0-9._-]+\/$/.test(manifest.basePath), '데이터 경로 오류');
  for (const [path, file] of Object.entries(manifest.files)) {
    requireValue(!path.startsWith('/') && !path.split('/').includes('..') && !path.includes('\\'), '안전하지 않은 데이터 경로');
    requireValue(record(file) && /^[0-9a-f]{64}$/.test(file.sha256) && Number.isSafeInteger(file.bytes) && file.bytes > 0, '파일 해시 정보 오류');
  }
  requireValue(manifest.files['stocks.json'], '종목 자료 해시 누락');
  return manifest;
}
export function validateStocks(value: unknown, manifest: PublicationManifest): Stock[] {
  requireValue(Array.isArray(value) && value.length === 2649, '전체 2,649종목 자료가 아닙니다.');
  const stocks = value as Stock[];
  const seen = new Set<string>(); const counts = [0, 0, 0, 0]; let complete = 0;
  for (const stock of stocks) {
    requireValue(record(stock) && /^[0-9A-Z]{6}$/.test(stock.issuerId) && !seen.has(stock.issuerId), '종목 코드 중복 또는 오류');
    seen.add(stock.issuerId);
    requireValue(typeof stock.name === 'string' && stock.name.trim() && ['KOSPI', 'KOSDAQ'].includes(stock.market), '종목명·시장 오류');
    requireValue(stock.asOfDate === manifest.snapshotDate, '종목 기준일 불일치');
    METRIC_IDS.forEach((id, index) => {
      const metric = getMetric(stock, id);
      requireValue(record(metric) && metric.id === id, '지표 스키마 오류');
      if (metric.rawValue === null) {
        requireValue(metric.percentileScore === null && !metric.isAvailable && typeof metric.reason === 'string' && metric.reason.trim(), '결측 사유 누락');
      } else {
        requireValue(Number.isFinite(metric.rawValue) && Number.isFinite(metric.percentileScore) && metric.percentileScore! >= 0 && metric.percentileScore! <= 100 && metric.isAvailable && metric.basis, '검증되지 않은 지표 값');
        counts[index]++;
      }
    });
    const full = METRIC_IDS.every(id => getMetric(stock, id).rawValue !== null);
    requireValue(stock.isCompositeComplete === full && (stock.compositeScore !== null) === full && (stock.rankOrder !== null) === full, '종합점수·순위 완성도 불일치');
    if (full) complete++;
  }
  const floors = [Math.max(Math.floor(2649 * .88), 2469 - 55), Math.max(Math.floor(2649 * .84), 2380 - 55), Math.max(Math.floor(2649 * .58), 1677 - 80), Math.max(Math.floor(2649 * .96), 2611 - 30)];
  counts.forEach((count, index) => {
    requireValue(count >= floors[index], '원본 앱 지표 품질 기준 미달');
    requireValue(count === manifest.coverage[`m0${index + 1}Available`], '지표 확보 수량 불일치');
  });
  requireValue(complete >= Math.max(Math.floor(2649 * .5), 1462 - 100) && complete === manifest.coverage.completeCount && manifest.coverage.stocks === stocks.length, '순위 확보 수량 불일치');
  const ranks = rankStocks(stocks, METRIC_IDS);
  for (const stock of stocks) {
    const rank = ranks.get(stock.issuerId)!;
    requireValue(rank.rank === stock.rankOrder, '원본 종합순위 불일치');
    // The original Python publication stores composite at six decimal places;
    // DynamicRankingEngine retains the unrounded mean. Preserve both contracts.
    const storedDecimalHalfUnit = .5 / 1_000_000 + Number.EPSILON * 200;
    requireValue(rank.score === null || Math.abs(rank.score - stock.compositeScore!) <= storedDecimalHalfUnit, '원본 종합점수 저장 정밀도 불일치');
  }
  requireValue(stocks.find(stock => stock.issuerId === '005930')?.name === '삼성전자', '대표 종목 식별 오류');
  return stocks;
}
export function validatePrice(value: unknown, code: string, cutoff: string): PriceData {
  requireValue(record(value) && value.schemaVersion === 1 && value.issuerId === code && Array.isArray(value.bars), '가격 자료 형식 오류');
  const price = value as unknown as PriceData;
  requireValue(['CACHE', 'MISSING'].includes(price.source) && price.adjustment === 'UNVERIFIED', '주가 출처·보정 정보 오류');
  requireValue(validDay(price.requestedCutoffDate) && price.requestedCutoffDate <= cutoff, '주가 수집 기준일 불일치');
  let previous = '';
  for (const bar of price.bars) {
    requireValue(validDay(bar.date) && bar.date > previous && bar.date <= cutoff && bar.date <= price.requestedCutoffDate, '거래일 중복·미래 날짜 오류');
    requireValue([bar.open, bar.high, bar.low, bar.close].every(number => typeof number === 'number' && Number.isFinite(number) && number > 0), '실제 OHLC 미확보');
    requireValue(bar.low <= Math.min(bar.open, bar.close) && bar.high >= Math.max(bar.open, bar.close) && bar.low <= bar.high, 'OHLC 범위 오류');
    requireValue(bar.volume === null || Number.isSafeInteger(bar.volume) && bar.volume >= 0, '거래량 오류');
    previous = bar.date;
  }
  requireValue(price.lastTradingDate === (price.bars.at(-1)?.date ?? null), '실제 마지막 거래일 불일치');
  requireValue(price.bars.length ? price.source === 'CACHE' : price.source === 'MISSING', '저장 가격 출처 불일치');
  requireValue(price.bars.length ? typeof price.collectedAt === 'string' && Number.isFinite(Date.parse(price.collectedAt)) : price.collectedAt === null && typeof price.error === 'string' && price.error.trim(), '주가 확보 시각·미확보 사유 오류');
  return price;
}
export interface CoreBundle { manifest: PublicationManifest; stocksText: string; lastSuccessAt?: string }
type Fetcher = (url: string) => Promise<Response>;
export class MarketReceiver {
  private state: MarketState;
  private listeners = new Set<() => void>();
  private pending: Promise<void> | null = null;
  private lastVerifiedCore: CoreBundle | null = null;
  private options: { bundled: CoreBundle; store: CacheStore; fetcher: Fetcher; baseUrl: string };
  constructor(options: { bundled: CoreBundle; store?: CacheStore; fetcher?: Fetcher; baseUrl?: string }) {
    this.options = { fetcher: url => fetch(url, { cache: 'no-store' }), baseUrl: import.meta.env.VITE_KR4_DATA_BASE_URL || `${import.meta.env.BASE_URL}data/`, ...options, store: options.store ?? new BrowserStore() };
    let stocks: Stock[] = []; let manifest: PublicationManifest | null = null;
    try { manifest = validateManifest(options.bundled.manifest); stocks = validateStocks(JSON.parse(options.bundled.stocksText), manifest); } catch { /* validated fallbacks checked again during refresh */ }
    this.state = { manifest, stocks, source: stocks.length ? 'BUNDLED' : 'FAILED', lastSuccessAt: '', lastAttemptAt: '', error: null, refreshing: false, financialByCode: {}, financialError: null, valuationsByCode: {}, candidatesByCode: {} };
  }
  getState = () => this.state;
  getDataBase = () => this.options.baseUrl;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private setState(change: Partial<MarketState>) { this.state = { ...this.state, ...change }; this.listeners.forEach(listener => listener()); }
  private async read(key: string) { try { return await this.options.store.read(key); } catch { return undefined; } }
  private async save(key: string, value: unknown) { try { await this.options.store.write(key, value); } catch { /* quota denial never rejects genuine network data */ } }
  private async verifyText(text: string, manifest: PublicationManifest, path: string) {
    const expected = manifest.files[path];
    requireValue(expected && new TextEncoder().encode(text).byteLength === expected.bytes && await sha256(text) === expected.sha256, `게시 파일 검증 실패: ${path}`);
    return text;
  }
  private async validCore(value: unknown): Promise<CoreBundle> {
    requireValue(record(value) && typeof value.stocksText === 'string', '저장 자료 오류');
    const core = value as unknown as CoreBundle;
    const manifest = validateManifest(core.manifest);
    await this.verifyText(core.stocksText, manifest, 'stocks.json');
    validateStocks(JSON.parse(core.stocksText), manifest);
    return core;
  }
  private async artifact(manifest: PublicationManifest, path: string, allowCache = true): Promise<string> {
    const key = `artifact:${manifest.files[path]?.sha256 || 'missing'}:${path}`;
    try {
      requireValue(manifest.files[path], `자료 미게시: ${path}`);
      const response = await this.options.fetcher(this.options.baseUrl + (manifest.basePath || '') + path);
      requireValue(response.ok, `자료 수신 HTTP ${response.status}`);
      const text = await this.verifyText(await response.text(), manifest, path);
      await this.save(key, text); return text;
    } catch (error) {
      if (!allowCache) throw error;
      const saved = await this.read(key);
      if (typeof saved === 'string') return this.verifyText(saved, manifest, path);
      throw error;
    }
  }
  refresh = (): Promise<void> => {
    if (this.pending) return this.pending;
    this.pending = this.refreshOnce().finally(() => { this.pending = null; });
    return this.pending;
  };
  private async refreshOnce() {
    this.setState({ refreshing: true, lastAttemptAt: new Date().toISOString() });
    let manifest: PublicationManifest | null = null;
    try {
      const response = await this.options.fetcher(this.options.baseUrl + 'manifest.json');
      requireValue(response.ok, `자료 목록 HTTP ${response.status}`);
      manifest = validateManifest(await response.json());
      const stocksText = await this.artifact(manifest, 'stocks.json', false);
      const stocks = validateStocks(JSON.parse(stocksText), manifest);
      const old = await this.read('core:current');
      if (old && JSON.stringify((old as CoreBundle).manifest) !== JSON.stringify(manifest)) {
        try { await this.validCore(old); await this.save('core:backup', old); } catch { /* never promote a corrupted current cache */ }
      }
      const lastSuccessAt = new Date().toISOString();
      this.lastVerifiedCore = { manifest, stocksText, lastSuccessAt };
      await this.save('core:current', { manifest, stocksText, lastSuccessAt });
      this.setState({ manifest, stocks, source: manifest.dataKind === 'REAL_MARKET_RECORDED' ? 'CACHE' : 'LIVE', lastSuccessAt, error: null, financialByCode: {}, financialError: null, valuationsByCode: {}, candidatesByCode: {}, candidatesError: undefined });
    } catch (error) {
      let recovered = false;
      for (const [key, source] of [[':memory', 'CACHE'], ['core:current', 'CACHE'], ['core:backup', 'BACKUP'], ['', 'BUNDLED']] as const) {
        try {
          const core = await this.validCore(key === ':memory' ? this.lastVerifiedCore : key ? await this.read(key) : this.options.bundled);
          manifest = core.manifest;
          if (source !== 'BUNDLED') this.lastVerifiedCore = core;
          this.setState({ manifest, stocks: JSON.parse(core.stocksText), source, lastSuccessAt: core.lastSuccessAt || '', error: errorText(error), financialByCode: {}, valuationsByCode: {}, candidatesByCode: {}, candidatesError: undefined });
          recovered = true; break;
        } catch { /* try the next independently validated fallback */ }
      }
      if (!recovered) this.setState({ source: 'FAILED', stocks: [], manifest: null, error: errorText(error), financialByCode: {}, valuationsByCode: {}, candidatesByCode: {} });
    }
    // Optional safety is a separate transaction: it never rejects verified quant.
    if (manifest && this.state.source !== 'FAILED') {
      try {
        const financial = JSON.parse(await this.artifact(manifest, 'financial.json'));
        requireValue(financial.status === 'VALID' && financial.snapshotDate === manifest.snapshotDate && record(financial.records), '재무 기준일 불일치 또는 검증 보류');
        const known = new Set(this.state.stocks.map(stock => stock.issuerId));
        const records = financial.records as Record<string, FinancialRecord>;
        requireValue(Object.keys(records).length === known.size, '재무 종목 집합 불일치');
        for (const [code, row] of Object.entries(records)) requireValue(known.has(code) && row.issuerId === code && ['PASS', 'FAIL', 'HOLD', 'NOT_APPLICABLE'].includes(row.status), '재무 상태 오류');
        this.setState({ financialByCode: records, financialDate: financial.snapshotDate, financialError: null });
      } catch (error) { this.setState({ financialByCode: {}, financialError: errorText(error) }); }
      // Descriptive PER bands come directly from the original Kotlin classifier.
      // They remain independent of Stage4 availability and never enter ranking.
      try {
        const value = JSON.parse(await this.artifact(manifest, 'valuations.json'));
        requireValue(value.snapshotDate === manifest.snapshotDate && record(value.records), 'PER 분류 기준일 불일치');
        const records = value.records as Record<string, ValuationBand | null>;
        requireValue(Object.keys(records).length === this.state.stocks.length, 'PER 분류 종목 집합 불일치');
        for (const stock of this.state.stocks) {
          requireValue(Object.hasOwn(records, stock.issuerId), 'PER 분류 종목 누락');
          const band = records[stock.issuerId];
          requireValue(band === null || record(band) && typeof band.code === 'string' && typeof band.labelKo === 'string' && typeof band.shortLabelKo === 'string' && Number.isFinite(band.percentile) && band.percentile >= 0 && band.percentile <= 100 && band.changesRank === false, '원본 PER 분류 형식 오류');
        }
        this.setState({ valuationsByCode: records, valuationError: null });
      } catch (error) { this.setState({ valuationsByCode: {}, valuationError: errorText(error) }); }
      try {
        requireValue(!this.state.financialError && Object.keys(this.state.financialByCode).length === this.state.stocks.length, '재무자료 미확보로 의존 최종후보 목록 보류');
        const value = JSON.parse(await this.artifact(manifest, 'candidates.json'));
        requireValue(value.status === 'VALID' && value.snapshotDate === manifest.snapshotDate && value.priceCutoffDate === manifest.priceCutoffDate && record(value.records), value.error || '최종후보 기준일·검증 보류');
        const records = value.records as Record<string, Candidate>;
        for (const [code, row] of Object.entries(records)) {
          requireValue(this.state.stocks.some(stock => stock.issuerId === code) && row.issuerId === code && row.financialSnapshotDate === manifest.snapshotDate && row.priceCutoffDate === manifest.priceCutoffDate && this.state.financialByCode[code]?.status === 'PASS', '최종후보 원본 또는 재무 조건 불일치');
        }
        this.setState({ candidatesByCode: records, candidatesError: null });
      } catch (error) { this.setState({ candidatesByCode: {}, candidatesError: errorText(error) }); }
    }
    this.setState({ refreshing: false });
  }
  async getDetail(code: string): Promise<Detail> {
    const manifest = this.state.manifest;
    requireValue(manifest && this.state.stocks.some(stock => stock.issuerId === code), '알 수 없는 종목');
    const detail = JSON.parse(await this.artifact(manifest, `details/${code}.json`)) as Detail;
    requireValue(detail.summary?.issuerId === code && detail.summary.asOfDate === manifest.snapshotDate && record(detail.analysis) && record(detail.history) && record(detail.evidence), '종목 상세 기준 불일치');
    const currentFinancial = this.state.financialByCode[code];
    if (Object.hasOwn(this.state.valuationsByCode || {}, code)) detail.valuation = this.state.valuationsByCode![code];
    if (!currentFinancial) {
      detail.financial = null; detail.candidate = null;
      detail.sourceStates = { ...detail.sourceStates, financial: { status: 'HOLD', error: this.state.financialError || '검증된 재무자료 미확보' }, candidate: { status: 'HOLD', error: '재무자료에 의존하는 후보평가 보류' } };
    } else {
      detail.financial = currentFinancial;
      if (this.state.candidatesError !== null) {
        detail.candidate = null;
        detail.sourceStates = { ...detail.sourceStates, candidate: { status: 'HOLD', error: this.state.candidatesError || '최종후보 목록 검증 대기' } };
      } else {
        detail.candidate = this.state.candidatesByCode?.[code] ?? null;
        detail.sourceStates = { ...detail.sourceStates, candidate: { status: 'VALID', error: null } };
      }
    }
    return detail;
  }
  async loadPrice(code: string, cutoff: string): Promise<PriceData> {
    requireValue(/^[0-9A-Z]{6}$/.test(code), '유효하지 않은 종목 코드');
    const manifest = this.state.manifest;
    try {
      requireValue(manifest, '게시 자료를 먼저 받아야 합니다.');
      // This method has its own verified-price fallback, which also reports the
      // failed reception. A generic silent artifact fallback would hide outages.
      const text = await this.artifact(manifest, `prices/${code}.json`, false);
      const price = validatePrice(JSON.parse(text), code, cutoff);
      await this.save(`price:${code}`, { text, sha256: await sha256(text) });
      return price;
    } catch (error) {
      const saved = await this.read(`price:${code}`);
      if (record(saved) && typeof saved.text === 'string' && saved.sha256 === await sha256(saved.text)) {
        const price = validatePrice(JSON.parse(saved.text), code, cutoff);
        return { ...price, error: [price.error, '게시 자료 갱신 실패: ' + errorText(error)].filter(Boolean).join(' · ') };
      }
      throw error;
    }
  }
}
let activeReceiver: MarketReceiver | null = null;
export function configureReceiver(receiver: MarketReceiver) { activeReceiver = receiver; }
export function subscribePublication(listener: () => void) {
  return activeReceiver?.subscribe(listener) ?? (() => undefined);
}
export function getPriceRevision(code: string): string {
  return activeReceiver?.getState().manifest?.files[`prices/${code}.json`]?.sha256 || '';
}
export function loadPrice(code: string, cutoff: string): Promise<PriceData> {
  requireValue(activeReceiver, '주가 수신기를 준비 중입니다.');
  return activeReceiver.loadPrice(code, cutoff);
}
