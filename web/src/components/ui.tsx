import { useId, useState, type CSSProperties, type ReactNode } from 'react';
import { CalendarDays, CheckCircle2, Database, Layers3, RefreshCw, ChevronDown, ChevronUp } from 'lucide-react';
import type { FinancialRecord, MarketState, SafetyStatus, Stock } from '../types';
import { METRIC_IDS, METRIC_LABELS } from '../types';
import { getMetric } from '../ranking';
import { formatDate, formatExactWon, formatMetric, formatNumber, safeExternalUrl } from '../format';

const safetyLabels: Record<SafetyStatus, string> = {
  PASS: '자료 확인', FAIL: '재무위험', HOLD: '미확보', NOT_APPLICABLE: '비교제외',
};

const financialReasons: Record<string, string> = {
  "PASS": "부채비율·유동비율·자본·회계 identity 기준 통과",
  "NONPOSITIVE_EQUITY": "자본이 0 이하라 안전성 gate 탈락",
  "DEBT_TO_EQUITY_OVER_400": "부채비율이 400%를 초과",
  "CURRENT_RATIO_UNDER_70": "유동비율이 70% 미만",
  "ACCOUNTING_IDENTITY_MISMATCH": "자산=부채+자본 회계 identity 오차가 허용범위를 초과",
  "NONPOSITIVE_ASSETS": "자산총계가 유효한 양수 값이 아님",
  "NONPOSITIVE_CURRENT_LIABILITIES": "유동부채가 유효한 양수 값이 아니라 비율 계산 보류",
  "DART_NO_BALANCE_SHEET": "OpenDART에서 검증 가능한 재무상태표를 찾지 못함",
  "FINANCIAL_SECTOR_NOT_COMPARABLE": "금융업은 일반기업 재무비율 gate와 직접 비교하지 않음",
  "SOURCE_UNAVAILABLE": "공식 공시 서버 응답을 확보하지 못해 검증 보류",
  "COLLECTION_ERROR": "공식 공시 서버 응답을 확보하지 못해 검증 보류",
  "DART_IDENTITY_UNCONFIRMED": "종목·법인·회사명 매핑을 검증하지 못해 보류",
  "ISSUER_NAME_MISMATCH": "종목·법인·회사명 매핑을 검증하지 못해 보류",
  "CORRECTION_VALUES_NOT_RECONCILED": "정정공시와 원본 금액의 일치 여부를 확인하지 못해 보류",
  "BULK_AMOUNT_MISMATCH": "정정공시 API와 재무상태표 원본 금액이 달라 보류",
  "RECEIPT_MISMATCH": "정정공시 접수번호와 재무항목 API의 접수번호가 달라 보류",
  "MIXED_PERIOD": "서로 다른 보고기간의 원자료가 충돌해 보류",
  "MIXED_CURRENCY": "통화·단위를 검증하지 못해 보류",
  "UNVERIFIED_CURRENCY_UNIT": "통화·단위를 검증하지 못해 보류",
  "NONFINITE_DERIVED_RATIO": "파생 재무비율이 유효한 유한값이 아니라 보류"
};
function financialReason(reason: string) { return financialReasons[reason] || (reason.startsWith('MISSING_') ? `필수 재무항목 누락으로 보류 (${reason})` : reason); }

export function Badge({ status, reason, onClick }: { status: SafetyStatus | null; reason?: string; onClick?: () => void }) {
  const resolved = status ?? 'HOLD';
  const label = safetyLabels[resolved];
  const className = `safety-badge safety-${resolved.toLowerCase()}`;
  return onClick
    ? <button type="button" className={className} title={reason ? financialReason(reason) : undefined} onClick={onClick} aria-label={`재무자료 상태: ${label}${reason ? ` · ${reason}` : ''}`}>{label}</button>
    : <span className={className} title={reason ? financialReason(reason) : undefined}>{label}</span>;
}

export function MetricBars({ stock, compact = false }: { stock: Stock; compact?: boolean }) {
  return <div className={`metric-bars${compact ? ' compact' : ''}`} aria-label="4지표 요약">
    {METRIC_IDS.map(id => {
      const metric = getMetric(stock, id);
      const value = metric.isAvailable && metric.rawValue !== null ? formatMetric(metric) : '미확보';
      const score = metric.isAvailable && metric.percentileScore !== null ? Math.max(0, Math.min(100, metric.percentileScore)) : 0;
      return <div className="metric-bar-row" key={id} title={metric.reason || `${metric.nameKo} · ${metric.basis}`}>
        <span>{METRIC_LABELS[id].short}</span>
        <span className="metric-track" aria-hidden="true"><span style={{ width: `${score}%` }} /></span>
        <span className={value === '미확보' ? 'muted' : ''}>{value}</span>
      </div>;
    })}
  </div>;
}

export function SectionTitle({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return <div className="section-title"><h2>{children}</h2>{aside && <div className="section-aside">{aside}</div>}</div>;
}

export function DataStatus({ state, onRefresh }: { state: MarketState; onRefresh: () => void }) {
  const complete = state.stocks.filter(stock => stock.isCompositeComplete).length;
  const total = state.stocks.length;
  const percent = total ? complete / total * 100 : 0;
  const sourceLabels = { LIVE: 'LIVE', CACHE: 'CACHE · 저장 자료', BACKUP: 'BACKUP · 이전 정상 자료', BUNDLED: 'BUNDLED · 기본 자료', FAILED: 'FAILED · 수신 실패' };
  return <section className={`panel data-status source-${state.source.toLowerCase()}`} aria-label="데이터 출처와 갱신 상태">
    <div className="data-status-main">
      <div className="data-source-heading"><span className={`source-dot${state.source === 'LIVE' ? ' live' : ''}`} /><strong>데이터 출처</strong><span className="source-label">{sourceLabels[state.source]}</span></div>
      <p className="data-publisher muted">GitHub에 게시된 스냅샷 · 원천자료 기준일 별도 표시</p>
      <dl className="status-lines">
        <div><dt><Layers3 aria-hidden="true" />4지표 완성</dt><dd>{formatNumber(complete, 0)} / {formatNumber(total, 0)}종목</dd></div>
        <div><dt><CalendarDays aria-hidden="true" />자료 기준</dt><dd>재무 {state.manifest?.snapshotDate || '미확보'}<br className="status-date-break" /> · 주가 {state.manifest?.priceCutoffDate || '미확보'}</dd></div>
        <div><dt><RefreshCw aria-hidden="true" />최근 갱신</dt><dd>{state.lastSuccessAt ? formatDate(state.lastSuccessAt) : '성공 기록 없음'}</dd></div>
        <div><dt><CheckCircle2 aria-hidden="true" />최근 확인</dt><dd>{state.lastAttemptAt ? formatDate(state.lastAttemptAt) : '확인 기록 없음'}</dd></div>
      </dl>
      {state.error && <p className="status-error" role="status">갱신 실패 · {state.error}{total > 0 ? ' · 마지막 정상 자료를 표시합니다.' : ''}</p>}
      <FinancialAvailability state={state} />
      <button type="button" className="button refresh-button" onClick={onRefresh} disabled={state.refreshing}><RefreshCw size={20} aria-hidden="true" className={state.refreshing ? 'spinning' : ''} />{state.refreshing ? '자료 확인 중…' : '새 자료 확인'}</button>
    </div>
    <div className="coverage-block"><div className="coverage-ring" style={{ '--coverage': `${percent}%` } as CSSProperties}><span><strong>{formatNumber(percent, 1)}<small>%</small></strong></span></div><span className="muted">4지표 완성률</span></div>
  </section>;
}

export function UniverseCounts({ total, ranked, selectedCount = 4 }: { total: number; ranked: number; selectedCount?: number }) {
  return <div className="universe-counts" aria-label="전체 종목 현황">
    <div><Database aria-hidden="true" /><span>전체 종목<strong>{formatNumber(total, 0)}<small>종목</small></strong></span></div>
    <div className="count-highlight"><Layers3 aria-hidden="true" /><span>순위 산출<strong>{formatNumber(ranked, 0)}<small>종목</small></strong></span></div>
    <div><span className="count-warning" aria-hidden="true">△</span><span>선택 {selectedCount}지표 결측<strong>{formatNumber(total - ranked, 0)}<small>종목</small></strong></span></div>
  </div>;
}


export function FinancialAvailability({ state }: { state: MarketState }) {
  return state.financialError ? <p className="financial-availability" role="status"><strong>HOLD · 재무자료 검증 보류</strong><span>{state.financialError}</span><span>검증된 4지표 자료와 순위는 계속 공개합니다. 재무자료 상태는 점수와 순위를 바꾸지 않습니다.</span></p> : null;
}


/** Detail navigation and comparison expansion are sibling controls, with independent keyboard targets. */
export function ExpandableStockRow({ stock, state, children, scoreLabel = '4지표 종합 상대점수', showRatios = false }: { stock: Stock; state: MarketState; children: ReactNode; scoreLabel?: string; showRatios?: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const evidenceId = useId();
  return <div className="stock-row-container"><div className="stock-row-header">{children}<button type="button" className="row-expand" aria-label={`${stock.name} 한눈 비교 ${expanded ? '접기' : '펼치기'}`} aria-expanded={expanded} aria-controls={evidenceId} onClick={() => setExpanded(value => !value)}>{expanded ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}</button></div>{expanded && <section id={evidenceId} className="stock-inline-evidence" aria-label={`${stock.name} 한눈 비교`}><StockInlineEvidence stock={stock} state={state} scoreLabel={scoreLabel} showRatios={showRatios} /></section>}</div>;
}

function StockInlineEvidence({ stock, state, scoreLabel, showRatios }: { stock: Stock; state: MarketState; scoreLabel: string; showRatios: boolean }) {
  const safety = state.financialError ? null : state.financialByCode[stock.issuerId];
  const valuation = state.valuationError ? undefined : state.valuationsByCode?.[stock.issuerId];
  const candidate = state.financialError || state.candidatesError ? undefined : state.candidatesByCode?.[stock.issuerId];
  const statusLabels: Record<string, string> = { REAL: '실데이터', REGISTERED: '등록', DEMO: '데모', MISSING: '결측', WAITING_FOR_AUTH: '연결 대기' };
  return <><div className="inline-heading"><h3>한눈 비교</h3><span className="muted">시장 내 상대위치</span><span className="count-pill">{statusLabels[stock.status] || stock.status}</span></div><p className="muted">{stock.issuerId} · {stock.market} · {stock.sector || '업종 미확보'}</p><p className="muted">{scoreLabel}</p>
    <div className="inline-metrics">{METRIC_IDS.map(id => {
      const metric = getMetric(stock, id), score = metric.percentileScore;
      // Presentation wording from original HomeScreen.marketPosition; no valuation band is calculated here.
      const position = score == null ? '상대점수 없음' : score >= 90 ? '시장 상위 10%' : score >= 75 ? '시장 상위 25%' : score >= 50 ? '시장 중상위' : score >= 25 ? '시장 중하위' : '시장 하위';
      return <div className="inline-metric" key={id}><strong>{metric.nameKo}</strong><span>{metric.isAvailable && metric.rawValue !== null ? formatMetric(metric) : 'N/A · 미확보'}</span><span className="muted">시장 상대점수 {score == null ? '미확보' : `${formatNumber(score, 1)}점`} · {metric.isAvailable ? position : metric.reason || (stock.isLossMaking && id === 'M03' ? '적자 · PER 미적용' : '자료 확인 필요')}</span>{metric.reason && metric.isAvailable && <span className="muted">{metric.reason}</span>}</div>;
    })}</div>
    <div className="inline-stage-grid"><div className="inline-stage"><h3>Stage4 재무안정성</h3><div className="inline-heading">{candidate && <span className="count-pill">FINAL #{candidate.candidateRank}</span>}<Badge status={safety?.status ?? null} reason={safety?.reasonCode} /><span className="muted">{safety?.status || 'HOLD'}</span></div>{state.financialError && <p className="financial-availability">HOLD · {state.financialError}</p>}{showRatios && <p className="muted">부채비율 {ratio(safety?.debtToEquityPct, 1)} · 유동비율 {ratio(safety?.currentRatioPct, 1)} · 회계 gap {ratio(safety?.accountingIdentityGapPct, 2)}</p>}<p className="muted">재무자료 출처: 검증된 전체 종목 재무자료 · 기준일 {state.financialDate || state.manifest?.snapshotDate || '미확보'}</p>{state.candidatesError && <p className="financial-availability">최종 조사 후보 확인 보류 · {state.candidatesError}</p>}<details className="inline-financial-details"><summary>재무자료와 판정 근거</summary><InlineFinancialFacts safety={safety ?? null} snapshotDate={state.financialDate || state.manifest?.snapshotDate || ''} /></details></div>
    <div className="inline-stage"><h3>Stage5 상대 PER 밴드</h3><span className="count-pill">{valuation?.shortLabelKo || 'PER 밴드 N/A'}</span><p>{valuation?.labelKo || '양수 PER만 분류 · 검증된 원본 분류 미확보'}</p><p className="muted">{valuation ? `PER 상대 ${formatNumber(valuation.percentile, 1)}` : 'PER 상대 분류 미확보'}</p>{state.valuationError && <p className="financial-availability">원본 PER 분류 확인 보류 · {state.valuationError}</p>}<p className="muted">원본 Kotlin ValuationBandPolicy 분류 · 설명용이며 순위를 변경하지 않습니다.</p></div></div>
    <p className="muted">재무위험 표시는 참고자료입니다. 종목 공개와 기존 4지표 순위에는 영향을 주지 않습니다.</p>
  </>;
}
function ratio(value: number | null | undefined, digits: number) { return value == null ? '미확보' : `${formatNumber(value, digits)}%`; }
function InlineFinancialFacts({ safety, snapshotDate }: { safety: FinancialRecord | null; snapshotDate: string }) {
  const f = safety?.facts;
  const amounts = [['assets', '자산총계'], ['liabilities', '부채총계'], ['equity', '자본총계'], ['current_assets', '유동자산'], ['current_liabilities', '유동부채']];
  const url = f?.sourceUrl ? safeExternalUrl(f.sourceUrl) : null;
  const rows: Array<[string, ReactNode]> = [
    ['판정', `${safety?.status || 'HOLD'} · ${safetyLabels[safety?.status || 'HOLD']}`], ['판정 사유', safety?.reasonCode ? financialReason(safety.reasonCode) : '기준일이 일치하는 검증된 재무자료가 없습니다'], ['판정 사유 코드', safety?.reasonCode || '미확보'], ['자료 기준일', snapshotDate || '미확보'],
    ['보고기간', f?.reportPeriod || '미확보'], ['실제 공시일', f?.filingDate || '미확보'], ['연결·별도', safety?.scope === 'CFS' ? '연결 (CFS)' : safety?.scope === 'OFS' ? '별도 (OFS)' : '미확보'], ['자료 검증', f?.verified ? '공식 원문·공시 검증 완료' : f ? '원문 수치 · 검증 대기' : '미확보'],
    ...amounts.map(([key, label]): [string, ReactNode] => { const value = f?.amountsExact && Object.prototype.hasOwnProperty.call(f.amountsExact, key) ? f.amountsExact[key] : f?.amounts?.[key]; return [label, value == null ? '미확보' : f?.currency === 'KRW' ? formatExactWon(value) : `${String(value)} ${f?.currency || '통화 미확보'}`]; }),
    ['통화·단위', f?.currency || '미확보'], ['부채비율', ratio(safety?.debtToEquityPct, 2)], ['유동비율', ratio(safety?.currentRatioPct, 2)], ['회계 일치 오차', ratio(safety?.accountingIdentityGapPct, 3)], ['기존 판정 기준', '자본 > 0 · 부채비율 ≤ 400% · 유동비율 ≥ 70% · 회계 오차 ≤ 5%'], ['법인번호', f?.corporationCode || '미확보'], ['공시 접수번호', f?.receiptNo || '미확보'], ['정정 확인', f?.correctionStatus === 'ORIGINAL' ? '최종보고서 조회 · 원공시 확인' : f?.correctionStatus === 'LATEST_CORRECTION' ? f.correctionReconciled ? '정정공시와 다섯 원문 금액 일치 확인' : '정정공시 발견 · 원문 수치 일치 미확인' : '미확보'], ['정정공시 상태 코드', f?.correctionStatus || '미확보'], ['정정 금액 일치 검증', f ? f.correctionReconciled ? '확인 (true)' : '미확인 (false)' : '미확보'], ['정정 확인 근거', f?.correctionCheck || '미확보'], ['정정 대조 오류', f?.reconciliationError ? financialReason(f.reconciliationError) : '기록된 오류 없음'], ['검증 보류 항목', f?.validationIssues?.map(financialReason).join(' · ') || '기록된 항목 없음'], ['공식 공시 출처', url ? <a href={url} target="_blank" rel="noreferrer noopener">{url}</a> : '미확보'], ['OpenDART 원본', f?.sourceFile || safety?.basis || '미확보'], ['원본 ZIP SHA-256', f?.sourceZipSha256 || '미확보'], ['정정 검증 SHA-256', f?.correctionProofSha256 || '미확보'],
  ];
  return <dl className="inline-fact-rows">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>;
}
