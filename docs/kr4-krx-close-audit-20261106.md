# KR4: 2026-11-06 종가 불일치 67건 (KRX 교차검증)

## 범위와 현재 판정
- 고정 입력: \`evidence/pc_krx_close_hold_20261106.csv\` (종목코드 67개, 저장 종가, 기존 \`kr4-pc-site\`의 가격 파일 Git blob SHA-1)
- 근거: GitHub Actions PR #45 \`43779878087\`의 \`.pc-work/price-collection.jsonl\`에서 \`Revised close requires manual review at 2026-11-06\` 총 67개.
- **현재 공식 KRX 종가 0개 확인. 67개 전부 HOLD**. KRX 응답이나 공식 데이터가 없는 상태에서 CI PASS를 종가 검증 완료로 해석하지 않는다.
- 공식 시장별 KRX OpenAPI: \`stk_bydd_trd\`(KOSPI), \`ksq_bydd_trd\`(KOSDAQ), 조회 기준 \`basDd=20261106\`. KRX 발급 및 *서비스별* 승인이 된 \`AUTH_KEY\`가 있어야 한다. 승인되지 않은 키 또는 샘플 키로 데이터 확인을 주장하지 않는다.

## 감사 도구
\`\`\`powershell
# 1. KRX 키가 없어도 고정 67건과 HOLD 상태 확인 가능
python scripts/audit_pc_krx_closes.py --status-only

# 2. 공식 KRX 두 시장 응답 JSON을 로컬에 이미 확보한 경우
python scripts/audit_pc_krx_closes.py --krx-stk .pc-work/krx/stk.json --krx-ksq .pc-work/krx/ksq.json

# 3. 별도 승인된 KRX API 키를 PC 환경변수에 보관한 경우 (키를 저장소/PR/채팅에 올리지 말 것)
python scripts/audit_pc_krx_closes.py --fetch-krx
\`\`\`

검증 결과는 \`.pc-work/krx-close-audit-20261106.json\`에 기록한다. 저장 종가는 **절대 자동 변경되지 않는다.**

## 판정
- 공식 응답 JSON의 날짜가 2026-11-06이 아니면 파일 전체를 거부한다.
- 공식 종목코드는 정확히 6자리여야 한다. 표준 ISIN을 임의로 잘라서 6자리로 변환하지 않는다.
- 시장 간 동일 코드 중복이나 원천 내 중복, 종가 0/비숫자, KRX 응답 누락을 거부하거나 HOLD 처리한다.
- 별도 Naver 관측값을 공급할 경우 \`--naver-candidates\`로 기존 저장값/공식값/새 관측값을 대조할 수 있다. 후보는 \`raw_sha256\`를 동반해야 한다.
- KRX가 새 관측값과 일치하더라도 **결과는 HOLD**. 종가 교체·재무·순위 재계산·운영 배포는 별도 승인과 회귀검증 없이는 하지 않는다.

## 원본 및 접근
- 공식 KRX 이용 방법: https://openapi.krx.co.kr/contents/OPP/INFO/OPPINFO003.jsp
- 유가증권 API: https://openapi.krx.co.kr/contents/OPP/USES/service/OPPUSES002_S2.cmd?BO_ID=JvJFzlAENzZlPBDNGAWC
- 코스닥 API: https://openapi.krx.co.kr/contents/OPP/USES/service/OPPUSES002_S2.cmd?BO_ID=hZjGpkllgCBCWqeTsYFj
- 원본 수집기 변경/본선 병합/운영 배포: 본 PR 범위 밖
- 근거 가격의 스냅샷: \`kr4-pc-site\` generation \`6f4d5c7809a58fbb5e379e02a167141e1f32b1fa03c01d33d29b76ee101665bd\`.

로컬 mock 단위 테스트와 PR CI PASS는 **검증기 동작 확인**이지 **공식 KRX 데이터 확보의 증거가 아니다**.
