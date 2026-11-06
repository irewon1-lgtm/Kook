# PC 기능·정보 대조 독립 감사 — 최종 근거 갱신

담당 변경은 `docs/pc/**`와 `web/public/verification/**`뿐입니다. 제품·Android·엔진·테스트·워크플로 파일 수정, 커밋·푸시는 하지 않았습니다. Android 기준 커밋은 `a1676eab7bfe6b0d3765e4340504f8ff2a2d2a3f`입니다.

## 작성한 산출물

- `docs/pc/feature-parity.md`, `feature-parity.json`: **552행**, 32개 분류. 원본 경로:줄 → PC 경로:줄·보이는 위치 → 검사 근거 → 범위를 제한한 상태입니다. 최종 상태 행수는 PASS235 / NOT_RUN316 / BLOCKED1이며, 중복·교차색인 행이 있으므로 기능 완성률로 계산하지 않습니다.
- 모든 20개 해석 필드, 14개 리포트 필드, 4×14개 지표 필드, 4×9개 피어 필드, 전체 재무/facts/정확 금액, 27개 후보 필드와 4×6개 중첩 지표 필드, 전체 분기 원본·파생 범위와 화면 8열, 차트 24조합, 순위 15조합을 포함합니다. 원본 감사표의 84개 행동 요구도 별도 교차색인으로 유지합니다.
- `docs/pc/android-vs-pc.md`: 메뉴·탭·항상 펼친 선택기·페이지 탐색·독립 펼침·순위 안내·글씨·관심·차트 설정·APK 갱신 대응·LIVE/CACHE 의미·분기 산술·Stage5·원문 상태의 차이를 한국어 표로 설명합니다.
- `web/public/verification/index.html`: 전체 매핑을 실제 HTML로 읽을 수 있는 네이비 정적 보고서입니다. 기본 22px, 반응형 줄바꿈, 모든 링크 최소44px, GitHub/main/기준커밋 링크, 동일 Markdown/JSON 다운로드, Android 전체/상세 감사, 차이 보고서 및 실행 근거를 연결합니다.
- `web/public/verification/evidence/*`: 실제 worker 결과와 최종 검사 로그, 원본 리뷰 및 수정 승인 기록을 복사했습니다. 실패 주입·실제 저장자료·새 원천 수집을 구분합니다. 예전 하네스 보고서의 실패는 그 시점의 기록이며 최신 로그와 구별합니다.

## 직접 확인한 최종 실행 근거

다음 파일의 실제 출력을 읽고 해당 검사에 한해서 상태를 갱신했습니다.

| 근거 | 실제 결과 | 범위 |
|---|---|---|
| `python-full-final.log` | 358 passed, 79 subtests passed | Python 전체 검사. 두 수를 임의의 기능 완성률로 합산하지 않음 |
| `web-unit-full-final.log` | 299 passed | 가격 원본 비교229개 포함, 순위2649×15, optional stages, R1/R2 회귀, 차트/수신/포맷 |
| `core-browser-final.log` | 12 passed | localhost Vite 실제 App6개 및 실제 원본 자료를 사용한 명시적 실패 복구6개 |
| `home-rank-parity-restoration-report.md`와 regression log | 복원 회귀 PASS | 독립 펼침·Stage4/5·정확 재무 원본·각 지표 확보수/비율·교육/출처·상시 순위 안내 |
| `chart-preferences-browser-result.json` | 탭/reload 복원·종목/새 브라우저 격리·잘못된 저장값·저장소 거부 PASS | 실제 삼성/하이닉스 관측 가격의 로컬 브라우저 |
| `detail-stage5-original-result.json` | 원본5필드·MID_PER/null·순위/summary 불변 PASS | 실제005930/003060의 요약과4지표 탭 |
| `oracle-stage5-candidates-report.md` | 원본 oracle16개 PASS | 기존 근거 보존14개 포함, 모든2649개 원본분류 정확 비교 |
| `code-review.md` | R1 CLOSED, R2 CLOSED, focused APPROVED | 처음 요청변경 판정은 수정 후 집중 재검토가 대체함. 공개/실물/전체기능 인증 아님 |

복합 요구의 일부 경로만 실행한 경우 전체 PASS로 올리지 않았습니다. 예를 들어 관심창 선택확인/재접속은 별도의 PASS 행으로 분리하고 취소·누락 identity는 NOT_RUN으로 남겼습니다. 코드/점수 정렬은 PASS이나 회사명 정렬까지 실행했다고 주장하지 않습니다.

## 초기 발견과 실제 해소

초기에 홈/순위 독립 펼침과 inline 근거, 홈 지표 확보율·교육, 없는 종목 전용 안내, 관심창 정렬·빈검색·바깥취소를 root에 원본/PC 경로와 함께 전달했습니다. root가 복원했습니다. 독립 펼침·홈 교육/확보율·순위 안내는 복원 회귀로 PASS, 없는 종목 전용 안내·관심창 세부 분기는 코드 복원만 확인하여 NOT_RUN입니다.

원래 순위 설명은 목록 아래 상시 ‘순위 산출 안내’로 정보를 유지했고 실제 복원 회귀를 확인했습니다. 차트 탭 왕복 상태도 종목별 개인 설정으로 복원한 실제 브라우저 근거를 확인했습니다. Stage5는 브라우저 임계값 재작성이 제거되고 원본 Kotlin 다섯 필드를 그대로 표시하는 것으로 확인했습니다.

독립 리뷰의 중요 결함 R1(후보 실패에도 상세후보 VALID)과 R2(저장소 거부 후 최신 메모리 대신 과거 번들로 후퇴)는 수정 후 회귀와 reviewer 추가 검사로 CLOSED입니다. 새로운 미해결 Critical/Important 결함을 이 문서 갱신에서 확인하지는 않았습니다.

## 남은 중요한 실행·자료 경계

- **공개·실물:** 공개 사이트 실행은 NOT_RUN / NOT_DEPLOYED입니다. Linux 로컬 App·컴포넌트 PASS는 공개 CDN/CORS·최종 배포·Windows/LG 물리 화면을 대신하지 않습니다. Windows/실물은 NOT_RUN을 유지했습니다.
- **아직 실행하지 않은 기능 분기:** 없는 종목 안내, 관심창 원래 정렬·빈검색·취소, 누락 관심 identity, 일부 값/피어/정정/분기 경계와 표의 교차색인 요구는 NOT_RUN입니다. 코드만으로 기능 PASS를 주지 않았습니다.
- **실제 원천 제약:** DART 새 접근은 Python/JVM 동일 HTTP503으로 BLOCKED입니다. 기존 진짜 접수번호·두 본문·원래 날짜의 보존은 새 수집 성공을 뜻하지 않습니다. 빈 IR/official은 MISSING입니다.
- **가격:** 실제 CACHE2636 + 명시적 MISSING13입니다. 모든종목 가격 확보, 실시간 호가, 수정주가 확인, 미래 분기/가격 생성 성공으로 표현하지 않습니다.

## 보고서 자체 실제 검사

`/tmp/check_pc_verification.cjs`를 Linux Chromium155에서 실행했습니다. 로컬 정적 `web/public`만127.0.0.1:5198에 서빙했습니다. CSS 폭390/768/1024/1366/1920/2560 × 글씨22/26/30px의18조건에서 가로 document 넘침0, 모든 실제 링크 높이44px 이상, 보고서 내 다운로드/근거/차이보고서 링크HTTP200, pageerror0입니다. 최종552행으로 다시 실행한 결과는 `verification-report-browser-result.json`에 있습니다.

Markdown/JSON 공개 복사본의 바이트 일치, 모든 PASS 행의 실제 근거 참조, 모든 공개상태 NOT_DEPLOYED, JSON 파싱, `git diff --check -- docs/pc web/public/verification`도 확인했습니다. 정적 HTML 텍스트는 escape하며 프레임워크나 시장 데이터 계산을 요구하지 않습니다.

전체 PASS, 기능 완성률,95점 등 품질 점수, 공개 배포 성공, Windows/LG 실물 성공은 주장하지 않습니다.
