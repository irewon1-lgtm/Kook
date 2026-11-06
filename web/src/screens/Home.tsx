import { useMemo } from 'react';
import { ArrowRight, ChevronRight, Layers3 } from 'lucide-react';
import type { MarketState } from '../types';
import { METRIC_IDS, METRIC_LABELS } from '../types';
import { getMetric, rankStocks } from '../ranking';
import { formatNumber } from '../format';
import { Badge, DataStatus, ExpandableStockRow, MetricBars, SectionTitle, UniverseCounts } from '../components/ui';

type Page = 'home' | 'ranking' | 'search' | 'favorites' | 'more';
export default function Home({ state, onStock, onNavigate, onRefresh }: {
  state: MarketState; onStock: (code: string) => void; onNavigate: (page: Page) => void; onRefresh: () => void;
}) {
  const ranks = useMemo(() => rankStocks(state.stocks, [...METRIC_IDS]), [state.stocks]);
  const ranked = useMemo(() => state.stocks.filter(stock => ranks.get(stock.issuerId)?.isEligible).sort((a, b) => (ranks.get(a.issuerId)?.rank ?? Infinity) - (ranks.get(b.issuerId)?.rank ?? Infinity)), [state.stocks, ranks]);
  return <div className="home-page stack">
    <div className="home-overview">
      <button type="button" className="home-hero" onClick={() => onNavigate('ranking')}>
        <span className="hero-kicker"><Layers3 size={20} aria-hidden="true" />4축 스크리닝</span>
        <span className="hero-title">4지표 전체 종목보기<ChevronRight aria-hidden="true" /></span>
        <span className="hero-subtitle">성장 · 수익성 · 가치 · 흐름</span>
        <span className="hero-counts">{formatNumber(state.stocks.length, 0)}종목 공개 · 순위 산출 {formatNumber(ranked.length, 0)}종목<br />결측 종목도 빠짐없이 확인하세요.</span>
        <span className="hero-bars" aria-hidden="true">{[28, 42, 35, 58, 50, 70, 64, 90].map((height, i) => <i key={i} style={{ height: `${height}%` }} />)}</span>
      </button>
      <DataStatus state={state} onRefresh={onRefresh} />
    </div>
    <UniverseCounts total={state.stocks.length} ranked={ranked.length} />
    <section aria-labelledby="home-ranking-title" className="home-top-section">
      <SectionTitle aside={<span className="count-pill">{formatNumber(ranked.length, 0)}종목</span>}><span id="home-ranking-title">4지표 상위 순위</span></SectionTitle>
      <p className="section-description muted">상위 5종목 미리보기 · 재무자료 상태는 점수와 순위에 영향을 주지 않습니다.</p>
      <div className="stock-table home-stock-table">
        <div className="stock-table-heading home-stock-grid" aria-hidden="true"><span>순위</span><span>종목정보</span><span>4지표 요약</span><span>종합점수</span><span /></div>
        {ranked.slice(0, 5).map(stock => {
          const result = ranks.get(stock.issuerId)!;
          const safety = state.financialError ? undefined : state.financialByCode[stock.issuerId];
          return <ExpandableStockRow key={stock.issuerId} stock={stock} state={state} showRatios><button type="button" className="stock-row home-stock-grid" onClick={() => onStock(stock.issuerId)} aria-label={`${result.rank}위 ${stock.name} ${formatNumber(result.score, 1)}점 상세보기`}>
            <span className="rank-badge">{result.rank}위</span>
            <span className="stock-company"><span className="stock-name-line"><strong className="stock-name">{stock.name}</strong><Badge status={safety?.status ?? null} reason={safety?.reasonCode} /></span><span className="stock-meta">{stock.issuerId} · {stock.market}</span><span className="stock-sector">{stock.sector || '업종 미확보'}</span></span>
            <MetricBars stock={stock} compact />
            <span className="stock-score">{formatNumber(result.score, 1)}<small>점</small></span><span className="row-chevron" aria-hidden="true" />
          </button></ExpandableStockRow>;
        })}
        {!ranked.length && <div className="empty-state"><h3>산출 가능한 순위가 없습니다.</h3><p className="muted">전체 종목보기에서 각 지표의 자료 확보 상태를 확인하세요.</p></div>}
      </div>
      <button type="button" className="button full-list-action" onClick={() => onNavigate('ranking')}>전체 종목과 순위 보기 ({formatNumber(state.stocks.length, 0)}종목)<ArrowRight aria-hidden="true" /></button>
    </section>
    <div className="two-column home-education"><section className="panel" aria-label="데이터 커버리지"><SectionTitle>데이터 커버리지</SectionTitle><dl className="coverage-metric-rows">{METRIC_IDS.map(id => { const count = state.stocks.filter(stock => getMetric(stock, id).isAvailable).length; const percent = state.stocks.length ? count / state.stocks.length * 100 : 0; return <div key={id} data-testid={`coverage-${id}`}><dt>{METRIC_LABELS[id].full}</dt><dd>{formatNumber(count, 0)}개 · {percent.toFixed(1)}%</dd></div>; })}</dl></section><section className="panel" aria-label="4지표 해석 프레임"><SectionTitle>4지표 해석 프레임</SectionTitle><p>성장=매출 · 수익성=영업이익률 · 가치=실적 PER · 흐름=6개월 주가</p><p className="muted">각 지표는 시장 내 상대점수로 비교합니다. 실제 값과 상대점수는 서로 다르며, 결측값은 추정하지 않습니다.</p></section></div>
    <p className="home-source-footnote muted">종목 KRX KIND · 재무 OpenDART · PER/가격 네이버증권 공개 데이터</p>
  </div>;
}
