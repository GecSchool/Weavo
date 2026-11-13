# 기능별 메시지 전달 순서와 형식 참조

- 작성일: 2026-11-07
- 형식 기준 파일: service-message-contracts.md. S3 객체의 경로·본문·정리 조건: [S3 저장 명세](s3-storage-contract.md).
- 흐름 참고 문서: [시스템 흐름 설명서](design/시스템_흐름_설명서.docx). 최신 메시지 형식과 미정 사항은 이 MD와 메시지 명세·스펙을 따른다.
- 흐름 대응: 설명서 N절·그림 N ↔ 이 MD 3.N ↔ 메시지 명세 MD 15.N. N은 1~20이다.
- 목적: 각 기능·예외에서 누가 누구에게 무엇을 보내는지 순서대로 읽고, 각 단계의 메시지 형식을 절 번호로 찾는다.

## 1 읽는 방법

- 먼저 설명서의 해당 절·그림으로 전체 흐름을 보고, 이 MD의 대응 절에서 전송 순서를 확인한 뒤 형식 참조 번호로 메시지 명세를 찾는다.
- 예: 설명서 4절·그림 4 → 이 MD 3.4 → service-message-contracts.md 15.4. 실제 수집 요청 JSON은 메시지 명세 7.2이며, 그림 4와 메시지 명세 4절은 서로 다른 번호다.
- 아래 표의 **형식 참조 번호는 모두 service-message-contracts.md의 절 번호**다. 이 파일의 절 번호와 혼동하지 않는다.
- 예: `6.1`이면 분석 요청 payload, `6.2`이면 분석 결과 payload를 확인한다. 번호는 텍스트로만 적으며 하이퍼링크는 넣지 않는다.
- SQS 작업 요청은 공통 envelope `3.1`과 해당 payload 절을 함께 읽는다. 결과 이벤트는 공통 envelope `3.2`와 해당 payload 절을 함께 읽는다. Scheduler 신호는 `7.1`의 별도 형식을 따른다.
- ID·날짜·null·필드값은 `1.1`, `1.2`, S3 참조는 `1.3`, 오류 객체는 `1.4`를 공통 적용한다. 파서는 `4.1`의 parser.code를 사용한다.
- 단계의 a·b·c는 조건 분기 또는 같은 단계의 세부 전송이다. 서로 다른 실패·성공 분기를 모두 순서대로 실행하는 것이 아니다.
- `웹 ↔ Control`처럼 왕복 표기가 있는 행은 요청과 응답을 묶어 적은 HTTP 조회다. 비동기 작업의 요청·결과는 각각 별도 행에 적는다.
- 상태를 화면에 표시하는 것은 HTTP 상태 조회 응답이다. 별도 웹 푸시 메시지를 추가한 것이 아니다.
- [제안]·[미정]은 원문 상태를 유지한다. 형식이 없는 통신은 메시지 명세 MD의 상황 절 번호와 함께 미정이라고 적는다. 참조 번호가 있다고 JSON 형식이 전부 확정되었다는 뜻은 아니다.
- 아래 내용의 `15.x`·`16.x` 등 후속 처리 참조도 기준 파일의 번호다.

## 2 메시지 종류별 형식 위치

| 메시지 | 보내는 쪽 → 받는 쪽 | 형식 참조 번호 | 용도 | 설명서 흐름·그림 |
|---|---|---|---|---|
| collection.schedule.triggered | Scheduler → Control | 7.1 | 정기 실행 신호. 공통 작업 요청과 envelope가 다름 | 5, 20 |
| analysis.execute | Control → Analysis | 6.1 | INITIAL·RETRY_SOURCE·ADD_SOURCE·REVALIDATE·REPAIR_NAVIGATION·REPAIR_CONNECTOR·RECHECK_SOURCE | 1, 2, 3, 8, 9, 10, 11 |
| analysis.completed | Analysis → Control | 6.2, 16.2 | 성공·부분 성공·실패 결과. 복구 문맥은 16.3도 참고 | 1, 2, 3, 8, 9, 10, 11 |
| collection.execute | Control → Crawler | 7.2 | 최초·정기·수동·리비전·복구 수집 | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| collection.completed | Crawler → Control | 7.3 | 종료 사유·스냅샷·프로젝트별 적재 가능 여부 | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| schema.prepare | Control → Ingestion | 18.1 | 승인된 v1/v2 스키마 준비 | 4, 13 |
| schema.prepared | Ingestion → Control | 18.1 | 스키마 준비 결과 | 4, 13 |
| ingestion.execute | Control → Ingestion | 8.1 | APPEND 일반 적재 / APPROVED_SOURCE_APPEND 승인 출처 적재 / PREPARE 비활성 준비 | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| ingestion.completed | Ingestion → Control | 8.2, 16.2 | 정상 추출 건수·적재 또는 준비 결과·실패 | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| job.issue.detected | Crawler / Ingestion → Control | 9.1, 16.3 | 작업 중 오류·목록 0건·프로젝트 정상 추출 0건 | 7, 8, 9, 10 |
| dataset.apply | Control → Ingestion | 10.1, 10.3 | 데이터 교체·출처 변경 적용. 10.3은 삭제만 있는 경우 | 11, 12, 13 |
| dataset.applied | Ingestion → Control | 10.2, 10.4 | 실제 적용 결과. 10.4는 삭제만 있는 경우 | 11, 12, 13 |
| project-data.delete | Control → Ingestion | 16.4 | 프로젝트 전체 데이터 삭제. 확정 계약 | 16, 17 |
| project-data.deleted | Ingestion → Control | 16.4 | 프로젝트 전체 데이터 삭제 결과. 확정 계약 | 16, 17 |

위 표의 마지막 열은 설명서의 절·그림 번호다. 모든 비동기 메시지에 공통인 중복 전달·중단 복구는 설명서 19절·그림 19를 함께 본다.

추가 공통 형식:

| 대상 | 형식 참조 번호 |
|---|---|
| 최신 데이터 조회의 요청·정상 0건·조회 실패 응답 | 16.1 |
| 삭제 영향 조회의 요청·건수 응답 | 16.1 |
| 실패·부분 성공 결과의 조건과 예제 | 16.2 |
| 복구 요청의 failure_context·0건 이슈 | 16.3 |
| Auth→Control 계정 정리 접수·완료 통지 | 16.5 |
| 스키마·필드 커넥터 | 5.1 |
| 목록·상세 이동 규칙 | 5.2 |
| 최종 수집 스냅샷 manifest | 5.3 |
| 큐 배치 | 3.3 |
| 중복·순서 역전·중단 복구 | 11 |

## 3 기능·예외별 전달 순서

### 3.1 초기 분석과 공통 스키마 추천

최초 분석에서 성공한 출처가 하나 이상이면 그 결과로 공동 스키마 후보를 추천하고 실패 출처는 재시도·제외할 수 있다. 모든 출처가 실패하면 후보를 승인할 수 없다. 재시도 결과는 사용자 수정을 보존하고 필요한 검증을 수행한다.

- 설명서: 1절 「초기 분석과 공통 스키마 추천」·그림 1.
- 메시지 명세의 상황별 전송 표: 15.1.

[기존] 공동 추천은 Analysis가 출처별 분석에 이어서 실행한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control / HTTP POST /control/v1/projects | Idempotency-Key, name, purpose, source_urls, email_enabled | 12.2, 15.1 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 프로젝트와 URL별 ProjectSource 생성 |
| 2 | Control → 웹 / HTTP 201 | project_id | 12.2, 15.1 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 프로젝트 ID 보관 후 출처 목록 조회 |
| 2a | 웹 ↔ Control / HTTP GET /control/v1/projects/{p}/sources | URL별 project_source_id | 18.3 — 생성 후 분석 요청 연결 | 분석 대상 출처 ID를 확보 |
| 3 | 웹 → Control / HTTP POST /control/v1/projects/{p}/analyses | project_source_ids, mode=initial | 12.2, 15.1 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | analysis_id·후보 revision·job_id 예약. 진행 중 동일 분석은 기존 작업 반환 |
| 4 | Control → 웹 / HTTP 202 | analysis_id, job_id | 12.2, 15.1 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 로딩 및 주기적 분석 조회. 생성 요청 처리와 분석 완료 응답을 분리 |
| 5 | Control → Analysis / SQS analysis.execute | purpose=INITIAL, sources, collection_purpose, revision | 6.1 — 메시지 형식 | 대상 출처별 분석 시작 |
| 6 | Analysis ↔ 사이트 / 브라우저 요청·응답 | 시작 URL·목록·상세·다음 목록 후보 | 5.1, 5.2, 4.1 — 실행 규칙; 사이트 응답 JSON은 정의하지 않음 | 렌더링 DOM 확보. 추출·탐색 후보 검증 |
| 7 | Analysis ↔ LLM / 외부 API 요청·응답 | DOM의 필요한 정보·수집 목적·검증 실패 관찰 | 6.1, 6.2, 15.1 — 후보 입출력; 모델 API body는 별도 | 필드·선택자·이동·파싱 코드 후보 생성. 부적합하면 예산 내 재생성 |
| 8 | Analysis → S3 / 객체 저장 | 분석·출처별 중간 결과 | 1.3, 6.1, 6.2 — 참조·후보 형식; S3 저장 명세 2~3절 — 객체 형식·정리 조건 | 성공 출처의 후보·샘플·매핑 정보를 모음 |
| 9 | Analysis ↔ S3·LLM / 객체 조회·외부 API | 출처별 중간 결과 | 1.3, 6.1, 6.2 — 참조·후보 형식; S3 저장 명세 2~3절 — 객체 형식·정리 조건 | 공통 스키마 추천. Control에 별도 공동 추천 요청을 요구하지 않음 |
| 10 | Analysis ↔ 사이트 / 브라우저·HTTP 검증 | 공통 후보와 출처별 규칙 | 5.1, 5.2, 4.1 — 실행 규칙; 사이트 응답 JSON은 정의하지 않음 | 실제 목록·상세 이동·추출 검증과 목록 HTTP 가능 여부 내부 확인. 상세는 브라우저 고정 |
| 11 | Analysis → S3 / 객체 저장 | analysis_id·revision·출처별 검증 샘플 | 1.3, 6.1, 6.2 — 참조·후보 형식; S3 저장 명세 2~3절 — 객체 형식·정리 조건 | 저장 성공 후에만 승인 가능한 성공 결과 생성 |
| 12 | Analysis → Control / SQS analysis.completed | revision, candidate, source_results, sample_ref, intermediate_ref | 6.2, 16.2 — 메시지 형식 | 현재 분석·revision 대조 후 후보·출처별 intermediate_ref·sample_ref와 상태 저장 |
| 13 | 웹 ↔ Control / HTTP GET /control/v1/projects/{p}/analyses/{a} | analysis_id | 12.2, 15.1 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 진행 상태·후보·출처별 오류 조회 |
| 14 | Control ↔ S3 / 객체 조회·응답 | 현재 revision의 sample_ref | 1.3, 15.1 — S3 참조·상황; S3 저장 명세 3절 — 샘플 형식; HTTP 응답은 메시지 명세 19.9 | 분석 조회 응답에 샘플 포함 |
| 15a | Control → 웹 / HTTP 조회 응답 | status=READY (전체 또는 일부 성공); 출처별 status로 구분 | 12.2, 15.1 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 성공 출처 후보와 실패 출처를 함께 표시. 수정·승인 또는 15.2 연결 |
| 15b | Control → 웹 / HTTP 조회 응답 | status=FAILED, 출처별 error | 12.2, 15.1 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 확인 필요 표시. 재시도·제외 선택. 성공하지 않은 후보를 승인하지 않음 |

### 3.2 실패 출처 재시도와 제외

- 설명서: 2절 「실패 출처 재시도와 제외」·그림 2.
- 메시지 명세의 상황별 전송 표: 15.2.

[기존] 초기 분석 재시도와 운영 재확인의 공통 분석 부분을 재사용한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control / HTTP POST /control/v1/projects/{p}/analyses/{a}/sources/{s}/retry | 대상 project_source_id | 12.2, 15.2 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 현재 후보·revision 확인. 동일 출처 진행 중이면 기존 job 반환, 아니면 새 재시도 작업 생성 |
| 2 | Control → 웹 / HTTP 202 | job_id | 12.2, 15.2 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 작업·분석 조회로 상태 확인 |
| 3 | Control → Analysis / SQS analysis.execute | purpose=RETRY_SOURCE, current_candidate, sources, revision, intermediate_refs | 6.1 — 메시지 형식 | 해당 출처만 재분석하고 현재 공동 후보에 맞춘 매핑·검증 수행 |
| 4 | Analysis ↔ 사이트·LLM·S3 / 브라우저·외부 API·객체 저장 | 해당 출처·공동 후보·새 샘플 | 1.3, 6.1, 6.2 — 참조·후보 형식; S3 저장 명세 2~3절 — 객체 형식·정리 조건 | 기존 사용자 수정 보존. Analysis 내부에서 공동 후보 반영안을 구성 |
| 5a | Analysis → Control / SQS analysis.completed | status=SUCCEEDED, candidate, source_results, revision | 6.2, 16.2 — 메시지 형식 | 현재 revision과 일치하면 대상 출처의 결과만 반영 |
| 5b | Analysis → Control / SQS analysis.completed | status=FAILED, source_results[].error | 6.2, 16.2, 1.4 — 메시지 형식 | 실패 상태 유지. 사용자는 재시도 또는 제외 가능 |
| 5c | Control 내부 / 결과 적용 검사 | 반환 revision이 현재 후보와 다름 | 11, 15.3 — 내부 처리; 전송 JSON 없음 | 현재 후보를 덮지 않음. 최신 후보를 기준으로 새 작업·재검증 필요 |
| 6 | 웹 → Control / HTTP PATCH /control/v1/projects/{p}/analyses/{a}/candidate | expected_revision, excluded_project_source_ids | 12.2, 15.2 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 제외를 후보에 반영하고 영향 범위에 필요한 검증을 서버가 판단 |
| 7 | Control → Analysis / 조건부 SQS analysis.execute | purpose=REVALIDATE, 새 revision | 6.1 — 메시지 형식 | 필요한 경우만 제외 후 후보 검증. 검증 승계 가능하면 별도 메시지 없음 |
| 8 | 웹 ↔ Control / HTTP 분석 조회·approve | 현재 후보와 candidate_revision | 12.2, 15.2 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 포함된 출처 결과를 확인하고 15.3 승인으로 진행 |

### 3.3 후보 수정과 재검증 및 승인

- 설명서: 3절 「후보 수정과 재검증 및 승인」·그림 3.
- 메시지 명세의 상황별 전송 표: 15.3.

[기존] 승인 가능한 revision 확인은 백엔드에서도 수행한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control / HTTP PATCH /control/v1/projects/{p}/analyses/{a}/candidate | expected_revision, fields, source_rules, excluded_project_source_ids | 12.2, 15.3 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 조건부 DB 갱신으로 revision 증가. 동시 수정 충돌은 409 |
| 2a | Control → Analysis / SQS analysis.execute | purpose=REVALIDATE, 현재 후보, 영향 출처, 새 revision | 6.1 — 메시지 형식 | 이동·추출·파싱·타입·HTTP 가능 여부를 검증 |
| 2b | Control 내부 / 검증 승계 | 재검증이 불필요한 변경 | 11, 15.3 — 내부 처리; 전송 JSON 없음 | 새 후보에 기존 검증을 승계하고 sample_revision·sample_ref와 승계 근거 유지. Analysis 메시지 없음 |
| 3 | Analysis → S3 / 객체 저장 | 새 revision의 검증 샘플 | 1.3, 15.3 — S3 참조·상황; S3 저장 명세 3절 — 샘플 형식; HTTP 응답은 메시지 명세 19.9 | 이전 샘플과 구분. 저장 실패는 검증 성공으로 반영하지 않음 |
| 4 | Analysis → Control / SQS analysis.completed | 목표 revision, validation_status, source_results | 6.2, 16.2 — 메시지 형식 | 현재 revision 일치 시 검증 결과·sample_revision·sample_ref 반영 |
| 5 | 웹 ↔ Control·S3 / HTTP 분석 조회 / Control의 S3 조회 | 현재 후보·샘플·출처별 실패·match_count | 1.3, 15.3 — S3 참조·상황; S3 저장 명세 3절 — 샘플 형식; HTTP 응답은 메시지 명세 19.9 | 사용자에게 수정 결과 표시 |
| 6 | 웹 → Control / HTTP POST /control/v1/projects/{p}/analyses/{a}/approve | candidate_revision | 12.2, 15.3 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 후보·검증 revision과 승인 조건을 원자적으로 확인 |
| 7a | Control → 웹 / HTTP 409 또는 422 | revision 충돌 또는 검증 미완료·실패 | 12.2, 15.3, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 승인하지 않고 원인 표시. 수정·검증 흐름으로 복귀 |
| 7b | Control → 웹 / HTTP 202 | 승인·적용 상위 job_id | 12.2, 15.3 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 승인 설정·샘플 연결 기록. 신규면 15.4, 출처 변경이면 15.11~12, 구조 변경이면 15.13 |

### 3.4 승인 후 최초 수집

공통 경계: 승인 후 `dataset.apply(BEGIN_CHANGE)` 성공을 확인하고 아래 단계를 수행한다. 완료 또는 최종 실패 시 `dataset.apply(END_CHANGE)` 결과의 실제 커밋 내역을 반영한 뒤 다음 변경을 허용한다. 전송 형식은 메시지 명세 10.5를 따른다.

선행: Control은 승인된 v1 스키마를 Ingestion에 전달하여 Data DB에 프로젝트 적용 작업 단위로 준비한다. Control은 기존 Ingestion 큐에 schema.prepare를 보내고 schema.prepared 성공 후 수집한다. 각 출처는 같은 승인 스키마를 사용한다. 정상 적재된 출처는 공개하고 실패·0건 출처는 문제 표시하며 다른 성공 출처를 되돌리지 않는다. 최초 모든 출처가 실패하거나 정상 추출 0건이면 준비된 v1만으로 정상 운영 API를 공개하지 않고 확인 필요를 표시한다. 이후 한 출처라도 정상 적재되면 해당 성공 데이터를 공개한다. 공개 전 최초 복구는 공개 스키마 조회가 아니라 승인 v1 후보·커넥터와 승인 샘플을 적용 작업에서 확보한다.

정기 일정 등록은 기준 파일 15.20, 설정 형식은 7.1을 참고한다. 최초 수집 자체는 Scheduler 신호를 기다리지 않는다.

- 설명서: 4절 「승인 후 최초 수집」·그림 4.
- 메시지 명세의 상황별 전송 표: 15.4.

[기존] 승인 후 즉시 수집·적재한다. 정기 일정의 등록·실행·정리는 설명서 20절·그림 20을 참고한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Control 내부 / 승인 설정·작업 생성 | source_id, 활성 프로젝트와 승인 규칙 | 11 — 내부 처리; 전송 JSON 없음 | 실행 중 같은 출처가 있으면 새 연결 최초 수집을 후속 예약 |
| 1-BEGIN | Control → Ingestion / SQS dataset.apply BEGIN_CHANGE | 승인 change_job_id·대상·버전 | 10.5 | OPEN 기록 |
| 1-OPEN | Ingestion → Control / SQS dataset.applied | 시작 결과 | 10.5 | 성공 확인 후 준비 |
| 1a | Control → Ingestion / SQS schema.prepare | 최초 승인 change_job_id·v1 스키마 정의 | 18.1 — 메시지 형식 | Data DB에 비공개 스키마 준비 |
| 1b | Ingestion → Control / SQS schema.prepared | 준비 결과·change_job_id | 18.1 — 메시지 형식 | 성공한 경우에만 최초 수집 요청; 실패 시 오류 처리 |
| 2 | Control → Crawler / SQS collection.execute | execution_reason=INITIAL, 신규 대상 collection_mode=FULL, latest_records=[] | 7.2 — 메시지 형식 | 첫 목록부터 수집. FULL 대상은 최신값 일치 조기 종료 제외. 최대 300초 |
| 3 | Crawler ↔ 사이트 / HTTP·브라우저 | 승인된 목록·상세·다음 목록/스크롤 규칙 | 5.1, 5.2, 4.1 — 실행 규칙; 사이트 응답 JSON은 정의하지 않음 | 목록은 검증된 방식으로, 상세는 브라우저로 공유 수집. 오류는 15.7~10으로 분기 |
| 4 | Crawler → S3 / 시도별 manifest·객체 및 결과 저장 | 해당 시도의 불변 snapshot_ref·결과 이벤트 | 1.3, 5.3, 11.1 — S3 참조·시도 계약 | 저장 자체는 채택이 아님. Control이 다음 단계에서 유효 시도를 확인해 채택 |
| 5 | Crawler → Control / SQS collection.completed | 종료 사유, project_results, snapshot_ref | 7.3 — 메시지 형식 | 적재 가능한 대상별 작업 생성 |
| 6 | Control → Ingestion / SQS ingestion.execute | mode=APPEND, 최초 승인 change_job_id·프로젝트·출처·규칙·snapshot_ref | 8.1 — 메시지 형식 | 신규 프로젝트 데이터 추출·검증·역순 저장 |
| 7 | Ingestion ↔ S3·DB / 객체 조회·DB 트랜잭션 | 스냅샷과 커넥터 | 11 — 내부 처리; 전송 JSON 없음 | 기존값 비교·신규 저장·receipt·결과 Outbox 기록 |
| 8 | Ingestion → Control / SQS ingestion.completed | status, normal_extracted_record_count, dataset_revision | 8.2, 16.2 — 메시지 형식 | 프로젝트 출처 최초 수집 상태 갱신. 0건·실패는 기존 복구 흐름 |
| 9 | 웹 ↔ Control / HTTP 프로젝트·작업 조회 | 프로젝트·출처별 준비·완료·오류 | 12.2, 15.4, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 대시보드 표시. 연결된 다른 프로젝트 정보는 숨김 |
| 종료 요청 | Control → Ingestion / SQS dataset.apply END_CHANGE | 종료 목적 | 10.5 | 실제 커밋·CLOSED 확인 |
| 종료 확인 | Ingestion → Control / SQS dataset.applied | 종료 결과 | 10.5 | 동기화 뒤 다음 변경 허용 |


### 3.5 정기 반복 수집

일반 수집은 마지막 페이지까지 확인해도 이번 결과에서 빠진 기존 레코드를 자동 삭제하지 않는다.

공개 스키마 원본은 Data DB다. Control이 대상 프로젝트별 공개 스키마와 버전을 Data 측에서 조회하고 Control의 커넥터와 결합한다. 조회 오류는 빈 스키마로 처리하지 않으며 작업 발행을 보류·재시도한다. Ingestion의 POST /internal/v1/project-schemas:query로 묶음 조회한다.

아래 정기 신호는 먼저 등록된 출처별 일정에서 발생한다. 등록·Input 설정은 7.1, 전체 등록 흐름은 15.20을 참고한다.

- 설명서: 5절 「정기 반복 수집」·그림 5.
- 메시지 명세의 상황별 전송 표: 15.5.

[기존] 최신값 비교 기준은 모든 대상 프로젝트에 대해 확보한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Scheduler → Control / SQS collection.schedule.triggered | source_id, scheduled_at | 7.1 — 메시지 형식 | 중복 신호·활성 연결·실행 중 출처 확인 |
| 2a | Control 내부 / 작업 조정 | 같은 출처가 이미 실행 중 | 11 — 내부 처리; 전송 JSON 없음 | 새 논리 작업 중복 생성 방지. 포함되지 않은 신규 대상은 후속 예약 |
| 2b | Control → Ingestion / 내부 HTTP 최신값 조회 | source_id, targets(project_id, project_source_id) | 16.1 — 내부 HTTP 형식 | 프로젝트·출처별 최신 저장 사용자 필드값 최대 3건 조회 |
| 3a | Ingestion → Control / HTTP 200 | projects[].latest_records, project_source_id, schema_version | 16.1 — 내부 HTTP 형식 | 0건은 정상 응답이며 해당 대상 FULL 수집 |
| 3b | Ingestion → Control / HTTP 오류 응답 | error.code, message, details | 16.1, 1.4 — 내부 HTTP 형식 | 빈 목록으로 바꾸지 않음. 비교 기준 없이 임의 수집하지 않고 기술 오류 재처리 |
| 4 | Control → Crawler / SQS collection.execute | execution_reason=SCHEDULED, projects[].규칙·최신값 | 7.2 — 메시지 형식 | 대상별 INCREMENTAL/FULL 기준으로 비교 |
| 5 | Crawler ↔ 사이트 / 목록·상세·스크롤 | 추출 순서 5개 묶음 또는 페이지 단위 | 5.1, 5.2, 4.1 — 실행 규칙; 사이트 응답 JSON은 정의하지 않음 | 프로젝트별 기준 충족 상태 유지. 모두 충족 또는 끝·시간 제한 종료 |
| 6a | Crawler → S3 / 객체 저장 | 최종 스냅샷과 manifest | 1.3, 5.3 — S3 참조·manifest 형식 | 정상 확정된 참조 확보. 전혀 변경이 없으면 새 정상 세트로 교체하지 않음 |
| 6b | Crawler → Control / SQS collection.completed | 종료 사유, snapshot_ref, project_results | 7.3 — 메시지 형식 | 적재 가능 대상만 다음 작업 생성 |
| 7 | Control → Ingestion / SQS ingestion.execute | mode=APPEND, 적재 가능한 프로젝트별 요청 | 8.1 — 메시지 형식 | 한 공유 스냅샷에 프로젝트별 커넥터 적용 |
| 8 | Ingestion → Control / SQS ingestion.completed | 프로젝트별 결과, 정상 추출 건수, 데이터 revision | 8.2, 16.2 — 메시지 형식 | 자식 작업 집합을 기준으로 종료 집계. 부분 실패는 해당 대상만 복구 |

### 3.6 프로젝트와 출처의 수동 수집

반복 수집과 같이 Control은 Data 측 공개 스키마와 Control 커넥터를 결합한다. 조회 오류는 작업 구성 실패로 처리한다.

- 설명서: 6절 「프로젝트와 출처의 수동 수집」·그림 6.
- 메시지 명세의 상황별 전송 표: 15.6.

[기존] 수동 요청은 공유 출처 수집을 프로젝트별로 중복 수행하는 요청이 아니다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | 웹 → Control / HTTP POST /control/v1/projects/{p}/collect | 소유 프로젝트 | 12.2, 15.6 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 프로젝트 대표 작업과 대상 공유 출처 작업 연결 |
| 1b | 웹 → Control / HTTP POST /control/v1/projects/{p}/sources/{s}/collect | 소유 프로젝트 출처 | 12.2, 15.6 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 해당 공유 출처 수집을 요청 |
| 2a | Control 내부 / 실행 중 작업 확인 | 요청 프로젝트가 현재 작업에 포함 | 11 — 내부 처리; 전송 JSON 없음 | 현재 실행 결과에 연결. 새 작업 중복 생성하지 않음 |
| 2b | Control 내부 / 후속 작업 예약 | 현재 작업에 요청 프로젝트가 없음 | 11 — 내부 처리; 전송 JSON 없음 | 현재 수집·관련 적재 종료 뒤 해당 프로젝트를 포함한 작업 예약 |
| 2c | Control → Ingestion / 내부 HTTP 최신값 조회 | 수집할 출처의 전체 활성 프로젝트 | 16.1 — 내부 HTTP 형식 | 15.5와 같은 정상 0건·조회 실패 구분 |
| 3 | Control → Crawler / 우선 큐 collection.execute | execution_reason=MANUAL, 규칙과 기준값 | 7.2 — 메시지 형식 | 미실행 대기 작업에 우선순위 적용. 이미 실행 중인 정기 작업은 중단하지 않음 |
| 4a | Crawler → Control / SQS collection.completed | 수집 종료 사유·스냅샷·프로젝트별 적재 가능 여부 | 7.3 — 메시지 형식 | 대상별 적재 요청 준비 |
| 4b | Control → Ingestion / SQS ingestion.execute | mode=APPEND, 대상 프로젝트·규칙·snapshot_ref | 8.1 — 메시지 형식 | 프로젝트별 최종 추출·기존값 비교·저장 |
| 4c | Ingestion → Control / SQS ingestion.completed | status·normal_extracted_record_count·dataset_revision | 8.2, 16.2 — 메시지 형식 | 공유 결과를 요청자 소유 프로젝트 범위로만 집계 |
| 5 | Control → 웹 / HTTP 작업 조회 응답 | 대표 또는 출처별 job_id와 현재 상태 | 12.2, 15.6 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 요청 직후의 202와 구분. 일부 실패도 다른 출처는 계속 처리하고 대상별 결과 표시 |

수동 수집 요청의 HTTP 202는 작업을 접수한 직후 반환한다. 수집·적재를 끝까지 기다린 뒤 반환하는 것이 아니다. 마지막 행은 이후 상태 조회 응답을 뜻한다.

### 3.7 접근과 시스템 오류 처리

- 설명서: 7절 「접근과 시스템 오류 처리」·그림 7.
- 메시지 명세의 상황별 전송 표: 15.7.

[기존] 내부 재시도와 SQS 재전달을 구분한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | Crawler → Control / SQS job.issue.detected 또는 collection.completed의 error | SITE_UNAVAILABLE, BROWSER_FAILED, SNAPSHOT_WRITE_FAILED | 7.3, 9.1, 16.3, 1.4 — 메시지 형식 | 문제 범위와 작업 종료 여부 기록 |
| 1b | Ingestion → Control / SQS 결과 | SNAPSHOT_READ_FAILED 등 결과 기록이 가능한 오류 | 8.2, 16.2, 9.1 | 유효한 스냅샷이 있는지 확인 |
| 1c | Ingestion 요청 재전달 / Control 기한 점검 | Data DB 불가 시 receipt·Outbox를 기록할 수 없음 | 10.5, 19.8 | 성공 삭제하지 않고 재전달·DLQ·기한 처리; 결과 발행을 보장한다고 가정하지 않음 |
| 2a | 실행 서비스 → 외부 대상 / 제한된 내부 재시도 | 네트워크·408·429·5xx, 기존 2초/5초 최대 2회 | 5.1, 5.2, 4.1 — 실행 규칙; 사이트 응답 JSON은 정의하지 않음 | 동일 실행 예산 내 재시도. 개별 시도마다 새 Control 작업을 만들지 않음 |
| 2b | 실행 서비스 → Control / 최종 결과의 error | 401·403·캡차·명시적 차단 | 7.3, 8.2, 16.2, 1.4 — 수집 또는 적재 완료 결과·오류 | 우회·사이트 접근 재시도 없이 중단. Control에 확인 필요 전달 |
| 3a | Control → Crawler / SQS collection.execute | 재수집이 필요한 일시 오류, 새 명시적 작업 | 7.2 — 메시지 형식 | 스냅샷 확보부터 다시 수행. SQS 단순 재전달이면 기존 job_id 유지 |
| 3b | Control → Ingestion / SQS ingestion.execute | 같은 유효 snapshot_ref, 동일 규칙 | 8.1 — 메시지 형식 | 화면 재방문 없이 읽기·적재 재시도. 기술 재전달은 같은 작업 |
| 4 | Crawler 또는 Ingestion → Control / 각 completed 이벤트 | 성공·실패, 영향 범위 | 7.3, 8.2, 16.2, 1.4 — 수집 또는 적재 완료 결과·오류 | 성공하면 이후 수집/적재 계속. 재시도 한도 소진은 종료·문제 표시 |
| 5 | Control → 웹·이메일 / 상태 조회 응답·설정에 따른 이메일 | 최종 실패 원인 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | 리비전이면 전체 적용 보류·기존 API 유지. 규칙 오류로 관찰되면 15.8~9 |

### 3.8 공유 이동 규칙 자동 복구

Control은 각 영향 프로젝트의 공개 스키마를 Data 측에서 조회한 뒤 Control의 커넥터·비교 기준과 함께 Analysis에 전달한다. 최초 수집이 모두 실패해 공개 전인 프로젝트는 승인된 v1 후보를 기준으로 사용한다. 조회 실패를 스키마 없음으로 판단하지 않는다.

- 설명서: 8절 「공유 이동 규칙 자동 복구」·그림 8.
- 메시지 명세의 상황별 전송 표: 15.8.

[기존 + 구현 보완] 복구 기준은 Control이 조회해 분석 요청에 전달하는 안이다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Crawler → Control / job.issue.detected + collection.completed | scope=SOURCE, NAVIGATION_FAILED, 단계·영향 출처 | 7.3, 9.1, 16.3 — 메시지 형식 | 공유 출처의 대상 수집 보류. 현재 실행 종료를 확인하고 후속 복구 조정 |
| 2a | Control → Ingestion / 내부 HTTP 최신값 조회 | source_id, targets(project_id, project_source_id) | 16.1 — 내부 HTTP 형식 | 최신 저장 필드값을 조회 |
| 2b | Ingestion → Control / HTTP 200 또는 오류 | 대상별 latest_records 또는 정형 error | 16.1, 1.4 — 내부 HTTP 형식 | 조회 오류는 복구 기준 없음과 구분 |
| 3 | Control → Analysis / SQS analysis.execute | purpose=REPAIR_NAVIGATION, 현재 규칙, recovery_baselines, failure_context | 6.1, 16.3 — 메시지 형식 | 브라우저에서 새 이동 후보 실행·검증 |
| 4 | Analysis ↔ S3 / 승인 샘플 조회 | DB 0건 대상의 approved_sample_ref | 1.3, 6.1, 6.2 — 참조·후보 형식; S3 저장 명세 2~3절 — 객체 형식·정리 조건 | DB 1~2건은 전부 일치해야 하며 0건은 승인 샘플 사용. 기준 없거나 검증 불가면 자동 적용하지 않음 |
| 5a | Analysis → Control / SQS analysis.completed | validation PASSED, schema_change_required=false, 모든 영향 대상 검증 | 6.2, 16.2 — 메시지 형식 | 규칙 revision 갱신 및 자동 적용. 사용자 승인 없이 재수집 |
| 5b | Analysis → Control / SQS analysis.completed | schema_change_required=true, 후보·샘플 | 6.2, 16.2 — 메시지 형식 | 해당 프로젝트별 사용자 검토·승인 흐름으로 연결 |
| 5c | Analysis → Control / SQS analysis.completed | FAILED 또는 UNVERIFIABLE, error | 6.2, 16.2, 1.4 — 메시지 형식 | 확인 필요 상태 유지. 사용자 수정·재검증 안내 |
| 6a | Control → Crawler / SQS collection.execute | execution_reason=RECOVERY, 새 navigation_revision | 7.2 — 메시지 형식 | 해당 규칙·대상으로 수집 실행 |
| 6b | Crawler → Control / SQS collection.completed | 작업 ID, snapshot_ref, 종료 사유·대상별 결과 | 7.3 — 메시지 형식 | 영향 범위 재수집. 다른 프로젝트의 규칙을 임의로 변경하지 않음 |
| 7a | Control → Ingestion / SQS ingestion.execute | 새 규칙으로 확보한 스냅샷·대상별 커넥터 | 8.1 — 메시지 형식 | 해당 적재·준비·적용·삭제 작업 실행 |
| 7b | Ingestion → Control / SQS ingestion.completed | 원래 job_id와 프로젝트·출처별 status·error, 해당 결과 필드 | 8.2, 16.2, 1.4 — 메시지 형식 | 적재까지 성공해야 복구 완료 |
| 8 | Control → 웹·이메일 / 상태 조회 응답·필요한 이메일 | 완료 또는 확인 필요·승인 대기 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | 재수집/적재 실패는 관찰된 오류 흐름으로 복귀 |

### 3.9 필드 커넥터와 파싱 오류 복구

선택자·접근·추출·파싱·타입 변환 실패가 하나라도 있는 레코드는 부분 저장하지 않고 정상 추출 건수에서도 제외한다. 실제 null과 원래 없는 ABSENT 필드는 정상이며 오류와 구분한다. 실패 원인과 확보 가능한 원문은 기존 검증·sidecar 정책에 따라 기록한다. 규칙 관련 실패로 해당 프로젝트·출처의 비교·수집을 계속할 수 없으면 Control은 관찰 정보와 영향 범위를 확인해 Analysis에 자동 복구를 요청한다. 근본 원인을 미리 확정하지 않는다. 반복 실패·검증 불가면 확인 필요로 전환한다. 기술 장애·접근 차단과 구별하며 복구 후보는 최초 시도 포함 최대 3회 검증한다. 비교에 필요한 필드가 추출·파싱·변환 실패로 확보되지 않아 해당 프로젝트 출처의 비교를 계속할 수 없으면 그 대상만 중단하고 관찰 정보로 자동 복구를 요청한다. 별도 실패율 임계치는 두지 않는다.

Control은 Data 측 공개 스키마와 Control의 프로젝트별 커넥터를 결합해 Analysis에 전달한다. 최초 수집이 모두 실패해 공개 전인 프로젝트는 승인된 v1 후보를 기준으로 사용한다. 스키마 조회 실패 시 규칙 복구를 성공으로 처리하지 않는다.

- 설명서: 9절 「필드 커넥터와 파싱 오류 복구」·그림 9.
- 메시지 명세의 상황별 전송 표: 15.9.

[기존] 정상 null과 출처에 원래 없는 필드는 오류로 만들지 않는다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | Crawler → Control / job.issue.detected 또는 collection.completed | scope=PROJECT_SOURCE, 임시 추출·parser 실패 | 7.3, 9.1, 16.3, 1.4 — 메시지 형식 | 해당 프로젝트·출처의 비교 중단. 다른 정상 프로젝트는 계속 |
| 1b | Ingestion → Control / job.issue.detected 또는 ingestion.completed | 최종 추출·변환·parser 실패, 확보 시 failure_evidence 참조 | 8.2, 16.2, 9.1, 16.3, 1.4 — 메시지 형식 | DB/S3 기술 오류와 구분. 원문은 7일 sidecar에서 확인. 실패 레코드는 저장·정상 건수에서 제외. 비교·수집 불가한 규칙 관련 실패는 자동 복구; 후보 검증은 최초 포함 최대 3회 |
| 2a | Control → Ingestion / 내부 HTTP 최신값 조회 | source_id, targets(project_id, project_source_id) | 16.1 — 내부 HTTP 형식 | 최신 저장 필드값을 조회 |
| 2b | Ingestion → Control / HTTP 200 또는 오류 | 대상별 latest_records 또는 정형 error | 16.1, 1.4 — 내부 HTTP 형식 | 복구 기준 확보. DB 0건이면 승인 샘플 참조 준비 |
| 3 | Control → Analysis / SQS analysis.execute | purpose=REPAIR_CONNECTOR, 현재 스키마·커넥터·기준·실패 정보 | 6.1, 16.3, 1.4 — 메시지 형식 | 같은 스키마를 유지하는 파싱·선택자 후보 생성·검증 |
| 4a | Analysis → Control / SQS analysis.completed | PASSED, schema_change_required=false | 6.2, 16.2 | 대상 프로젝트 출처의 새 작업 보류 |
| 4c | Control → Ingestion / SQS dataset.apply SET_CONNECTOR_REVISION | 활성 출처는 ACTIVE, 승인 변경 대상은 CHANGE와 change_job_id·기존/새 revision | 10.6, 19.8 | Data DB 활성 또는 pending 가드 조건부 갱신 |
| 4d | Ingestion → Control / SQS dataset.applied | 적용 결과·revision | 10.6 | 성공 확인 후 Control 규칙 활성화·재수집. 미확인이면 대기 |
| 4b | Analysis → Control / SQS analysis.completed | 스키마 변경 필요 또는 검증 실패·기준 부족 | 6.2, 16.2, 1.4 — 메시지 형식 | 승인 대기 또는 확인 필요. 기존 정상 데이터 유지 |
| 5a | Control → Crawler / SQS collection.execute | execution_reason=RECOVERY, 새 커넥터 | 7.2 — 메시지 형식 | 해당 규칙·대상으로 수집 실행 |
| 5b | Crawler → Control / SQS collection.completed | 작업 ID, snapshot_ref, 종료 사유·대상별 결과 | 7.3 — 메시지 형식 | 영향 프로젝트에 필요한 화면 재수집 |
| 6a | Control → Ingestion / SQS ingestion.execute | 같은 승인 코드·규칙과 최종 스냅샷 | 8.1 — 메시지 형식 | 해당 적재·준비·적용·삭제 작업 실행 |
| 6b | Ingestion → Control / SQS ingestion.completed | 원래 job_id와 프로젝트·출처별 status·error, 해당 결과 필드 | 8.2, 16.2, 1.4 — 메시지 형식 | 정상 적재 후 대상 문제 상태 해소 |
| 7 | Control → 웹·이메일 / 상태 조회 응답·필요한 이메일 | 대상별 성공 또는 실패 원인 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | 공유 출처 전체를 프로젝트 한 곳의 필드 오류로 실패 처리하지 않음 |

### 3.10 수집 결과 0건과 사용자 재확인

- 설명서: 10절 「수집 결과 0건과 사용자 재확인」·그림 10.
- 메시지 명세의 상황별 전송 표: 15.10.

[기존] DB 기존 데이터 0건·신규 저장 0건·최신값 일치 0건과 정상 추출 0건을 구분한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | Crawler → Control / job.issue.detected, code=EMPTY_LIST | scope=SOURCE, 목록 항목 0건 | 9.1, 16.3, 1.4 — 메시지 형식 | 즉시 확인 필요·기존 데이터 유지 |
| 1b | Ingestion → Control / job.issue.detected, code=EMPTY_EXTRACTION | scope=PROJECT_SOURCE, 정상 추출 0건 | 9.1, 16.3, 1.4 — 메시지 형식 | 즉시 대상 프로젝트 출처 문제 표시 |
| 2 | Control → 웹·이메일 / 대시보드 조회 응답·설정 시 이메일 | 0건 대상·원인 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | 리비전이면 전체 적용 보류. 같은 문제 중복 이메일 제한 |
| 3a | 웹 → Control / HTTP POST /control/v1/projects/{p}/sources/{s}/recheck | 해당 project_source_id | 12.2, 15.10 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 새 분석·작업 생성. 202 analysis_id·job_id 반환 |
| 4a | Control → Analysis / SQS analysis.execute | purpose=RECHECK_SOURCE, 현재 설정·실패 정보 | 6.1, 16.3, 1.4 — 메시지 형식 | 해당 출처 재방문·추출·규칙 검증 |
| 5a | Analysis → Control / SQS analysis.completed | 정상 검증 또는 실패·0건 | 6.2, 16.2, 1.4 — 메시지 형식 | 성공 시 기존 승인 설정에 따라 10.7의 새 변경 또는 15.8~9 복구로 연결. 실패면 확인 필요 유지 |
| 3b | 웹 → Control / HTTP 출처 삭제 후보·approve | analysis_id, expected_revision, candidate_revision | 12.2, 15.10 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 삭제 영향 확인과 최종 승인 후 15.12 진행 |
| 6 | Crawler/Ingestion → Control / 완료 이벤트 | 현재 작업의 최종 결과 | 15.10 — 상황·처리 참고; 독립 전송 형식 없음 | 중간 0건 보고와 같은 문제를 중복 처리하지 않음 |

사용자 조치가 필요해지면 0건 표시는 즉시 유지하고 현재 승인 변경의 END_CHANGE 결과를 확인한다. 재확인은 이전 종료가 불확실하면 대기하며, 이후 공개 여부에 따라 10.7의 새 INITIAL 또는 SOURCE_CHANGE로 이어진다. 리비전 재확인은 새 SCHEMA_REVISION에서 최종 출처를 다시 준비한다.

### 3.11 기존 프로젝트에 출처 추가

공통 경계: 승인 후 `dataset.apply(BEGIN_CHANGE)` 성공을 확인하고 아래 단계를 수행한다. 완료 또는 최종 실패 시 `dataset.apply(END_CHANGE)` 결과의 실제 커밋 내역을 반영한 뒤 다음 변경을 허용한다. 전송 형식은 메시지 명세 10.5를 따른다.

분석 요청 전 Control은 공개 상태면 Data의 공개 스키마, 미공개 승인 v1만 있으면 Data의 승인 prepared v1을 읽어 기존 커넥터·후보와 결합한다. 조회 실패 시 임의 스키마로 분석하지 않는다.

- 설명서: 11절·그림 11. 메시지 형식: 6.1, 6.2, 7.2, 7.3, 8.1, 8.2.
- 기존 스키마에 맞춰 분석·검증하고 사용자가 최종 후보를 승인한다. 구조가 변경되면 3.13의 리비전으로 진행한다.

| 순서 | 송신 → 수신 | 내용과 처리 |
|---|---|---|
| 1 | 웹 → Control → Analysis | list_url·대상 analysis_id·expected_revision으로 후보에 출처를 연결하고 새 revision의 ADD_SOURCE 분석·수정·재검증을 시작한다. |
| 2 | Analysis → Control → 웹 | 후보와 샘플을 표시하고 사용자가 최신 검증 후보를 승인한다. |
| 승인 이후 | Control → Ingestion | dataset.apply BEGIN_CHANGE 및 dataset.applied 성공을 확인한다. |
| 3 | Control → Crawler | 새 출처를 INITIAL/FULL로 최대 300초 수집한다. |
| 4 | Crawler → Control | 결과·최종 snapshot_ref 또는 실패를 전달한다. |
| 5 | Control → Ingestion | 공개 프로젝트는 APPROVED_SOURCE_APPEND, 공개 전 승인 v1은 새 INITIAL APPEND를 요청한다. 승인 change_job_id·snapshot_ref와 pending revision을 전달한다. |
| 6 | Ingestion → Control | 성공 시 데이터를 공개하고 완료 결과를 전달한다. 0건·실패는 부분 적재를 공개하지 않고 확인·복구한다. |
| 7 | Control | 결과를 멱등 반영해 출처를 활성화하고 상태를 표시한다. 이벤트 누락은 재발행으로 복구한다. |
| 종료 | Control → Ingestion → Control | END_CHANGE와 dataset.applied의 실제 커밋 결과를 확인하고 다음 변경을 허용한다. |

추가와 삭제가 함께 있으면 모든 추가가 성공한 뒤 3.12 삭제를 수행한다. 추가 실패 시 삭제하지 않으며 이미 성공한 출처는 유지한다. 삭제 실패 시 공개된 출처를 되돌리지 않고 삭제를 재시도하며 상태를 표시한다.


### 3.12 출처 삭제만 있는 변경

공통 경계: 승인 후 `dataset.apply(BEGIN_CHANGE)` 성공을 확인하고 아래 단계를 수행한다. 완료 또는 최종 실패 시 `dataset.apply(END_CHANGE)` 결과의 실제 커밋 내역을 반영한 뒤 다음 변경을 허용한다. 전송 형식은 메시지 명세 10.5를 따른다.

- 설명서: 12절 「출처 삭제만 있는 변경」·그림 12.
- 메시지 명세의 상황별 전송 표: 15.12.

[기존 + 구현 보완] 삭제만 있는 변경은 dataset.apply로 처리한다. 0건 확인 후 사용자가 삭제를 선택하는 진입 흐름은 설명서 10절·그림 10이다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control / HTTP DELETE /control/v1/projects/{p}/sources/{s} | analysis_id, expected_revision | 12.2, 15.12 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 삭제 후보만 기록. 삭제 출처 전용 필드도 자동으로 스키마에서 지우지 않음 |
| 2 | Control → Ingestion / 내부 HTTP deletion-preview | project_id, project_source_id | 16.1 — 내부 HTTP 형식 | 삭제 대상 건수 조회. 이 시점에는 레코드 삭제 안 함 |
| 3a | Ingestion → Control / HTTP 200 | project_source_id, record_count | 16.1 — 내부 HTTP 형식 | Control이 최종 설정과 삭제 영향 표시 |
| 3b | Ingestion → Control / HTTP 오류 | 조회 실패 error | 16.1, 1.4 — 내부 HTTP 형식 | 0건 삭제로 오인하지 않고 표시·재조회. 영향 정보 확인 전 승인을 보류한다 |
| 4 | 웹 → Control / HTTP approve | candidate_revision | 12.2, 15.12 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 현재 검증·후보·삭제 승인 조건 확인 |
| 4-BEGIN | Control → Ingestion / SQS dataset.apply BEGIN_CHANGE | 승인 대상·버전 | 10.5 | OPEN 기록 |
| 4-OPEN | Ingestion → Control / SQS dataset.applied | 시작 결과 | 10.5 | 성공 확인 후 삭제 |
| 5 | Control → Ingestion / SQS dataset.apply | operation=UPDATE_SOURCES, prepared_results=[], delete_project_source_ids | 10.3 — 메시지 형식 | 남은 출처 재수집 없이 삭제. 프로젝트 쓰기 차단·멱등 적용 |
| 6 | Ingestion → Control / SQS dataset.applied | status, active_project_source_ids, dataset_revision | 10.4 — 메시지 형식 | 성공하면 활성 연결 해제 완료 표시. 실패면 기존 상태 유지·기술 재처리 |
| 7 | Control → Scheduler·S3 / AWS API·객체 정리 | 삭제 후 공유 출처의 활성 연결이 0개인지 | 1.3, 15.12 — S3 참조·상황; S3 저장 명세 4.4~4.5절 — 스냅샷 정리 | 마지막 연결에만 일정 정리. 참조 중 스냅샷은 작업 종료 후 삭제 |
| 종료 요청 | Control → Ingestion / SQS dataset.apply END_CHANGE | 종료 목적 | 10.5 | 실제 커밋·CLOSED 확인 |
| 종료 확인 | Ingestion → Control / SQS dataset.applied | 종료 결과 | 10.5 | 동기화 뒤 다음 변경 허용 |


### 3.13 스키마 변경과 복합 변경의 적용

공통 경계: 승인 후 `dataset.apply(BEGIN_CHANGE)` 성공을 확인하고 아래 단계를 수행한다. 완료 또는 최종 실패 시 `dataset.apply(END_CHANGE)` 결과의 실제 커밋 내역을 반영한 뒤 다음 변경을 허용한다. 전송 형식은 메시지 명세 10.5를 따른다.

Control은 Data 측 공개 스키마를 읽어 후보의 기준으로 사용한다. 승인 후 출처별 PREPARE 전에 v2 스키마 정의를 Data DB에 해당 변경 작업 단위로 비공개 준비한다. Control이 schema.prepare/schema.prepared로 먼저 준비한다. 모든 최종 출처가 성공해야 스키마·데이터 공개 버전을 전환하며 실패·0건이면 v1을 유지한다. Control이 해당 프로젝트의 새 일반 작업을 보류하고 진행 중 쓰기 종료를 확인한 뒤 Data 전환·커넥터 활성화를 마치고 재개한다.

- 설명서: 13절·그림 13. 메시지 형식: 7.2, 7.3, 8.1, 8.2, 10.1, 10.2.

| 순서 | 송신 → 수신 | 내용과 처리 |
|---|---|---|
| 1 | 웹 → Control | 재수집 범위·기존 데이터 및 record_id 교체 영향을 안내하고 검증된 최종 스키마를 승인받는다. |
| 승인 이후 | Control → Ingestion | dataset.apply BEGIN_CHANGE 및 dataset.applied 성공을 확인한다. |
| 1a | Control → Ingestion | schema.prepare로 승인된 v2 스키마와 change_job_id를 전달해 비공개 준비한다. |
| 1b | Ingestion → Control | schema.prepared 성공을 확인한 뒤 수집한다. 실패하면 기존 API를 유지하고 오류를 처리한다. |
| 2 | Control → Crawler | 최종 대상 출처마다 REVISION/FULL로 처음부터 최대 300초 수집한다. |
| 3 | Crawler → Control | 출처별 결과·snapshot_ref를 전달한다. |
| 4 | Control → Ingestion | 각 성공 출처를 PREPARE로 요청한다. 한 RECORD 테이블에 새 schema_version·준비 job_id로 미공개 저장한다. |
| 5 | Ingestion → Control | 출처별 준비 상태·정상 추출 건수와 job_id를 반환한다. 실패·0건이면 전체 교체를 보류하고 이전 API와 데이터를 유지한다. |
| 6 | Control → Ingestion | 모두 성공했을 때 dataset.apply(REPLACE)로 준비 job_id 목록을 전달한다. |
| 7 | Ingestion → Control | 공개 데이터 버전·스키마를 일관되게 전환하고 결과를 발행한다. Control은 운영 설정을 반영한다. 이전 데이터는 전환 성공 후 정리한다. |
| 종료 | Control → Ingestion → Control | END_CHANGE와 dataset.applied의 실제 커밋 결과를 확인하고 다음 변경을 허용한다. |

구조가 유지되는 출처 추가·삭제는 3.11~3.12의 순차 처리다. 구조 변경이 함께 있으면 이 흐름의 모든 최종 대상 출처 준비 후 일괄 전환을 유지한다.
REPLACE 결과가 불확실하면 같은 job_id·payload를 재전달하고 원래 결과를 재발행해 재조정한다. ALREADY_APPLIED라면 실제 커밋을 Control에 반영한 뒤 END_CHANGE(COMPLETED)한다. 상세는 메시지 명세 10.8이다.

### 3.14 회원 인증과 계정 기본 설정

- 설명서: 14절 「회원 인증과 계정 기본 설정」·그림 14.
- 메시지 명세의 상황별 전송 표: 15.14.

[기존] Auth API의 인증·계정 기본 설정 흐름이다. 이메일 공급자 호출 세부 방식은 미정이다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Auth / HTTP POST /auth/v1/email-verifications | email | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 회원가입용 6자리 인증번호 생성·만료 관리 |
| 2 | Auth → 이메일 공급자 / 이메일 발송 API [공급자 미정] | 수신 이메일·인증번호·인증 목적 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | 발송 결과 확인. 비밀번호·토큰은 수집 큐에 넣지 않음 |
| 3 | Auth → 웹 / HTTP 202 또는 오류 | 발송 접수 또는 발송 실패 | 12.2, 15.14, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 사용자가 인증번호 입력. 정상 발송으로 오인하지 않게 오류 구분 |
| 4a | 웹 → Auth / HTTP POST /auth/v1/email-verifications/confirm | email, code → 검증 결과 | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 만료·오류 시 재입력·재발송. 인증번호 5분·최대 5회; 발송 빈도는 운영 설정 |
| 4b | Auth → 웹 / HTTP 응답 | 가입 전용 verification_token 또는 오류 | 18.3, 15.14, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 5a | 웹 → Auth / HTTP POST /auth/v1/signup | email, password, verification_token → user_id | 18.3, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 이메일 인증 확인 후 계정 생성 |
| 5b | Auth → 웹 / HTTP 응답 | 201 user_id 또는 가입 실패 | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 6 | 웹 → Auth ↔ Auth 저장소 / HTTP POST /auth/v1/login·DB | email, password | 11 — 내부 처리; 전송 JSON 없음 | 비밀번호·계정 상태 검증 및 refresh session 기록 |
| 7 | Auth → 웹 / HTTP 200 | access_token, csrf_token, refresh 쿠키 | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 이후 Bearer JWT로 Control·Data 요청. 실패면 인증 오류 |
| 8a | 웹 → Auth / HTTP POST /auth/v1/refresh | refresh 쿠키·X-CSRF-Token | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 세션 회전·새 토큰 응답. 만료·폐기면 재로그인 |
| 8b | Auth → 웹 / HTTP 응답 | 새 access_token·csrf_token·refresh 쿠키 또는 인증 오류 | 12.2, 15.14, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 9a | 웹 → Auth / HTTP POST /auth/v1/logout | refresh 쿠키·X-CSRF-Token | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | refresh 세션 폐기·204. 기존 access token의 짧은 잔여 유효시간 허용; 정확한 시간 미정 |
| 9b | Auth → 웹 / HTTP 응답 | 204 또는 인증·CSRF 오류 | 12.2, 15.14, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 10a | 웹 → Auth / HTTP POST /auth/v1/password-reset-requests | email | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 초기화용 6자리 인증번호 생성 |
| 10b | Auth → 이메일 공급자 / 이메일 발송 API [공급자 미정] | email, 초기화용 인증번호 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | 발송 결과 확인 |
| 10c | Auth → 웹 / HTTP 202 또는 오류 | 초기화 요청 접수·오류; 202는 실제 발송 보장 아님 | 12.2, 15.14, 1.4 | 번호 입력 화면 또는 재요청 안내, 계정 존재 여부 비노출 |
| 11a | 웹 → Auth / POST /auth/v1/password-reset-requests/confirm | email, code | 18.3 — 인증 HTTP 형식 | 초기화용 번호 확인; 초기화 전용 일회용 reset_token 반환 |
| 11b | Auth → 웹 / HTTP 응답 | reset_token 또는 오류 | 18.3, 15.14, 1.4 — 상세 형식 미정 | 실패 시 재입력·재발송 |
| 11c | 웹 → Auth / POST /auth/v1/password-resets | email, reset_token, new_password | 18.3 — 인증 HTTP 형식 | 증명 소비 후 비밀번호 변경·모든 refresh 세션 폐기. 기존 access token은 만료까지 유효 |
| 11d | Auth → 웹 / HTTP 응답 | 변경 성공 또는 오류 | 12.2, 15.14, 1.4 — 상세 형식 미정 | 성공 시 로그인, 실패 시 오류 표시 |
| 12 | 웹 ↔ Auth / HTTP GET/PATCH /auth/v1/me | Access JWT·name | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 현재 계정 조회·이름 변경. 탈퇴는 15.17로 연결 |
| 13 | Control·Data → Auth / HTTP GET /auth/v1/jwks | 공개키 조회 | 12.2, 15.14 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 공개키 캐시로 JWT 서명을 검증하며 각 요청의 계정 접근 상태는 별도 Auth 내부 API에서 확인한다(19.5). |

### 3.15 웹과 외부 애플리케이션의 데이터 조회

- 설명서: 15절 「웹과 외부 애플리케이션의 데이터 조회」·그림 15.
- 메시지 명세의 상황별 전송 표: 15.15.

[확정] Data는 서비스 신원으로 Control의 20.4 접근 확인 API를 호출한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹·외부 소비자 → Data / HTTP GET /api/projects/{p}/v{v}/records 또는 /{r} | 웹 Bearer JWT 또는 외부 X-API-Key, filter·sort·cursor·fields | 12.2, 15.15 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 인증 정보 종류와 프로젝트 읽기 권한 구분 |
| 2 | Data ↔ Redis / 키 호출 시 제한 검사 | API 키의 호출 한도; 실패 시 503 | 15.15 | 키 유효성은 Data DB 원본에서 확인 |
| 3a-요청 | Data → Control / 내부 HTTP GET /internal/v1/projects/{p}/access | 검증된 principal type·id, project_id, scope | 15.15, 20.4 | 웹 소유권 또는 프로젝트 상태 확인. 키 해시·폐기는 Data DB에서 검증 |
| 3a-응답 | Control → Data / 내부 HTTP 응답 | 권한 또는 정형 오류 | 12.2, 15.15, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 유효한 경우 데이터 조회 진행, 불가하면 요청 거절 |
| 3b | Data → 소비자 / HTTP 400/401/403/410/429 | 입력·인증·권한·종료 버전·호출 한도 오류 | 12.2, 15.15, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | DB 데이터 조회 전에 오류 반환. 429는 Retry-After 포함 |
| 4 | Data ↔ RDS / DB 조회 | 공개 schema_version·dataset_revision·커서 조건 | 11, 15.3 — 내부 처리; 전송 JSON 없음 | 직전 완료된 공개 데이터만 조회 |
| 5a | Data → 소비자 / HTTP 409 | 커서의 dataset_revision 또는 조건 불일치 | 12.2, 15.15, 1.4 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 처음부터 다시 조회하도록 안내 |
| 5b | Data → 소비자 / HTTP 200 | data, meta(schema_version, dataset_revision, next_cursor, has_more, returned_count) | 12.2, 15.15 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 필터·정렬·선택 필드 적용 결과 반환. 단건 없음은 404 |
| 6 | Data → Redis / 키 사용 시각 기록 [API 키 요청] | key_id, last_used_at | 15.15, 18.2 | Data DB의 키 원본을 확인한다. 공개 스키마와 데이터는 Data DB에서 일관되게 조회한다. |

### 3.16 프로젝트 전체 삭제

Data 측 삭제 범위에는 API 키, 공개·비공개 스키마 정의와 필드, 데이터 및 공개 상태가 포함된다. Control은 후보·커넥터·작업을 별도로 정리한다. 지연 작업과 공유 객체 정리의 기존 조건도 유지한다.

- 설명서: 16절 「프로젝트 전체 삭제」·그림 16.
- 메시지 명세의 상황별 전송 표: 15.16.

[확정] 사용자 API는 기존 스펙을 따르며 데이터 정리 메시지는 16.4와 20.4를 따른다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control / HTTP DELETE /control/v1/projects/{p} | confirm=true, 소유권 | 12.2, 15.16 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 프로젝트 삭제 작업 생성. 계정 삭제의 자식 작업으로도 재사용 |
| 2 | Control → 웹 / HTTP 202 | job_id | 12.2, 15.16 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 삭제 진행 조회. 이 응답을 데이터 삭제 완료로 표시하지 않음 |
| 3 | Control 내부 / 실행·설정 변경 차단 | project_id, 삭제 작업 | 11 — 내부 처리; 전송 JSON 없음 | 새 분석·수집·적재·변경 적용 발행 차단. 기존 실행의 늦은 결과도 적용 대상에서 제외 |
| 4 | Control → Ingestion / SQS project-data.delete | project_id | 16.4 — 메시지 형식 | 활성 데이터·비활성 준비 데이터·관련 데이터 메타데이터 전체 정리 |
| 5a | Ingestion → Control / SQS project-data.deleted | status=SUCCEEDED, project_id | 16.4 — 메시지 형식 | Control이 프로젝트 설정·출처 연결·분석 후보 정리 조정. API 키는 Data DB 삭제 범위에 포함 |
| 5b | Ingestion → Control / SQS project-data.deleted | status=FAILED, error | 16.4, 1.4 — 메시지 형식 | 삭제 완료로 표시하지 않음. 기술 재처리·최종 실패 안내. 대상 프로젝트는 삭제 중 상태 유지 |
| 6 | Control → Redis·S3·Scheduler / 캐시 무효화·객체/일정 정리 | 프로젝트 전용 객체·공유 결과·큐 재처리 기간 | 1.3, 15.16; S3 저장 명세 3.4·4.4~4.5 | 다른 프로젝트는 유지하고 공유 manifest의 삭제 대상 참조를 교체. 기존 결과·대기/DLQ 메시지는 재처리 종료 후 정리 |
| 7 | 웹 ↔ Control / HTTP 작업 조회 | 삭제 작업 job_id | 12.2, 15.16 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 전용 객체·공유 결과·대기/DLQ 정리를 포함해 전체 필수 정리 후 완료. 큐 보존 기간에 따라 지연 가능 |

### 3.17 회원 탈퇴와 소유 프로젝트 정리

- 설명서: 17절 「회원 탈퇴와 소유 프로젝트 정리」·그림 17.
- 메시지 명세의 상황별 전송 표: 15.17.

[확정] Auth↔Control 내부 HTTP 경로는 메시지 명세 16.5와 20.4를 따른다. 일반 로그아웃은 refresh 세션을 폐기하고 이미 발급된 access token은 짧은 잔여 유효시간을 허용한다. 탈퇴 진행 계정은 유효한 기발급 access token이 있어도 서버에서 접근을 차단한다. 계정·키 원본 상태를 확인할 수 없으면 접근을 거절한다. token 유효시간은 운영 설정이다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Auth / HTTP DELETE /auth/v1/me | 탈퇴 확인·인증 | 12.2, 15.17 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 계정 탈퇴 진행 상태 기록·refresh 세션 폐기. 로그인/갱신 차단 |
| 2 | Auth → Control / 내부 HTTP 계정 정리 접수 | owner_id | 16.5 — 확정 HTTP 형식 | Control이 해당 소유자의 프로젝트·정리 상위 작업 생성 |
| 3 | Control → Auth / HTTP 202 | job_id, 정리 상태 | 16.5 — 확정 HTTP 형식 | Auth는 소유자별 접수 상태를 기록. 응답 누락은 같은 소유자의 기존 작업으로 재접수 |
| 3a | Auth → 웹 / HTTP 202 탈퇴 접수 응답 | status=ACCEPTED, job_id | 12.2, 15.17 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 정리 완료를 기다리지 않고 사용자 세션 정리. 탈퇴 진행 계정은 기존 JWT가 있어도 서버 접근 차단; 원본 계정 상태 확인 불가 시 503으로 거절 |
| 4a | Control → Ingestion / SQS project-data.delete | 소유 프로젝트마다 project_id와 개별 job_id | 16.4 — 메시지 형식 | 프로젝트별 데이터 전체 삭제 실행 |
| 4b | Ingestion → Control / SQS project-data.deleted | 원래 job_id, project_id, status, error | 16.4, 1.4 — 메시지 형식 | 15.16 정리 흐름을 소유 프로젝트마다 수행. 다른 소유자의 공유 데이터 유지 |
| 5a | Control → Auth / 내부 HTTP 정리 완료 통지 | job_id, owner_id, status=SUCCEEDED | 16.5 — 확정 HTTP 형식 | Auth가 계정 개인정보 최종 삭제. 식별 가능한 최소 작업 원장은 재처리 가능 기간에 유지 |
| 5b | Control → Auth / 내부 HTTP 정리 실패 통지 | status=FAILED, error | 16.5 — 확정 HTTP 형식 | 삭제 작업을 끝났다고 처리하지 않음. 기존 작업 재처리·실패 원인 유지 |
| 6 | Auth → Control / HTTP 확인 응답 | 완료 통지 수신 결과 | 16.5 — 확정 HTTP 형식 | 응답 유실이면 Control이 같은 결과를 재통지. 계정이 이미 삭제된 성공도 멱등 수신 |

### 3.18 프로젝트 설정과 키 및 알림 운영

웹의 현재 스키마 조회는 Control이 Data 측 공개 스키마를 조회해 반환한다. 후보 조회는 Control 분석 상태와 S3 샘플을 사용하며 공개 스키마 조회와 구분한다.

- 설명서: 18절 「프로젝트 설정과 키 및 알림 운영」·그림 18.
- 메시지 명세의 상황별 전송 표: 15.18.

[기존] 프로젝트 설정·API 키·알림 운영의 통신이다. 미정 외부 연동은 명시적으로 표시한다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 ↔ Control / HTTP GET/PATCH /control/v1/projects/{p} | name, email_enabled | 12.2, 15.18 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 프로젝트 설정 조회·변경. 목적은 조회만 가능. 변경만으로 수집 SQS를 발행하지 않음 |
| 2 | 웹 ↔ Control / HTTP 프로젝트 목록·출처·스키마·분석·작업 조회 | 소유자·프로젝트 범위 | 12.2, 15.18 — 상황만 명시; 이 MD에 전체 HTTP 형식 없음 | 각 화면에 필요한 상태·샘플·필드 제공. HTTP 요청마다 별도 SQS 생성 없음 |
| 3 | 웹 ↔ Data / HTTP GET/POST /api/projects/{p}/keys | access token, 키 이름 → key_id·최초 1회 secret | 15.18, 18.5 | Control의 소유권·상태 확인 후 Data DB에 해시만 저장 |
| 4 | 웹 → Data / HTTP DELETE /api/projects/{p}/keys/{k} | access token, 소유 프로젝트 키 | 15.18, 18.5 | Control 소유권·상태 확인 후 Data DB 폐기 완료 시 응답 |
| 5 | Data → Data DB / 키 원본 조회 | project_id, key_id, 검증값 | 15.18, 18.5 | 외부 요청의 해시·프로젝트·폐기 상태 확인. 기존에 인증을 마친 요청의 취소는 보장하지 않음 |
| 6 | Data → Data DB / 공개 스키마·데이터 읽기 | project_id, schema_version, dataset_revision | 15.18, 20.4 | 동일 DB 읽기 스냅샷; MVP 공개 스키마 캐시 없음 |
| 7 | Control → 이메일 공급자 / 이메일 발송 API [공급자 미정] | 수신자·프로젝트·문제 출처·안내 종류·중복 방지 기준 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | email_enabled일 때 발송. 0건 즉시, 복구 가능한 다른 오류는 복구 흐름 후 사용자 조치 필요 시 발송 |
| 8 | 이메일 공급자 → Control/Auth / 발송 API 응답 | 접수 성공·일시 오류·최종 실패 | 12.2, 15.18 — 상황만 명시; 공급자 전송 형식 미정 | 메일 실패로 성공한 데이터 적재를 롤백하지 않음. 메일 자체의 재시도·중복 제한 필요 |
| 9 | Control → Scheduler / AWS 일정 생성·변경·중지 API | 활성 공유 출처·12시간 분산 슬롯 | 12.2, 15.18 — AWS API 호출; 별도 업무 메시지 없음 | 최초 수집 요청과 별도로 미래 슬롯에 일정 등록. 수동 실행은 기존 정기 시각을 변경하지 않음. 상세 15.20 |
| 10 | Scheduler → Control / collection.schedule.triggered | 이미 해제된 출처 또는 중복 예정 시각 | 7.1 — 메시지 형식 | 작업 원장·활성 연결 확인 후 무시. 비활성 신호 때문에 출처를 재생성하지 않음 |

### 3.19 중복 전달과 작업 중단의 복구

- 설명서: 19절 「중복 전달과 작업 중단의 복구」·그림 19.
- 메시지 명세의 상황별 전송 표: 15.19.

[기존 방향 + 구현 보완] 모든 비동기 흐름에 적용한다. 기존 요청·결과를 재사용하며, 그림의 처리 서비스는 Analysis·Crawler 또는 Ingestion이다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | SQS → Analysis/Crawler / 기존 command | 동일 job_id·입력 | 3.1, 11 | Control에 새 attempt_id 선점 요청 |
| 2 | Analysis/Crawler ↔ Control / 내부 HTTP | job_id·attempt_id·lease | 11.1 | 유효 시도만 실행. BUSY는 visibility 연장 |
| 3 | Analysis/Crawler → S3 / 시도별 결과 | event_id·payload·불변 참조 | 11, S3 1.2 | 시도별 result.json 저장은 최종 채택이 아님 |
| 4 | Analysis/Crawler → Control / completed | job_id·attempt_id·event_id | 3.2, 6.2, 7.3 | 발행 성공 후 command 삭제; 동일 시도 재발행 시 같은 이벤트 |
| 5 | Control → Control DB / 트랜잭션 | 현재 lease·attempt·revision | 11 | 유효 시 결과·참조·수신 기록·후속 Outbox를 함께 확정. 이전 시도는 무시 |
| 6 | Control Outbox → SQS / 후속 command | 저장된 요청 | 3.1, 11 | 대상 서비스가 중복 처리 방지 |
| 7 | Control 내부 / 주기 점검 | 미완료 작업·lease·기한 | 11.1 | lease 만료 시 시작 기한 내 동일 command 재발행. 기한 초과는 종료 |
| 8 | SQS → Control / DLQ | job_id·원장 상태 | 11 | 다른 유효 실행이 있으면 종료하지 않음 |
| 9 | Ingestion → DB·Outbox / 트랜잭션 | receipt·데이터·결과 이벤트 | 11 | 같은 적재를 다시 저장하지 않음 |
| 10 | 웹 → Control / 수정·승인 | expected_revision / candidate_revision | 12.2, 15.3 | 조건부 갱신으로 충돌 방지 |
| 11 | Ingestion → DB / 삭제·적재·적용 | 프로젝트 상태·버전·receipt | 11 | 지연 적재와 중복 반영 차단 |
### 3.20 공유 출처의 일정 등록과 실행

- 설명서: 20절 「공유 출처의 일정 등록과 실행」·그림 20.
- 메시지 명세의 상황별 전송 표: 15.20.

[기존 정책 + 구체화] Control의 AWS 일정 관리와 정기 신호를 구분한다. 설정·Input 형식은 메시지 명세 7.1이다.

| 순서 | 보내는 쪽 → 받는 쪽 / 통신 | 전달 내용·조건 | 형식 참조 번호 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Control 내부 / 일정 준비 | 승인된 공유 source_id와 활성 연결 | 7.1, 15.20 | 기존 일정 재사용 여부와 미래 실행 슬롯 결정 |
| 2a | Control 내부 / 기존 공유 출처 연결 | 이미 일정이 있는 source_id | 7.1, 15.20 | 일정 재사용. 새 프로젝트 최초 수집만 15.4로 진행 |
| 2b | Control → Scheduler / CreateSchedule | 고정 일정 이름, rate(12 hours), StartDate, SQS ARN, RoleArn, Input | 7.1 — AWS 설정·Input 예시 | 최초 수집 요청과 별도로 정기 일정 등록 |
| 3a | Scheduler → Control / AWS API 응답 | 생성된 ScheduleArn | 7.1, 15.20 | 등록 상태 기록 |
| 3b | Scheduler → Control / AWS 오류·응답 누락 | 일정 이름·오류 | 7.1, 15.20 | GetSchedule로 실제 설정을 대조해 재처리 |
| 4 | Scheduler 내부 / 예정 시각 도래 | 저장된 Input의 scheduled-time 문맥 키 | 7.1 — 템플릿과 실제 수신 메시지 비교 | source_id는 고정, scheduled_at은 해당 예정 시각으로 치환 |
| 5 | Scheduler → 정기 요청 SQS / SendMessage | collection.schedule.triggered | 7.1 — 정기 신호 형식 | SQS에 저장. 전송 실패는 Scheduler 재시도 정책 적용 |
| 6 | SQS → Control / 정기 신호 수신 | source_id, scheduled_at | 7.1, 15.5 | 활성 연결·중복·진행 중 작업 확인 후 최신값 조회·Crawler 요청 |
| 7a | Control 내부 / 커넥터 변경·수동 요청 | 일정 자체는 변경할 필요 없음 | 7.1, 15.6, 15.20 | 실행 시 최신 설정 조회. 수동 요청은 별도 즉시 실행 |
| 7b | Control → Scheduler / GetSchedule·UpdateSchedule | 변경된 주기·시각과 유지할 전체 설정 | 7.1 — 설정 갱신 규칙 | 일부 필드 생략으로 기존 설정이 초기화되지 않게 갱신 |
| 7c | Control → Scheduler / DISABLED 또는 DeleteSchedule | 마지막 활성 연결이 해제된 출처 | 7.1, 15.20 | 정기 일정 정리. 다른 프로젝트가 사용하면 유지 |
| 8 | SQS → Control / 지연 신호 | 이미 비활성 출처의 정기 신호 | 7.1, 15.20 | 신호를 무시하고 출처를 다시 만들지 않음 |

## 4 형식이 아직 없는 통신을 읽을 때

| 통신 | 기준 파일의 상황 번호 | 현재 상태 |
|---|---|---|
| 사용자 프로젝트 생성·후보 수정·승인·상태 조회 | 12.2, 15.1, 15.3, 15.18 | 경로·주요 필드는 있으나 메시지 MD 안의 전체 HTTP JSON 명세는 아님 |
| 회원가입·인증번호·로그인·갱신·초기화 | 15.14 | 기존 사용자 API 계약을 따라 구현. 공급자 이메일 body와 일부 인증 정책은 미정 |
| LLM 호출과 분석 중간 객체 | 6.1, 6.2, 15.1 | 업무 후보 데이터 구조를 참고하되 모델 공급자 API·중간 객체 상세는 별도 |
| API 키 발급·폐기·검증 | 15.18, 18.5 | Data DB 원본 확인; 원본 확인 실패 시 거절 |
| 새 버전 적용 후 Data 조회 | 10.1, 15.18, 20.4 | Data DB 동일 스냅샷에서 읽으며 MVP의 공개 스키마 캐시는 없음 |
| 사용자 문제 이메일·인증 이메일 | 12.2, 15.14, 15.18 | 발송 상황·대상은 명시. 공급자 API와 전송 재처리는 추가 확정 필요 |
| DLQ 실패 사실의 Control 전달 | 11, 15.19, 18.4 | Control이 DLQ 소비·기한 점검을 담당 |

위 항목에 업무상 전송이 필요하다는 사실과 구체 JSON 형식이 이미 확정되었다는 사실을 구분한다. 현재 파일은 흐름과 형식 위치를 연결하는 문서이며 새 큐·메시지 종류·API를 추가로 확정하지 않는다.

## 5. 확정한 연결 세부

이 절은 3.1~3.20의 순서에 적용하며 실제 payload·내부 HTTP 형식은 메시지 명세 18절이다. 승인 변경의 BEGIN_CHANGE·END_CHANGE 형식과 순서는 메시지 명세 10.5를 따른다.

1. 가입/초기화: Auth가 목적별 6자리 번호를 Redis에 보관한다. 가입의 202는 이메일 공급자 접수 확인을 뜻하고, 초기화의 202는 계정 존재를 숨기는 요청 접수로 실제 발송을 보장하지 않는다. 번호 확인은 10분 일회용 증명을 발급하며 가입·비밀번호 변경은 증명을 별도 제출·소비한다. 발송 실패 및 DB 실패는 재인증으로 복구한다. 웹 재시작 시 Auth csrf 조회 후 refresh한다.
2. 프로젝트 생성: Idempotency-Key로 Control에 생성 → sources 조회로 project_source_id 확보 → analyses 시작. 프로젝트 목록/상세에서 current_analysis_id·current_job_id로 재진입한다. 후보 PATCH는 부분 변경, 승인 전 취소만 허용한다.
3. 최초/리비전 승인: Control → Ingestion dataset.apply BEGIN_CHANGE → dataset.applied 확인 → schema.prepare → Data DB 비공개 스키마 → schema.prepared → 출처별 FULL 수집. 최초 v1은 정상 출처별 공개, 모든 출처 실패면 비공개다. 리비전은 모든 출처 준비 성공 후 Control이 프로젝트 쓰기를 보류·종료 확인 → Data 전환 → Control 커넥터 활성화 → 재개한다. 성공·최종 실패 시 END_CHANGE 결과와 실제 커밋을 확인한 뒤 다음 변경을 허용한다.
4. 반복 수집: Control → Ingestion project-schemas:query와 latest-records:query(targets의 project_id·project_source_id) → Crawler. 일반 수집의 정상 레코드는 적재하며 실패 레코드는 제외한다. 신규 출처 추가 실패 및 리비전은 기존 비공개·보류 규칙을 따른다.
5. 상태/조회: Control은 실제 확보한 출처별 시각·건수·오류를 작업 API로 반환하고 미확인 값은 null이다. Data의 레코드 source_id는 Control project-sources:resolve로 확인한다. 승인·적용 중인 출처의 이미 공개된 레코드도 매핑하며 승인 전 후보는 공개하지 않는다. API 키 마지막 사용 시각은 Data가 Redis에 기록하고 Data 키 목록에 제공한다.
6. 중복·최종 실패: Analysis·Crawler는 Control의 job_id·attempt_id lease를 선점·갱신하고 시도별 S3 결과를 남긴다. Control이 유효 시도·revision을 원자적으로 검사해 채택한다. Ingestion은 receipt/Outbox를 트랜잭션으로 관리한다. Control이 DLQ와 작업 기한을 점검해 사용자에게 실패 상태를 제공하고 S3 정리 주기 작업도 담당한다. 재처리 가능 기간 동안 결과·최소 작업 원장을 보존한다.

### 승인 변경 시작과 종료

Control은 프로젝트마다 승인 변경을 하나만 진행한다. 기존 dataset.apply로 BEGIN_CHANGE를 보내고 Data DB의 OPEN 기록과 dataset.applied 성공을 확인한 후 스키마 준비·수집·적재를 발행한다. 성공 또는 최종 실패 시 END_CHANGE로 닫고 Data의 실제 커밋 결과를 반영한 뒤 다음 변경을 허용한다. OPEN 여부·대상·버전과 출처 삭제 차단은 Data DB 쓰기 트랜잭션에서 확인한다. END 결과가 불확실하면 재전달하고 새 변경은 보류한다. 초기 일부 성공·추가 일부 성공은 유지하고 리비전 실패는 기존 버전을 유지한다. 상세는 [메시지 명세 10.5](service-message-contracts.md#105-승인-변경의-시작과-종료-기존-datasetapply-확장)를 따른다.

## 6. 공개 전 복구·빈 출처·상태 조회 세부

1. 공개 전 최초/추가 출처의 자동 복구: 실패 → Control → Analysis 검증 → OPEN 작업 pending revision의 Data 조건부 갱신 성공 → Control 실행 규칙 활성화 → 새 자식 FULL 수집 → 승인된 mode의 적재와 첫 성공 공개·가드 생성. 이미 공개된 출처는 기존 SET_CONNECTOR_REVISION 결과 확인 후 재수집한다. 형식 19.1.
2. 공개 전 최초 출처 삭제: 사용자의 삭제 후보 승인 전 기존 END 확인 → SOURCE_CHANGE BEGIN(expected=null, target=1) → UPDATE_SOURCES 삭제·차단 → END 확인. 남은 대상은 새 INITIAL, 없으면 프로젝트·승인 v1 정의만 유지한다. 공개된 마지막 출처도 삭제하고 활성 스키마·빈 API는 유지한다. 형식 19.3.
3. 취소된 v2를 다시 준비: 기존 END가 CLOSED이고 REPLACE 미커밋인지 확인 → 새 BEGIN → schema.prepare가 이전 미공개 v2와 준비 레코드를 정리하고 새 change_job_id로 동일 승인 정의를 준비 → 모든 출처 PREPARE·REPLACE. 형식 19.2.
4. 표시 정보 변경: 승인 → dataset.apply UPDATE_SCHEMA_METADATA → Data DB의 예상 버전·metadata revision 검증 및 표시 정보·receipt·Outbox 원자 갱신 → dataset.applied → Control 완료. 형식 19.4.
5. 탈퇴 차단: Control·Data의 JWT 요청 → Auth 내부 계정 상태 원본 조회 → ACTIVE만 허용; 키 요청은 Data→Control→Auth 소유자 확인. 장애 시 503. 형식 19.5.
6. source_id 조건: Data→Control 프로젝트별 출처 매핑·revision 조회 → Data DB project_source_id로 조건 적용·source_id 기준 정렬 → cursor에 mapping_revision 결합. 매핑 변경 409, 확인 실패 503. 형식 19.6.
7. 동일 job의 REPLACE 요청은 원래 요청 envelope·job_id·payload로 재전달한다. 원래 event_id는 결과 이벤트 재발행 시에만 유지한다. 최초 분석 생성은 프로젝트 행 잠금과 JOB·Outbox 트랜잭션으로 중복을 막고 도메인 요청은 Redis 임대·간격으로 조정한다. 상세 19.7.


## 7. 재점검 실행 경계

후보 GET은 이동 규칙·제외 목록·검증/샘플 revision을 반환하고 navigation 수정은 출처별 전체 교체다. 최초 필드 표시 정보는 Control이 후보 채택 때 초기화한다. 공개 전 복구는 준비 정의와 S3 승인 샘플, 공개 후는 공개 스키마와 DB 최신값을 전달하며 CHANGE/ACTIVE는 각 대상 규칙에 맞게 선택한다.

공유 collection을 기다리는 수동 요청은 project_source별 ingestion 결과가 종료될 때까지 진행 중이다. 늦게 연결하면 Control 원장 조회와 같은 적재 요청 재전달을 통한 기존 receipt 결과 재발행으로 확인하며 성공/부분 실패/실패를 요청 프로젝트 범위에서 집계한다. 공유 manifest는 전체 collection 대상을 검증하고 Ingestion은 자신의 참여 항목만 처리한다. 프로젝트 삭제로 정리한 manifest의 실제 경로와 참조 전환은 S3 명세 8절이다.

상세 브라우저 이동은 필드별 목록 항목 기준 open.selector와 상세 DOM의 값 추출 selector를 구분하며 CURRENT_TAB/NEW_TAB/MODAL과 같은 대상의 대기·복귀를 사용한다. Data 공개 스키마·레코드는 동일 DB 스냅샷에서 읽고 API 키 Redis 제한 장애는 503이다. 전체 payload 및 내부 HTTP 응답은 메시지 명세 20절이다.


## 7. 추가 확정 실행 경계

Analysis·Crawler·Ingestion은 같은 공통 파서 모듈을 사용하고 생성 Java 코드를 별도 격리 없이 각 서비스 JVM에서 실행한다. 코드가 서비스 권한과 자원을 공유하므로 무한 반복·메모리 고갈·파일 및 네트워크 접근이 해당 서비스에 영향을 줄 수 있으며 별도 강제 격리는 보장하지 않는다. 파서 사용자 편집 UI는 없다.

목록 이동은 URL만이 아닌 전후 항목 구간·진행 상태를 확인한다. MVP에서 가상화 DOM으로 이미 사라진 항목 복원과 완전한 중복 판별은 보장하지 않으며 누락을 허용한다. 확인된 스냅샷에서 상세를 다른 항목에 임의 매칭하지 않는다. 변경 없음 정상 종료에는 이메일을 보내지 않는다.

문제 이메일 발송 실패는 수집·적재를 되돌리지 않고 제한 재시도한다. 같은 미해결 문제의 발송 상태를 보관한다. 공급자 응답 유실 시 중복 가능성이 있으며 지원하는 경우 동일 발송 식별자를 재사용한다. receipt·이벤트 수신 기록·Outbox·S3 결과는 관련 SQS·DLQ 재처리 가능 기간 이상 유지한다. 구체 수치는 운영 설정이다.

## 8. 상태·후보 경계 보완

- 분석 진행 화면은 전체 진행 상태만 표시한다. 분석 완료 이벤트 `SUCCEEDED`·`PARTIAL`은 후보가 있으면 분석 GET의 `READY`로 매핑하고 출처별 결과를 함께 반환한다. 실패·취소는 HTTP `FAILED`·`CANCELLED`다. 개별 출처의 중간 진행 이벤트는 추가하지 않는다.
- 같은 분석·출처의 진행 중 retry는 기존 job_id를 반환한다. 결과는 요청 revision이 현 후보와 같을 때만 병합하고 후보 revision 증가 및 필요한 재검증을 원자적으로 기록한다. stale 결과는 버리고 최신 후보로 다시 요청한다.
- 기존 후보 출처 추가는 `POST /projects/{p}/sources`에 list_url·analysis_id·expected_revision을 전달한다. Control은 출처 연결·revision 증가·ADD_SOURCE job과 Outbox를 함께 기록하고 201에 project_source_id·analysis_id·candidate_revision·job_id를 반환한다. 다른 변경이 선행되면 409다. 메시지 명세 20.9를 따른다.
- 초기화 `202`는 계정 유무와 개별 발송 결과를 구별하지 않는다. 가입·초기화 증명의 소비 이후 결과가 불확실하면 인증부터 다시 시작하고 가입은 로그인도 안내한다. 탈퇴 접수 202는 Control 접수 뒤 바로 반환하며 비동기 정리는 계속된다.
