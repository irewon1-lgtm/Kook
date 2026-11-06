# 최종 공개·수집·실행 상태

공식 사이트는 https://irewon1-lgtm.github.io/Kook/ 입니다. 제품 소스 `e388ea2b7aa52843af5c0d32861cc0493bfe5f5a`, 게시 generation `c76743237bbcdc05e5e6ca74df35082235ac9a103238e8e7765c8eaccf0a3e71`의 fresh 익명 직접 진입, 실제 공개 화면 24개 및 Windows Chrome·Edge 실행을 확인했습니다. GitHub 인증401과 Pages설정403은 해결됐습니다. 과거 임시호스트·초기 e3 증거는 당시 기록으로 보존합니다.

552행 대조는 **범위 한정 PASS 551 / NOT_RUN 1**입니다. 단 하나의 NOT_RUN은 **물리 LG Gram**입니다. 행별 원본 경로·PC 경로·보이는 이름·시험 및 증거의 범위를 확인해야 하며, 이 숫자를 완성률이나 전체 품질 점수로 해석하지 않습니다.

| 실제 실행 | 결과와 범위 |
|---|---|
| 최종 공식 PUBLIC | fresh 직접 접속 PASS, host 안내 클릭0, 원본24 PNG SHA 확인. [공개 화면·리뷰](../../web/public/verification/captures/index.html) |
| 실제 Windows | run42157888715: Win32 Chrome156.0.8078.80 / Edge156.0.4314.56 각각53 PASS, skip·flaky·unexpected0. 물리LG/사용자Windows11 화면 검증과 구분. [원본 요약](../../web/public/verification/evidence/final-e388-ci/summary.json) |
| 최신 Nov6 통합 | Python366 +103 subtests, 웹319, portable build PASS. 문서·증거만 변경한 동일 runtime/input에서 재실행. [통합 증거](../../web/public/verification/evidence/final-integrate-proof.json) |
| 원본 JVM | 최신Nov6 입력153 tests/26 suites, failure·error·skip0,48초 강제 재실행. [요약](../../web/public/verification/evidence/original-jvm-final-nov6-summary.json) |
| 원본→실제 DOM | 여섯 대표 종목2208개 값, 보강399개, 독립 Decimal 분기증감96개. 별도 보강/SSR을 native에서 실행한 것으로 합치지 않음. [전체 대조](feature-parity.md) |
| 원천 접근 | 실제GHA 3종목 각각 DART30/news10/Naver20, corpcode·공식roots 접근 성공. cloud executor503은 역사적 로컬 실패. 전체2649 fresh맥락·IR 성공은 미주장. [실제 로그](../../web/public/verification/evidence/upstream-ci-pr42-verification-excerpts.log) |

최신 원천은 quant·financial·candidate **2026-11-06**, 가격 마감 **2026-11-05**입니다. 전체2649, 산출2370, 결측279; M01/M02/M03/M04 가용2561/2410/2620/2619입니다. 원래 PER 교차검증 gate 때문에 추가6개가 미확보이며, 이전2376을 강제로 유지하지 않습니다. 새 동일입력의15조합39735개 원본Kotlin 순위 비교가 일치하며 Nov5와 Nov6 순위불변 주장이 아닙니다.

분기 원천 coverage4q/8q는2467/2214, 원래 같은 회계범위의 연속 비교가능 coverage는2345/2119입니다. 원천8q floor1192는 유지합니다. 재무는2109PASS/275FAIL/39HOLD/226N/A, 후보10/eligible1366입니다. 가격2636CACHE/13MISSING과 미래자료 부재를 유지합니다.

수동 quant42152978807 SUCCESS 뒤 **자동 workflow_run** recovery42155937715 및 financial42155937716 SUCCESS를 확인했습니다. 과거 timer quant42100942826 FAILURE는 보존합니다. 이후 timer-triggered quant와 PC daily cron의 실제 실행은 아직 관측하지 않았습니다. 수동 복구·자동후속 성공을 예약timer 성공으로 바꾸지 않습니다.

실제5분 poll로 같은 문서에서 Nov5→Nov6,2376→2370,025560 순위3→보류·미산출을 관측했습니다. reload·수동refresh·mock·clock변경0, documentLoads1입니다. **원래 observer는 현지화 prefix 기대값 오류로 exit1**했으며 실제 network/DOM 저장본과 원본Kotlin을 다시 검증한 **DERIVED_AUDIT_PASS**입니다. 새 live exit0 재실행 주장이 아니며 같은 문서 afterPNG는 없습니다. 별도 최종24PNG를 observer의 after로 표시하지 않습니다. [원본 실패와 파생감사](../../web/public/verification/evidence/real-background-observer/report.md).

공개 화면 시각 review **96.75/100**은 공개된 rubric의 감점을 포함한 화면 평가이며 전체 기능 점수가 아닙니다. 원본 APK `kr4-app-c20210`, SHA `579d25b95a02e5795c31a05526c4ef4bbcdbddb1f0334b843828b7b40a1c0017`는 전후 동일합니다. app/engine/signing 생산입력은 변경하지 않았습니다. 원래 예약시험의 날짜 결합은 시험 계약만 수정했고 실제 자동후속 자료수집 성공을 별도 기록했습니다.

남은 범위: 물리 LG Gram 화면·사용자 조작 **NOT_RUN**, 향후 예약timer 실제 관측 **NOT_OBSERVED**, 전체 종목의 매번 fresh맥락·비어 있는 IR은 보장하지 않습니다. 최종 문서 병합 이후 main 게시의 정확한 commit 재확인은 별도 후속 증거로 남깁니다.
