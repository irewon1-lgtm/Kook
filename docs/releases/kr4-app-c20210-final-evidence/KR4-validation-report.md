# KR4 다크 네이비 UI 최종 검증 보고서

2026-11-05 공개 버전 **4.3.210 / versionCode 20210**의 외부 검증 결과는 **PASS**다. 공개 APK·CI 빌드 APK·에뮬레이터에 설치된 APK의 바이트가 일치하며, 원래 인증서로 서명된 4.3.209에서 앱 데이터 삭제 없이 업그레이드했다. Main CI 전체가 성공했고, 승인 이미지와 실제 MAIN 화면 4장의 독립 에이전트 시각 평가는 **95.25/100, Critical 0건**이다.

검증의 기준 기록은 [공개 배포 검증 JSON](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/public-delivery-verification.json), [소스·데이터 보존 JSON](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/main-preservation-proof.json), [독립 시각 평가 JSON](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/visual-review-main-8b1-evidence.json)이다.

## 1. 전달물 10개 묶음

모든 다운로드 링크는 고정 버전 태그 `kr4-app-c20210`을 사용한다. 원본 PNG는 Android에서 직접 캡처한 파일이고, `KR4-compare-*.png`는 승인 이미지와 원본 PNG를 나란히 표시한 **브라우저 비교 화면**이다.

| 번호 | 전달물 | 링크 |
|---|---|---|
| 1 | 최종 설치 APK·업데이트 정보·인증서 | [KR4.apk](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4.apk) · [update.json](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/update.json) · [인증서](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/APK_SIGNING_CERT.txt) |
| 2 | 공개 릴리스·소스·CI·기계 검증 | [릴리스](https://github.com/irewon1-lgtm/Kook/releases/tag/kr4-app-c20210) · [PR #40](https://github.com/irewon1-lgtm/Kook/pull/40) · [최종 Main 커밋](https://github.com/irewon1-lgtm/Kook/commit/8b1b45671072636dff59f1ced48aa311204fe2ff) · [Main CI](https://github.com/irewon1-lgtm/Kook/actions/runs/42070717369) · [외부 검증 JSON](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/public-delivery-verification.json) |
| 3 | 실제 홈 전체 화면 원본 | [KR4-home.png](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-home.png) |
| 4 | 실제 종목 순위 전체 화면 원본 | [KR4-ranking.png](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-ranking.png) |
| 5 | 실제 종목 상세·4개 지표 전체 화면 원본 | [KR4-detail.png](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-detail.png) |
| 6 | 실제 종목 차트 전체 화면 원본 | [KR4-chart.png](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-chart.png) |
| 7 | 승인안 대 실제 화면 비교·최종 점수 | [한국어 최종 비교 HTML](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-final-reviewed-gallery.html) · 비교 PNG [홈](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-compare-home.png) / [순위](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-compare-ranking.png) / [상세](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-compare-detail.png) / [차트](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-compare-chart.png) · [평가 JSON](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/visual-review-main-8b1-evidence.json) · CI 원본 [HTML](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-navy-comparison.html) / [ZIP](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-navy-comparison.zip) / [manifest](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-navy-comparison-manifest.json) / [브라우저 비교 PNG](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-navy-browser-comparison.png) |
| 8 | 전체 원본 PNG 634장과 출처·해시 | [전체 갤러리 HTML](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-UI-gallery.html) · [전체 ZIP](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-UI-gallery.zip) · [manifest.json](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/manifest.json) · [SHA256SUMS.txt](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/SHA256SUMS.txt) |
| 9 | 원래 서명 APK 업그레이드·데이터 보존 증거 | [KR4-upgrade-proof.zip](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-upgrade-proof.zip) · [소스·데이터 보존 JSON](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/main-preservation-proof.json) · [실제 기준 버전 4.3.209 APK](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20209/KR4.apk) |
| 10 | 한국어 최종 검증 보고서 | [KR4-validation-report.md](https://github.com/irewon1-lgtm/Kook/releases/download/kr4-app-c20210/KR4-validation-report.md) |

## 2. 공개 APK·원래 서명·업그레이드

| 확인 항목 | 실제 결과 |
|---|---|
| 패키지 | `com.krstock.v3` 유지 |
| 실제 설치 기준 버전 → 최종 버전 | `20209 / 4.3.209` → `20210 / 4.3.210` |
| 공개·CI 빌드·설치 APK SHA-256 | `579d25b95a02e5795c31a05526c4ef4bbcdbddb1f0334b843828b7b40a1c0017` — 세 파일 동일 |
| 기존·최종 서명 인증서 SHA-256 | `1070e82beee574cd29ffd2dc3e693fd7f7862374f16d1606567db303ab152477` — 동일 |
| 실제 기준 APK SHA-256 | `3051d0c999284b3718e7d6f2274590f580c8d7fc2ad7bd72856b25aba2fc731c` |
| 업그레이드 전·후 UID | `10192` → `10192` |
| 업그레이드 전·후 최초 설치 시각 | `2026-11-05 17:31:05` → 같은 값 |
| 앱 전용 데이터 표식 | 업데이트 직후와 재실행 후 모두 보존 |
| 설정 확인 범위 | Android 시스템 테마 `ui_night_mode=2` 유지 |
| 공개 전송 확인 | CI 원본 11개 릴리스 첨부물과 최신 업데이트 메타데이터를 인증 없이 내려받아 바이트·버전·해시 확인 |

기존 시장 캐시 `kr4_latest_snapshot.json`은 **2,328,697바이트**이며, 업그레이드 전과 **업데이트 앱을 처음 실행하기 전**의 원본 바이트가 일치했다. SHA-256은 `a7e296f069387674d52c9fdf39bd18088dc331c68127e153417f3447b4119745`다. 첫 실행·재실행 후에도 같은 캐시 해시와 LIVE 수신 증거를 확인했다. 화면과 수신 기록의 기준일은 `2026-11-05`, 전체 종목 수는 `2,649`, 완전 데이터 표시는 `2,376/2,649`다.

소스 보존 기록에서 데이터 계층 전체, `app/build.gradle.kts`, `AndroidManifest.xml`, 퀀트 스냅샷, 재무 안전성 스냅샷, 분기 이력이 기존 기준과 동일하다. 이번 검증은 UI 변경과 기존 데이터 보존에 관한 것이며, 2,649종목의 모든 원천 필드를 새로 수집했다는 주장은 포함하지 않는다.

## 3. 최종 Main에서 실제 실행한 테스트

최종 소스는 `8b1b45671072636dff59f1ced48aa311204fe2ff`다. 아래 수치는 이 커밋의 원격 Main CI 로그와 결과 파일에서 확인했다.

| 검증 | 실행 결과 | 실패 / 오류 / 건너뜀 |
|---|---:|---:|
| Python | 298개 통과 + 하위 검사 44개 통과 | 실패 0 |
| JVM 단위 테스트 | 152개 | 실패 0 / 무시 0 |
| Android14 전체 계측 테스트 | 41개 | 0 / 0 / 0 |
| Android14 휴대폰·시스템 밝은 모드 | 24개 | 0 / 0 / 0 |
| Android14 휴대폰·시스템 어두운 모드 | 24개 | 0 / 0 / 0 |
| Android14 태블릿·시스템 밝은 모드 | 24개 | 0 / 0 / 0 |
| Android14 태블릿·시스템 어두운 모드 | 24개 | 0 / 0 / 0 |

Android는 전체 테스트 41회와 4개 환경의 96회를 합쳐 **137회 실행**했다. 같은 테스트를 환경별로 반복하므로 서로 다른 테스트 137개를 뜻하지 않는다. Python 하위 검사 44개도 별도로 표기했으며 298개에 더한 고유 테스트 수로 취급하지 않았다.

각 환경에서 차트 24조합, 지표 선택 15조합, 데이터 출처 5상태, 재무 안전성 4상태, MAIN 화면 4종과 실제 긴 종목명의 잘림 여부를 확인했다. 시스템 밝은·어두운 모드 모두 승인 네이비 색상을 유지했다. 휴대폰 기본 환경은 360dp·글꼴 배율 1.0, 좁은 화면 환경은 **320dp·배율 1.3**이며, 좁은 화면은 필수 값·조작부의 잘림과 탐색 가능성을 검사했다.

추가로 원래 서명된 최종 APK를 직접 설치·업그레이드한 뒤 홈 재실행, 종목 `005930`, 재무 안전성 상세, 일봉·주봉·월봉을 실제 터치했다. 수신된 원본 일봉 캐시를 별도로 해시 검증하고 선택 구간의 OHLCV를 독립 계산해 화면과 비교했다. 일봉은 실제 1거래일, 최근 주봉·월봉은 각각 실제 4거래일 집계와 일치했다. 단순 최신 시세 행이 아니라 **선택된 구간의 하단 요약과 거래일 수**를 대조했다.

## 4. 실제 캡처·승인안 비교·시각 평가

대표 홈·순위·상세·차트는 Android14의 원래 `com.krstock.v3.MainActivity`에서 실행한 **1080×2280 전체 화면 PNG**다. 4장 모두 `REAL_MARKET_LIVE`, `source=LIVE`, `network_succeeded=true`, `controlled_state=false`의 메타데이터와 원본 캐시 출처를 갖는다. 차트 대표 화면은 실제 OHLCV의 **6개월·캔들** 상태다.

승인 JPEG 4장, 실제 PNG 4장, 캡처 메타데이터 4개의 해시를 CI manifest와 독립 평가 기록 양쪽에 대조했다. HTML에는 원래 JPEG·PNG 바이트를 그대로 넣었다. 생성 이미지, 재구성한 앱 화면, 색상 보정한 캡처를 실제 실행 증거로 사용하지 않았다. 비교 PNG와 브라우저 미리보기는 Android 앱 캡처와 구분한다.

독립 **에이전트**가 실제 Main 캡처를 새로 열어 시각 평가했다. 사람의 수동 인증을 뜻하지 않는다. 화면별 배점은 색상 5·배치 5·글자 4·간격 4·가독성 4·정보 3으로 25점, 전체 100점이다.

| 화면 | 색상 /5 | 배치 /5 | 글자 /4 | 간격 /4 | 가독성 /4 | 정보 /3 | 합계 /25 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 홈 | 4.75 | 4.50 | 3.75 | 4.00 | 4.00 | 3.00 | 24.00 |
| 순위 | 4.75 | 4.75 | 3.75 | 3.75 | 4.00 | 3.00 | 24.00 |
| 상세 | 4.75 | 4.50 | 3.75 | 3.50 | 4.00 | 3.00 | 23.50 |
| 차트 | 4.75 | 4.50 | 3.75 | 3.75 | 4.00 | 3.00 | 23.75 |
| 합계 | | | | | | | **95.25/100** |

Critical은 **0건**이다. 검토한 대표 화면에서 읽기 어려운 겹침, 필수 조작부 잘림·가림, 눈에 띄는 수치 표기의 불일치가 관찰되지 않았다는 범위다. 정적 이미지 검토만으로 상위 데이터 제공자의 정확성을 인증하는 것은 아니다.

남은 차이는 다음과 같은 경미한 시각 차이이며, 이 검토에서 배포를 막는 오류로 분류되지 않았다.

| 화면 | 평가 기록의 남은 차이 |
|---|---|
| 홈 | 기준안보다 지표 막대가 이름·값 열에 비해 짧고 일부 테두리·글자가 조금 강하다. 화면 아래에 보조 커버리지 영역의 상단 선이 조금 보인다. |
| 순위 | 열 구분선과 점수 제목 정보 표현이 단순하며, 제목·숫자 글자가 조금 강하다. |
| 상세 | 시세 장식이 옅은 캔들 대신 선형 스파크라인이다. 일부 지표 아이콘·화살표가 단순하며, 재무 설명 둘째 줄 끝이 짧게 남는다. |
| 차트 | 가격축 격자 밀도와 날짜의 짧은 표기가 다르다. 아래쪽에 보조 범위·봉 수 행의 시작이 보이며 필수 출처 표시는 온전히 보인다. |

전체 634장 갤러리, CI 승인안 비교 HTML, 최종 한국어 비교 HTML은 390px 브라우저에서 접기 전·후 모두 가로 스크롤 없이 표시됨을 확인했다(`scrollWidth=390`). 최종 비교 HTML SHA-256은 `78ac90a2b5952f510d0fe5ea51c030155fe9993710fe3ccd987a9d26279756b9`다. CI에서 배포한 원본 11개 첨부물은 수정하지 않았고, 그 안의 자동화 단계 평가 대기 기록도 원래대로 보존했다. 최종 독립 평가와 점수는 별도 한국어 HTML·평가 JSON에서 확인할 수 있다.

## 5. 증거의 범위

원본 PNG **634장**은 LIVE 151장, 기록된 실제 시장 데이터 404장, 제어 테스트 79장으로 구성된다. 전체를 실시간 시장 수신 화면으로 취급하지 않는다. 자동화 4개 환경에서 각각 155장, 서명 APK 확인에서 14장을 수집했다. 대표 4장은 모두 LIVE이며, 설치된 서명 APK의 첫 홈·재실행 홈에도 별도 LIVE 수신 기록이 있다. 서명 APK의 일·주·월 차트 캡처에는 보수적인 `CONTROLLED_FIXTURE / NOT_VISIBLE` 출처 표기를 유지하면서, 실제 수신 일봉 원본과 선택 구간 OHLCV의 독립 일치 검증을 별도 기록했다.

OHLCV의 수정주가 여부는 **`UNVERIFIED`**로 남겨 두었다. 제공 원본 값을 보존하며, 수정주가 검증이나 임의 보정이 완료되었다고 주장하지 않는다.

실행 환경은 **Android14 에뮬레이터**다. 접근 가능한 물리 기기는 없었으며 물리 기기 검증은 수행하지 않았다(`physicalDeviceVerified=false`, 증거 목록 비어 있음). 이 보고서의 PASS는 위에 명시한 소스·배포·업그레이드·데이터 보존·자동화 UI·대표 화면 시각 검증의 실제 실행 범위에 해당한다.
