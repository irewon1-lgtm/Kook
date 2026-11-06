# 승인 이미지와 실제 공식 PC 웹 시각 검수

검수 범위: 공식 Pages에서 새 익명 브라우저로 실행한 UI e388, 2026-11-06 스냅샷. 승인 JPEG 네 장과 실제 1365×853·1920×1200 원본 PNG, 일반 스크롤 캡처를 독립적으로 열어 비교했습니다. 기본 글씨22px입니다. 원본 사진은 편집하지 않았습니다.

이 점수는 명시한 항목에 대한 리뷰어 판단이며 자동 픽셀 일치율이나 전체 작업 완료 판정이 아닙니다. 시각적 기준 목표95점은 충족했습니다. 실물 LG그램 시험은 미실행입니다.

각 화면25점: 색상·상태 대비5, 정보 위계6, 카드·표 비율/여백5, 글씨·가독성5, 조작 요소 발견성4.

| 화면 | 색상 /5 | 위계 /6 | 비율·여백 /5 | 가독성 /5 | 조작 /4 | 합계 /25 |
|---|---:|---:|---:|---:|---:|---:|
|홈|5|5.5|4.25|5|4|23.75|
|전체 순위|5|5.5|5|5|4|24.5|
|종목 상세|5|6|4.5|5|4|24.5|
|차트|4.75|6|5|4.75|3.5|24.0|

합계 **96.75/100**. 직접 검수한 이미지에서 심각한 시각 결함 **0건**. 이 관측은 별도 전체 뷰포트·기능 회귀시험이나 실제 기기 시험을 대체하지 않습니다.

## 항목별 실제 감점 근거

### 홈

- information_hierarchy: −0.5점. At1365x853 the top-five table starts at the fold, so reading actual ranks requires ordinary scrolling; approved mobile reference gives ranks more immediate prominence. 근거: `originals/canonical-home-1365x853.png`.
- geometry_spacing: −0.75점. Hero preserves navy/blue language but has more unused vertical space around the title/counts and uses simple bars instead of the approved subtle candle motif. 근거: `originals/canonical-home-1365x853.png`.

### 전체 순위

- information_hierarchy: −0.5점. Default expanded metrics and visible source/search controls push actual rank rows below the first fold. This is readable and nonfixed, but a minor difference from approved table-first density. 근거: `originals/canonical-ranking-1365x853.png`.

### 종목 상세

- geometry_spacing: −0.5점. The score-card middle fact label wraps to two lines (기존4지표순위), so rank22 has a lower baseline than adjacent total/count numbers; all text remains readable and uncut. 근거: `originals/canonical-detail-1365x853.png`.

### 차트

- palette_status_contrast: −0.25점. Rising candles use a softer pink-red than the vivid red in the approved reference, while up/down semantics remain correct. 근거: `originals/focus-chart-1365x853-chart-controls-and-plot.png`.
- typography_readability: −0.25점. Automatically calculated price ticks include half-won decimals (.5), adding minor reading noise compared with the approved integer labels; these are axis ticks, not fabricated transaction prices. 근거: `originals/focus-chart-1365x853-chart-controls-and-plot.png`.
- controls_discoverability: −0.5점. Daily/weekly/monthly and date-range buttons share one row with limited group separation; both selected states are visible but the approved separate-row grouping is more immediately distinct. 근거: `originals/focus-chart-1365x853-chart-controls-and-plot.png`.

## 보존한 디자인과 의도한 PC 차이

- 깊은 네이비 배경, 차콜/네이비 카드, 파란 강조, 흰 본문, 얇은 경계, 위험 빨강/미확보 주황/중립 상태를 유지합니다.
- 모바일 하단 메뉴를 PC 상단 메뉴로 이동하고 기본22px·44px 이상 조작 영역과 글씨 조절을 제공합니다. 점수·4지표는 PC에서 나란히 배치합니다.
- 지표 네 개를 처음부터 펼쳐 일반 문서 흐름에 배치합니다. 목록으로 스크롤한 실제 focus 캡처에서 선택창이 화면을 덮지 않습니다.
- 승인 이미지의 예시 숫자는 복사하지 않았습니다. 실제 최신2649종목·2370순위산출·279결측, 실제 삼성전자201500원/2026-11-05 가격과 CACHE 출처를 사용합니다. 데이터 차이를 외형 감점으로 취급하지 않습니다.
- 홈·순위에서 표가 첫 화면 아래로 이어지는 점은 큰 글씨·설명·항상 펼친 선택 영역에 따른 실제 차이이며 위계 항목에서 감점했습니다. 숨겨진 고정 패널로 목록을 가리지 않습니다.

## 독립 확인한 대비

최종 CSS의 실제 적용값을 사용한 WCAG 상대휘도 비율: 본문15.781:1, 보조설명8.791:1, 주요 흰글씨/파랑버튼4.911:1, 재무위험6.773:1, 미확보8.213:1, 중립상태11.300:1. 선행 기본 스타일을 덮는 app.css의 실제 버튼색 #0670d4를 기준으로 계산했습니다.

## 원본·접속·버전 증거

- 실제 URL: https://irewon1-lgtm.github.io/Kook/
- 캡처 UI/source: e388ea2b7aa52843af5c0d32861cc0493bfe5f5a.
- generation: c76743237bbcdc05e5e6ca74df35082235ac9a103238e8e7765c8eaccf0a3e71.
- 24개 PNG 모두 동일 UI/generation, LIVE 게시자료,22px, controlled:false, 정확한 SHA-256으로 검증했습니다.
- 공식 사이트 직접 익명 접속 PASS. 로그인·쿠키 사전 주입·호스팅 안내 클릭0. 실제 page/request/console 오류0은 official-public-capture-summary.json에 기록됐습니다.
- 원본 PNG/메타데이터: originals/. 승인 이미지와 원본을 나란히 표시한 HTML 및 비교 PNG: comparisons/. 비교 PNG는 비교 HTML의 브라우저 캡처이며 원본 제품 캡처로 표시하지 않습니다.
- 이 갤러리 원본의 실제 OS/브라우저는 Linux Chromium입니다. 동일 코드의 실제 Windows Chrome·Edge 실행은 CI42157888715의 별도 원본 기록입니다. 실물 LG그램 및 실제 Windows 데스크톱 배율 변경은 NOT_RUN이며 CSS 뷰포트/DPR 모사를 실물 시험으로 보고하지 않습니다.
- 최종 main이 문서만 추가한 후손일 수 있습니다. 원본 JSON에는 실제 촬영한 e388을 그대로 남기며 실행하지 않은 후속 버전으로 바꾸지 않습니다.
