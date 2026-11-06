export type MetricId = 'M01' | 'M02' | 'M03' | 'M04';
export const METRIC_IDS: readonly MetricId[] = ['M01', 'M02', 'M03', 'M04'];
export const METRIC_LABELS: Record<MetricId, { short: string; full: string; unit: string }> = {
  M01: { short: '성장', full: '매출 증가율', unit: '%' },
  M02: { short: '수익성', full: '영업이익률', unit: '%' },
  M03: { short: '가치', full: '실적 PER', unit: '배' },
  M04: { short: '흐름', full: '6개월 상승률', unit: '%' },
};
export interface PeerComparison {
  groupLabel: string; basis: string; sampleSize: number; median: number | null;
  deltaFromMedian: number | null; relativeToMedianPct: number | null;
  betterThanMedian: boolean | null; higherIsBetter: boolean; reason: string | null;
}
export interface Metric {
  id: MetricId; nameKo: string; rawValue: number | null; percentileScore: number | null;
  unit: string; isAvailable: boolean; reason: string | null; description: string;
  interpretation: string; caution: string; source: string; basis: string; asOfDate: string;
  peerComparison: PeerComparison | null;
}
export interface Stock {
  issuerId: string; name: string; market: string; sector: string; listingDate: string;
  isFinancial: boolean; isLossMaking: boolean; m01RevGrowth: Metric; m02OpMargin: Metric;
  m03Per: Metric; m04Price6m: Metric; compositeScore: number | null; rankOrder: number | null;
  isCompositeComplete: boolean; asOfDate: string; dataSource: string; status: string;
}
export interface RankResult {
  issuerId: string; score: number | null; rank: number | null;
  selectedMetricCount: number; isEligible: boolean;
}
export type SafetyStatus = 'PASS' | 'FAIL' | 'HOLD' | 'NOT_APPLICABLE';
export interface FinancialFacts {
  amounts: Record<string, number | null>; currency: string; reportPeriod: string; filingDate: string;
  amountsExact?: Record<string, string | null>;
  corporationCode: string; receiptNo: string; sourceUrl: string; sourceFile: string;
  sourceZipSha256: string; correctionStatus: string; correctionCheck: string; verified: boolean;
  validationIssues: string[]; correctionReconciled: boolean; correctionProofSha256: string; reconciliationError: string;
}
export interface FinancialRecord {
  issuerId: string; status: SafetyStatus; reasonCode: string; scope: string;
  debtToEquityPct: number | null; currentRatioPct: number | null; accountingIdentityGapPct: number | null;
  basis: string; facts: FinancialFacts | null;
}
export interface EvidenceItem {
  id: string; kind: 'NEWS' | 'DISCLOSURE' | 'IR' | 'OFFICIAL'; title: string; source: string;
  publishedAt: string; url: string; sourceTier: string; receiptNo: string; bodyText: string;
}
export interface Evidence {
  issuerId: string; news: EvidenceItem[]; disclosures: EvidenceItem[]; ir: EvidenceItem[];
  official: EvidenceItem[]; all: EvidenceItem[]; primarySourceCount: number; loaded: boolean;
  error: string | null; disclosureDiff: Record<string, unknown> | null;
  collectedAt?: string; source?: string;
}
export interface Analysis {
  regimeTitle: string; thesis: string; industryContext: string; combinationMeaning: string;
  causeInvestigation: string; consequence: string; falsifiers: string[]; confidenceNote: string;
  evidenceHighlights: EvidenceItem[]; industryKpiGuide: string; disclosureDiffNote: string;
  causeCandidates: string[]; nextQuarterWatch: string[]; businessState: string; priceBurden: string;
  causeConfidence: string; futureUncertainty: string; quarterlyTrend: string; quarterlySignal: string; quarterlyCoverage: string;
}
export interface Quarter {
  period: string; fiscalYear: number; quarter: number; revenue: number | null;
  operatingIncome: number | null; operatingMargin: number | null; revenueYoY: number | null;
  revenueQoQ: number | null; scope: string; basis: string; sourceFile: string; reason: string | null;
}
export interface History {
  issuerId: string; points: Quarter[]; loaded: boolean; source: string; generatedAt: string;
  error: string | null; availableQuarterCount?: number; directQuarterCount?: number;
  reconstructedQuarterCount?: number; comparablePoints?: Quarter[]; hasTrailingGap?: boolean;
}
export interface CompanyReport {
  issuerId: string; recentChanges: string; changeReason: string; positiveFactors: string[];
  riskFactors: string[]; counterArguments: string; nextVerificationConditions: string[];
  updatedAt: string; oneLineView: string; quantSummary: string; businessQuality: string;
  valuationView: string; momentumView: string; dataLimitations: string;
}
export interface Candidate {
  issuerId: string; candidateRank: number; sourceRank: number; researchStatus: string;
  selectionReasonKo: string; selectionFlags: string[]; financialSnapshotDate: string;
  priceCutoffDate: string; [key: string]: unknown;
}
export interface ValuationBand {
  code: string; labelKo: string; shortLabelKo: string; percentile: number; changesRank: false;
}
export interface Detail {
  summary: Stock; report: CompanyReport; news: Array<Record<string, unknown>>;
  filings: Array<Record<string, unknown>>; analysis: Analysis; evidence: Evidence; history: History;
  financial: FinancialRecord | null; candidate: Candidate | null; valuation?: ValuationBand | null; sourceStates?: Record<string, unknown>;
}
export interface PublicationManifest {
  schemaVersion: number; generatedAt: string; sourceCommit: string; snapshotDate: string;
  priceCutoffDate: string; coverage: {
    stocks: number; details: number; rankingMasks: number; completeCount: number;
    m01Available: number; m02Available: number; m03Available: number; m04Available: number;
    [key: string]: number | boolean;
  }; inputHashes: Record<string, string>;
  files: Record<string, { sha256: string; bytes: number }>;
  financial?: Record<string, unknown>;
  basePath?: string; generationId?: string;
  dataKind?: 'VERIFIED_PUBLISHED_SNAPSHOT' | 'REAL_MARKET_RECORDED';
}
export type ConnectionSource = 'LIVE' | 'CACHE' | 'BACKUP' | 'BUNDLED' | 'FAILED';
export interface MarketState {
  manifest: PublicationManifest | null; stocks: Stock[]; source: ConnectionSource;
  lastSuccessAt: string; lastAttemptAt: string; error: string | null; refreshing: boolean;
  financialByCode: Record<string, FinancialRecord>;
  financialError?: string | null; financialDate?: string;
  valuationsByCode?: Record<string, ValuationBand | null>; valuationError?: string | null;
  candidatesByCode?: Record<string, Candidate>; candidatesError?: string | null;
}
export interface DailyBar { date: string; open: number; high: number; low: number; close: number; volume: number | null }
export interface PriceData {
  schemaVersion: number; issuerId: string; source: 'CACHE' | 'MISSING'; sourceUrl: string;
  requestedCutoffDate: string; lastTradingDate: string | null; collectedAt: string | null; lastAttemptAt: string;
  adjustment: string; bars: DailyBar[]; error: string | null; pages?: unknown[];
}
