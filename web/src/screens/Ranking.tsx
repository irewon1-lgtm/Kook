import { useMemo, useState } from 'react';
import { Check, ChevronLeft, ChevronRight, Search } from 'lucide-react';
import type { MarketState, MetricId, Stock } from '../types';
import { METRIC_IDS, METRIC_LABELS } from '../types';
import { getMetric, rankStocks } from '../ranking';
import { formatDate, formatNumber } from '../format';
import { Badge, ExpandableStockRow, FinancialAvailability, SectionTitle, UniverseCounts } from '../components/ui';

type SafetyFilter = 'ALL' | 'HOLD' | 'FAIL';
type Sort = 'COMBINATION' | 'CODE' | 'NAME';
const sourceLabels = { LIVE: 'LIVE', CACHE: 'CACHE · 저장 자료', BACKUP: 'BACKUP · 이전 정상 자료', BUNDLED: 'BUNDLED · 기본 자료', FAILED: 'FAILED · 수신 실패' };
export default function Ranking({ state, selected, onSelected, query, onQuery, onStock, mode = 'ranking' }: {
  state: MarketState; selected: MetricId[]; onSelected: (ids: MetricId[]) => void; query: string; onQuery: (query: string) => void; onStock: (code: string) => void; mode?: 'ranking' | 'search';
}) {
  const [market, setMarket] = useState('전체');
  const [completeOnly, setCompleteOnly] = useState(false);
  const [safety, setSafety] = useState<SafetyFilter>('ALL');
  const [sort, setSort] = useState<Sort>('COMBINATION');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const ranks = useMemo(() => rankStocks(state.stocks, selected), [state.stocks, selected]);
  const rankedCount = useMemo(() => [...ranks.values()].filter(rank => rank.isEligible).length, [ranks]);
  const filtered = useMemo(() => {
    const search = query.trim().toLocaleLowerCase('ko');
    return state.stocks.filter(stock => {
      const status = state.financialError ? 'HOLD' : state.financialByCode[stock.issuerId]?.status ?? 'HOLD';
      return (!search || [stock.name, stock.issuerId, stock.sector, stock.market].some(value => value.toLocaleLowerCase('ko').includes(search)))
        && (market === '전체' || stock.market === market)
        && (!completeOnly || ranks.get(stock.issuerId)?.isEligible === true)
        && (safety === 'ALL' || status === safety);
    }).sort((a, b) => {
      if (sort === 'NAME') return a.name < b.name ? -1 : a.name > b.name ? 1 : compareCode(a, b);
      if (sort === 'CODE') return compareCode(a, b);
      return (ranks.get(a.issuerId)?.rank ?? Infinity) - (ranks.get(b.issuerId)?.rank ?? Infinity) || compareCode(a, b);
    });
  }, [state.stocks, state.financialByCode, state.financialError, ranks, query, market, completeOnly, safety, sort]);
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const currentPage = Math.min(page, pages);
  const visible = filtered.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const eligibleShown = filtered.filter(stock => ranks.get(stock.issuerId)?.isEligible).length;
  const resetPage = (action: () => void) => { action(); setPage(1); };
  const toggleMetric = (id: MetricId) => {
    if (selected.includes(id) && selected.length === 1) return;
    resetPage(() => onSelected(METRIC_IDS.filter(metric => selected.includes(metric) ? metric !== id : metric === id)));
  };
  const weight = selected.length === 3 ? '각 33.3%' : selected.length === 1 ? '100%' : `각 ${100 / selected.length}%`;
  return <div className="ranking-page stack">
    <section className="ranking-intro"><h1>{mode === 'search' ? '종목 검색' : '종목 순위'}</h1><p className="muted">선택한 {selected.length}지표의 종합 상대점수 순위입니다. 시장·검색·재무자료 필터를 바꿔도 전체 시장 순위는 유지됩니다.</p></section>

    <section className="panel metric-selector" aria-labelledby="metric-selector-title">
      <SectionTitle aside={<span className="selection-weight">{selected.length}개 선택 · {weight}</span>}><span id="metric-selector-title">비교할 지표</span></SectionTitle>
      <p className="muted">최소 1개를 선택하세요. 선택한 지표를 같은 비중으로 비교합니다.</p>
      <div className="metric-selector-grid">{METRIC_IDS.map(id => <button key={id} type="button" className={`metric-toggle${selected.includes(id) ? ' selected' : ''}`} aria-pressed={selected.includes(id)} aria-disabled={selected.includes(id) && selected.length === 1} onClick={() => toggleMetric(id)} data-testid={`metric-toggle-${id}`}><span className="metric-toggle-heading"><span>{METRIC_LABELS[id].short}</span><span className="metric-check" aria-hidden="true">{selected.includes(id) && <Check size={21} />}</span></span><span>{METRIC_LABELS[id].full}</span></button>)}</div>
      <label className="checkbox-label"><input type="checkbox" checked={completeOnly} onChange={event => resetPage(() => setCompleteOnly(event.target.checked))} />선택지표 완성 종목만 보기</label>
    </section>
    <div className="ranking-source-summary panel"><span className={`source-dot${state.source === 'LIVE' ? ' live' : ''}`} /><strong>데이터 출처 <span className="source-label">{sourceLabels[state.source]}</span></strong><span className="muted">GitHub 게시 자료 · 재무 {state.manifest?.snapshotDate || '미확보'} · 주가 {state.manifest?.priceCutoffDate || '미확보'}</span><span className="muted ranking-receive-times">최근 갱신 {state.lastSuccessAt ? formatDate(state.lastSuccessAt) : '성공 기록 없음'} · 최근 확인 {state.lastAttemptAt ? formatDate(state.lastAttemptAt) : '확인 기록 없음'}</span>{state.error && <span className="status-error">갱신 실패 · {state.error} · {state.stocks.length ? '마지막 정상 자료 표시' : '표시할 검증 자료 없음'}</span>}</div>
    <FinancialAvailability state={state} />
    <section className="ranking-filters panel" aria-label="종목 검색 및 필터">
      <label className="search-field"><span>회사명 · 종목코드 · 업종 · 시장 검색</span><span className="search-input-wrap"><Search size={24} aria-hidden="true" /><input type="search" value={query} onChange={event => resetPage(() => onQuery(event.target.value))} placeholder="회사명 또는 종목코드를 입력하세요" autoFocus={mode === 'search'} /></span></label>
      <div className="filter-line"><div className="filter-group" role="group" aria-label="시장">{['전체', 'KOSPI', 'KOSDAQ'].map(value => <button type="button" key={value} className={`button filter-button${market === value ? ' active' : ''}`} aria-pressed={market === value} onClick={() => resetPage(() => setMarket(value))}>{value}</button>)}</div><label className="sort-select">정렬 <select value={sort} onChange={event => resetPage(() => setSort(event.target.value as Sort))}><option value="COMBINATION">종합점수 순</option><option value="CODE">종목코드</option><option value="NAME">회사명</option></select></label></div>
      <div className="filter-line"><div className="filter-group" role="group" aria-label="재무자료 상태">{(['ALL', 'HOLD', 'FAIL'] as const).map(value => <button type="button" key={value} className={`button filter-button${safety === value ? ' active' : ''}`} aria-pressed={safety === value} onClick={() => resetPage(() => setSafety(value))}>{value === 'ALL' ? '전체' : value === 'HOLD' ? '미확보만' : '재무위험만'}</button>)}</div><span className="filter-explanation muted">재무자료 상태는 점수·순위에 영향 없음</span></div>
    </section>
    <UniverseCounts total={state.stocks.length} ranked={rankedCount} selectedCount={selected.length} />
    <section aria-labelledby="ranking-results-title" className="ranking-results">
      <SectionTitle aside={<label className="page-size">한 번에 <select value={pageSize} onChange={event => resetPage(() => setPageSize(Number(event.target.value)))}><option value="50">50종목</option><option value="100">100종목</option></select></label>}><span id="ranking-results-title">검색 결과 {formatNumber(filtered.length, 0)}종목</span></SectionTitle>
      <p className="muted result-description" aria-live="polite">순위 산출 {formatNumber(eligibleShown, 0)}종목 · 선택지표 결측 {formatNumber(filtered.length - eligibleShown, 0)}종목 · {filtered.length ? formatNumber((currentPage - 1) * pageSize + 1, 0) : '0'}–{formatNumber(Math.min(currentPage * pageSize, filtered.length), 0)}번째 표시</p>
      <div className="stock-table ranking-stock-table"><div className="stock-table-heading ranking-stock-grid" aria-hidden="true"><span>전체 순위</span><span>종목명 · 코드</span><span>시장 · 업종</span><span>종합점수 · 재무자료</span><span /></div>
        {visible.map(stock => {
          const rank = ranks.get(stock.issuerId);
          const financial = state.financialError ? undefined : state.financialByCode[stock.issuerId];
          const missing = selected.map(id => getMetric(stock, id)).filter(metric => !metric.isAvailable || metric.percentileScore === null || metric.rawValue === null);
          return <ExpandableStockRow key={stock.issuerId} stock={stock} state={state} scoreLabel={rank?.isEligible ? `선택 ${selected.length}개 지표 종합 상대점수` : '선택지표 결측 · 조합순위 보류'}><button type="button" className={`stock-row ranking-stock-grid${rank?.isEligible ? '' : ' incomplete-stock'}`} onClick={() => onStock(stock.issuerId)} aria-label={`${rank?.rank ? `${rank.rank}위` : '순위 보류'} ${stock.name} 상세보기`}>
            <span className={rank?.rank ? 'rank-badge' : 'rank-pending'}>{rank?.rank ? `${rank.rank}위` : '보류'}</span>
            <span className="stock-company"><strong className="stock-name">{stock.name}</strong><span className="stock-meta">{stock.issuerId}</span></span>
            <span className="stock-market"><span>{stock.market}</span><span className="stock-sector">{stock.sector || '업종 미확보'}</span></span>
            <span className="ranking-score-safety"><span className="stock-score">{rank?.score === null || rank?.score === undefined ? '미산출' : <>{formatNumber(rank.score, 1)}<small>점</small></>}</span><Badge status={financial?.status ?? null} reason={financial?.reasonCode} /></span><span className="row-chevron" aria-hidden="true" />
            {!rank?.isEligible && <span className="missing-reasons"><strong>선택지표 결측 · 조합순위 보류</strong>{missing.length ? missing.map(metric => <span key={metric.id}>{METRIC_LABELS[metric.id].full}: {metric.reason || '순위 산출에 필요한 검증된 값이 없습니다.'}</span>) : <span>선택한 지표의 순위 산출 조건이 충족되지 않았습니다.</span>}</span>}
          </button></ExpandableStockRow>;
        })}
        {!filtered.length && <div className="empty-state"><h3>검색 결과가 없습니다.</h3><p className="muted">검색어·시장·완성 조건·재무자료 필터를 바꿔보세요.</p><button type="button" className="button" onClick={() => resetPage(() => { onQuery(''); setMarket('전체'); setCompleteOnly(false); setSafety('ALL'); })}>검색 조건 초기화</button></div>}
      </div>
      <nav className="pagination" aria-label="종목 목록 페이지"><button type="button" className="button" disabled={currentPage <= 1} onClick={() => setPage(1)}>처음</button><button type="button" className="button" disabled={currentPage <= 1} onClick={() => setPage(currentPage - 1)}><ChevronLeft size={20} aria-hidden="true" />이전</button><span aria-live="polite">{currentPage} / {pages} 페이지</span><button type="button" className="button" disabled={currentPage >= pages} onClick={() => setPage(currentPage + 1)}>다음<ChevronRight size={20} aria-hidden="true" /></button><button type="button" className="button" disabled={currentPage >= pages} onClick={() => setPage(pages)}>마지막</button></nav>
    </section>
    <section className="panel ranking-guide" aria-label="순위 산출 안내"><SectionTitle>순위 산출 안내</SectionTitle><p>성장·수익성·가치·흐름 중 선택한 {selected.length}개 지표의 시장 내 상대점수를 동일 비중({weight})으로 비교합니다.</p><p>선택한 지표: {METRIC_IDS.filter(id => selected.includes(id)).map(id => METRIC_LABELS[id].short).join(' · ')}</p><p>4개 지표에서 최소 1개를 선택하며, 15가지 조합을 비교할 수 있습니다. 선택한 지표가 없는 종목도 목록에 공개하며 순위는 보류합니다.</p><p>재무자료 상태는 점수와 순위에 영향을 주지 않습니다. 시장·검색·재무자료 필터를 적용해도 전체 시장에서 계산한 순위는 유지됩니다.</p><p className="muted">종목명을 누르면 상세정보로 이동합니다. 행 오른쪽 펼치기 버튼은 한눈 비교와 재무자료·PER 분류를 열고 닫습니다. 홈으로 돌아가려면 상단 ‘홈’ 메뉴를 선택하세요.</p></section>
  </div>;
}
function compareCode(a: Stock, b: Stock) { return a.issuerId < b.issuerId ? -1 : a.issuerId > b.issuerId ? 1 : a.market < b.market ? -1 : a.market > b.market ? 1 : 0; }
