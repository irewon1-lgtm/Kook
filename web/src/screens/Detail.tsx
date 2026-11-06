import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import type { Analysis, Candidate, Detail as StockDetail, Evidence, EvidenceItem, FinancialRecord, History, MarketState, Metric, Quarter } from '../types';
import { formatMetric, formatNumber, formatDate, safeExternalUrl } from '../format';
import { Badge, SectionTitle } from '../components/ui';
import PriceChart, { usePriceData } from '../chart/PriceChart';
import './detail.css';

export interface DetailProps {
  detail: StockDetail;
  state: MarketState;
  initialTab?: number;
  onBack: () => void;
  onStock: (code: string) => void;
  onToggleFavorite: (code: string) => void;
  isFavorite: boolean;
  onRefresh: () => void;
}

const TABS = ['요약', '4지표', '종합해석', '체크·출처', '차트'];
const METRIC_HEADINGS = ['성장', '수익성', '가치', '흐름'];
const STATUS: Record<string, string> = { REAL: '실데이터', REGISTERED: '등록', DEMO: '데모', MISSING: '결측', WAITING_FOR_AUTH: '연결 대기' };
const SAFETY: Record<string, string> = { PASS: '자료 확인', FAIL: '재무위험', HOLD: '미확보', NOT_APPLICABLE: '비교제외' };
const FINANCE_NOTE = '재무현황은 4지표 점수·조합 순위·기존 종합순위를 변경하거나 종목을 숨기지 않습니다.';
const CRITERIA = '자본 > 0 · 부채비율 ≤ 400% · 유동비율 ≥ 70% · 회계 오차 ≤ 5%';
const REASONS: Record<string, string> = {
  PASS: '부채비율·유동비율·자본·회계 identity 기준 통과', NONPOSITIVE_EQUITY: '자본이 0 이하라 안전성 gate 탈락',
  DEBT_TO_EQUITY_OVER_400: '부채비율이 400%를 초과', CURRENT_RATIO_UNDER_70: '유동비율이 70% 미만',
  ACCOUNTING_IDENTITY_MISMATCH: '자산=부채+자본 회계 identity 오차가 허용범위를 초과', NONPOSITIVE_ASSETS: '자산총계가 유효한 양수 값이 아님',
  NONPOSITIVE_CURRENT_LIABILITIES: '유동부채가 유효한 양수 값이 아니라 비율 계산 보류', DART_NO_BALANCE_SHEET: 'OpenDART에서 검증 가능한 재무상태표를 찾지 못함',
  FINANCIAL_SECTOR_NOT_COMPARABLE: '금융업은 일반기업 재무비율 gate와 직접 비교하지 않음', SOURCE_UNAVAILABLE: '공식 공시 서버 응답을 확보하지 못해 검증 보류',
  COLLECTION_ERROR: '공식 공시 서버 응답을 확보하지 못해 검증 보류', DART_IDENTITY_UNCONFIRMED: '종목·법인·회사명 매핑을 검증하지 못해 보류', ISSUER_NAME_MISMATCH: '종목·법인·회사명 매핑을 검증하지 못해 보류',
  CORRECTION_VALUES_NOT_RECONCILED: '정정공시와 원본 금액의 일치 여부를 확인하지 못해 보류', BULK_AMOUNT_MISMATCH: '정정공시 API와 재무상태표 원본 금액이 달라 보류',
  RECEIPT_MISMATCH: '정정공시 접수번호와 재무항목 API의 접수번호가 달라 보류', MIXED_PERIOD: '서로 다른 보고기간의 원자료가 충돌해 보류',
  MIXED_CURRENCY: '통화·단위를 검증하지 못해 보류', UNVERIFIED_CURRENCY_UNIT: '통화·단위를 검증하지 못해 보류', NONFINITE_DERIVED_RATIO: '파생 재무비율이 유효한 유한값이 아니라 보류',
};
const reasonKo = (s: string) => REASONS[s] || (s.startsWith('MISSING_') ? `필수 재무항목 누락으로 보류 (${s})` : s);
const present = (v: unknown): boolean => v !== null && v !== undefined && v !== '';
const text = (v: unknown, missing = '미확보'): string => present(v) ? String(v) : missing;
const num = (v: number | null | undefined, digits = 1) => v == null || !Number.isFinite(v) ? '미확보' : digits === 0 ? formatNumber(v, 0) : new Intl.NumberFormat('ko-KR', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(v);
const pct = (v: number | null | undefined, digits = 1) => v == null || !Number.isFinite(v) ? '미확보' : `${num(v, digits)}%`;
const scopeKo = (v: string | undefined) => v === 'CFS' ? '연결 (CFS)' : v === 'OFS' ? '별도 (OFS)' : text(v);
const SOURCE_STATUS_KO: Record<string, string> = {
  AVAILABLE: '자료 확보', CACHE: '이전 확인 자료 유지', PARTIAL: '부분 확보', PARTIAL_OR_CACHE: '부분 확보 · 이전 자료 포함',
  ERROR: '확인 실패', MISSING: '미확보', NOT_COLLECTED: '미수집', UNRECORDED: '수록 자료 있음 · 확인 상태 미확보',
};
function sourceRecord(value: unknown): Record<string, unknown> | undefined { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined; }
function sameOriginalValue(left: unknown, right: unknown): boolean {
  if (left === right) return true;
  if (Array.isArray(left) || Array.isArray(right)) return Array.isArray(left) && Array.isArray(right) && left.length === right.length && left.every((value, index) => sameOriginalValue(value, right[index]));
  const a = sourceRecord(left), b = sourceRecord(right);
  if (!a || !b) return false;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every(key => Object.hasOwn(b, key) && sameOriginalValue(a[key], b[key]));
}
function contextState(detail: StockDetail) { return sourceRecord(detail.sourceStates?.context); }
function sourceStatusKo(value: unknown) { return typeof value === 'string' ? SOURCE_STATUS_KO[value] || `확인 상태 · ${value}` : '확인 상태 미확보'; }

function Panel({ title, eyebrow, children, className = '' }: { title: string; eyebrow?: string; children: ReactNode; className?: string }) {
  return <section className={`dtl-panel ${className}`}><header className="dtl-section-heading">{eyebrow && <span className="dtl-eyebrow">{eyebrow}</span>}<SectionTitle>{title}</SectionTitle></header>{children}</section>;
}
function Prose({ children }: { children: ReactNode }) { return <div className="dtl-prose">{children || '확인할 자료가 없습니다.'}</div>; }
function Bullets({ values }: { values: readonly string[] | undefined }) { return values?.length ? <ul className="dtl-bullets">{values.map((v, i) => <li key={i}>{v}</li>)}</ul> : <p className="dtl-muted">추가 항목 없음</p>; }
function Rows({ rows }: { rows: Array<[string, ReactNode]> }) { return <dl className="dtl-rows">{rows.map(([label, value], i) => <div key={`${label}-${i}`}><dt>{label}</dt><dd>{value ?? '미확보'}</dd></div>)}</dl>; }
function External({ url, children }: { url: string; children?: ReactNode }) { const safe = safeExternalUrl(url); return safe ? <a href={safe} target="_blank" rel="noopener noreferrer">{children || url}<span className="dtl-sr-only"> (새 창)</span></a> : <span>{children || text(url)}</span>; }

// Labels accompany the original values; no source value is truncated or rewritten.
const FIELD_LABELS: Record<string, string> = {
  issuerId: '종목코드', schemaVersion: '자료 형식 버전', policyVersion: '정책 버전', candidateRank: '최종후보 순위', sourceRank: '기존 종합순위',
  name: '회사명', market: '시장', sector: '업종', compositeScore: '종합점수', financialSnapshotDate: '재무 기준일', priceCutoffDate: '주가 마감일',
  m01: '성장 지표 원본', m02: '수익성 지표 원본', m03: '가치 지표 원본', m04: '흐름 지표 원본', id: '식별자', rawValue: '실제 값', percentileScore: '시장 상대점수',
  basis: '계산·판정 근거', asOfDate: '기준일', source: '자료 출처', selectionFlags: '선정 확인 항목', researchStatus: '조사 상태', selectionReasonKo: '선정 사유',
  financialSafetyStatus: '재무현황 상태', financialSafetyReason: '재무현황 사유', financialSafetyBasis: '재무현황 근거', debtToEquityPct: '부채비율 (%)',
  currentRatioPct: '유동비율 (%)', accountingIdentityGapPct: '회계 일치 오차 (%)', valuationBand: '상대 PER 밴드 코드', valuationBandKo: '상대 PER 밴드', valuationPercentile: 'PER 시장 상대점수',
  available: '본문 비교 가능', scope: '비교 범위', currentTitle: '최근 공시 제목', previousTitle: '이전 공시 제목', newlyAppeared: '새로 등장한 내용', disappeared: '사라진 내용',
  strengthened: '강화된 내용', weakened: '약화된 내용', note: '비교 주의사항', loaded: '자료 수신 완료', error: '연결·검증 오류', collectedAt: '실제 수집 확인 시각',
  checkedAt: '확인 시각', generatedAt: '자료 생성 시각', lastAttemptAt: '최근 확인 시도', publishedAt: '원문 발표일', sourceUrl: '원문 주소', url: '원문 주소',
  title: '제목', bodyText: '원문 본문', kind: '자료 종류', sourceTier: '출처 등급', receiptNo: '공시 접수번호', reason: '확인 사유', status: '상태',
  amounts: '실제 재무 금액', currency: '통화', reportPeriod: '보고기간', filingDate: '실제 공시일', corporationCode: '법인번호', sourceFile: '원본 파일',
  sourceZipSha256: '원본 ZIP SHA-256', correctionStatus: '정정공시 상태', correctionCheck: '정정 확인 근거', verified: '원문 검증 완료', validationIssues: '검증 보류 항목',
  correctionReconciled: '정정 금액 일치 검증', correctionProofSha256: '정정 검증 SHA-256', reconciliationError: '정정 대조 오류',
  assets: '자산총계', liabilities: '부채총계', equity: '자본총계', current_assets: '유동자산', current_liabilities: '유동부채',
  financial: '재무자료 연결 상태', candidate: '최종후보 연결 상태', evidence: '최근 근거 연결 상태', news: '뉴스', filings: '공시',
  comparisonScope: '분기 비교 회계범위', availableQuarterCount: '비교 가능 분기 수', directQuarterCount: '직접 분기 수', reconstructedQuarterCount: '재구성 분기 수',
  hasFourQuarters: '4분기 비교 가능', hasEightQuarters: '8분기 비교 가능', hasTrailingGap: '최신 분기 결측',
  latestTargetPoint: '최신 목표 분기 원본', period: '분기', fiscalYear: '회계연도', quarter: '분기 번호', revenue: '매출 원본 (원)', operatingIncome: '영업이익 원본 (원)', operatingMargin: '영업이익률 (%)', revenueYoY: '매출 전년비 (%)', revenueQoQ: '매출 전분기비 (%)', context: '최근 근거 연결 상태',
  categories: '분류별 실제 확인 상태', disclosures: '공시', ir: '기업 IR', official: '기업·공식자료', count: '수록 건수',
  lastAttemptError: '최근 확인 시도 오류', retainedCount: '이전 확인 자료 유지 건수', itemObservedAt: '자료별 실제 확인 시각', retainedIds: '이전 확인 자료 식별자',
};
function RawValue({ value }: { value: unknown }) {
  if (value == null) return <span className="dtl-muted">미확보</span>;
  if (typeof value === 'boolean') return <span>{value ? '예 (true)' : '아니요 (false)'}</span>;
  if (Array.isArray(value)) return value.length ? <ul className="dtl-bullets">{value.map((v, i) => <li key={i}><RawValue value={v} /></li>)}</ul> : <span className="dtl-muted">항목 없음</span>;
  if (typeof value === 'object') return <AuditRows value={value as Record<string, unknown>} />;
  if (typeof value === 'string' && /^https?:\/\//.test(value)) return <External url={value} />;
  return <span className="dtl-prose">{String(value) || '미확보'}</span>;
}
function AuditRows({ value }: { value: Record<string, unknown> }) { return <Rows rows={Object.entries(value).map(([k, v]) => [FIELD_LABELS[k] || `추가 원본 항목 · ${k}`, k === 'status' && typeof v === 'string' && SOURCE_STATUS_KO[v] ? <span>{sourceStatusKo(v)} ({v})</span> : <RawValue value={v} />])} />; }

function Composite({ detail, state }: { detail: StockDetail; state: MarketState }) {
  const s = detail.summary, metrics = [s.m01RevGrowth, s.m02OpMargin, s.m03Per, s.m04Price6m];
  return <Panel title="종합점수" className="dtl-composite">
    <div className="dtl-score-heading"><p>4지표 종합 상대평가 · 백분위와 구분</p><strong className="dtl-score">{s.compositeScore == null ? '미산출' : <>{num(s.compositeScore)}<small>점</small></>}</strong></div>
    <div className="dtl-facts-grid"><div><span>전체 등록종목</span><strong>{num(state.stocks.length || 2649, 0)}종목</strong></div><div><span>기존 4지표 순위</span><strong>{s.rankOrder == null ? '보류' : `${num(s.rankOrder, 0)}위`}</strong></div><div><span>사용 가능 지표</span><strong>{metrics.filter(m => m.isAvailable).length} / 4</strong></div></div>
    <p className="dtl-footnote">재무 {formatDate(s.asOfDate)} · 주가 {formatDate(state.manifest?.priceCutoffDate || '')} · <span className="dtl-chip">{STATUS[s.status] || s.status}</span></p>
    <p className="dtl-muted">{s.isCompositeComplete ? '4지표 완성 · 순위 산출 가능' : '결측값은 추정하지 않고 보류'}</p>
  </Panel>;
}
function CompactMetrics({ metrics }: { metrics: Metric[] }) {
  return <section className="dtl-panel" aria-label="4지표 실제 값과 상대점수"><div className="dtl-section-heading"><h2>4지표 상세</h2></div><div className="dtl-metric-overview">{metrics.map((m, i) => <div className="dtl-compact-metric" key={m.id}>
    <div><strong>{METRIC_HEADINGS[i]}</strong><span>{m.nameKo}</span></div><div className="dtl-compact-value"><strong>{m.isAvailable && m.rawValue != null ? formatMetric(m) : 'N/A'}</strong><div className="dtl-bar" role={m.percentileScore == null ? 'img' : 'meter'} aria-label={`${m.nameKo} 시장 상대점수${m.percentileScore == null ? ' 보류' : ''}`} aria-valuemin={m.percentileScore == null ? undefined : 0} aria-valuemax={m.percentileScore == null ? undefined : 100} aria-valuenow={m.percentileScore ?? undefined} aria-valuetext={m.percentileScore == null ? undefined : `${num(m.percentileScore)}점`}><span style={{ width: `${Math.max(0, Math.min(100, m.percentileScore ?? 0))}%` }} /></div></div><div className="dtl-relative"><strong>{m.percentileScore == null ? '보류' : `${num(m.percentileScore)}점`}</strong><span>시장 상대점수</span></div>
  </div>)}</div><p className="dtl-footnote">실제 값과 시장 상대점수는 서로 다른 값입니다. 지표별 근거와 기준일은 4지표 탭에서 확인할 수 있습니다.</p></section>;
}

function FinancialCard({ safety, snapshotDate, onOpen }: { safety: FinancialRecord | null; snapshotDate: string; onOpen: () => void }) {
  const facts = safety?.facts, equity = exactFinancialAmount(facts, 'equity');
  const equityLabel = equity == null ? '미확보' : facts?.currency === 'KRW' ? `${num(Number(equity) / 100000000, 1)}억원` : `${exactAmount(equity)} ${text(facts?.currency)}`;
  return <Panel title="재무현황" eyebrow="Stage4 재무안정성">
    <div className="dtl-status-line"><Badge status={safety?.status || null} reason={safety ? reasonKo(safety.reasonCode) : '기준일이 일치하는 검증된 재무자료가 없습니다'} onClick={onOpen} /><span>{safety?.status === 'PASS' ? '기존 재무안정성 기준 통과' : safety?.status === 'FAIL' ? '기존 재무안정성 기준 미충족' : safety?.status === 'NOT_APPLICABLE' ? '금융업 · 일반기업 비율 비교 제외' : '검증 가능한 재무자료 미확보 · 판정 보류'}</span></div>
    <Rows rows={[
      ['회계요건', `${safety?.accountingIdentityGapPct == null ? '미확보' : pct(safety.accountingIdentityGapPct, 3)} · 허용 오차 ≤ 5%`],
      ['자료상태', facts ? facts.verified ? '검증 완료 · 공식 원문·공시 수치를 확인했습니다.' : '검증 대기 · 공식 원문 수치의 검증이 완료되지 않았습니다.' : '미확보 · 기준일이 일치하는 검증된 재무자료가 없습니다.'],
      ['판정 사유', safety ? reasonKo(safety.reasonCode) : '기준일이 일치하는 검증된 재무자료가 없습니다.'],
    ]} />
    <div className="dtl-facts-grid"><div><span>유동비율</span><strong>{pct(safety?.currentRatioPct, 2)}</strong></div><div><span>부채비율</span><strong>{pct(safety?.debtToEquityPct, 2)}</strong></div><div><span>자본총계</span><strong>{equityLabel}</strong></div></div>
    <p className="dtl-footnote">OpenDART · {text(facts?.reportPeriod, '보고기간 미확보')} · 기준일 {formatDate(snapshotDate)}</p>
    <button type="button" className="dtl-evidence-entry" onClick={onOpen}><span>▤</span><span><strong>근거 및 참고정보</strong><span>계산식, 실제 금액, 데이터 출처, 기준일 확인</span></span><span aria-hidden="true">›</span></button>
    <p className="dtl-footnote">{FINANCE_NOTE}</p>
  </Panel>;
}
function exactFinancialAmount(f: FinancialRecord['facts'] | undefined, key: string) { return f?.amountsExact && Object.prototype.hasOwnProperty.call(f.amountsExact, key) ? f.amountsExact[key] : f?.amounts?.[key]; }
function exactAmount(value: unknown) { if (!present(value)) return '미확보'; if (typeof value === 'number' && !Number.isSafeInteger(value)) return '정확한 원 단위 미확인'; const s = String(value); return /^-?\d+$/.test(s) ? s.replace(/\B(?=(\d{3})+(?!\d))/g, ',') : s; }
function FinancialEvidence({ safety, snapshotDate }: { safety: FinancialRecord | null; snapshotDate: string }) {
  const f = safety?.facts;
  const correction = f?.correctionStatus === 'ORIGINAL' ? '최종보고서 조회 · 원공시 확인' : f?.correctionStatus === 'LATEST_CORRECTION' ? f.correctionReconciled ? '정정공시와 다섯 원문 금액 일치 확인' : f.reconciliationError ? `정정공시 대조 보류 · ${reasonKo(f.reconciliationError)}` : '정정공시 발견 · 원문 수치 일치 미확인' : '미확보';
  const officialUrl = f?.sourceUrl && /^https:\/\/dart\.fss\.or\.kr\/dsaf001\/main\.do\?rcpNo=\d{14}$/.test(f.sourceUrl) ? f.sourceUrl : null;
  return <><Rows rows={[
    ['판정', `${safety?.status || 'HOLD'} · ${SAFETY[safety?.status || 'HOLD']}`], ['판정 사유', safety ? reasonKo(safety.reasonCode) : '기준일이 일치하는 검증된 재무자료가 없습니다'], ['판정 사유 코드', text(safety?.reasonCode)],
    ['자료 기준일', formatDate(snapshotDate)], ['보고기간', text(f?.reportPeriod)], ['실제 공시일', text(f?.filingDate)], ['연결·별도', scopeKo(safety?.scope)],
    ['자료 검증', f?.verified ? '공식 원문·공시 검증 완료' : f ? '원문 수치 · 검증 대기' : '미확보'],
    ...(['assets', 'liabilities', 'equity', 'current_assets', 'current_liabilities'] as const).map(k => [FIELD_LABELS[k], exactFinancialAmount(f, k) == null ? '미확보' : `${exactAmount(exactFinancialAmount(f, k))}${f?.currency === 'KRW' ? '원' : ` ${text(f?.currency)}`}`] as [string, ReactNode]),
    ['통화·단위', text(f?.currency)], ['부채비율', pct(safety?.debtToEquityPct, 2)], ['유동비율', pct(safety?.currentRatioPct, 2)], ['회계 일치 오차', pct(safety?.accountingIdentityGapPct, 3)],
    ['기존 판정 기준', CRITERIA], ['법인번호', text(f?.corporationCode)], ['공시 접수번호', text(f?.receiptNo)], ['정정 확인', correction],
    ['정정 확인 근거', text(f?.correctionCheck)], ['정정공시 상태 코드', text(f?.correctionStatus)], ['정정 금액 일치 검증', f ? f.correctionReconciled ? '확인 (true)' : '미확인 (false)' : '미확보'],
    ['정정 검증 SHA-256', text(f?.correctionProofSha256)], ['정정 대조 오류', f?.reconciliationError ? reasonKo(f.reconciliationError) : '기록된 오류 없음'],
    ['검증 보류 항목', <Bullets values={f?.validationIssues?.map(reasonKo)} />], ['공식 공시 출처', f?.sourceUrl ? <External url={f.sourceUrl} /> : '미확보'],
    ['OpenDART 원본', text(f?.sourceFile || safety?.basis)], ['원본 ZIP SHA-256', text(f?.sourceZipSha256)], ['판정 근거 원문', text(safety?.basis)],
  ]} />{officialUrl && <External url={officialUrl}>공식 공시 원문 열기 ↗</External>}<p className="dtl-footnote">{FINANCE_NOTE}</p></>;
}

function Valuation({ metric, band }: { metric: Metric; band: StockDetail['valuation'] }) {
  const v = metric.rawValue, p = metric.percentileScore;
  return <Panel title="Stage5 상대 PER 밴드" eyebrow="양수 trailing PER의 시장 상대 분류"><span className="dtl-chip">{band?.shortLabelKo || 'PER 밴드 N/A'}</span><Rows rows={[
    ['실적 trailing PER', v == null ? 'N/A' : `${num(v, 2)}배`], ['시장 상대점수', band ? `${num(band.percentile)}점` : '미적용'],
    ['원본 분류명', band?.labelKo || '미적용'], ['원본 짧은 표시명', band?.shortLabelKo || '미적용'], ['원본 분류 코드', band?.code || '미적용'],
    ['원본 백분위 (0–100)', band ? String(band.percentile) : '미적용'], ['순위 변경', band ? band.changesRank ? '예 (true)' : '없음 (false)' : '미적용'],
  ]} />{!band && <p>{v == null ? 'PER 자료 없음' : v <= 0 ? '양수 PER이 아니어서 Stage5 상대밴드 미적용' : p == null ? 'PER 상대점수가 없어 밴드 미적용' : 'Stage5 밴드 미적용'}</p>}<p className="dtl-footnote">원본 Kotlin 엔진이 제공한 상대 PER 밴드를 표시합니다. 상대 PER 밴드는 설명용 분류입니다. 종합점수·조합 순위·기존 종합순위를 변경하지 않습니다.</p></Panel>;
}
function CandidateCard({ candidate }: { candidate: Candidate }) { return <Panel title="최종 조사 후보" eyebrow="Stage6/7 · 기존 KR4 순위 재사용"><div className="dtl-candidate-top"><span className="dtl-chip">FINAL #{candidate.candidateRank}</span><strong>최종후보 {candidate.candidateRank}위</strong></div><Rows rows={[
  ['기존 종합순위', `${num(candidate.sourceRank, 0)}위`], ['종합점수', typeof candidate.compositeScore === 'number' ? `${num(candidate.compositeScore)}점` : '미산출'], ['조사 상태', candidate.researchStatus],
]} /><Prose>{candidate.selectionReasonKo}</Prose><details open><summary>선정 조건·4지표 원본·재무 판정·자료 버전 전체</summary><AuditRows value={candidate} /></details><p className="dtl-footnote">최종 조사 후보는 조사 우선순위이며 매수·매도 추천이 아닙니다.</p></Panel>; }

function MetricDetail({ metric: m, loss }: { metric: Metric; loss: boolean }) {
  const p = m.percentileScore, peer = m.peerComparison;
  const basisLabels: Record<string, string> = { KRX_EXACT_SECTOR: 'KRX 세부업종', EXACT_KRX_SECTOR: 'KRX 세부업종', KRX_SECTOR: 'KRX 세부업종', STANDARD_SECTOR_FAMILY: '표준 피어그룹', SECTOR_FAMILY: '표준 피어그룹', STANDARD_FAMILY: '표준 피어그룹', INSUFFICIENT: '표본 부족' };
  const category = p == null ? '점수 보류' : p >= 90 ? '시장 상위 10%' : p >= 75 ? '시장 상위 25%' : p >= 50 ? '시장 중상위' : p >= 25 ? '시장 중하위' : '시장 하위';
  const sufficient = peer != null && peer.basis !== 'INSUFFICIENT' && peer.median != null;
  let comparison = '피어 엔진 정보 없음';
  if (peer) {
    if (!sufficient) comparison = `중앙값 미산출 · ${peer.reason || '충분한 동종업계 표본 없음'}`;
    else if (!m.isAvailable || m.rawValue == null) comparison = '실제 값 미확보 · 직접 비교 보류';
    else if (peer.deltaFromMedian == null) comparison = '중앙값과의 차이 미확보 · 비교 실패';
    else if (Math.abs(peer.deltaFromMedian) <= 1e-12) comparison = '업종 중앙값과 같은 수준';
    else {
      const delta = peer.deltaFromMedian;
      const difference = m.id === 'M03' ? peer.relativeToMedianPct != null && Number.isFinite(peer.relativeToMedianPct) ? `${num(Math.abs(peer.relativeToMedianPct))}%` : `${num(Math.abs(delta), 2)}배` : `${num(Math.abs(delta))}%p`;
      comparison = `업종 중앙값보다 ${difference} ${delta > 0 ? '높음' : '낮음'} · ${peer.betterThanMedian == null ? '유불리 미확인' : peer.betterThanMedian ? m.id === 'M03' ? '상대적으로 유리' : '상대 우위' : m.id === 'M03' ? '상대 부담' : '상대 열위'}`;
    }
  }
  return <Panel title={m.nameKo} eyebrow={`${m.id} · ${METRIC_HEADINGS[Number(m.id.slice(1)) - 1]}`}><Prose>{m.description}</Prose><div className="dtl-metric-numbers"><strong>{m.isAvailable && m.rawValue != null ? formatMetric(m) : 'N/A'}</strong><span>{m.id === 'M03' && loss ? '적자 · PER 미적용' : p == null ? '점수 보류' : `시장 상대 ${num(p)}점`}</span></div>
    <div className="dtl-bar dtl-bar-large" role={p == null ? 'img' : 'meter'} aria-label={`${m.nameKo} 전체 시장 상대 위치${p == null ? ' 점수 보류' : ''}`} aria-valuemin={p == null ? undefined : 0} aria-valuemax={p == null ? undefined : 100} aria-valuenow={p ?? undefined} aria-valuetext={p == null ? undefined : `${num(p)}점`}><span style={{ width: `${Math.max(0, Math.min(100, p ?? 0))}%` }} /></div><p className="dtl-footnote">{category} · 전체 시장 기준이며 업종 내 순위가 아닙니다.</p>
    <div className="dtl-peer"><h3>업종 중앙값과 직접 비교</h3>{peer ? <><p>{peer.groupLabel} · N={peer.sampleSize} · {basisLabels[peer.basis] || peer.basis}</p><Rows rows={[
      ['업종 중앙값', sufficient ? m.id === 'M03' ? `${num(peer.median, 2)}배` : pct(peer.median) : '미산출'], ['직접 비교', comparison],
      ['비교 기준', peer.higherIsBetter ? '큰 값이 유리' : '작은 값이 유리'], ['중앙값 차이 원본', peer.deltaFromMedian == null ? '미확보' : `${num(peer.deltaFromMedian, m.id === 'M03' ? 2 : 1)}${m.id === 'M03' ? '배' : '%p'}`],
      ['중앙값 대비 비율', pct(peer.relativeToMedianPct)], ['비교 사유', text(peer.reason, '추가 사유 없음')],
    ]} /></> : <p>피어 엔진 정보 없음</p>}</div>
    <div className={m.isAvailable ? '' : 'dtl-missing-reason'}><Prose>{m.isAvailable ? m.interpretation : m.reason || '검증 가능한 값 없음'}</Prose></div><Rows rows={[
      ['실제 값 원본', m.rawValue == null ? 'N/A' : `${String(m.rawValue)} ${m.unit}`], ['시장 상대점수 원본', m.percentileScore == null ? '보류' : `${String(m.percentileScore)}점`],
      ['계산 기준', text(m.basis)], ['출처', text(m.source)], ['기준일', formatDate(m.asOfDate)], ['주의', text(m.caution)], ['가용 상태', m.isAvailable ? '사용 가능' : '보류'], ['결측·상태 사유', text(m.reason, '추가 사유 없음')],
    ]} />
  </Panel>;
}

function evidenceStatus(e: Evidence, context?: Record<string, unknown>) { return context?.status ? sourceStatusKo(context.status) : !e.loaded ? '확인 대기' : e.error ? '부분 확인' : '자료 수신 · 분류별 확인 필요'; }
const EVIDENCE_KINDS: Record<string, string> = { DISCLOSURE: '공시', IR: 'IR', OFFICIAL: '공식자료', NEWS: '뉴스' };
function EvidenceRow({ item, observedAt, retained }: { item: EvidenceItem; observedAt?: string; retained?: boolean }) { const extra = Object.fromEntries(Object.entries(item).filter(([k]) => !['id','kind','title','source','publishedAt','url','sourceTier','receiptNo','bodyText'].includes(k))); return <article className="dtl-evidence-row"><div className="dtl-evidence-meta"><span className="dtl-chip">{EVIDENCE_KINDS[item.kind] || item.kind}</span><span>{[item.publishedAt, item.source].filter(Boolean).join(' · ') || '발표일·출처 미확보'}</span>{retained && <span className="dtl-chip">이전 확인 자료</span>}</div><h3>{item.title}</h3><Rows rows={[
  ...(observedAt !== undefined ? [['이 자료의 실제 확인 시각', observedAt ? formatDate(observedAt) : '확인 시각 미확보']] as Array<[string, ReactNode]> : []),
  ['원문 주소', item.url ? <External url={item.url}>원문 열기 ↗ · {item.url}</External> : '미확보'], ['출처 등급', text(item.sourceTier)], ['공시 접수번호', text(item.receiptNo)], ['자료 식별자', text(item.id)],
]} />{Object.keys(extra).length > 0 && <AuditRows value={extra} />}<details><summary>수집한 원문 본문 전체 보기 {item.bodyText ? `(${num(item.bodyText.length, 0)}자)` : '· 미확보'}</summary><Prose>{item.bodyText || '원문 본문을 확보하지 못했습니다. 제목만으로 원인 확정을 하지 않습니다.'}</Prose></details></article>; }
function EvidenceList({ items, category }: { items: EvidenceItem[]; category?: Record<string, unknown> }) {
  const observations = sourceRecord(category?.itemObservedAt), retainedIds = Array.isArray(category?.retainedIds) ? category.retainedIds : [];
  return items.length ? <div className="dtl-evidence-list">{items.map((item, i) => {
    const receiptKey = `receipt:${item.receiptNo}`, itemKey = `${item.kind}:${item.id}:${item.url}:${item.title}`;
    const key = observations && typeof observations[receiptKey] === 'string' ? receiptKey : itemKey;
    const observedAt = observations && typeof observations[key] === 'string' ? observations[key] as string : '';
    return <EvidenceRow item={item} observedAt={category ? observedAt : undefined} retained={category?.source === 'CACHE' || retainedIds.includes(key)} key={`${item.kind}:${item.id}:${i}`} />;
  })}</div> : <p className="dtl-muted">확보한 자료 없음</p>;
}
function EvidenceCategory({ name, items, category, loaded }: { name: string; items: EvidenceItem[]; category?: Record<string, unknown>; loaded: boolean }) {
  const retained = typeof category?.retainedCount === 'number' ? category.retainedCount : null;
  const status = items.length === 0 ? loaded || category?.loaded === true ? 'MISSING' : category?.status || 'NOT_COLLECTED' : category?.status || 'UNRECORDED';
  const remaining = category && Object.fromEntries(Object.entries(category).filter(([key]) => !['status', 'loaded', 'count', 'source', 'checkedAt', 'lastAttemptAt', 'lastAttemptError', 'retainedCount', 'itemObservedAt'].includes(key)));
  return <Panel title={`${name} 원문·출처 (${items.length}건)`}><div className="dtl-status-line"><span className="dtl-chip">{sourceStatusKo(status)}</span><span className="dtl-footnote">{retained && retained > 0 ? `이전 확인 자료 ${num(retained, 0)}건을 유지합니다.` : items.length === 0 ? `${name} 자료를 확보하지 못했습니다.` : category?.source === 'CACHE' ? `이전에 확인한 저장자료 ${num(items.length, 0)}건을 표시합니다.` : '원문 발표일과 자료 확인일을 구분해 확인하세요.'}</span></div>
    <Rows rows={[
      [items.length ? '이 분류의 실제 확인 기준일' : '미확보 분류의 확인 기록', typeof category?.checkedAt === 'string' && category.checkedAt ? formatDate(category.checkedAt) : '확인 시각 미확보'],
      ['최근 확인 시도', typeof category?.lastAttemptAt === 'string' && category.lastAttemptAt ? formatDate(category.lastAttemptAt) : '확인 시도 기록 없음'],
      ['최근 확인 시도 오류', category?.lastAttemptError ? <RawValue value={category.lastAttemptError} /> : category ? '기록된 시도 오류 없음' : '확인 기록 미확보'],
      ['실패·부분 수집 후 유지한 자료', retained == null ? '유지 여부 미확보' : `${num(retained, 0)}건`], ['수집 상태', typeof category?.source === 'string' ? ({ COLLECTED: items.length ? '이번 수집에서 확보' : '이번 수집에서 확인 시도 · 확보 자료 없음', CACHE: '이전에 확인한 저장자료', MIXED: '이번 수집과 이전 확인 자료가 함께 있음', NOT_COLLECTED: '미수집' } as Record<string, string>)[category.source] || category.source : '미확보'],
    ]} />
    {(retained != null && retained > 0 || status === 'PARTIAL_OR_CACHE' || status === 'CACHE') && <p className="dtl-info-note">이전 자료가 포함되어 있습니다. 분류의 실제 확인 기준일은 포함된 자료 중 가장 이른 확인 시각입니다. 최근 확인 시도 시각은 이전 자료의 새로운 확인일이 아닙니다. 각 원문 아래의 실제 확인 시각을 함께 확인하세요.</p>}
    {remaining && Object.keys(remaining).length > 0 && <details><summary>이 분류의 추가 확인 정보 전체</summary><AuditRows value={remaining} /></details>}<EvidenceList items={items} category={category} />
  </Panel>;
}

function operatingChange(point: Quarter, other: Quarter | undefined, quarters: number) {
  if (!other || !['CFS', 'OFS'].includes(point.scope) || other.scope !== point.scope || point.fiscalYear * 4 + point.quarter - (other.fiscalYear * 4 + other.quarter) !== quarters || point.operatingIncome == null || other.operatingIncome == null || other.operatingIncome <= 0) return null;
  const v = (point.operatingIncome / other.operatingIncome - 1) * 100;
  return Number.isFinite(v) ? v : null;
}
function QuarterHistory({ history: h, analysis: a }: { history: History; analysis: Analysis }) {
  const points = [...h.points].sort((a, b) => b.fiscalYear - a.fiscalYear || b.quarter - a.quarter);
  const comparable = h.comparablePoints || [];
  const extra = Object.fromEntries(Object.entries(h).filter(([k]) => !['issuerId', 'points', 'loaded', 'source', 'generatedAt', 'error', 'comparablePoints'].includes(k)));
  return <Panel title="실제 분기 숫자가 어떻게 변했는지" eyebrow="4–8 QUARTER TREND"><span className="dtl-chip">{a.quarterlyCoverage || (h.loaded ? '비교 범위 미확보' : '확인 중')}</span><Prose>{a.quarterlyTrend}</Prose><h3>분기 신호</h3><Prose>{a.quarterlySignal}</Prose>
    <Rows rows={[
      ['자료 상태', h.loaded ? '수신 완료' : '확인 대기'], ['수록된 분기', `${h.points.length}개`], ['비교 가능 분기', h.availableQuarterCount == null ? comparable.length ? `${comparable.length}개` : '미확보' : `${h.availableQuarterCount}개`],
      ['직접 분기 (DIRECT_3M)', h.directQuarterCount == null ? '미확보' : `${h.directQuarterCount}개`], ['누적 차감 등 재구성 분기', h.reconstructedQuarterCount == null ? '미확보' : `${h.reconstructedQuarterCount}개`],
      ['최신 분기 결측', h.hasTrailingGap == null ? '미확보' : h.hasTrailingGap ? '있음 · 이전 분기로 옮겨 해석하지 않음' : '없음'], ['연결 상태', h.error || '기록된 오류 없음'],
    ]} />
    {points.length > 0 && <table className="dtl-quarter-table"><caption>수록된 실제 분기 전체 · 최신 분기부터</caption><thead><tr>{['분기', '매출', '영업이익', '영업이익률', '매출 YoY', '매출 QoQ', '영업이익 YoY', '영업이익 QoQ'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{points.map(q => {
      const previous = points.find(p => p.fiscalYear * 4 + p.quarter === q.fiscalYear * 4 + q.quarter - 1);
      const yearAgo = points.find(p => p.fiscalYear * 4 + p.quarter === q.fiscalYear * 4 + q.quarter - 4);
      const cells: Array<[string, string]> = [['매출', quarterAmount(q.revenue)], ['영업이익', quarterAmount(q.operatingIncome)], ['영업이익률', pct(q.operatingMargin)], ['매출 YoY', pct(q.revenueYoY)], ['매출 QoQ', pct(q.revenueQoQ)], ['영업이익 YoY', pct(operatingChange(q, yearAgo, 4))], ['영업이익 QoQ', pct(operatingChange(q, previous, 1))]];
      return <tr key={`${q.period}:${q.scope}`}><th scope="row">{q.period}</th>{cells.map(([label, value]) => <td data-label={label} key={label}>{value}</td>)}</tr>;
    })}</tbody></table>}
    {points.length ? <details open><summary>분기별 회계범위·직접/재구성 근거·원본 수치와 출처 전체</summary><div className="dtl-quarter-list">{points.map(q => {
      const previous = points.find(p => p.fiscalYear * 4 + p.quarter === q.fiscalYear * 4 + q.quarter - 1);
      const yearAgo = points.find(p => p.fiscalYear * 4 + p.quarter === q.fiscalYear * 4 + q.quarter - 4);
      return <article className="dtl-quarter" key={`${q.period}:${q.scope}`}><header><h3>{q.period}</h3><span className="dtl-chip">{scopeKo(q.scope)}</span><span className="dtl-chip">{q.basis === 'DIRECT_3M' ? '직접 분기' : q.revenue != null && q.basis ? '재구성 분기' : '결측·미확인'}</span></header><Rows rows={[
        ['회계연도·분기', `${q.fiscalYear}년 ${q.quarter}분기`], ['매출 원본 (원)', q.revenue == null ? '미확보' : num(q.revenue, 0)], ['영업이익 원본 (원)', q.operatingIncome == null ? '미확보' : num(q.operatingIncome, 0)], ['분기 계산 기준', text(q.basis)], ['원본 파일', text(q.sourceFile)], ['결측·검증 사유', text(q.reason, '추가 사유 없음')],
      ]} /></article>;
    })}</div></details> : <p>{h.loaded ? '해당 종목은 아직 검증 가능한 4분기 이상 시계열이 없습니다.' : '분기 자료 확인 중입니다.'}</p>}
    <p className="dtl-footnote">영업이익 YoY·QoQ는 제공된 원본 분기 금액의 단순 증감률입니다. 같은 연결·별도 범위이며 전년·전분기 영업이익이 양수인 경우에만 표시합니다. 누락·회계범위 변경·전기 0 이하에서는 미확보로 표시합니다. 분석 해석은 원래 엔진의 비교 가능 연속 분기 기준을 따릅니다.</p>
    <Rows rows={[[ '시계열 출처', text(h.source)], ['생성 시각', text(h.generatedAt)], ['비교 가능 분기 목록', comparable.length ? comparable.map(p => p.period).join(' · ') : '미확보']]} />
    {Object.keys(extra).length > 0 && <details><summary>분기 범위·검증 정보 전체</summary><AuditRows value={extra} /></details>}
  </Panel>;
}
function quarterAmount(v: number | null) { if (v == null || !Number.isFinite(v)) return '미확보'; return `${num(v / 100000000, 1)}억원`; }

function AnalysisTab({ detail, toSources }: { detail: StockDetail; toSources: () => void }) {
  const a = detail.analysis, e = detail.evidence;
  return <div className="dtl-stack"><div className="dtl-tab-intro"><h2>4지표 종합해석</h2><p>네 지표가 함께 보여주는 사업 의미와 실제 최근 근거를 확인합니다.</p></div><Panel title={a.regimeTitle} eyebrow="CORE READ · 핵심 판독"><Prose>{a.thesis}</Prose></Panel>
    <div className="dtl-two-columns">{([
      ['사업 상태', a.businessState], ['가격 부담', a.priceBurden], ['원인 확신도', a.causeConfidence], ['미래 불확실성', a.futureUncertainty],
    ] as Array<[string, string]>).map(([title, value]) => <Panel key={title} title={title}><Prose>{value}</Prose></Panel>)}</div>
    <QuarterHistory history={detail.history} analysis={a} />
    <Panel title="이 산업에서 숫자를 읽는 법" eyebrow="INDUSTRY MAP"><Prose>{a.industryContext}</Prose><h3>산업별 핵심 확인 지표</h3><Prose>{a.industryKpiGuide}</Prose></Panel>
    <Panel title="네 지표 조합의 숨은 뜻" eyebrow="HIDDEN MEANING"><Prose>{a.combinationMeaning}</Prose></Panel>
    <Panel title="왜 이런 숫자가 나왔는지 최근 근거 점검" eyebrow="NEWS & DISCLOSURE CHECK"><span className="dtl-chip">{evidenceStatus(e, contextState(detail))}</span>{e.error && <p className="dtl-error">최근 근거 연결 상태: {e.error}</p>}<Prose>{a.causeInvestigation}</Prose><h3>서로 경쟁하는 원인 후보</h3><Bullets values={a.causeCandidates} />{a.evidenceHighlights.length > 0 && <><h3>해석에 실제로 사용한 최근 단서</h3><EvidenceList items={a.evidenceHighlights} /></>}</Panel>
    <Panel title="공시 변화와 경영진 설명 비교"><Prose>{a.disclosureDiffNote}</Prose>{e.disclosureDiff && <details open><summary>공시 본문·목록 비교 결과 전체</summary><AuditRows value={e.disclosureDiff} /></details>}</Panel>
    <Panel title="이 조합이 이어질 때 생길 일" eyebrow="OUTCOME"><Prose>{a.consequence}</Prose></Panel>
    <div className="dtl-two-columns"><Panel title="이 해석이 틀렸다고 볼 조건" eyebrow="FALSIFIERS"><Bullets values={a.falsifiers} /></Panel><Panel title="다음 분기 확인할 연결고리"><Bullets values={a.nextQuarterWatch} /></Panel></div>
    <Panel title="해석의 신뢰도와 한계"><Prose>{a.confidenceNote}</Prose><button className="dtl-button" onClick={toSources}>조사 체크·출처 전체 보기 →</button></Panel>
  </div>;
}

function SourcesTab({ detail, state }: { detail: StockDetail; state: MarketState }) {
  const r = detail.report, e = detail.evidence;
  const context = contextState(detail), categories = sourceRecord(context?.categories);
  const extraEvidence = Object.fromEntries(Object.entries(e).filter(([k]) => !['issuerId','news','disclosures','ir','official','all','primarySourceCount','loaded','error','disclosureDiff','collectedAt','source'].includes(k)));
  const groups: Array<[string, string, EvidenceItem[]]> = [['disclosures', '공시', e.disclosures], ['ir', '기업 IR', e.ir], ['official', '기업·공식자료', e.official], ['news', '뉴스', e.news]];
  return <div className="dtl-stack"><div className="dtl-tab-intro"><h2>조사 체크포인트</h2><p>정량 밖의 반대 근거와 원문, 실제 자료 확인 상태를 함께 읽어주세요.</p></div>
    <Panel title="정량 밖에서 반드시 확인할 것" eyebrow="RESEARCH CHECK"><h3>위험·결측</h3><Bullets values={r.riskFactors} /><h3>반대 근거</h3><Prose>{r.counterArguments}</Prose><h3>다음 확인 조건</h3><Bullets values={r.nextVerificationConditions} /></Panel>
    <Panel title="원래 기업 리포트 전체" eyebrow={`종목 ${r.issuerId} · 갱신 ${text(r.updatedAt)}`}><Rows rows={[
      ['한 줄 관점', <Prose>{r.oneLineView}</Prose>], ['정량 요약', <Prose>{r.quantSummary}</Prose>], ['최근 변화', <Prose>{r.recentChanges}</Prose>], ['변화 이유', <Prose>{r.changeReason}</Prose>],
      ['사업의 질', <Prose>{r.businessQuality}</Prose>], ['가치평가 관점', <Prose>{r.valuationView}</Prose>], ['가격 흐름 관점', <Prose>{r.momentumView}</Prose>], ['긍정 요인', <Bullets values={r.positiveFactors} />],
    ]} /></Panel>
    <Panel title="데이터 한계와 출처"><Prose>{r.dataLimitations}</Prose><Rows rows={[
      ['종목·업종', 'KRX KIND'], ['재무', '금융감독원 OpenDART'], ['PER·가격', '네이버증권 공개 데이터'], ['최근 근거 묶음 상태', evidenceStatus(e, context)], ['수록된 1차 출처', `${e.primarySourceCount}건`], ['주가 마감', formatDate(state.manifest?.priceCutoffDate || '')],
      ['최근 근거 연결 상태', e.error || (e.loaded ? '기록된 연결 오류 없음 · 분류별 상태는 아래에서 확인' : '아직 확인되지 않았습니다')], ['수집 묶음 기록 시각 (개별 확인일 아님)', e.collectedAt ? formatDate(e.collectedAt) : '미확보'], ['근거 수집 출처', text(e.source)],
    ]} /><p className="dtl-footnote">아래 자료는 공개된 수집본입니다. 뉴스·공시·IR·공식자료는 각각의 확인 상태와 시각이 다릅니다. 묶음의 수집·확인 시각이나 최근 시도 시각은 이전에 확보한 자료의 새로운 확인일이 아닙니다. 웹페이지를 연 시각도 원문의 발표일·자료 확인일과 다릅니다.</p></Panel>
    {Object.keys(extraEvidence).length > 0 && <Panel title="근거 수집·확인 정보 전체"><AuditRows value={extraEvidence} /></Panel>}
    {groups.map(([key, name, items]) => <EvidenceCategory key={key} name={name} items={items} category={sourceRecord(categories?.[key])} loaded={e.loaded} />)}
    {e.disclosureDiff && <Panel title="공시 변화 비교 원본"><AuditRows value={e.disclosureDiff} /></Panel>}
    {detail.sourceStates && <Panel title="개별 자료 연결·검증 상태"><AuditRows value={detail.sourceStates} /></Panel>}
    {(detail.news.length > 0 || detail.filings.length > 0) && <Panel title="기존 기업 리포트의 뉴스·공시 원본"><details open><summary>뉴스 {detail.news.length}건 · 공시 {detail.filings.length}건 전체</summary><h3>뉴스</h3><RawValue value={detail.news} /><h3>공시</h3><RawValue value={detail.filings} /></details></Panel>}
  </div>;
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null), titleId = useId();
  useEffect(() => { const el = ref.current; el?.showModal(); return () => el?.close(); }, []);
  return <dialog className="dtl-dialog" ref={ref} aria-labelledby={titleId} onCancel={onClose} onClick={e => { if (e.target === e.currentTarget) { const rect = e.currentTarget.getBoundingClientRect(); if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) onClose(); } }}><header><h2 id={titleId}>{title}</h2><button type="button" className="dtl-button" onClick={onClose} aria-label={`${title} 닫기`}>닫기 ×</button></header><div className="dtl-dialog-content">{children}</div><footer><button type="button" className="dtl-button" onClick={onClose}>닫기</button></footer></dialog>;
}

function Quote({ issuerId, cutoffDate }: { issuerId: string; cutoffDate: string }) {
  const { data, loading, error } = usePriceData(issuerId, cutoffDate);
  const bars = [...(data?.bars || [])].sort((a, b) => a.date.localeCompare(b.date)), latest = bars.at(-1), previous = bars.at(-2);
  const delta = latest && previous && previous.close > 0 ? latest.close - previous.close : null;
  const change = delta != null && previous ? delta / previous.close * 100 : null;
  const recent = bars.slice(-24), min = Math.min(...recent.map(b => b.close)), max = Math.max(...recent.map(b => b.close));
  const spark = recent.map((b, i) => `${recent.length <= 1 ? 0 : i / (recent.length - 1) * 180},${max === min ? 25 : 48 - (b.close - min) / (max - min) * 44}`).join(' ');
  return <section className="dtl-quote" aria-label="최근 완료 일봉 시세"><div className="dtl-quote-main"><strong>{latest ? `${num(latest.close, 0)}원` : loading ? '종가 확인 중' : '종가 미확보'}</strong><span className={delta != null && delta > 0 ? 'dtl-up' : 'dtl-down'}>{delta == null ? '전 거래일 비교자료 미확보' : `${delta > 0 ? '▲ +' : delta < 0 ? '▼ ' : '— '}${num(delta, 0)}원 (${change != null && change > 0 ? '+' : ''}${num(change, 2)}%)`}</span><p>{latest?.date || '가격 기준일 미확보'} · 네이버증권 · {data?.source === 'CACHE' ? '저장자료 (CACHE)' : '완료 일봉 기준'}</p></div>{recent.length > 1 && <svg className="dtl-sparkline" viewBox="0 0 180 52" role="img" aria-label={`최근 ${recent.length}거래일 종가 흐름`}><polyline points={spark} fill="none" stroke="currentColor" strokeWidth="2.5" /></svg>}<div className="dtl-quote-facts">{[['시가', latest?.open], ['고가', latest?.high], ['저가', latest?.low], ['거래량', latest?.volume]].map(([label, v]) => <div key={String(label)}><span>{label}</span><strong>{typeof v === 'number' ? `${num(v, 0)}${label === '거래량' ? '주' : '원'}` : '—'}</strong></div>)}</div>{(error || data?.error) && <p className="dtl-quote-error">가격 연결 상태: {error || data?.error}</p>}</section>;
}

export default function Detail({ detail, state, initialTab = 0, onBack, onToggleFavorite, isFavorite, onRefresh }: DetailProps) {
  const [tab, setTab] = useState(Math.min(4, Math.max(0, initialTab))), [modal, setModal] = useState<'finance' | 'update' | null>(null);
  const tabsRef = useRef<Array<HTMLButtonElement | null>>([]), scrolls = useRef<Record<number, number>>({}), panelRef = useRef<HTMLDivElement>(null);
  const id = useId(), s = detail.summary, metrics = [s.m01RevGrowth, s.m02OpMargin, s.m03Per, s.m04Price6m];
  const cutoffDate = state.manifest?.priceCutoffDate || '';
  const matchingDate = !!state.manifest && s.asOfDate === state.manifest.snapshotDate;
  const matchingCore = matchingDate && sameOriginalValue(s, state.stocks.find(stock => stock.issuerId === s.issuerId));
  // A mounted detail may outlive a core/optional reception transaction. Only the
  // current validated maps can authorize active financial or candidate status.
  const financialReady = matchingCore && state.financialDate === state.manifest?.snapshotDate && state.financialError === null;
  const currentFinancial = financialReady ? state.financialByCode[s.issuerId] : null;
  const safety = currentFinancial?.issuerId === s.issuerId ? currentFinancial : null;
  const financialHold = !matchingCore ? '종목 상세와 현재 공개자료의 기준 또는 원본 내용이 다릅니다.' : state.financialError || (state.financialDate !== state.manifest?.snapshotDate ? '현재 기준일의 재무자료 검증 대기' : '검증된 재무자료 미확보 · 검증 대기');
  const currentCandidate = state.candidatesByCode?.[s.issuerId];
  const candidateDatesMatch = !currentCandidate || (currentCandidate.issuerId === s.issuerId && currentCandidate.financialSnapshotDate === state.manifest?.snapshotDate && currentCandidate.priceCutoffDate === cutoffDate);
  const candidateSourceMatch = !currentCandidate || (currentCandidate.sourceRank === s.rankOrder && currentCandidate.compositeScore === s.compositeScore);
  const candidateHold = !safety || safety.status !== 'PASS' ? '현재 재무자료의 PASS 확인 전까지 최종후보 표시 보류' : state.candidatesError !== null ? state.candidatesError || '현재 최종후보 목록 검증 대기' : !candidateDatesMatch ? '최종후보의 종목·기준일 불일치' : !candidateSourceMatch ? '최종후보의 원본 순위·종합점수 불일치' : null;
  const candidate = candidateHold ? null : currentCandidate ?? null;
  const currentDetail: StockDetail = { ...detail, financial: safety, candidate, sourceStates: {
    ...detail.sourceStates,
    financial: { ...sourceRecord(detail.sourceStates?.financial), status: safety ? 'VALID' : 'HOLD', error: safety ? null : financialHold },
    candidate: { ...sourceRecord(detail.sourceStates?.candidate), status: candidateHold ? 'HOLD' : 'VALID', error: candidateHold },
  } };
  useEffect(() => { setTab(Math.min(4, Math.max(0, initialTab))); setModal(null); scrolls.current = {}; }, [s.issuerId, initialTab]);
  function selectTab(next: number, focus = false) { if (panelRef.current) scrolls.current[tab] = window.scrollY; setTab(next); if (focus) tabsRef.current[next]?.focus(); }
  useEffect(() => { window.scrollTo({ top: scrolls.current[tab] ?? 0, behavior: 'instant' }); }, [tab, s.issuerId]);
  const updateRows: Array<[string, ReactNode]> = [
    ['연결 출처', matchingCore ? `${state.source}${state.source === 'LIVE' ? ' · GitHub에 공개된 자료 수신' : state.source === 'CACHE' ? ' · 검증된 저장자료' : ''}` : '데이터 출처 확인 중 · 종목과 공개자료의 기준 또는 원본 내용 불일치'], ['화면의 재무 기준일', formatDate(s.asOfDate)], ['공개자료 재무 기준일', formatDate(state.manifest?.snapshotDate || '')], ['주가 마감일', formatDate(cutoffDate)],
    ['4지표 완성', `${state.stocks.filter(stock => stock.isCompositeComplete).length} / ${state.stocks.length}`], ['최근 정상 수신', text(state.lastSuccessAt)], ['최근 확인 시도', text(state.lastAttemptAt)], ['연결 상태', state.error ? `갱신 실패 · 마지막 정상 자료를 표시합니다. ${state.error}` : state.refreshing ? '자료 갱신 중…' : '기록된 수신 오류 없음'], ['공개자료 생성 시각', text(state.manifest?.generatedAt)],
  ];
  return <section className="detail-screen" data-testid="stock-detail"><header className="dtl-company-header"><button type="button" className="dtl-button dtl-back" onClick={onBack} aria-label="종목 목록으로 돌아가기">← <span>목록</span></button><div className="dtl-company-identity"><h1>{s.name} <span>({s.issuerId})</span></h1><p>{s.market} · {s.sector}</p></div><div className="dtl-header-actions"><button type="button" className={`dtl-button ${isFavorite ? 'dtl-favorited' : ''}`} onClick={() => onToggleFavorite(s.issuerId)} aria-pressed={isFavorite} aria-label={`${s.name} 관심종목 ${isFavorite ? '해제' : '추가'}`}>{isFavorite ? '★ 관심종목' : '☆ 관심 추가'}</button><button type="button" className="dtl-button" onClick={() => setModal('update')} aria-label="데이터 갱신 상태 열기">↻ 갱신 상태</button></div></header>
    {!matchingCore && <p className="dtl-info-note" role="status">데이터 출처 확인 중 · 종목 상세와 현재 공개자료의 기준 또는 원본 내용이 달라 재무·최종후보 표시를 보류합니다.</p>}
    {tab !== 4 && <Quote issuerId={s.issuerId} cutoffDate={cutoffDate} />}
    <div className="dtl-tabs" role="tablist" aria-label="종목 상세 정보">{TABS.map((label, i) => <button key={label} type="button" id={`${id}-tab-${i}`} role="tab" aria-selected={tab === i} aria-controls={`${id}-panel-${i}`} tabIndex={tab === i ? 0 : -1} ref={el => { tabsRef.current[i] = el; }} onClick={() => selectTab(i)} onKeyDown={e => { let next = i; if (e.key === 'ArrowRight') next = (i + 1) % 5; else if (e.key === 'ArrowLeft') next = (i + 4) % 5; else if (e.key === 'Home') next = 0; else if (e.key === 'End') next = 4; else return; e.preventDefault(); selectTab(next, true); }}>{label}</button>)}</div>
    <div role="tabpanel" tabIndex={0} id={`${id}-panel-${tab}`} aria-labelledby={`${id}-tab-${tab}`} className="dtl-tab-panel" ref={panelRef}>
      {tab === 0 && <div className="dtl-stack"><div className="dtl-overview-grid"><Composite detail={detail} state={state} /><CompactMetrics metrics={metrics} /></div>{candidate && <CandidateCard candidate={candidate} />}<div className="dtl-two-columns"><FinancialCard safety={safety} snapshotDate={s.asOfDate} onOpen={() => setModal('finance')} /><Valuation metric={s.m03Per} band={detail.valuation} /></div><Panel title="핵심 판독" eyebrow={detail.analysis.regimeTitle}><Prose>{detail.analysis.thesis}</Prose><button className="dtl-button" onClick={() => selectTab(2)}>종합해석 전체 보기 →</button></Panel><Panel title="종목 기본정보"><Rows rows={[
        ['종목코드', s.issuerId], ['시장', s.market], ['업종', s.sector], ['상장일', text(s.listingDate, '확인 필요')], ['재무 기준일', formatDate(s.asOfDate)], ['주가 마감일', formatDate(cutoffDate)], ['원자료 출처', s.dataSource], ['금융업 여부', s.isFinancial ? '금융업' : '비금융업'], ['적자 여부', s.isLossMaking ? '적자' : '적자 표시 없음'],
      ]} /><button className="dtl-button" onClick={() => selectTab(1)}>4지표 근거와 업종 비교 보기 →</button></Panel></div>}
      {tab === 1 && <div className="dtl-stack"><div className="dtl-overview-grid"><Composite detail={detail} state={state} /><CompactMetrics metrics={metrics} /></div><FinancialCard safety={safety} snapshotDate={s.asOfDate} onOpen={() => setModal('finance')} /><p className="dtl-info-note">ⓘ 은행·보험·증권 등 금융업은 일반기업의 부채·유동비율과 직접 비교하지 않아 비교에서 제외합니다.</p><div className="dtl-tab-intro"><h2>지표별 근거와 업종 비교</h2><p>실제 값, 업종 중앙값, 전체 시장의 상대 위치와 계산 근거를 구분해 확인합니다.</p></div><div className="dtl-two-columns">{metrics.map(m => <MetricDetail metric={m} loss={s.isLossMaking} key={m.id} />)}</div><Valuation metric={s.m03Per} band={detail.valuation} /></div>}
      {tab === 2 && <AnalysisTab detail={detail} toSources={() => selectTab(3)} />}
      {tab === 3 && <SourcesTab detail={currentDetail} state={state} />}
      {tab === 4 && <PriceChart issuerId={s.issuerId} cutoffDate={cutoffDate} />}
    </div>
    {TABS.map((_, i) => i !== tab && <div key={i} hidden role="tabpanel" id={`${id}-panel-${i}`} aria-labelledby={`${id}-tab-${i}`} />)}
    {modal === 'finance' && <Modal title="재무자료와 판정 근거" onClose={() => setModal(null)}><FinancialEvidence safety={safety} snapshotDate={s.asOfDate} /></Modal>}
    {modal === 'update' && <Modal title="데이터 갱신 상태" onClose={() => setModal(null)}><Rows rows={updateRows} />{state.manifest?.coverage && <details><summary>공개자료 수록 범위 전체</summary><AuditRows value={state.manifest.coverage} /></details>}<button type="button" className="dtl-button dtl-button-primary" onClick={onRefresh} disabled={state.refreshing}>{state.refreshing ? '데이터 갱신 중…' : '지금 자료 갱신'}</button><p className="dtl-footnote">가격·근거 수집일과 원문 발표일은 각 자료의 출처에서 별도로 표시합니다.</p></Modal>}
  </section>;
}
