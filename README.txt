국내주식 앱 V3 제작 인수 패키지

먼저 00_START_HERE.txt를 읽는다. 사용자에게는 docs의 PDF 앞 5쪽을 안내한다.
이 패키지는 실제 국내 앱이 아니라 설계 + 오프라인 참고 엔진 + 시험 증거다.
engine/core.py는 Python 표준 라이브러리만 사용한다. DB 모델은 SQLite이며 PostgreSQL 운영 구현은 별도다.

검사 재현 (압축을 푼 디렉터리에서):
python tests/run_all.py evidence/reproduced_clean_pass.json
python tests/run_stress.py
python tests/run_mutations.py 16
python tests/run_mutations.py 16

mutation runner는 기존 체크포인트가 있으면 완료 항목을 건너뛴다.
32종을 새로 재현하려면 evidence/mutation_checkpoint.json을 다른 이름으로 보존하고 다시 실행한다.
모든 시험은 가상·로컬이며 실제 시세/기업 원문/API/Android/배포/수익률 검증이 아니다.
실제 실패 기록과 수정 전 코드도 evidence에 보존되어 있다.
완성 APK와 운영 연결을 이 패키지에 있다고 주장하지 않는다.

중요 파일:
metric_registry.json = 10개 계산과 순위 규칙
requirements.json = 요건별 완료 증거
company_bundle.schema.json = 앱/보고서 공통 묶음 구조 (사실성 검증 아님)
decision_policy.json = 매수/매도 판단 정책
update_dependencies.json = 자료 변경의 영향 범위
data_rights_matrix.json = 아직 승인되지 않은 자료 연결 목록
research/sources.json = 공식 출처
evidence/*.json 및 *.log = 실제 실행 결과
SHA256SUMS.txt = 파일 무결성 목록; 자기 자신은 제외
