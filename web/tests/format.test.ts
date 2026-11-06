import { expect, it } from 'vitest';
import { formatExactWon, formatNumber, safeExternalUrl } from '../src/format';
it('distinguishes missing and zero values', () => { expect(formatNumber(null)).toBe('미확보'); expect(formatNumber(0)).toBe('0'); });
it('preserves exact won integers beyond binary64 safe integer range', () => { expect(formatExactWon('9007199254740993')).toBe('9,007,199,254,740,993원'); });
it('rejects executable URLs and embedded credentials', () => {
  expect(safeExternalUrl('javascript:alert(1)')).toBeNull();
  expect(safeExternalUrl('https://user:password@example.com')).toBeNull();
  expect(safeExternalUrl('https://dart.fss.or.kr/dsaf001/main.do?rcpNo=202600001')).toContain('dart.fss.or.kr');
});
