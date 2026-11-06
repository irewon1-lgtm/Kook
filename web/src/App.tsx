import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { BarChart3, Bell, Home as HomeIcon, ListOrdered, Search, Star, MoreHorizontal, Minus, Plus, RefreshCw, X } from 'lucide-react';
import Home from './screens/Home';
import Ranking from './screens/Ranking';
import DetailScreen from './screens/Detail';
import { Badge, DataStatus, SectionTitle } from './components/ui';
import { MarketReceiver } from './data/receiver';
import { METRIC_IDS, type Detail, type MetricId, type Stock } from './types';
import { formatDate, formatNumber } from './format';
import './styles.css';
import './app.css';

type Page = 'home' | 'ranking' | 'search' | 'favorites' | 'more';
interface Route { page: Page | 'stock'; code?: string; tab?: number; origin?: Page }
const MENUS = [
  { page: 'home', label: '홈', icon: HomeIcon }, { page: 'ranking', label: '종목순위', icon: BarChart3 },
  { page: 'search', label: '종목검색', icon: Search }, { page: 'favorites', label: '관심종목', icon: Star },
  { page: 'more', label: '더보기', icon: MoreHorizontal },
] as const;
function readRoute(): Route {
  const hash = window.location.hash.slice(1);
  const match = /^stock\/([0-9A-Z]{6})(?:\/([0-4]))?$/.exec(hash);
  if (match) return { page: 'stock', code: match[1], tab: Number(match[2] || 0), origin: window.history.state?.kr4Origin || 'ranking' };
  return { page: MENUS.some(menu => menu.page === hash) ? hash as Page : 'home' };
}
function stored<T>(key: string, fallback: T, accept: (value: unknown) => value is T): T {
  try { const value: unknown = JSON.parse(localStorage.getItem(key) || 'null'); return accept(value) ? value : fallback; } catch { return fallback; }
}
export default function App({ receiver }: { receiver: MarketReceiver }) {
  const state = useSyncExternalStore(receiver.subscribe, receiver.getState, receiver.getState);
  const [route, setRoute] = useState<Route>(readRoute);
  const [selected, setSelected] = useState<MetricId[]>([...METRIC_IDS]);
  const [query, setQuery] = useState('');
  const [textSize, setTextSize] = useState(() => stored('kr4-pc:text-size', 22, (value): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 20 && value <= 32));
  const [favorites, setFavorites] = useState<string[]>(() => stored('kr4-pc:favorites', [], (value): value is string[] => Array.isArray(value) && value.every(code => typeof code === 'string' && /^[0-9A-Z]{6}$/.test(code))));
  const [storageError, setStorageError] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);
  const detailVersion = useRef(0);
  useEffect(() => {
    const onRoute = () => setRoute(readRoute());
    window.addEventListener('popstate', onRoute); window.addEventListener('hashchange', onRoute);
    return () => { window.removeEventListener('popstate', onRoute); window.removeEventListener('hashchange', onRoute); };
  }, []);
  useEffect(() => {
    document.documentElement.style.fontSize = `${textSize}px`;
    try { localStorage.setItem('kr4-pc:text-size', JSON.stringify(textSize)); } catch { setStorageError('저장소 접근이 제한되어 글씨 설정이 재접속 후 유지되지 않을 수 있습니다.'); }
  }, [textSize]);
  useEffect(() => {
    try { localStorage.setItem('kr4-pc:favorites', JSON.stringify([...new Set(favorites)])); } catch { setStorageError('저장소 접근이 제한되어 관심종목이 재접속 후 유지되지 않을 수 있습니다.'); }
  }, [favorites]);
  useEffect(() => {
    const sync = (event: StorageEvent) => {
      try {
        const value: unknown = JSON.parse(event.newValue || 'null');
        if (event.key === 'kr4-pc:text-size' && typeof value === 'number' && Number.isInteger(value) && value >= 20 && value <= 32) setTextSize(value);
        if (event.key === 'kr4-pc:favorites' && Array.isArray(value) && value.every(code => typeof code === 'string' && /^[0-9A-Z]{6}$/.test(code))) setFavorites([...new Set(value)]);
      } catch { /* retain current user settings if another tab stores invalid data */ }
    };
    window.addEventListener('storage', sync); return () => window.removeEventListener('storage', sync);
  }, []);
  useEffect(() => {
    void receiver.refresh();
    const timer = window.setInterval(() => { if (!document.hidden) void receiver.refresh(); }, 5 * 60_000);
    const refresh = () => { if (!document.hidden) void receiver.refresh(); };
    window.addEventListener('online', refresh); window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh);
    return () => { clearInterval(timer); window.removeEventListener('online', refresh); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [receiver]);
  useEffect(() => {
    const version = ++detailVersion.current;
    if (route.page !== 'stock' || !route.code) return;
    setDetailLoading(true); setDetailError('');
    receiver.getDetail(route.code).then(value => { if (version === detailVersion.current) setDetail(value); })
      .catch(error => { if (version === detailVersion.current) { setDetail(null); setDetailError(error instanceof Error ? error.message : String(error)); } })
      .finally(() => { if (version === detailVersion.current) setDetailLoading(false); });
    return () => { ++detailVersion.current; };
  }, [receiver, route.page, route.code, state.manifest, state.financialByCode, state.valuationsByCode, state.candidatesByCode, state.candidatesError]);
  const navigate = (page: Page) => {
    window.history.pushState({}, '', `#${page}`); setRoute({ page }); window.scrollTo(0, 0);
  };
  const openStock = (code: string) => {
    const origin = route.page === 'stock' ? route.origin || 'ranking' : route.page;
    window.history.pushState({ kr4Origin: origin }, '', `#stock/${code}`);
    setRoute({ page: 'stock', code, tab: 0, origin }); window.scrollTo(0, 0);
  };
  const toggleFavorite = (code: string) => setFavorites(previous => previous.includes(code) ? previous.filter(value => value !== code) : [...previous, code]);
  const refresh = () => { void receiver.refresh(); };
  return <div className="shell" data-testid="kr4-app" data-source={state.source} data-refreshing={state.refreshing} data-source-commit={state.manifest?.sourceCommit || ''} data-data-base={receiver.getDataBase()}
    data-generation-id={state.manifest?.generationId || ''} data-stocks-sha={state.manifest?.files['stocks.json']?.sha256 || ''} data-data-kind={state.manifest?.dataKind || 'VERIFIED_PUBLISHED_SNAPSHOT'}
    data-input-hashes={JSON.stringify(Object.fromEntries(Object.entries(state.manifest?.inputHashes || {}).filter(([key]) => !key.startsWith('context/'))))}>
    <a className="skip-link" href="#main-content">본문 바로가기</a>
    <header className="app-header">
      <button className="brand" onClick={() => navigate('home')} aria-label="KR4 국내주식 홈"><BarChart3 aria-hidden="true"/><span><strong>KR4 국내주식</strong><small>성장 · 수익성 · 가치 · 흐름</small></span></button>
      <nav className="app-nav" aria-label="주요 메뉴">{MENUS.map(menu => <button key={menu.page} onClick={() => navigate(menu.page)} aria-current={(route.page === menu.page || route.page === 'stock' && route.origin === menu.page) ? 'page' : undefined}><menu.icon size={23} aria-hidden="true"/>{menu.label}</button>)}</nav>
      <div className="header-tools"><button className="button icon-button" onClick={() => navigate('search')} aria-label="종목 검색 열기"><Search/></button><button className="button icon-button" onClick={() => navigate('more')} aria-label="데이터 알림과 출처"><Bell/></button></div>
      <div className="text-controls" role="group" aria-label="글씨 크기"><span>큰 글씨 {textSize}px</span><button onClick={() => setTextSize(value => Math.max(20, value - 2))} disabled={textSize <= 20} aria-label="글씨 줄이기"><Minus size={20}/><span>줄이기</span></button><button onClick={() => setTextSize(value => Math.min(32, value + 2))} disabled={textSize >= 32} aria-label="글씨 더 크게"><Plus size={20}/><span>더 크게</span></button><button onClick={() => setTextSize(22)}>기본 큰 글씨</button></div>
    </header>
    {storageError && <p className="storage-notice" role="status">{storageError}</p>}
    <main id="main-content" className="content" tabIndex={-1}>
      <div hidden={route.page !== 'home'}><Home state={state} onStock={openStock} onNavigate={navigate} onRefresh={refresh}/></div>
      <div hidden={route.page !== 'ranking' && route.page !== 'search'}><Ranking state={state} selected={selected} onSelected={setSelected} query={query} onQuery={setQuery} onStock={openStock} mode={route.page === 'search' ? 'search' : 'ranking'}/></div>
      {route.page === 'favorites' && <Favorites stocks={state.stocks} financial={state.financialByCode} favorites={favorites} onStock={openStock} onRemove={toggleFavorite} onAdd={() => setPickerOpen(true)}/>}
      {route.page === 'more' && <div className="stack"><h1>더보기 · 데이터와 이용 안내</h1><DataStatus state={state} onRefresh={refresh}/>
        <section className="panel stack"><SectionTitle>내 브라우저 설정</SectionTitle><p>기본 주식 조회는 로그인 없이 무료입니다. 관심종목과 글씨 크기는 이 브라우저에만 저장되며 다른 컴퓨터·ChatGPT 계정과 공유하지 않습니다.</p><p>현재 글씨 {textSize}px · 관심종목 {favorites.length}개</p><button className="button" onClick={() => setTextSize(22)}>기본 큰 글씨로 되돌리기</button></section>
        <section className="panel stack"><SectionTitle>데이터 기준과 갱신</SectionTitle><p>4지표는 기존 KR4 원본 엔진의 매출 증가율·영업이익률·실적 PER·6개월 상승률입니다. 선택한 상대점수의 동일 비중 평균으로 순위를 계산하며, 재무위험은 점수와 순위를 바꾸지 않습니다.</p><p>LIVE는 GitHub에 게시된 검증 스냅샷을 새로 받은 상태입니다. 실시간 호가를 뜻하지 않습니다. 차트는 원천에서 수집·검증한 저장 일봉이므로 CACHE와 실제 마지막 거래일을 표시합니다.</p><p>재무 기준일 {state.manifest?.snapshotDate || '미확보'} · 주가 기준일 {state.manifest?.priceCutoffDate || '미확보'}</p>{state.financialError && <p className="notice-warning">재무자료 검증 보류: {state.financialError}. 4지표 순위는 계속 공개합니다.</p>}<p>사이트는 5분 간격과 창 복귀 시 새 게시본을 확인합니다. 서버 수집은 GitHub Actions 예약·자료 게시 후 실행되며 제공자 장애나 무료 서비스 제약으로 지연될 수 있습니다. 새 자료 확인 버튼은 게시본을 다시 확인합니다.</p><p>수정주가 보정 여부는 미확인입니다. 검증되지 않은 분할·병합 보정이나 결측 가격·분기를 만들지 않습니다.</p></section>
        <section className="panel stack"><SectionTitle>출처와 원본</SectionTitle><p>KRX KIND 종목 정보 · 금융감독원 OpenDART 재무자료 · 네이버증권 실제 일별 가격 · DART 공시와 회사 공식자료</p><p>웹 원본 커밋 <a href={`https://github.com/irewon1-lgtm/Kook/commit/${state.manifest?.sourceCommit || ''}`} target="_blank" rel="noopener noreferrer">{state.manifest?.sourceCommit || '미확인'}</a></p><a href="https://github.com/irewon1-lgtm/Kook" target="_blank" rel="noopener noreferrer">공개 저장소·계산 근거 보기</a><a href={`${import.meta.env.BASE_URL}verification/index.html`} target="_blank" rel="noopener noreferrer">PC 기능·정보 대조표와 검증 기록</a><p className="muted">판독과 최종 후보는 조사 우선순위를 위한 참고 정보입니다. 원본의 결측·부분 확인·불확실성을 함께 확인하세요.</p></section>
      </div>}
      {route.page === 'stock' && <>
        {detailLoading && (!detail || detail.summary.issuerId !== route.code) && <section className="panel" role="status">검증된 종목 상세자료를 불러오고 있습니다…</section>}
        {detailError && <section className="panel stack" role="alert"><h1>{state.stocks.length && !state.stocks.some(stock => stock.issuerId === route.code) ? '종목을 찾을 수 없습니다' : '종목 상세 수신 실패'}</h1><p>입력한 종목코드: {route.code}</p><p>{detailError}</p><p>정확한 6자리 종목코드를 확인해 주세요. 비슷한 이름의 다른 종목으로 자동 대체하지 않습니다.</p><div className="action-row"><button className="button" onClick={() => navigate(route.origin || 'ranking')}>종목 목록으로</button><button className="button" onClick={() => { setQuery(route.code || ''); navigate('search'); }}>종목코드로 다시 검색</button><button className="button" onClick={refresh}><RefreshCw/>다시 확인</button></div></section>}
        {detail && detail.summary.issuerId === route.code && <DetailScreen key={detail.summary.issuerId} detail={detail} state={state} initialTab={route.tab || 0} onBack={() => navigate(route.origin || 'ranking')} onStock={openStock} onToggleFavorite={toggleFavorite} isFavorite={favorites.includes(detail.summary.issuerId)} onRefresh={refresh}/>}
      </>}
    </main>
    <footer className="app-footer">KR4 국내주식 PC · 전체 공개 · 로그인 없이 이용 · 마지막 게시 {state.manifest ? formatDate(state.manifest.generatedAt) : '미확인'}</footer>
    {pickerOpen && <FavoritePicker stocks={state.stocks} current={favorites} onClose={() => setPickerOpen(false)} onSave={codes => { setFavorites(codes); setPickerOpen(false); }}/>}
  </div>;
}

function Favorites({ stocks, financial, favorites, onStock, onRemove, onAdd }: {
  stocks: Stock[]; financial: ReturnType<MarketReceiver['getState']>['financialByCode']; favorites: string[];
  onStock: (code: string) => void; onRemove: (code: string) => void; onAdd: () => void;
}) {
  const known = useMemo(() => new Map(stocks.map(stock => [stock.issuerId, stock])), [stocks]);
  return <div className="stack"><div className="section-title"><h1>관심종목 <span className="count-pill">{favorites.length}개</span></h1><button className="button primary" onClick={onAdd}><Plus/>관심종목 추가</button></div><p className="muted">이 브라우저에 저장합니다. 다른 사람의 컴퓨터·계정과 공유하지 않습니다.</p>
    {!favorites.length && <section className="panel"><p>아직 저장한 관심종목이 없습니다. 관심종목 추가에서 이름이나 종목코드를 검색하세요.</p></section>}
    <div className="favorite-list">{favorites.map(code => { const stock = known.get(code); return <section className="panel favorite-row" key={code}><div>{stock ? <button className="stock-open-button" onClick={() => onStock(code)}><strong>{stock.name}</strong><span>{code} · {stock.market} · {stock.sector}</span></button> : <p>{code} · 현재 게시자료에서 종목을 확인하지 못했습니다.</p>}</div>{stock && <Badge status={financial[code]?.status || null}/>}<button className="button" onClick={() => onRemove(code)} aria-label={`${stock?.name || code} 관심종목 삭제`}>삭제</button></section>; })}</div>
  </div>;
}
function FavoritePicker({ stocks, current, onClose, onSave }: { stocks: Stock[]; current: string[]; onClose: () => void; onSave: (codes: string[]) => void }) {
  const dialog = useRef<HTMLDialogElement>(null); const [query, setQuery] = useState(''); const [chosen, setChosen] = useState(new Set(current)); const [page, setPage] = useState(0);
  useEffect(() => { dialog.current?.showModal(); return () => dialog.current?.close(); }, []);
  const matches = stocks.filter(stock => `${stock.name} ${stock.issuerId} ${stock.sector}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
    .sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : a.issuerId < b.issuerId ? -1 : a.issuerId > b.issuerId ? 1 : 0);
  return <dialog ref={dialog} className="favorite-dialog" onCancel={onClose} onClick={event => {
    if (event.target !== event.currentTarget) return;
    const box = event.currentTarget.getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) onClose();
  }}><div className="stack"><div className="section-title"><h2>관심종목 추가</h2><button className="button" onClick={onClose} aria-label="관심종목 선택 닫기"><X/></button></div><label className="field-label">종목명·종목코드 검색<input autoFocus value={query} onChange={event => { setQuery(event.target.value); setPage(0); }} placeholder="예: 삼성전자 또는 005930"/></label><p>검색 {formatNumber(matches.length)}종목 · 선택 {chosen.size}개</p>{!matches.length && <p role="status">검색 결과가 없습니다. 회사명 또는 정확한 6자리 종목코드를 다시 확인하세요.</p>}<div className="favorite-picker-list">{matches.slice(page * 50, (page + 1) * 50).map(stock => <label className="favorite-choice" key={stock.issuerId}><input type="checkbox" checked={chosen.has(stock.issuerId)} onChange={() => setChosen(previous => { const next = new Set(previous); next.has(stock.issuerId) ? next.delete(stock.issuerId) : next.add(stock.issuerId); return next; })}/><span><strong>{stock.name}</strong><small>{stock.issuerId} · {stock.market}</small></span></label>)}</div><div className="action-row"><button className="button" onClick={() => setPage(value => value - 1)} disabled={!page}>이전</button><span>{page + 1} / {Math.max(1, Math.ceil(matches.length / 50))}</span><button className="button" onClick={() => setPage(value => value + 1)} disabled={(page + 1) * 50 >= matches.length}>다음</button></div><div className="action-row"><button className="button" onClick={onClose}>취소</button><button className="button primary" onClick={() => onSave([...chosen])}>선택 완료 ({chosen.size}개)</button></div></div></dialog>;
}
