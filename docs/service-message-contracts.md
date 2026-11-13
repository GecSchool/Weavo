# 서비스 작업 메시지와 데이터 형식

- 작성일: 2026-11-06
- 최근 수정일: 2026-11-07
- 상태: 구현 계약 초안. 기존 합의와 이번 검토의 보완 제안을 구분한다.
- 목적: 기능·예외별 모든 전송 상황을 15절에서 확인하고, 각 단계의 공통 메시지 형식·payload를 1~10절과 16절에서 찾아 구현할 수 있도록 한다. S3 객체별 본문·경로·수명은 [S3 저장 명세](s3-storage-contract.md)를 따른다.
- 흐름 기준 문서: [시스템 흐름 설명서](design/시스템_흐름_설명서.docx). 아래에서 “설명서 N절·그림 N”은 이 문서의 번호다.
- 전달 순서 기준: service-message-flows.md 3.1~3.20. 이 파일의 15.1~15.20과 설명서의 1~20절이 같은 흐름에 대응한다.
- 제품 정책 기준: spec-decision-log.md. 설계 제안서는 제품·구성의 보조 자료로 참고한다.
- 기존 합의: Control 중심 조정, SQS 표준 큐, Outbox·수신 멱등 처리, 드문 중복 실행의 비용 허용과 결과의 중복 반영 방지.
- 구현 계약: UUIDv4, 구체 필드명·열거값·작업 관계 및 아래 세부 실행·적용 계약. [제안]·[미정] 표시는 유지하며, 그림이 있다는 이유로 미정 항목을 확정하지 않는다.
- 참조 구분: 설명서의 절·그림 번호는 실행 흐름, 이 MD의 1~12·16절은 형식·처리 규칙, 15절은 상황별 전송 표다. 전체 대응표는 17.1에 있다.
- 파서 형식 합의: FieldConnector.parser에는 Java 함수 본문인 code만 넣는다. 별도 파서 버전·JAR·소스 경로·해시는 메시지에 넣지 않는다. Java 21 공통 모듈을 사용하며 생성 코드는 별도 격리 없이 각 서비스 JVM에서 실행한다.
- 추가 단순화 합의: 준비 결과는 PREPARE 작업의 job_id로 참조하고, 출처 삭제는 dataset.apply로 통합한다. 운영 통계는 선택 항목으로 둔다.

## 1 데이터 형식

### 1.1 식별자와 버전

| 항목 | JSON 형식 | 생성과 의미 |
|---|---|---|
| project_id, owner_id, project_source_id, source_id, field_id | UUIDv4 문자열 | 소문자 하이픈 포함 36자. 해당 도메인을 소유한 서비스가 발급한다. 의미 있는 접두사를 ID에 붙이지 않는다. |
| job_id | UUIDv4 문자열 | Control이 실제 분석·수집·적재·적용 작업별로 생성한다. 삭제만 있는 변경도 적용 작업으로 생성한다. 재전달에서는 유지한다. |
| parent_job_id | UUIDv4 문자열 또는 null | 상위 작업. 프로젝트 전체 수집 → 출처별 수집 → 프로젝트별 적재를 연결한다. |
| change_job_id | UUIDv4 문자열 또는 null | 승인된 변경의 상위 job_id를 다른 단계에서 참조하는 이름이다. 별도 종류의 ID를 발급하지 않는다. |
| analysis_id | UUIDv4 문자열 | Control이 분석 과정별로 생성한다. 후보 수정과 그에 대한 재검증 작업은 같은 분석 안에 속할 수 있다. |
| event_id | UUIDv4 문자열 | 결과를 만든 서비스가 생성하고 확정 결과에 보관한다. 같은 결과 재발행 시 유지한다. |
| prepared_job_id | UUIDv4 문자열 | Control이 발급한 PREPARE 작업의 job_id를 참조하는 이름이다. 별도의 준비 결과 ID는 생성하지 않는다. |
| revision, connector_revision, navigation_revision | 양의 JSON 정수 | 각 설정 내에서 1부터 증가. 후보 revision, 커넥터 묶음 revision, 공유 이동 revision을 서로 혼용하지 않는다. |
| schema_version | 양의 JSON 정수 | 프로젝트의 승인 스키마·Data API 버전. 후보 revision과 다르다. |
| dataset_revision | 0 이상의 JSON 정수 | Ingestion이 데이터의 공개 상태를 갱신할 때 증가. 스키마 구조가 같아도 바뀔 수 있다. |
| record_id | JSON 정수 | Data DB 전역 시퀀스; 프로젝트별 연속/1 시작 보장 없음. 프로젝트 내 쓰기를 직렬화한다. |

버전·건수·record_id를 프론트에서 JavaScript Number로 다룰 때는 안전 정수 범위를 넘지 않게 계약을 맞춰야 한다. 이 문서는 기존 외부 API의 record_id를 임의로 문자열로 바꾸지 않는다. DB의 큰 정수 범위 전체를 외부에 지원할지는 별도 확정한다.

동일 요청의 SQS 재전달은 같은 job_id다. 일시 오류로 같은 입력을 기술적으로 다시 처리할 때도 같은 job_id와 payload를 유지한다. 사용자 재요청, 복구된 규칙 적용, 변경된 비교 기준을 사용한 실행은 새로운 job_id다. 같은 job_id의 payload를 수정해서 재발행하지 않는다.

### 1.2 일반 값

| 항목 | 형식·규칙 |
|---|---|
| 메시지 인코딩 | UTF-8 JSON. 구현 언어의 클래스 이름을 메시지의 직렬화 타입 정보로 사용하지 않는다. |
| 시간 | RFC 3339 UTC 문자열. 예: 2026-11-06T00:00:00Z. 비교·정렬은 수신 시각 대신 작업 관계와 버전을 기준으로 한다. |
| 실행 시간 | 양의 정수 초. max_duration_seconds는 실행 시작 후 예산이며 expires_at과 다르다. |
| URL | 절대 HTTP(S) URL. 이동 시 base URL에 상대 주소를 해석할 수 있지만 메시지의 시작 URL은 절대 주소다. |
| 사용자 필드값 | string, number, boolean 또는 null. date·datetime·url도 JSON에서는 문자열이다. 배열·중첩 객체는 사용자 스키마 값으로 지원하지 않는다. |
| 날짜 | YYYY-MM-DD 문자열. 시간대 없는 날짜로 취급한다. |
| 일시 | 오프셋을 해석한 RFC 3339 문자열. 비교 전에 동일한 UTC 표현으로 정규화한다. |
| 숫자 | JSON number. NaN·Infinity는 금지한다. 서비스별 소수 정밀도와 비교 방식은 동일해야 하며 세부 지원 범위는 확인 필요다. |
| 필드값 객체의 키 | field_id. api_name은 외부 API의 필드 이름으로 별도 관리한다. |
| null과 누락 | null은 실제 빈 값 또는 명시적인 해당 없음이다. 필드 처리 실패는 field_states·error로 별도 표시한다. 누락된 필수 메시지 항목을 null의 정상 값으로 간주하지 않는다. |
| 선택 필드 | 공통 스키마에서 nullable로 명시한 것은 null을 사용한다. purpose·mode별 선택 항목은 아래 조건표를 따른다. |
| enum | 대문자 영문 문자열. 메시지 종류 이름은 소문자 점 구분 표기를 사용한다. |

latest_records는 저장 순서상 최신 최대 3개이며, 각 항목에는 사용자 필드값 전체가 들어간다. record_id·수집 시각·외부 게시물 ID는 내용 동일성 비교에서 제외한다. 1~2개면 전부, 0개면 FULL 수집이다. 비교 오류를 실제 null 또는 일치로 처리하지 않는다. 같은 값이 반복될 때 개별 기준 항목의 충족을 어떻게 셀지는 비교 모듈의 세부 계약으로 남긴다.

### 1.3 S3 참조

```json
{
  "bucket": "capstone-artifacts",
  "key": "collections/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/attempts/43f5b110-f193-4157-91f2-1a16b8fb70d6/manifest.json"
}
```

허용된 버킷·prefix만 읽는다. `snapshot_ref`는 채택된 실행 시도의 불변 `attempts/{attempt_id}/manifest.json` 또는 같은 시도 아래의 정리용 `rewrites/{cleanup_job_id}/manifest.json`을 가리키며 `sample_ref`는 해당 분석 작업·revision·출처·시도의 불변 샘플 객체를 가리킨다. Control은 실제 참조를 보관하고 revision만으로 객체 위치를 추측하지 않는다. 메시지에 presigned URL이나 AWS 자격증명을 넣지 않는다. 최신 포인터가 바뀌어도 기존 작업은 원래 객체를 읽는다. 중간 결과·검증 샘플·스냅샷의 본문과 정리 조건은 [S3 저장 명세](s3-storage-contract.md)를 따른다.

### 1.4 오류 형식

```json
{
  "code": "FIELD_EXTRACTION_FAILED",
  "stage": "TEMPORARY_EXTRACTION",
  "message": "상세 화면에서 본문 요소를 찾지 못했습니다.",
  "details": {
    "page_url": "https://school.example/notices/123",
    "selector": ".article-body",
    "matched_element_count": 0
  }
}
```

error.code는 정형 코드, message는 사람에게 표시할 요약, details는 관찰 사실이다. 필드 실패 원본이 확보된 Crawler·Ingestion 오류는 선택 배열 `failure_evidence`를 error 객체에 추가한다. 각 항목은 `project_source_id`, `field_id`, S3 `ref`와 `expires_at`을 담는다. 형식과 저장 순서는 S3 저장 명세 3.3절을 따른다. 원본이 없으면 생략하거나 빈 배열이며 원문은 메시지에 넣지 않는다. 같은 오류를 issue와 completed에 함께 넣을 때는 같은 참조를 사용한다. Control은 범위 검증 후 만료 전 조회·검증 정보 반영을 수행한다. 서비스가 관찰한 실패를 사이트 구조 변경으로 단정하지 않는다. 내부 예외·토큰·쿠키·접속 비밀값은 전달하지 않는다. retryable을 넣더라도 힌트일 뿐 Control의 재시도 정책을 대체하지 않는다.

예를 들어 파싱 실패 원본이 있을 때 error 객체는 다음과 같다. 참조 경로와 본문 예시는 S3 저장 명세 3.3절의 동일한 객체다.

```json
{
  "code": "PARSER_FAILED",
  "stage": "TEMPORARY_EXTRACTION",
  "message": "필드 변환에 실패했습니다.",
  "details": {},
  "failure_evidence": [{
    "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
    "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
    "ref": {"bucket": "capstone-artifacts", "key": "failures/CRAWLER/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/43f5b110-f193-4157-91f2-1a16b8fb70d6/95f7d7de-ec00-4fbe-b430-fa3a79763c18/ba4f9e1e-ae8e-4a3c-9261-dca457848148/0.json"},
    "expires_at": "2026-11-13T00:01:10Z"
  }]
}
```

## 2 흐름별 메시지 계약의 구체화

| 대조 지점 | 앞선 예시의 부족한 점 | 이 문서의 처리 |
|---|---|---|
| 설명서 1·2절 및 그림 1·2 | 공동 추천을 별도 Control 요청으로 쪼갤 수 있었음 | Analysis가 출처 분석·S3 중간 결과 조회·공동 추천을 이어서 수행한다. |
| 접근 방식 | collection_method 하나로 목록·상세를 모두 표현 | 목록 list_access와 각 field_connector.detail_navigation.access를 구분한다. |
| 탐색 종류 | NEXT_LINK 예시만 존재 | NEXT_LINK, URL_TEMPLATE, CLICK_NEXT, LOAD_MORE, SCROLL과 상세 LINK·CLICK·목록 복귀를 표현한다. |
| 상세 파싱 | 상세 URL만으로 목록과 상세 연결이 불명확 | manifest의 record_key·item_index·consumers로 수집 항목과 상세 객체를 연결한다. |
| 분석 입력 | 사용자 선택 그룹·제외 출처·부분 성공 출처 보존이 부족 | current_candidate의 fields 및 excluded_project_source_ids, 출처별 결과와 변경 대상 목록을 전달한다. |
| 복구 기준 | 최신 3건 또는 승인 샘플을 누가 전달할지 불명확 | 보완 제안으로 Control이 Ingestion에서 조회해 recovery_baselines에 넣는다. DB 0건일 때 승인 샘플 참조를 넣는다. |
| 정상 0건 | 새 데이터 0건과 정상 추출 0건 혼동 가능 | normal_extracted_record_count와 inserted_record_count를 분리하고 issue 코드를 구분한다. |
| 설명서 12절·그림 12 | 승인 전 삭제 영향 조회 누락 | 내부 HTTP 계약으로 삭제 대상 건수 조회를 명시한다. |
| 설명서 13절·그림 13 | 준비 완료와 실제 데이터 반영 구분 부족 | PREPARE와 dataset.apply를 분리한다. 삭제만 있는 변경도 dataset.apply로 처리하며 같은 조건 검사와 쓰기 차단을 적용한다. |
| 버전 전환 | Control DB와 데이터 DB를 하나의 트랜잭션처럼 보일 수 있음 | 데이터·공개 스키마 버전 포인터의 최종 반영은 Ingestion DB 트랜잭션, Control은 결과를 받아 운영 메타데이터를 동기화하는 안을 제안한다. |
| 수집·적재 파싱 | Crawler와 Ingestion의 언어가 다르면 값 비교가 달라질 수 있음 | 같은 parser.code와 정규화 계약을 두 실행 지점에서 사용한다. 실행 방법은 최종 확인 필요다. |
| 설명서 14·16~18절 및 해당 그림 | 회원 탈퇴·프로젝트 삭제·키 폐기 경로가 수집 메시지에 없음 | 12절에 별도 통신과 미정 경로를 남긴다. 수집 계약으로 전체 서비스 계약이 완성됐다고 간주하지 않는다. |

위의 버전 공개 방식은 기존 문서에 없던 구체화 제안이다. Control이 운영 상태를 저장하는 책임은 유지하되, 두 DB의 포인터를 동시에 원자적으로 바꿀 수 있다는 전제를 두지 않는다.

## 3 공통 envelope와 큐

### 3.1 작업 요청

```json
{
  "format_version": 1,
  "command_type": "collection.execute",
  "job_id": "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc",
  "parent_job_id": "6397f960-4993-42b7-889b-07d6d186b56c",
  "requested_at": "2026-11-06T00:00:00Z",
  "expires_at": "2026-11-06T00:10:00Z",
  "payload": {
    "source_id": "1c226765-48b1-4518-ad16-057e8998eb84"
  }
}
```

위 payload는 envelope 설명용 축약이다. 실제 수집 payload는 7절의 전체 형식이다. expires_at은 실행을 시작할 수 있는 마지막 시각이다. 실행 중 초과 여부는 max_duration_seconds로 판단한다. 지연된 완료 결과를 expires_at 초과만으로 버리지 않는다. 취소·작업 상태·revision을 함께 확인한다.

### 3.2 결과 이벤트

```json
{
  "format_version": 1,
  "event_type": "collection.completed",
  "event_id": "9f920ad3-bb73-4288-badb-06a452922c93",
  "job_id": "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc",
  "occurred_at": "2026-11-06T00:01:10Z",
  "payload": {
    "attempt_id": "43f5b110-f193-4157-91f2-1a16b8fb70d6",
    "source_id": "1c226765-48b1-4518-ad16-057e8998eb84"
  }
}
```

동일 시도 결과의 재발행은 envelope와 payload, event_id를 그대로 유지한다. Analysis·Crawler 완료 이벤트는 payload.attempt_id가 필수이고 공통 envelope는 바꾸지 않는다. Crawler의 job.issue.detected에도 payload.attempt_id가 필수다. Ingestion 결과는 기존 receipt 방식으로 처리한다. 결과의 상위 작업은 Control 원장에서 job_id로 확인한다. event_id 중복 확인만으로는 충분하지 않으며, 작업별 최종 결과 채택 여부와 예상한 자식 작업 집합도 함께 확인한다. 같은 작업의 성공과 실패가 경합하지 않도록 Analysis·Crawler의 최종 결과도 작업별로 하나만 확정한다.

### 3.3 큐 배치

| 논리 큐 | 송신 → 수신 | 메시지 |
|---|---|---|
| 정기 요청 큐 | Scheduler → Control | collection.schedule.triggered |
| Analysis 작업 큐 | Control → Analysis | analysis.execute |
| 우선 수집 큐 | Control → Crawler | 최초·수동 수집의 collection.execute |
| 정기 수집 큐 | Control → Crawler | 정기 collection.execute |
| Ingestion 작업 큐 | Control → Ingestion | schema.prepare, ingestion.execute, dataset.apply, project-data.delete |
| Control 결과 큐 | Analysis·Crawler·Ingestion → Control | 각 결과와 job.issue.detected |

리비전·복구 작업의 우선 큐 배치는 별도 운영 확인 항목이다. 동일 큐에 서로 다른 서비스 소비자를 연결하지 않는다. Control 결과 큐는 Control만 소비하고 message type에 따라 내부 handler로 분기한다. 한 큐에서 type별 Listener를 독립적으로 경쟁 소비시키지 않는다. 우선·정기 큐의 기존 3:1 정책은 별도 소비 조정 코드가 필요하며 @SqsListener를 두 개 붙이는 것만으로 보장되지 않는다.

## 4 파싱 코드

### 4.1 메시지 형식

파서는 FieldConnector의 parser 속성에 Java 함수 본문만 전달한다. 언어는 Java로 고정하고 클래스·메서드 형태와 런타임은 시스템 공통 설정으로 관리한다.

```json
{
  "parser": {
    "code": "return rawValues.get(0).replace(\"[공지]\", \"\").strip();"
  }
}
```

별도 가공이 필요 없으면 parser는 null이다. parser가 null이면 HTML 파서가 엔티티를 해석한 원문을 추가 공백 가공 없이 사용하고 공통 타입 검증을 수행한다.

시스템이 아래의 고정된 함수 틀에 code를 삽입한다. 클래스 선언·메서드 선언·import 문은 code에 포함하지 않는다. 추가 표준 클래스가 필요하면 함수 본문에서 정규화된 클래스 이름을 사용할 수 있다.

```java
String parse(java.util.List<String> rawValues) throws Exception {
    // parser.code를 이 위치에 삽입한다.
}
```

- 입력은 해당 필드의 selector에서 추출한 문자열 목록이다.
- 출력은 가공한 문자열 또는 null이다. 문자열은 이후 스키마에 맞춰 number·boolean·date 등으로 변환한다.
- selector 불일치 같은 추출 실패는 실행기가 별도 상태로 판정한다. code가 null을 반환했다고 앞선 추출 실패를 정상 값으로 바꾸지 않는다.
- 코드 실행 중 관찰된 예외는 실제 null과 구분한다. 같은 JVM에서 무한 반복을 안전하게 강제 종료한다고 보장하지 않는다. null 반환은 정상적인 파싱 결과일 수 있다.
- 같은 입력에는 같은 결과를 반환해야 한다. 시각·난수·외부 네트워크 결과에 의존하지 않는다.

### 4.2 생성·검증·전달

Analysis가 코드를 생성하고 샘플로 검증한다. 검증된 코드는 필드 커넥터의 설정에 그대로 보관하며, 수집 요청과 적재 요청에 같은 code를 포함한다. 후보 수정이나 코드 변경은 기존 connector_revision으로 관리한다. 별도의 파서 ID·파서 버전은 만들지 않는다.

language, runtime_profile, entry_class, source_ref, source_sha256, artifact_ref, artifact_sha256은 파서 payload에 넣지 않는다. 컴파일 결과 캐시가 필요하면 실행기가 내부에서 코드와 실행 환경을 기준으로 키를 계산한다. 캐시 키나 컴파일 파일 경로를 서비스 간 메시지에 노출할 필요는 없다.

사용자에게 Java 편집 화면을 새로 제공하는 것은 이번 변경에 포함하지 않는다. 현재 사용자 편집 범위는 스키마·CSS·탐색 설정이며, Java 코드는 Analysis가 생성·검증하는 방식이다.

### 4.3 공통 실행 규칙

Analysis·Crawler·Ingestion은 같은 함수 틀, Java 실행 환경, 승인 parser.code와 공통 타입 변환 규칙을 사용해야 한다. 공백 자동 가공은 하지 않는다. 세 서비스는 Java 21·Spring Boot와 공통 Java 파서 모듈을 사용하고 생성 코드는 별도 격리 없이 각 서비스 JVM에서 실행한다. 실행 환경이나 정규화 규칙이 다르면 수집 중 비교값과 최종 저장값이 달라질 수 있다.

생성 코드는 서비스 JVM의 권한과 자원을 공유한다. 외부 페이지에 영향을 받은 코드가 파일·네트워크에 접근하거나 무한 반복·메모리 고갈을 일으킬 수 있다. 사용자의 코드 편집을 제공하지 않더라도 이러한 영향은 남는다. 별도의 샌드박스나 강제 자원 제한을 MVP에서 보장하지 않는다.

파서 전용 서비스나 격리 작업자는 만들지 않는다. 컴파일과 캐시는 각 서비스의 공통 모듈 내부에서 처리하며 세 서비스의 파싱 결과 일치를 검증한다.

## 5 공유 데이터 구조

### 5.1 스키마와 필드 커넥터

상세 detail_navigation.access.method는 BROWSER만 허용하며 링크·클릭·복귀는 브라우저에서 실행한다. 목록 list_access는 검증 결과에 따라 HTTP 또는 BROWSER다. 사용자의 상세 URL 생성 템플릿 직접 입력은 MVP에서 지원하지 않는다.

공개 및 적용 준비 스키마의 원본은 Data DB에 있으며 Ingestion이 쓰고 Data가 읽는다. Control은 분석 후보와 커넥터를 보유하고, 일반 수집·복구·출처 추가에 필요한 공개 스키마는 Data 측 내부 조회로 확보한다. 최초·리비전의 비공개 승인 후보는 Control이 전달한다. 아래 schema가 메시지에 포함되는 것은 영구 원본을 Control DB에 복제한다는 뜻이 아니다.

```json
{
  "schema": {
    "fields": [
      {
        "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
        "api_name": "title",
        "display_name": "제목",
        "type": "string",
        "nullable": true
      }
    ]
  },
  "connector_revision": 2,
  "field_connectors": [
    {
      "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
      "location": "LIST",
      "selector": ".notice-title",
      "extract": {
        "type": "TEXT",
        "attribute": null
      },
      "parser": {
        "code": "return rawValues.get(0).replace(\"[공지]\", \"\").strip();"
      },
      "detail_navigation": null
    }
  ]
}
```

field connector는 해당 project_source_id와 field_id 조합에 속한다. connector_revision은 이번 프로젝트·출처의 필드 커넥터 묶음 버전이다. 내부에서 필드별 이력도 별도로 둘 수 있지만 이 계약에서는 실행할 묶음을 하나의 revision으로 고정한다. location은 LIST, DETAIL, ABSENT다. ABSENT인 필드는 selector·detail_navigation·parser를 null로 두고 정상 null을 생성한다. DETAIL은 필드별 detail_navigation(접근·이동·복귀)을 요구한다. selector는 LIST이면 item_selector로 찾은 항목 기준, DETAIL이면 연결된 상세 DOM 기준이다.

**DETAIL 필드 예시:** 아래의 `detail_navigation`은 해당 필드 커넥터 내부에 포함한다. `open.selector`는 목록 항목 기준으로 상세를 여는 링크 또는 버튼을 찾고, 별도의 필드 `selector`는 열린 상세 DOM에서 값을 추출한다. 화면은 이 두 값을 별도로 편집하며 변경한 후보를 검증한다. 대상·대기·복귀 설정은 분석이 생성한 해당 필드의 이동 규칙에 포함된다.

```json
{
  "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
  "location": "DETAIL",
  "detail_navigation": {
    "access": {"method": "BROWSER", "wait_selector": ".article-body"},
    "open": {"type": "CLICK", "selector": ".notice-title button", "target": "CURRENT_TAB"},
    "return": {"type": "HISTORY_BACK", "wait_selector": ".notice-list"}
  },
  "selector": ".article-body",
  "extract": {"type": "TEXT", "attribute": null},
  "parser": null
}
```

### 5.2 목록과 상세 이동

```json
{
  "list_url": "https://school.example/notices",
  "initial_actions": [],
  "list_access": {
    "method": "HTTP",
    "wait_selector": null
  },
  "item_selector": ".notice-list .notice",
  "pagination": {
    "type": "NEXT_LINK",
    "selector": ".pagination .next",
    "url_attribute": "href",
    "wait_selector": null
  }
}
```

pagination.type별 필수 항목:

| type | 추가 항목 | 조건 |
|---|---|---|
| NONE | 없음 | 단일 목록 |
| NEXT_LINK | selector, url_attribute | 다음 주소를 추출하고 base URL 기준 절대 주소로 해석 |
| URL_TEMPLATE | url_template, start, step | `{value}` 한 곳에 정수를 대입. 실제 시작 및 다음 목록 화면을 분석에서 검증 |
| CLICK_NEXT | selector, wait_selector | 다음 페이지 클릭 후 검증된 화면 변화 대기 |
| LOAD_MORE | selector, wait_selector | 클릭으로 추가된 항목을 누적 목록에서 구분 |
| SCROLL | scroll_container_selector, wait_selector | null container는 문서 스크롤. 추출 항목 5개 묶음으로 비교 |

`URL_TEMPLATE` 예시:

```json
{"type":"URL_TEMPLATE","url_template":"https://school.example/notices?page={value}","start":1,"step":1}
```

`url_template`는 `{value}`를 정확히 한 번 포함하는 절대 HTTP(S) URL이며 `start`는 첫 목록에 대응하는 정수, `step`은 양의 정수다. Crawler는 `start + n * step`을 십진 문자열로 대입해 목록 주소를 만든다. 쿼리뿐 아니라 경로에도 쓸 수 있으며 `parameter`를 별도로 두지 않는다. Analysis는 실제 첫 화면과 다음 화면의 URL 및 목록 추출을 비교해 규칙을 확정한다. 최초 URL의 생략 파라미터 등 표현이 다르면 같은 목록임을 확인해야 한다. 정수 템플릿으로 검증할 수 없는 커서·불규칙 URL에는 NEXT_LINK 또는 브라우저 이동 규칙을 사용한다. 각 요청·리디렉션은 기존 URL 접근 안전 검증을 거친다. 종료는 기존 탐색 종료와 최신 데이터·시간 제한을 따른다.

목록 접근·이동 조합은 다음과 같이 검증한다. HTTP는 NONE·NEXT_LINK·URL_TEMPLATE만 허용한다. BROWSER는 여섯 종류 모두 허용한다. 클릭·스크롤·더 보기를 필요로 하거나 HTTP에서 전체 목록 이동을 검증하지 못하면 BROWSER로 선택한다. Analysis는 첫 화면을 확인하고 실제 다음 화면이 있는 이동 방식은 다음 화면도 승인된 선택자로 확인하며 NONE은 현재 화면만 검증한다. Crawler는 지원하지 않는 조합을 `INVALID_NAVIGATION_RULE`로 거부한다. 서버는 클라이언트의 임의 조합을 자동 실행하지 않는다.

`initial_actions`는 순서 있는 배열이며 비어 있지 않을 때 각 항목은 `{"type":"CLICK","selector":".tab","wait_selector":".notice-list"}` 형식이다. MVP는 CLICK만 지원하고 HTTP에서는 빈 배열만 허용한다. 각 클릭 후 필수 wait_selector를 제한 시간 안에 확인하고 실패하면 탐색 오류로 처리한다. 그 밖의 초기 동작은 사용자 확인이 필요하다.

DETAIL은 항상 BROWSER다. `open`은 LINK `{ "type":"LINK", "selector":"a.title", "url_attribute":"href", "target":"CURRENT_TAB" }` 또는 CLICK `{ "type":"CLICK", "selector":"button.title", "target":"NEW_TAB" }`이다. selector는 목록 항목 기준이며 LINK도 브라우저에서 실행한다. `access.wait_selector`는 상세 DOM에서 확인할 필수 CSS다. `return`은 HISTORY_BACK·CLOSE·CLOSE_TAB 중 하나와 목록 복귀 후 `wait_selector`를 가진다. 일반 페이지는 HISTORY_BACK, 모달은 CLOSE에 닫기 버튼 `selector`, 새 탭은 CLOSE_TAB을 사용한다. 탭·모달 전환과 대기 실패는 정상 null로 취급하지 않는다. 목록으로 돌아온 뒤 목록 선택자와 대상 항목을 재확인하고 안전하게 재선택할 수 없으면 해당 대상에 오류를 남긴다. 각 대상 URL·리다이렉트는 기존 안전 검사와 호스트 제한을 거친다. 정상 마지막 페이지 판정은 기존 탐색 종료 정책을 따른다.

### 5.3 수집 스냅샷 manifest

이 본문은 [S3 저장 명세](s3-storage-contract.md) 4절의 수집 시도별 `manifest.json`에 저장한다. 아래 object_ref들은 manifest와 같은 수집 작업·실행 시도의 HTML 객체를 가리킨다.

```json
{
  "format_version": 1,
  "collection_job_id": "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "navigation_revision": 3,
  "created_at": "2026-11-06T00:01:10Z",
  "list_snapshots": [
    {
      "snapshot_id": "list-0001",
      "url": "https://school.example/notices",
      "captured_at": "2026-11-06T00:00:12Z",
      "access_method": "HTTP",
      "object_ref": {
        "bucket": "capstone-artifacts",
        "key": "collections/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/attempts/43f5b110-f193-4157-91f2-1a16b8fb70d6/lists/list-0001.html"
      },
      "items": [
        {
          "record_key": "list-0001:item-0000",
          "item_index": 0,
          "sequence_index": 0,
          "source_urls": [
            {
              "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
              "source_url": null
            }
          ],
          "details": [
            {
              "url": "https://school.example/notices",
              "object_ref": {
                "bucket": "capstone-artifacts",
                "key": "collections/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/attempts/43f5b110-f193-4157-91f2-1a16b8fb70d6/details/detail-0001.html"
              },
              "consumers": [
                {
                  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
                  "field_ids": [
                    "f1109132-a764-4415-a221-e0757bf0b112"
                  ]
                }
              ]
            }
          ]
        }
      ],
      "item_selector": ".notice-list .notice"
    }
  ]
}
```

`items[].source_urls`의 project_source_id 존재 자체가 그 항목에 대한 해당 프로젝트의 처리 참여 표시다. 참여했지만 직접 URL이 없으면 source_url=null로 기록한다. 표시가 없는 프로젝트는 그 항목을 의도적으로 처리하지 않은 것이며 Ingestion은 추출·오류 판정을 하지 않는다. 대상 참여 표시는 컬렉션 메시지의 project_source_id와 일치해야 하며 중복 ID는 잘못된 manifest다. 참여 표시가 있는 항목의 상세 수집 실패는 프로젝트 결과 오류로 기록하고 범위 밖으로 바꾸지 않는다. 각 프로젝트의 종료는 페이지 또는 스크롤 5개 묶음을 완전히 처리한 뒤 적용하며 해당 구간까지 표시한다. FULL 대상은 종료 조건 또는 시간 제한까지 표시한다.

각 `items[].source_urls`는 프로젝트 출처별 항목 직접 접근 URL을 전달한다. Crawler는 해당 항목을 수집한 각 프로젝트 출처별로 확인한 URL 또는 null을 기록하고 Ingestion은 일치하는 project_source_id의 값을 RECORD.source_url에 사용한다. 항목에 명확한 직접 URL을 확인할 수 없다면 null이다. 이 값은 목록·상세 스냅샷의 관찰 `url`과 다르다.

이 manifest는 별도 DETAIL 필드 f1109132-a764-4415-a221-e0757bf0b112가 본문을 사용하는 예시이며 7.2의 LIST 제목 필드만 있는 축약 요청과 독립적이다. 상세 객체의 consumers는 해당 상세를 사용하는 project_source_id와 field_ids를 명시한다. Crawler는 필드별 동일한 상세 접근·이동·복귀 규칙을 작업 중 묶어 방문하고, 같은 객체를 여러 consumer에 연결한다. LINK는 정규화한 대상과 이동 조건, CLICK은 선택자·대기·복귀 조건 등 동작 전체가 같아야 묶는다. 목록 항목별로 구분하며 URL만 같은 클릭은 합치지 않는다. 영구 상세 규칙 ID는 없다. record_key는 이번 스냅샷 안의 목록–상세 연결용 식별자다. 외부 게시물 ID나 기존 데이터 매칭 키로 사용하지 않는다. 클릭형 상세는 URL이 목록과 같아도 object_ref와 연결 관계로 구분한다. `item_selector`는 Crawler가 그 목록 DOM에서 항목을 찾는 데 사용한 CSS이며, Ingestion은 같은 DOM에 그 선택자를 적용한 순서에서 0부터 시작하는 item_index를 사용한다. 스크롤 묶음 내부 번호로 해석하지 않는다. sequence_index는 이번 수집의 전체 추출 순서를 나타내며 Ingestion 역순 저장에 사용한다. 무한 스크롤 누적 DOM에서는 같은 항목을 중복 열거하지 않도록 각 구간에서 새로 확보한 항목의 범위를 기록한다. 동일 항목을 판별하는 브라우저 구현은 별도 확인 필요다.

## 6 분석 요청과 결과

### 6.1 analysis.execute

Control → Analysis. 초기 분석부터 공동 추천까지 한 작업으로 시작할 수 있으며 출처별 중간 결과는 Analysis가 관리한다.

```json
{
  "analysis_id": "ef7ba73e-6843-4ecf-963a-599cc3442714",
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "purpose": "INITIAL",
  "revision": 1,
  "collection_purpose": "학교 공지사항의 제목을 통합해서 조회",
  "max_duration_seconds": 300,
  "sources": [
    {
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "source_id": null,
      "url": "https://school.example/notices"
    }
  ],
  "current_candidate": null,
  "recovery_baselines": [],
  "failure_context": null,
  "intermediate_refs": []
}
```

| purpose | 필수 입력과 처리 |
|---|---|
| INITIAL | sources, collection_purpose. 성공한 출처의 결과를 모아 공동 후보 생성 |
| RETRY_SOURCE | 대상 sources, 현재 공동 후보, 기준 revision, 기존 유효 출처별 intermediate_refs. 사용자 수정은 보존하고 해당 출처의 매핑·샘플을 제안 |
| ADD_SOURCE | 대상 sources, 현재 승인 스키마·후보. 기존 스키마에 맞춰 매핑하며 임의로 스키마를 확장하지 않음 |
| REVALIDATE | 현재 수정 후보와 영향 출처, revision. 실행·샘플·HTTP 확인 후 결과 반환 |
| REPAIR_NAVIGATION | 현재 스키마·공유 이동 설정, 영향 프로젝트의 비교 기준, 실패 정보 |
| REPAIR_CONNECTOR | 현재 스키마·대상 커넥터, 비교 기준, 실패 정보 |
| RECHECK_SOURCE | 운영 중 0건 출처의 재확인. 해당 프로젝트·출처와 현재 승인 설정을 전달 |

`intermediate_refs`는 재시도 시 Control이 저장한 기존 성공 출처의 불변 중간 결과 참조다. INITIAL 등 참고할 기존 결과가 없는 요청은 빈 배열이다. 각 항목은 `project_source_id`, `intermediate_ref`(`bucket`, `key`)로 구성되며 기존 후보·기준 revision과 함께 전달한다. Analysis는 참조 대상의 analysis_id·project_source_id·형식 및 사용 권한을 검증한다. 참조가 없거나 삭제·조회 실패한 경우 공동 추천을 성공으로 가장하지 않고 해당 출처 재분석 또는 확인 필요로 처리한다. 아래 예시는 실제 RETRY_SOURCE 요청에 추가되는 형태다.

```json
{
  "intermediate_refs": [
    {
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "intermediate_ref": {
        "bucket": "capstone-artifacts",
        "key": "analysis/ef7ba73e-6843-4ecf-963a-599cc3442714/intermediate/95f7d7de-ec00-4fbe-b430-fa3a79763c18/4ad6b0fb-6031-4c38-a8a9-7f52185a6601/43f5b110-f193-4157-91f2-1a16b8fb70d6/result.json"
      }
    }
  ]
}
```

현재 후보의 전체 형식은 다음과 같다. 기존 스키마에 맞춰야 하는 ADD_SOURCE에서도 같은 형태를 쓰되 schema.fields를 유지한다.

```json
{
  "schema": {
    "fields": [
      {
        "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
        "api_name": "title",
        "display_name": "제목",
        "type": "string",
        "nullable": true
      }
    ]
  },
  "source_configs": [
    {
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "navigation": {
        "list_url": "https://school.example/notices",
        "initial_actions": [],
        "list_access": {
          "method": "HTTP",
          "wait_selector": null
        },
        "item_selector": ".notice-list .notice",
        "pagination": {
          "type": "NEXT_LINK",
          "selector": ".pagination .next",
          "url_attribute": "href",
          "wait_selector": null
        }
      },
      "connector_revision": 2,
      "field_connectors": [
        {
          "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
          "location": "LIST",
          "selector": ".notice-title",
          "extract": {
            "type": "TEXT",
            "attribute": null
          },
          "parser": {
            "code": "return rawValues.get(0).replace(\"[공지]\", \"\").strip();"
          },
          "detail_navigation": null
        }
      ]
    }
  ],
  "excluded_project_source_ids": [],
  "candidate_mappings": [
    {
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "intermediate_ref": {
        "bucket": "capstone-artifacts",
        "key": "analysis/ef7ba73e-6843-4ecf-963a-599cc3442714/intermediate/95f7d7de-ec00-4fbe-b430-fa3a79763c18/4ad6b0fb-6031-4c38-a8a9-7f52185a6601/43f5b110-f193-4157-91f2-1a16b8fb70d6/result.json"
      },
      "candidate_key": "candidate-1",
      "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148"
    }
  ]
}
```

INITIAL·RETRY_SOURCE 공동 추천에서 Analysis는 분석 중간 결과의 `(intermediate_ref, candidate_key) → field_id` 매핑을 후보의 `candidate_mappings`에 보관하고 결과 candidate에 함께 반환한다. 다음 재시도는 current_candidate로 전달한다. 매핑 키는 중간 파일 내부에서만 고유하므로 참조와 함께 식별하며, 기존 사용자 수정과 현재 revision을 우선한다. 재분석으로 참조가 바뀌면 새 후보와 명시적으로 다시 매핑하고 불명확한 경우 자동으로 덮어쓰지 않는다. ADD_SOURCE·REVALIDATE 등 중간 결과가 필요 없는 경우 이 배열은 비워도 된다. 이 정보는 분석 후보용이며 운영 커넥터의 필수 항목이 아니다.

revision은 Control이 예약한 현재 후보 버전이다. Analysis는 revision을 독자적으로 증가시키거나 Control의 사용자 수정 전체를 덮어쓰지 않는다. RETRY_SOURCE 결과의 매핑을 적용할 때 현재 revision이 달라졌으면 자동 반영하지 않고 최신 후보 기준의 새 작업으로 재검증한다. 재시도는 해당 출처의 기존 공통 필드 매핑·source_configs와 샘플만 병합하며 공통 schema.fields와 사용자의 다른 편집·제외 목록을 덮지 않는다. 새 공통 필드 제안은 별도 사용자 후보 수정과 재검증으로 처리한다.

복구 요청의 recovery_baselines 항목 예시:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "schema_version": 1,
  "connector_revision": 2,
  "latest_records": [
    {
      "field_values": {
        "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "장학금 신청 안내"
      }
    },
    {
      "field_values": {
        "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "수강 신청 일정 안내"
      }
    },
    {
      "field_values": {
        "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "도서관 운영 시간 변경"
      }
    }
  ],
  "approved_sample_ref": null,
  "schema": {
    "fields": [
      {
        "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
        "api_name": "title",
        "display_name": "제목",
        "type": "string",
        "nullable": true
      }
    ]
  },
  "field_connectors": [
    {
      "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
      "location": "LIST",
      "detail_navigation": null,
      "selector": ".notice-title",
      "extract": {
        "type": "TEXT",
        "attribute": null
      },
      "parser": null
    }
  ]
}
```

DB 0건이면 latest_records는 []이고 approved_sample_ref에 승인된 S3 샘플을 넣는다. 조회 실패를 []로 바꾸지 않는다. 기준이 모두 없으면 자동 복구 성공으로 처리하지 않는다. 공유 이동 규칙 복구에서는 영향 프로젝트 전부의 기준을 전달한다. 이때 각 recovery_baselines 항목에 해당 프로젝트의 schema와 field_connectors도 함께 넣어야 한다. 최신값만 있고 프로젝트별 추출 설정이 없으면 전체 영향 프로젝트를 검증할 수 없다. 프로젝트 범위가 아닌 공유 출처 복구의 최상위 project_id는 null을 허용하고, 실제 대상은 recovery_baselines 항목들로 특정한다. 자동 복구에서 DB 기준이 3건 이상이면 최신 3건, 1~2건이면 존재하는 데이터를 모두 비교하며 모두 일치해야 한다. DB 0건은 승인 샘플을 사용한다. 승인 샘플이 부족해 기준을 모두 확인할 수 없으면 자동 적용하지 않는다. 복구 후보 검증은 최초 시도 포함 최대 3회다.

### 6.2 analysis.completed

최초 분석에서 성공한 출처가 하나 이상이면 그 결과로 공동 스키마 후보를 추천하고 실패 출처는 재시도·제외할 수 있다. 모든 출처가 실패하면 후보를 승인할 수 없다. 재시도 결과는 사용자 수정을 보존하고 필요한 검증을 수행한다.

```json
{
  "attempt_id": "43f5b110-f193-4157-91f2-1a16b8fb70d6",
  "analysis_id": "ef7ba73e-6843-4ecf-963a-599cc3442714",
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "purpose": "INITIAL",
  "revision": 1,
  "status": "SUCCEEDED",
  "schema_change_required": false,
  "candidate": {
    "schema": {
      "fields": [
        {
          "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
          "api_name": "title",
          "display_name": "제목",
          "type": "string",
          "nullable": true,
          "recommendation_reason": "목록 항목의 제목을 나타냅니다"
        }
      ]
    },
    "source_configs": [
      {
        "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
        "navigation": {
          "list_url": "https://school.example/notices",
          "initial_actions": [],
          "list_access": {
            "method": "HTTP",
            "wait_selector": null
          },
          "item_selector": ".notice-list .notice",
          "pagination": {
            "type": "NEXT_LINK",
            "selector": ".pagination .next",
            "url_attribute": "href",
            "wait_selector": null
          }
        },
        "connector_revision": 2,
        "field_connectors": [
          {
            "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
            "location": "LIST",
            "selector": ".notice-title",
            "extract": {
              "type": "TEXT",
              "attribute": null
            },
            "parser": {
              "code": "return rawValues.get(0).replace(\"[공지]\", \"\").strip();"
            },
            "detail_navigation": null
          }
        ]
      }
    ],
    "excluded_project_source_ids": [],
    "candidate_mappings": [
      {
        "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
        "intermediate_ref": {
          "bucket": "capstone-artifacts",
          "key": "analysis/ef7ba73e-6843-4ecf-963a-599cc3442714/intermediate/95f7d7de-ec00-4fbe-b430-fa3a79763c18/4ad6b0fb-6031-4c38-a8a9-7f52185a6601/43f5b110-f193-4157-91f2-1a16b8fb70d6/result.json"
        },
        "candidate_key": "candidate-1",
        "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148"
      }
    ]
  },
  "source_results": [
    {
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "status": "SUCCEEDED",
      "validation_status": "PASSED",
      "sample_ref": {
        "bucket": "capstone-artifacts",
        "key": "analysis/ef7ba73e-6843-4ecf-963a-599cc3442714/revisions/1/95f7d7de-ec00-4fbe-b430-fa3a79763c18/4ad6b0fb-6031-4c38-a8a9-7f52185a6601/43f5b110-f193-4157-91f2-1a16b8fb70d6/sample.json"
      },
      "error": null,
      "intermediate_ref": {
        "bucket": "capstone-artifacts",
        "key": "analysis/ef7ba73e-6843-4ecf-963a-599cc3442714/intermediate/95f7d7de-ec00-4fbe-b430-fa3a79763c18/4ad6b0fb-6031-4c38-a8a9-7f52185a6601/43f5b110-f193-4157-91f2-1a16b8fb70d6/result.json"
      }
    }
  ],
  "error": null
}
```

AI 추천 필드는 근거 문자열 recommendation_reason을 필수로 담아 Control 분석 조회가 사용자에게 제공한다. 사용자가 직접 추가한 필드는 recommendation_reason=null을 허용하며 화면에 추천 근거 없음으로 표시한다. status는 SUCCEEDED, PARTIAL, FAILED, SKIPPED다. 최초 분석 또는 출처 재시도에서 성공한 출처는 `source_results[].intermediate_ref`로 채택된 중간 JSON의 실제 참조를 전달한다. 다른 목적이거나 중간 결과가 없는 출처는 이 값을 null로 둔다. Control은 반환 revision이 현재 후보에 유효할 때만 출처별 참조를 저장하고 RETRY_SOURCE에 기존 유효 참조를 전달한다. 출처별 sample_ref가 가리키는 샘플 본문은 [S3 저장 명세](s3-storage-contract.md) 3절의 revision·프로젝트·출처별 JSON이다. Control은 성공적으로 저장된 실제 sample_ref와 sample_revision을 유효한 현재 검증에 연결하며 사용자 승인 후에는 그 참조를 복구 기준으로 보관한다. 실패 검증도 샘플 저장에 성공했다면 실패 결과의 sample_ref를 읽어 match_count와 7일 만료되는 failure_raw_ref를 확인할 수 있다. 재검증이 불필요한 후보 수정의 승계는 11절을 따른다. 초기 일부 출처 실패는 PARTIAL과 source_results에 남기고 성공 출처의 후보를 제공한다. validation_status는 PASSED, FAILED, UNVERIFIABLE이다. 스키마 변경이 필요한 복구는 schema_change_required=true로 반환하고 자동 승인하지 않는다. 샘플 저장 실패면 PASSED로 반환하지 않는다. 목록의 정적 수집 가능 여부만 list_access에 반영하고 상세는 항상 브라우저로 수집한다. 별도의 HTTP 검사 화면은 없다.

## 7 수집 요청과 결과

### 7.1 collection.schedule.triggered와 출처별 일정

Amazon EventBridge Scheduler를 사용한다. Control이 공유 출처별 일정을 AWS SDK로 등록하고, Scheduler가 정해진 시각에 정기 요청 SQS로 메시지를 보낸다. Control은 그 큐를 소비해 실제 Crawler 작업을 생성한다. 사용자 URL 등록마다 일정을 만드는 것이 아니라 분석·승인으로 source_id와 활성 연결이 확정된 공유 출처에 일정을 만든다.

**일정 등록 시 보관하는 메시지 템플릿**

```json
{
  "format_version": 1,
  "event_type": "collection.schedule.triggered",
  "payload": {
    "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
    "scheduled_at": "<aws.scheduler.scheduled-time>"
  }
}
```

source_id는 해당 일정에 고정해 두는 값이다. scheduled_at의 문맥 키는 Scheduler가 호출할 때 해당 예정 실행 시각으로 치환한다. 재시도마다 바뀔 수 있는 execution-id 대신 source_id + scheduled_at을 Control의 정기 신호 중복 기준으로 사용한다.

**정기 실행 때 Control이 SQS에서 받는 메시지**

```json
{
  "format_version": 1,
  "event_type": "collection.schedule.triggered",
  "payload": {
    "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
    "scheduled_at": "2026-11-08T00:20:00Z"
  }
}
```

아직 작업 생성 전이므로 job_id가 없다. Control은 신호 수신 시 활성 연결·현재 규칙·출처별 진행 중 작업을 확인하고, Ingestion에서 대상 프로젝트별 최신값을 조회한 뒤 collection.execute를 발행한다. Scheduler는 프로젝트 목록·최신 데이터·커넥터를 DB에서 조회하거나 실행하지 않는다.

**Control이 등록할 일정 설정 예시**

아래는 AWS SDK CreateSchedule 호출에 전달할 값을 표현한 예시다. 실제 AWS 호출이나 자원 생성은 이 문서 작업에서 수행하지 않았다. Name은 API의 일정 이름, GroupName은 미리 준비한 일정 그룹이다. 예시 AWS 계정·ARN은 배포 환경 값으로 교체한다.

```json
{
  "Name": "collect-1c226765-48b1-4518-ad16-057e8998eb84",
  "GroupName": "source-collections",
  "ScheduleExpression": "rate(12 hours)",
  "StartDate": 1794097200,
  "FlexibleTimeWindow": {
    "Mode": "OFF"
  },
  "State": "ENABLED",
  "Target": {
    "Arn": "arn:aws:sqs:ap-northeast-2:123456789012:collection-schedule-requests",
    "RoleArn": "arn:aws:iam::123456789012:role/collection-scheduler-execution",
    "Input": "{\"format_version\":1,\"event_type\":\"collection.schedule.triggered\",\"payload\":{\"source_id\":\"1c226765-48b1-4518-ad16-057e8998eb84\",\"scheduled_at\":\"<aws.scheduler.scheduled-time>\"}}"
  }
}
```

| 설정 | 역할 |
|---|---|
| Name | collect-{source_id}로 고정해 같은 출처에 일정 중복 생성을 방지 |
| GroupName | 운영 환경에서 준비한 일정 그룹. 프로젝트별로 새 그룹을 만들지 않음 |
| ScheduleExpression | rate(12 hours). 공유 출처의 반복 주기는 기존 12시간 정책 유지 |
| StartDate | Control이 배정한 첫 정기 실행 시각. 예시 숫자는 2026-11-08T00:20:00Z의 Unix 초이며 SDK에서는 Instant로 전달 가능 |
| FlexibleTimeWindow.Mode | OFF. 별도 유연 시간창 없이 배정된 슬롯에 실행 요청 |
| Target.Arn | Control이 소비하는 정기 요청 SQS ARN. Crawler 큐가 아님 |
| Target.RoleArn | Scheduler가 지정 큐에 SendMessage할 실행 역할. Control 자신의 실행 역할과 구분 |
| Target.Input | 위 템플릿을 직렬화한 JSON 문자열. AWS SDK 요청 안에서는 객체가 아닌 문자열 |

StartDate를 생략하면 rate 일정이 바로 시작할 수 있으므로 최초 수집과 별도로 배정한 미래 시각을 명시한다. Scheduler의 실행 정밀도는 60초이며 큐 대기·재시도로 실제 Crawler 시작은 더 늦어질 수 있다. 슬롯 분산은 중복 실행 금지·도메인 요청 제한의 대체 수단이 아니다.

**등록·변경·정리 시점**

| 상황 | Control의 AWS 호출·처리 |
|---|---|
| 새 공유 출처에 활성 연결 확정 | 최초 수집 요청과 별도로 CreateSchedule. 첫 정기 시각은 미래 슬롯으로 배정 |
| 기존 공유 출처에 프로젝트 추가 | 기존 source_id의 일정 재사용. 신규 프로젝트 최초 수집만 따로 실행 |
| 스키마·CSS·파싱 코드·이동 규칙 변경 | source_id와 일정이 유지된다면 보통 일정 수정 없음. Control이 실행 시 현재 승인 규칙 조회 |
| 반복 주기·첫 시각·대상 큐·실행 역할 변경 | 해당 일정의 GetSchedule 후 필요한 전체 설정으로 UpdateSchedule |
| 마지막 활성 프로젝트 연결 해제 | Control에서 비활성화한 뒤 UpdateSchedule의 DISABLED 또는 DeleteSchedule로 정리 |
| 일정 등록·변경 실패 또는 응답 누락 | 성공으로 기록하지 않음. 고정 Name으로 GetSchedule 후 원하는 설정과 대조·재처리 |
| 비활성화 후 이미 큐에 들어온 신호 | Control이 현재 활성 연결 확인 후 무시. 출처를 다시 만들지 않음 |

UpdateSchedule은 일부 필드만 바꾸는 PATCH가 아니다. 생략한 선택 설정이 기본값으로 바뀔 수 있으므로 기존 일정 또는 Control의 원하는 전체 설정을 사용한다. 일정 관리 작업도 출처별로 직렬화하고, 지연된 CreateSchedule 재시도가 삭제된 출처의 일정을 되살리지 않도록 현재 활성 상태를 확인한다.

Control은 일정 이름·원하는 실행 슬롯·등록 상태·최근 오류를 기존 출처 메타데이터에서 관리할 수 있다. 별도 일정 서비스는 필요하지 않다. Control에는 일정 관리와 실행 역할 전달에 필요한 권한을, Scheduler 실행 역할에는 대상 SQS 전송 권한을 부여한다.

일정 관리 API 실패, Scheduler의 SQS 발행 실패, SQS를 받은 이후 작업 실패는 서로 다른 단계다. Scheduler 전송 재시도·DLQ 설정의 운영 값은 별도 확정하며, Crawler 작업 DLQ와 같은 실패로 집계하지 않는다. 사용자 화면에 실패를 표시하는 기준은 기존 문제·재시도 정책과 연결한다.

상세 흐름은 15.20을 참고한다. 메시지 종류나 업무 큐를 추가한 것은 아니며 기존 정기 신호의 등록 주체와 발행 과정을 구체화했다.

### 7.2 collection.execute payload

```json
{
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "execution_reason": "SCHEDULED",
  "max_duration_seconds": 60,
  "navigation_revision": 3,
  "navigation": {
    "list_url": "https://school.example/notices",
    "initial_actions": [],
    "list_access": {
      "method": "HTTP",
      "wait_selector": null
    },
    "item_selector": ".notice-list .notice",
    "pagination": {
      "type": "NEXT_LINK",
      "selector": ".pagination .next",
      "url_attribute": "href",
      "wait_selector": null
    }
  },
  "projects": [
    {
      "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "schema_version": 1,
      "connector_revision": 2,
      "collection_mode": "INCREMENTAL",
      "schema": {
        "fields": [
          {
            "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
            "api_name": "title",
            "display_name": "제목",
            "type": "string",
            "nullable": true
          }
        ]
      },
      "field_connectors": [
        {
          "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
          "location": "LIST",
          "selector": ".notice-title",
          "extract": {
            "type": "TEXT",
            "attribute": null
          },
          "parser": {
            "code": "return rawValues.get(0).replace(\"[공지]\", \"\").strip();"
          },
          "detail_navigation": null
        }
      ],
      "latest_records": [
        {
          "field_values": {
            "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "장학금 신청 안내"
          }
        },
        {
          "field_values": {
            "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "수강 신청 일정 안내"
          }
        },
        {
          "field_values": {
            "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "도서관 운영 시간 변경"
          }
        }
      ]
    }
  ]
}
```

execution_reason은 INITIAL, SCHEDULED, MANUAL, REVISION, RECOVERY다. collection_mode는 INCREMENTAL 또는 FULL이다. FULL에서는 latest_records=[]이고 일치 조기 종료를 적용하지 않는다. 프로젝트별 선택은 수집 계기와 구분한다. FULL 대상이 하나라도 있으면 출처 실행 예산은 최초·리비전 기준 최대 300초, 순수 반복 대상만 있으면 최대 60초다. 예산은 사이트 접근·대기·재시도·임시 파싱 시간을 포함한다. S3 업로드·결과 발행에 필요한 완료 처리는 별도 작업 종료 기한 안에서 수행한다.

프로젝트별 비교 기준은 작업 발행 시점 값이다. Control은 관련 적재가 종료될 때까지 같은 출처의 다음 논리 작업을 기다리게 하여 오래된 기준의 중첩을 줄인다. 여러 프로젝트가 요구하는 상세를 한 번 확보하며 완료된 프로젝트는 추가 비교를 중단한다. 스크롤 묶음 크기는 5개, 기준 확인 건수는 min(3, 저장 건수)다.

### 7.3 collection.completed payload

```json
{
  "attempt_id": "43f5b110-f193-4157-91f2-1a16b8fb70d6",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "navigation_revision": 3,
  "status": "SUCCEEDED",
  "stop_reason": "TIME_LIMIT",
  "started_at": "2026-11-06T00:00:10Z",
  "finished_at": "2026-11-06T00:01:10Z",
  "list_item_count": 40,
  "snapshot_ref": {
    "bucket": "capstone-artifacts",
    "key": "collections/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/attempts/43f5b110-f193-4157-91f2-1a16b8fb70d6/manifest.json"
  },
  "project_results": [
    {
      "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "schema_version": 1,
      "connector_revision": 2,
      "status": "SUCCEEDED",
      "can_ingest": true,
      "normal_extracted_record_count": 40,
      "error": null
    }
  ],
  "error": null
}
```

stop_reason은 TIME_LIMIT, END_OF_LIST, NO_CHANGE, NAVIGATION_FAILED, ACCESS_BLOCKED, TECHNICAL_FAILURE, EMPTY_LIST, CANCELLED다. latest_data_confirmed 같은 새 결과 코드는 도입하지 않는다. TIME_LIMIT과 정상 END_OF_LIST는 성공 종료가 가능하다. NO_CHANGE라도 앞에서 신규 데이터를 확보했다면 스냅샷과 적재 대상이 있을 수 있다. 전혀 변경이 없으면 snapshot_ref=null, can_ingest=false이며 기존 정상 스냅샷을 유지한다. PARTIAL이면 실패 프로젝트와 적재 가능한 프로젝트를 구분한다. 일반 수집의 정상적으로 확보·검증된 레코드는 적용 가능하나 오류로 완료하지 못한 프로젝트 출처는 복구 대상으로 표시한다. completed는 먼저 전달한 issue와 같은 오류·0건 범위를 포함해 전달 순서가 뒤집혀도 문제 표시를 놓치지 않는다. 승인 출처 추가 실패는 부분 쓰기 미공개, 리비전 일부 실패는 전체 전환 보류다. can_ingest는 유효 스냅샷·대상별 적용 조건을 확인해 설정한다.

## 8 적재·준비 요청과 결과

### 8.1 ingestion.execute payload

최초 적용·구조 변경은 프로젝트 적용 작업 기준으로 승인된 스키마 정의를 Data DB에 준비한 뒤 출처별 적재가 같은 정의를 참조한다. 동일 프로젝트·버전의 준비가 중복될 때 승인 내용이 같은지 확인하고 서로 다른 내용을 덮어쓰지 않는다. v2 준비는 change_job_id를 대조한다. Control은 기존 큐에서 schema.prepare를 보내고 schema.prepared 성공 후 출처 수집을 요청한다. 최초·리비전 모두 change_job_id로 준비를 식별한다. 구현 완료를 뜻하지는 않는다.

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "mode": "APPEND",
  "schema_version": 1,
  "connector_revision": 2,
  "change_job_id": null,
  "snapshot_ref": {
    "bucket": "capstone-artifacts",
    "key": "collections/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/attempts/43f5b110-f193-4157-91f2-1a16b8fb70d6/manifest.json"
  },
  "schema": {
    "fields": [
      {
        "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
        "api_name": "title",
        "display_name": "제목",
        "type": "string",
        "nullable": true
      }
    ]
  },
  "field_connectors": [
    {
      "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
      "location": "LIST",
      "selector": ".notice-title",
      "extract": {
        "type": "TEXT",
        "attribute": null
      },
      "parser": {
        "code": "return rawValues.get(0).replace(\"[공지]\", \"\").strip();"
      },
      "detail_navigation": null
    }
  ]
}
```

APPEND는 기존 사용자 필드값과 비교해 신규만 역순 저장한다. 최초 승인 출처의 APPEND에는 OPEN 변경의 change_job_id를 필수로 포함하고 일반 반복 APPEND에는 null을 사용한다. PREPARE는 스키마 구조 변경의 새 버전 데이터를 비활성 상태로 준비하며 change_job_id가 필수다. 구조 유지 출처 추가는 APPROVED_SOURCE_APPEND를 사용한다. 승인된 변경의 change_job_id와 현재 버전을 검증하고 최초 수집 결과를 기존 필드값과 비교하여 정상 적재한 뒤 공개한다. 정상 추출 0건·실패는 부분 쓰기를 롤백하고 공개하지 않는다. 같은 job_id 재전달은 완료 기록을 재사용한다. 같은 추출·검증 코드를 공유하되 저장·적용 단계만 구분한다. 구조 변경의 PREPARE는 기존 레코드와 매칭하거나 기존 record_id를 유지하지 않는다. APPEND도 해당 프로젝트 출처가 삭제되었거나 대상 schema_version이 종료되었으면 쓰지 않는다. 최초 승인 출처는 공개 전이라도 승인된 최초 change_job_id·project_source_id·준비된 v1과 일치하면 APPEND를 허용하고, 첫 정상 적재·v1 공개를 같은 Data DB 트랜잭션으로 확정한다. APPROVED_SOURCE_APPEND는 미활성 신규 연결이지만 승인된 change_job_id·project_source_id 및 현재 schema_version이 일치할 때만 허용한다. 실패·취소·삭제 후 지연 작업은 거부한다. 적재 결과는 DB 트랜잭션으로 확정하고 Control의 활성화 갱신 실패는 같은 완료 이벤트를 재처리해 수렴시킨다.

**승인된 출처 추가 요청 예시:** 8.1의 일반 payload와 동일한 필드를 사용하고 다음 값만 바꾼다. `schema`, `field_connectors`, `snapshot_ref`는 8.1과 동일한 형식으로 필수 전달한다.

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "mode": "APPROVED_SOURCE_APPEND",
  "schema_version": 1,
  "connector_revision": 2,
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "snapshot_ref": {"bucket": "capstone-artifacts", "key": "collections/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/attempts/43f5b110-f193-4157-91f2-1a16b8fb70d6/manifest.json"},
  "schema": {"fields": [{"field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148", "api_name": "title", "display_name": "제목", "type": "string", "nullable": true}]},
  "field_connectors": [{"field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148", "location": "LIST", "detail_navigation": null, "selector": ".notice-title", "extract": {"type": "TEXT", "attribute": null}, "parser": null}]
}
```

### 8.2 ingestion.completed payload

선택자·접근·추출·파싱·타입 변환 실패가 하나라도 있는 레코드는 부분 저장하지 않고 정상 추출 건수에서도 제외한다. 실제 null과 원래 없는 ABSENT 필드는 정상이며 오류와 구분한다. 실패 원인과 확보 가능한 원문은 기존 검증·sidecar 정책에 따라 기록한다.

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "mode": "APPEND",
  "schema_version": 1,
  "connector_revision": 2,
  "change_job_id": null,
  "status": "SUCCEEDED",
  "normal_extracted_record_count": 40,
  "dataset_revision": 18,
  "error": null
}
```

PREPARE 성공 예시:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "mode": "PREPARE",
  "schema_version": 2,
  "connector_revision": 3,
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "status": "SUCCEEDED",
  "normal_extracted_record_count": 40,
  "dataset_revision": null,
  "error": null
}
```

normal_extracted_record_count=0이면 기존의 사용자 확인 흐름 대상이다. 정상 추출 건수와 status·error는 결과 판단에 필요한 필수 항목이다. APPEND 및 APPROVED_SOURCE_APPEND 성공 시 공개 데이터 상태를 나타내는 dataset_revision은 필수이며, PREPARE 결과에서는 아직 공개되지 않았으므로 null이다.

준비 결과는 PREPARE 작업의 envelope.job_id로 식별한다. 위 PREPARE 예시의 공통 envelope.job_id가 `bf521885-5d26-4c2c-9586-bf57b7d3d46f`라면 dataset.apply의 prepared_job_id에도 같은 값을 넣는다. 결과 payload에는 그 job_id를 다시 복사하거나 별도 준비 결과 ID를 넣지 않는다. Ingestion은 해당 job_id와 준비 데이터를 같은 트랜잭션으로 보관하고, 재전달에는 같은 준비 결과를 사용한다.

운영 통계는 아래와 같이 선택 항목으로 추가할 수 있다. 누락을 0으로 해석하지 않으며, Control은 이 값이 없어도 성공·실패 및 정상 추출 0건을 판단할 수 있어야 한다.

| 선택 항목 | 의미 | 사용 위치 |
|---|---|---|
| inserted_record_count | 공개 데이터에 새로 저장한 건수 | APPEND 결과. PREPARE에서는 생략 |
| duplicate_record_count | 기존 필드값과 같아 저장을 생략한 건수 | APPEND 결과 |
| invalid_record_count | 검증 실패로 분류된 건수 | 적재·준비 결과 |
| matched_latest_count | 수집 중 확인한 최신 기준 데이터 건수 | collection.completed의 프로젝트별 결과 |
| record_count | 적용 후 공개 데이터의 전체 건수 | dataset.applied |
| deleted_record_count | 적용하면서 삭제한 레코드 수 | 삭제를 포함한 dataset.applied |

정상 추출 결과가 있어도 inserted_record_count는 0일 수 있으며 정상이다. 선택자·접근·추출·파싱·타입 변환 실패가 하나라도 있는 레코드는 부분 저장하지 않고 정상 추출 건수에서도 제외한다. 실제 null과 원래 없는 ABSENT 필드는 정상이며 오류와 구분한다. 실패 원인과 확보 가능한 원문은 기존 검증·sidecar 정책에 따라 기록한다.

## 9 중간 문제 감지

### 9.1 job.issue.detected payload

```json
{
  "attempt_id": "43f5b110-f193-4157-91f2-1a16b8fb70d6",
  "reporting_service": "CRAWLER",
  "scope": "PROJECT_SOURCE",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "field_ids": [
    "a68629e0-30fd-49a6-abbf-3be63bdc8d4d"
  ],
  "schema_version": 1,
  "connector_revision": 2,
  "navigation_revision": 3,
  "error": {
    "code": "FIELD_EXTRACTION_FAILED",
    "stage": "TEMPORARY_EXTRACTION",
    "message": "상세 화면에서 본문 요소를 찾지 못했습니다.",
    "details": {
      "page_url": "https://school.example/notices/123",
      "selector": ".article-body",
      "matched_element_count": 0
    }
  }
}
```

Crawler의 issue는 현재 유효한 attempt_id를 필수로 포함하며 Control은 유효 lease·작업 상태를 먼저 확인한다. 늦은 이전 시도의 issue는 알림이나 복구를 시작하지 않고 소비한다. Ingestion issue는 별도 동일 lease가 없으므로 attempt_id를 생략한다. scope는 SOURCE 또는 PROJECT_SOURCE다. SOURCE면 project_id·project_source_id·connector_revision은 null이고 field_ids=[]이다. PROJECT_SOURCE면 영향 프로젝트 출처를 필수로 담는다. code 예시는 EMPTY_LIST, EMPTY_EXTRACTION, NAVIGATION_FAILED, FIELD_EXTRACTION_FAILED, TYPE_CONVERSION_FAILED, ACCESS_BLOCKED, SITE_UNAVAILABLE, BROWSER_FAILED, SNAPSHOT_WRITE_FAILED, SNAPSHOT_READ_FAILED, DATABASE_UNAVAILABLE, PARSER_FAILED다.

0건은 즉시 문제 표시·설정에 따른 이메일 대상이다. 다른 문제는 기존 자동 복구·재시도 정책을 따른다. 중간 issue와 완료 결과에 같은 오류가 나타날 수 있으므로 Control은 job_id·영향 범위·코드별 알림을 중복 발송하지 않는다. issue만으로 작업을 완료 처리하거나 아직 실행 중인 출처의 재수집을 병렬 시작하지 않는다. 기술 재시도 중의 모든 개별 오류를 반드시 issue로 보낼 필요는 없다.

## 10 승인 변경의 적용과 삭제

### 10.1 dataset.apply payload

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "expected_schema_version": 1,
  "target_schema_version": 2,
  "operation": "REPLACE",
  "prepared_results": [
    {
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "prepared_job_id": "bf521885-5d26-4c2c-9586-bf57b7d3d46f"
    }
  ],
  "final_project_source_ids": [
    "95f7d7de-ec00-4fbe-b430-fa3a79763c18"
  ],
  "connector_revisions": [{"project_source_id":"95f7d7de-ec00-4fbe-b430-fa3a79763c18","connector_revision":3}],
  "delete_project_source_ids": []
}
```

operation은 REPLACE, UPDATE_SOURCES, BEGIN_CHANGE, END_CHANGE, SET_CONNECTOR_REVISION 또는 UPDATE_SCHEMA_METADATA다. SET_CONNECTOR_REVISION은 10.6의 별도 필드를 사용한다. BEGIN_CHANGE·END_CHANGE의 별도 필드와 동작은 10.5를 따른다. REPLACE는 스키마 구조 변경이며 최종 출처 전부의 PREPARE 결과를 요구한다. UPDATE_SOURCES는 구조 유지의 삭제에만 사용한다. 구조 유지 출처 추가는 APPROVED_SOURCE_APPEND로 별도 적재·공개한다. 추가와 삭제가 함께 있으면 모든 추가 출처가 정상 적재된 후에만 삭제 요청을 보낸다. 추가 실패 시 기존 출처는 유지하고 성공한 추가 출처는 공개 상태를 유지한다. 삭제 실패는 이미 공개된 추가 출처를 되돌리지 않고 재시도·상태 표시한다. 프로젝트당 승인 변경은 하나씩 적용하며 같은 schema_version의 출처 변경끼리도 직렬화한다. 승인 시점과 적용 시점의 불일치를 schema_version만으로 감지할 수는 없으므로 Control의 현재 승인 change_job_id도 대조한다.

REPLACE 전 Data DB의 대상 버전 스키마 정의와 출처별 준비 결과가 같은 승인 change_job_id에 속하는지 확인한다. REPLACE의 connector_revisions는 최종 대상 출처별 유일한 connector_revision 전체이며 준비 receipt와 일치해야 한다. 전환과 각 출처의 유효 (schema_version, connector_revision) 갱신을 같은 Data DB 트랜잭션으로 처리한다. 공개 포인터 전환과 Control 커넥터 활성화가 서로 다른 DB에서 일어나므로 해당 프로젝트의 일반 수집·적재가 서로 다른 버전을 섞지 않게 조정한다. Control은 해당 프로젝트의 새 일반 작업을 보류하고 기존 쓰기 종료, Data 전환, Control 커넥터 활성화를 순서대로 수행한 후 재개한다. 구조 변경 없이 공개 필드 표시 정보가 바뀌는 경우 19.4의 UPDATE_SCHEMA_METADATA로 Data DB의 같은 버전 정의에 반영한다.

보완 제안: 해당 프로젝트의 실행 중 쓰기가 종료되도록 Control이 조정한 뒤 적용 요청을 보낸다. Ingestion은 프로젝트 데이터 상태 행을 잠그고, 준비 결과의 프로젝트·출처·스키마 버전·change_job_id를 검증하며 데이터 반영·삭제 차단 표시·데이터 revision·적용 완료 기록·결과 Outbox를 같은 트랜잭션에 기록한다. 프로젝트의 일반 적재·삭제도 동일한 상태 행을 기준으로 쓰기를 조정한다. 이로써 삭제 직후 늦게 도착한 구작업이 데이터를 다시 넣지 못하게 한다.

같은 API 버전 내 출처 추가·삭제도 현재 dataset_revision을 바꾼다. 구조 변경이면 Ingestion이 데이터 DB의 공개 스키마 버전 포인터를 전환하고 Data API가 해당 버전의 필드 정의와 레코드를 일관되게 읽는다. RECORD는 한 테이블에 project_id·project_source_id·schema_version 및 준비 작업 식별자를 보관한다. DATASET 별도 테이블은 필요하지 않다. 같은 대상 버전의 예전 취소·실패 준비 행이 노출되지 않도록 전환 트랜잭션에서 승인된 change_job_id의 성공 prepared_job_id 목록에 속하지 않는 비공개 행을 정리하고, 나열된 대상별 결과를 확인한 뒤 전환한다. 이전 버전 정리는 전환 성공 후 수행한다. Control은 완료 결과로 운영 상태·후보 적용 상태를 갱신한다. Control의 메타데이터 반영 전이라도 Data API가 데이터와 다른 스키마를 섞어 읽지 않게 해야 한다. Control 반영 지연에도 Data API의 버전별 스키마와 레코드가 섞이지 않도록 캐시 무효화·조회 경계를 구현한다.

### 10.2 dataset.applied payload

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "status": "SUCCEEDED",
  "schema_version": 2,
  "dataset_revision": 19,
  "active_project_source_ids": [
    "95f7d7de-ec00-4fbe-b430-fa3a79763c18"
  ],
  "error": null
}
```

Control은 단순 준비 완료가 아니라 dataset.applied 성공을 받은 뒤 사용자에게 적용 완료를 표시한다. 완료 이벤트가 늦으면 Ingestion이 같은 작업의 기존 결과를 재발행한다.

### 10.3 삭제만 있는 dataset.apply payload

삭제만 승인한 경우도 dataset.apply를 사용한다. UPDATE_SOURCES에서 prepared_results는 빈 배열이다. 공개된 스키마가 있으면 expected_schema_version과 target_schema_version은 같고, 승인된 v1이 아직 미공개인 경우에는 각각 null과 1이다(19.3). final_project_source_ids는 삭제 후 남을 전체 출처 목록이며, 마지막 출처 삭제도 허용하며 final_project_source_ids=[]로 표현한다. 공개 버전이 있으면 기존 스키마·API를 유지하고 출처 데이터만 삭제한 빈 목록을 반환한다. 미공개 최초 상태의 삭제는 19.3을 따른다.

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "expected_schema_version": 1,
  "target_schema_version": 1,
  "operation": "UPDATE_SOURCES",
  "prepared_results": [],
  "final_project_source_ids": [
    "95f7d7de-ec00-4fbe-b430-fa3a79763c18"
  ],
  "delete_project_source_ids": [
    "263fe9e4-af2d-45d3-b8a9-a4e04d41fc45"
  ]
}
```

별도 출처 삭제 명령·이벤트는 만들지 않는다. 추가·삭제가 함께 있으면 모든 추가 출처의 APPROVED_SOURCE_APPEND 성공 후 prepared_results=[]인 삭제 요청을 보낸다. 추가 실패·0건이면 삭제하지 않는다. 재전달에 대한 멱등 처리와 프로젝트 쓰기 조정, 삭제된 출처에 대한 늦은 적재 차단은 일반 dataset.apply와 동일하다. 이미 완료한 적용 작업은 기존 결과를 반환한다. 다른 프로젝트의 연결과 데이터는 영향을 받지 않는다.

처리 완료된 삭제의 차단 식별정보를 즉시 없애면 늦은 적재를 막을 수 없으므로 적어도 메시지 재처리 가능 기간 동안 유지한다. 이는 사용자 레코드의 삭제 이력을 제공하는 기능과 구분한다.

### 10.4 삭제만 있는 dataset.applied payload

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "status": "SUCCEEDED",
  "schema_version": 1,
  "dataset_revision": 20,
  "active_project_source_ids": [
    "95f7d7de-ec00-4fbe-b430-fa3a79763c18"
  ],
  "error": null
}
```

deleted_record_count는 필요한 경우 선택 통계로 추가한다. Control은 dataset.applied 성공 후 연결·활성 설정의 최종 상태를 반영한다. 마지막 연결이 해제되면 정기 일정을 중지한다. SQS의 특정 메시지를 찾아 제거한다는 전제를 두지 않고 남은 요청은 무효 작업으로 소비·폐기한다. 실행 중인 이전 작업의 결과는 적용하지 않으며 스냅샷은 사용 작업 종료 후 정리한다.


### 10.5 승인 변경의 시작과 종료: 기존 dataset.apply 확장

기존 Ingestion 작업 큐의 `dataset.apply`와 Control 결과 큐의 `dataset.applied`를 재사용한다. `operation`에 `BEGIN_CHANGE`, `END_CHANGE`를 추가한다. 두 요청은 각각 별도의 Control 자식 `job_id`를 사용하고 같은 승인 상위 작업을 `change_job_id`로 참조한다. 각 자식 요청의 기술적 재전달은 같은 envelope·job_id·payload를 사용한다. 서로 다른 operation 또는 다른 disposition은 새 자식 job_id를 쓴다. 두 동작은 데이터 공개·교체 자체가 아니다.

**시작 요청** (`dataset.apply` payload):

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "operation": "BEGIN_CHANGE",
  "change_type": "SOURCE_CHANGE",
  "expected_schema_version": 1,
  "target_schema_version": 1,
  "target_project_source_ids": ["95f7d7de-ec00-4fbe-b430-fa3a79763c18", "263fe9e4-af2d-45d3-b8a9-a4e04d41fc45"],
  "add_project_source_ids": ["95f7d7de-ec00-4fbe-b430-fa3a79763c18"],
  "delete_project_source_ids": ["263fe9e4-af2d-45d3-b8a9-a4e04d41fc45"],
  "target_connector_revisions": [{"project_source_id":"95f7d7de-ec00-4fbe-b430-fa3a79763c18","schema_version":1,"connector_revision":2}]
}
```

`change_type`은 `INITIAL`, `SOURCE_CHANGE`, `SCHEMA_REVISION`이다. 최초 준비 전 또는 승인된 v1만 있는 미공개 상태의 SOURCE_CHANGE에서 expected_schema_version은 null이고 target_schema_version은 1이다. SOURCE_CHANGE의 target_project_source_ids는 이 변경에서 추가·삭제 대상으로 승인된 연결 ID들의 합이며 add_project_source_ids와 delete_project_source_ids는 그 대상을 구분한다. 추가만 또는 삭제만 있을 때 다른 배열은 빈 배열이다. INITIAL과 SCHEMA_REVISION에서는 두 배열을 빈 배열로 둔다. INITIAL과 SCHEMA_REVISION은 승인된 전체 대상 출처다. 대상·버전·종류는 시작 후 변경하지 않는다. 변경해야 하면 현재 변경의 종료 확인 후 새로운 change_job_id로 시작한다. 표시 정보·커넥터만 바꾸는 기존 경로는 데이터 집합을 변경하지 않는 한 이 절의 대상이 아니다.

Ingestion은 Data DB의 프로젝트 상태 행을 잠그고 프로젝트 삭제 상태·현재 공개 버전·다른 열린 변경을 확인한 뒤 변경 가드와 시작 receipt·결과 Outbox를 같은 트랜잭션에서 기록한다. 행이 없는 최초 프로젝트는 project_id 유일 제약으로 생성·잠금하며 중복 경합을 직렬화한다. 시작 결과를 확인한 뒤 Control은 최초·리비전의 `schema.prepare` 또는 출처 수집·적재를 발행한다. 시작이 거절·미확인이면 수집·적용을 발행하지 않고 재전달·상태 재확인한다.

**종료 요청** (`dataset.apply` payload):

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "operation": "END_CHANGE",
  "disposition": "ABANDONED"
}
```

`disposition`은 `COMPLETED` 또는 `ABANDONED`다. 자동 복구·재시도 중에는 계속 OPEN이며, 성공 또는 더 이상 같은 변경을 계속하지 않기로 결정한 뒤 종료한다. `ABANDONED`는 승인 후 사용자 취소 기능을 뜻하지 않는다. Ingestion은 같은 프로젝트 행과 변경 가드를 잠가 종료와 데이터 커밋을 직렬화하고 OPEN을 CLOSED로 바꾸며 receipt·결과 Outbox를 함께 기록한다. 종결된 가드는 다시 OPEN으로 만들지 않는다. 이후 새 변경은 새 change_job_id와 새 BEGIN을 사용한다. 다음 승인 변경은 Control이 `END_CHANGE`의 성공을 받아 실제 반영 결과를 동기화한 뒤에만 허용한다.

두 동작의 성공 결과 (`dataset.applied` payload; 예시는 종료 결과):

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "operation": "END_CHANGE",
  "status": "SUCCEEDED",
  "change_state": "CLOSED",
  "disposition": "ABANDONED",
  "committed_appends": [{"project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18", "job_id": "bf521885-5d26-4c2c-9586-bf57b7d3d46f"}],
  "replace_committed": false,
  "committed_deletions": [],
  "dataset_revision": 20,
  "error": null
}
```

BEGIN 성공은 operation=BEGIN_CHANGE, change_state=OPEN, disposition=null, committed_appends=[], committed_deletions=[], replace_committed=false를 반환한다. dataset_revision은 공개 전이면 null이다. 실패는 같은 operation과 정형 error를 포함하며 성공으로 처리하지 않는다. 일시적인 DB 장애는 최종 receipt로 확정하지 않고 같은 job_id로 재처리한다. 최종 오류 receipt는 같은 결과를 재발행한다. `committed_appends`는 이미 공개 커밋된 출처와 적재 job_id이며 실패한 출처를 포함하지 않는다. `committed_deletions`는 이미 삭제 커밋된 project_source_id·적용 job_id의 목록이다. 부분 성공 후 ABANDONED가 되어도 이미 공개한 출처나 삭제 완료 결과를 되돌리지 않는다. 결과의 dataset_revision은 종료 트랜잭션에서 확인한 공개 상태다.

쓰기가 END보다 먼저 커밋됐지만 결과 메시지만 지연됐다면 그 커밋은 취소되지 않는다. END는 실제 커밋된 추가·삭제 출처를 반환하며 Control은 이를 반영한다. REPLACE가 이미 커밋된 상태에서 ABANDONED를 요청하면 `ALREADY_APPLIED` 오류와 기존 적용 job_id를 반환하고 열린 상태를 유지한다. 오류는 `error.code=ALREADY_APPLIED`, `error.details.applied_job_id`에 적용 job_id를 포함한다. Control은 기존 REPLACE의 `dataset.applied` 결과를 그 job_id로 재확인해 실제 전환과 커넥터 활성화를 마친 뒤 새로운 END 자식 job_id와 COMPLETED로 종료한다. 실패한 END job_id의 payload를 바꾸지 않는다. 알 수 없는 결과를 실패로 추정해 기존 버전이 유지된다고 표시하지 않는다. END가 먼저 커밋되면 이후 도착한 쓰기는 `CHANGE_CLOSED`로 거절된다.

schema.prepare는 Data DB에서 OPEN 가드의 project_id·change_job_id·변경 종류·목표 버전을 실제 쓰기 트랜잭션에서 검증한다. 출처별 승인 쓰기는 19.8의 pending revision도 같은 트랜잭션에서 검증한다. 프로젝트 단위 schema.prepare에는 출처 ID가 없다. INITIAL의 출처별 적재, APPROVED_SOURCE_APPEND, PREPARE와 REPLACE·UPDATE_SOURCES는 OPEN 여부와 해당 요청의 프로젝트·변경·대상 출처 집합·버전을 실제 쓰기 트랜잭션에서 검증한다. INITIAL 출처별 `APPEND`에도 change_job_id를 포함한다. 일반 반복 APPEND는 change_job_id=null이며 삭제된 출처·프로젝트 삭제 상태와 유효한 (schema_version, connector_revision)을 같은 Data DB 트랜잭션에서 검증한다. 해당 값은 SOURCE_WRITE_GUARD에 보관하며 누락·불일치는 정상 빈 값으로 처리하지 않고 STALE_CONNECTOR_REVISION 또는 해당 버전 오류로 거절한다. 스키마 전환 중 일반 작업 발행 보류·기존 쓰기 종료와 공개 포인터 전환 조건은 기존 10.1을 따른다. S3 읽기·추출 중에는 DB 락을 유지하지 않고 커밋 직전에 다시 검사한다.

BEGIN/END와 각 쓰기가 같은 행을 잠그므로 선행 커밋은 END 결과에 반영되고 후행 쓰기는 차단된다. SQS 순서 보장은 가정하지 않는다. job_id receipt가 이미 있으면 결과를 재발행하되 CLOSED를 다시 열거나 쓰지 않는다. Control 역시 현재 job·change_job_id에 속하지 않는 늦은 결과를 무시한다. 프로젝트 삭제는 Data DB에 프로젝트 삭제 차단 상태를 커밋하고 키·데이터를 정리하며, 삭제가 진행 중이면 새 BEGIN과 모든 늦은 쓰기를 거절한다. UPDATE_SOURCES는 BEGIN에 승인된 모든 추가 출처의 정상 공개 receipt를 Data DB에서 확인한 후 삭제하며, 삭제된 project_source_id의 차단 기록을 같은 트랜잭션에 남긴다. 승인된 추가 중 하나라도 미완료·실패면 삭제하지 않는다. 삭제 후 같은 URL을 다시 연결할 때는 새 project_source_id를 발급한다. 가드와 차단 기록은 SQS·DLQ 재처리 가능 기간 이상 유지한다.

최초 변경이 종료된 뒤 실패 출처를 재확인할 때도 원래 승인된 v1 스키마·커넥터를 사용하고 새 change_job_id로 시작한다. v1이 이미 준비됐다면 동일한 승인 필드 정의인지 확인하고 새 OPEN 가드에서 기존 v1 정의를 참조한다. 최초 승인 정의와 기존 데이터는 덮어쓰지 않는다. 성공 출처의 기존 데이터는 유지한다.

### 10.6 기존 출처의 커넥터 revision 동기화

기존 활성 출처의 스키마 유지 커넥터 수정·자동 복구가 성공하면 Control은 그 프로젝트 출처의 새 수집과 적재 발행을 보류한다. 공유 수집에 참여한 다른 프로젝트는 계속할 수 있다. Control은 기존 Ingestion 작업 큐에 dataset.apply `SET_CONNECTOR_REVISION`을 보낸다. 사용자 승인 없는 자동 복구를 위해 별도 승인 변경이나 새 큐는 만들지 않는다.

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "operation": "SET_CONNECTOR_REVISION",
  "guard_scope": "ACTIVE",
  "change_job_id": null,
  "schema_version": 1,
  "expected_connector_revision": 2,
  "connector_revision": 3
}
```

성공 결과 (`dataset.applied` payload):

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "operation": "SET_CONNECTOR_REVISION",
  "guard_scope": "ACTIVE",
  "change_job_id": null,
  "schema_version": 1,
  "connector_revision": 3,
  "status": "SUCCEEDED",
  "error": null
}
```

요청과 결과는 기존 envelope의 같은 자식 job_id로 연결한다. 실패 시 status=FAILED와 error.code·message·details를 반환하며 connector_revision은 확인할 수 없으면 null이다. 동일 job 결과는 event_id를 유지한다.

Ingestion은 프로젝트 상태 행과 SOURCE_WRITE_GUARD를 같은 Data DB 트랜잭션에서 잠그고 프로젝트·출처가 삭제되지 않았는지, 활성 schema_version과 현재 connector_revision이 요청의 expected 값과 같은지 확인한다. 일치하면 유효 revision을 갱신하고 receipt와 결과 Outbox를 함께 기록한다. 결과는 dataset.applied에 project_id·project_source_id·operation·schema_version·connector_revision·status·error를 담는다. 불일치·삭제는 갱신하지 않고 정형 오류를 반환한다. 같은 job_id·같은 payload의 재전달은 기존 결과를 재발행하며 다른 payload에는 새 job_id가 필요하다. 성공 응답이 유실되면 Control은 같은 job_id와 payload를 재전달하고 확인될 때까지 해당 출처를 보류한다. 이후 Control DB에서 해당 revision과 규칙을 활성화하고 재수집한다. Data DB에 먼저 반영된 동안 이전 규칙 요청이 거절되는 것을 허용하며, 활성화 실패 시 같은 결과를 재처리한다.

일반 APPEND는 SOURCE_WRITE_GUARD의 (schema_version, connector_revision)과 요청이 일치할 때만 데이터·receipt·Outbox를 커밋한다. 전환이 먼저 커밋되면 예전 요청은 거절되고, 예전 적재가 먼저 커밋됐다면 그 결과를 유지하며 새 규칙으로 재수집한다. 최초 v1 공개 또는 APPROVED_SOURCE_APPEND는 정상 저장·공개와 해당 출처 guard의 활성 버전·revision 초기화를 함께 확정한다. REPLACE는 각 PREPARE receipt의 connector_revision과 최종 connector_revisions를 대조하고, 버전·데이터 교체와 모든 최종 출처의 유효 revision 갱신을 함께 커밋한다. 이전 버전이나 삭제된 출처의 guard가 비어 있으면 일반 APPEND를 허용하지 않는다.

사용자가 승인한 구조 변경 진행 중에 후보 커넥터를 복구하면 후보와 영향을 받은 PREPARE를 재검증·재실행한다. 이미 공개 중인 별도 커넥터 변경이 해당 프로젝트의 구조 변경 적용과 충돌할 수 있으면 Control은 한 작업을 보류하고 현재 승인 변경의 종료·동기화 뒤 다른 작업을 진행한다. Data와 Control은 하나의 분산 트랜잭션이 아니며 양쪽 상태가 확인될 때까지 해당 출처의 작업을 보류한다.

### 10.7 사용자 조치가 필요한 0건과 재확인

목록 또는 정상 추출 0건은 기존 정책대로 즉시 문제 표시·설정된 이메일을 보낸다. 자동 복구가 계속 가능한 동안 현재 변경은 OPEN이다. 재시도를 마치거나 사용자 재확인·삭제 선택이 필요한 상태가 되면 Control은 진행 결과를 확인하고 END_CHANGE(ABANDONED)를 보낸다. 이미 공개된 최초·추가 성공 출처는 유지하고 v2 REPLACE가 미커밋이면 기존 API를 유지한다. END 결과가 불확실하거나 ALREADY_APPLIED면 10.5의 재조정으로 해결하며 해당 프로젝트의 새 변경 적용·승인을 대기시킨다. 문제 표시는 기다리지 않는다.

사용자 재확인은 기존 `/sources/{s}/recheck`를 사용하고, 종료가 아직 확인되지 않았으면 요청 접수 후 대기 상태로 유지한다. 종료 확인 후 Analysis가 승인 설정·샘플로 재검증한다. 통과하면 동일 승인 정의를 다시 사용해 새 change_job_id로 BEGIN한다. 최초 v1이 아직 비공개면 INITIAL(expected_schema_version=null)로 다시 시작해 준비된 동일 v1을 확인한다. 이미 v1이 공개됐고 해당 최초 출처가 실패 상태라면 SOURCE_CHANGE(expected_schema_version=1)의 add 대상으로 승인된 해당 project_source_id를 재시도하고 APPROVED_SOURCE_APPEND로 정상 결과를 공개한다. 이미 공개한 다른 출처를 덮어쓰지 않는다. 스키마 리비전이 0건으로 중단됐다면 기존 승인 후보가 그대로 유효한 경우 새 SCHEMA_REVISION으로 최종 출처를 다시 준비하고 전부 성공할 때만 전환한다. 승인된 스키마 자체가 달라지면 기존 사용자 승인 흐름을 거친다.

재확인 대신 삭제를 고르면 사용자는 후보를 편집할 수 있지만 기존 END 확인 전 최종 승인은 보류한다. 종료 후 승인된 삭제를 새 SOURCE_CHANGE로 적용한다. 공개 전 최초와 마지막 출처 삭제는 19.3의 버전·빈 프로젝트 분기를 따른다. 공유 출처의 0건은 영향 프로젝트별 변경 상태를 각각 종료·조정하며 다른 프로젝트의 성공 결과를 되돌리지 않는다. 실패 출처 재확인 결과가 기존 스키마 유지의 단순 운영 복구라면 15.8~15.9의 규칙 동기화와 일반 재수집을 따른다.

### 10.8 적용 완료 결과 재조정

REPLACE의 데이터 커밋 여부가 불확실하면 Control은 기존 dataset.apply REPLACE를 원래 요청 envelope·job_id·payload 그대로 재전달한다. Ingestion은 데이터 변경을 다시 수행하지 않고 해당 job receipt와 결과 Outbox의 원래 dataset.applied 및 event_id를 재발행한다. 기존 결과와 그 event_id는 재전달·DLQ 재처리 가능 기간 이상 보존한다. ABANDONED 응답의 ALREADY_APPLIED와 applied_job_id도 같은 복구 절차로 연결된다.

Control은 진행 중인 change_job_id와 승인 대상·applied_job_id를 확인하고 해당 작업을 결과 재조정 대상으로 표시한다. 같은 event_id가 이미 수신 기록에 있더라도 단순 중복으로 버리지 않고 기존에 기록된 결과를 이 재조정에서 한 번 검토한다. payload가 기록되지 않았다면 같은 원본 event_id의 재전달을 받아 job·변경·결과를 대조한다. 재조정 완료 표시는 Control DB에서 조건부로 기록하여 이전에 반영한 데이터·후속 작업을 다시 적용하지 않는다. Data DB의 실제 성공이 확인되면 Control 커넥터·상태를 동기화하고 새로운 END_CHANGE(COMPLETED) 자식 job으로 닫는다. 결과가 계속 불확실하면 기존 새 작업 보류를 유지한다.

## 11 중복·동시성·복구 공통 동작

1. Control은 출처별 활성 논리 작업을 DB에서 원자적으로 생성한다. 같은 출처의 다음 작업은 필요한 적재 종료까지 기다린다. 실패한 자식 작업도 종료로 집계하고 확인 필요 대상의 이후 실행 여부는 기존 정책으로 결정한다.
2. Analysis·Crawler는 command 수신 후 시도별 UUID를 만들어 Control 내부 API에서 job_id·attempt_id를 선점한다. DB 시간 기준 유효 lease와 SQS visibility를 실행 중 연장한다. 유효한 다른 시도가 있으면 실행하거나 요청을 삭제하지 않고 가시성을 연장한다. Control이 종료한 작업의 중복 command는 실행 없이 소비한다.
3. 두 서비스는 시도별 S3 경로에 파일과 전체 결과 이벤트를 저장하고 같은 시도의 event_id를 유지해 SQS 결과 큐로 발행한다. S3에 먼저 저장된 순서는 최종 채택 기준이 아니다. 결과 발행 성공 후에만 command를 삭제한다. 완료 결과는 payload.attempt_id를 반드시 포함한다.
4. Control은 완료 결과의 event_id 중복을 먼저 확인한다. 처음 받는 결과는 현재 유효 attempt_id·lease·작업 상태를 검사하고 Analysis에는 현재 후보 revision과 대상·취소 상태를 추가로 확인한다. 같은 DB 트랜잭션에서 결과 채택, 수신 기록, 참조·상태 변경과 후속 작업 Outbox를 확정한다. 시도 결과 파일의 key는 결과 종류·job_id·analysis_id(분석 시)·attempt_id로 S3 명세의 경로에서 결정한다. 실패나 기한·취소·revision 변경으로 무효가 된 결과는 업무 상태를 바꾸지 않고 안전하게 소비한다.
5. 동일한 결과가 채택 이후 재전달되면 lease가 그사이 만료됐더라도 기존 event_id 수신 기록을 기준으로 중복 처리한다. 미처리 결과가 lease 만료 후 도착하면 거절한다. 처리 중 Control 장애·DB 롤백은 결과 SQS 메시지를 삭제하지 않아 재전달한다.
6. Ingestion은 작업별 완료 기록·레코드 변경·결과 Outbox를 같은 Data DB 트랜잭션에 남긴다. DB 고유 제약과 쓰기 직렬화를 사용하며 위 Analysis·Crawler 시도 lease와 혼동하지 않는다.
7. 후보 수정·분석 결과 반영·승인 검사는 현재 revision을 조건으로 한 DB 갱신으로 수행한다. 재검증이 불필요한 변경은 출처별 현재 후보에 대한 검증 revision을 새 값으로 승계하되 불변 sample_ref와 샘플을 생성한 sample_revision은 기존 값을 보존한다. 승계는 필드·매핑·이동·추출·파싱과 샘플 해석에 영향이 없고 유효한 기존 검증인 경우에만 허용한다. 승인에서는 현재 후보 revision과 유효 검증 revision이 같은지 검사하고, sample_ref의 생성 revision이 다르면 이 승계 근거와 소유 범위를 검증한다. 두 요청이 같은 revision을 읽더라도 하나만 수정에 성공하게 한다. 별도 검증 중 플래그가 없어도 검증 결과와 현재 revision의 일치가 필요하다.
8. 예상한 자식 작업 목록을 먼저 기록하고 해당 목록의 종료 결과를 집계한다. 수신한 성공 이벤트 수만 세면 중복 이벤트가 준비 출처 수를 부풀릴 수 있다.
9. Control이 이미 채택·종료한 작업의 command 재전달은 재실행 없이 소비한다. Control 점검은 미완료 작업의 lease 만료 시 동일 job_id·동일 payload를 Outbox로 재발행한다. 시작 기한 expires_at을 넘으면 새 시도를 시작하지 않는다. 이미 시작한 시도는 expires_at만으로 버리지 않고 유효 lease·작업 상태·별도 종료 기한을 보며, 유효 시도가 없고 재처리할 수 없을 때 실패·확인 필요로 종료한다.
10. 사이트의 일시 네트워크 오류는 기존 최대 2회·2초/5초 재시도를 마치기 전 업무 FAILED를 내지 않는다. S3·SQS 기술 오류가 해소되지 않으면 command를 성공 삭제하지 않고 재전달·DLQ·기한 처리를 따른다. 새 시도는 새 attempt_id로 작업하며 Control이 유효한 시도의 성공 또는 실패만 채택한다. 규칙 복구는 Control이 새 job_id로 생성한다.
11. DLQ 소비와 작업 기한 점검은 Control이 담당한다. 중복 command의 DLQ 도착만으로 유효한 다른 실행을 종료하지 않는다. Control은 원장의 현재 lease·작업 상태를 확인하고 실행이 없으면 재발행 가능 여부를 판단한다. 최종 종료 뒤 늦은 결과는 적용하지 않는다.
12. 스냅샷은 참조하는 적재·재처리 작업 종료 전에는 삭제하지 않는다. 최신 한 세트 정책에도 진행 중 참조 객체는 예외 보관한다. 부분 성공 프로젝트의 적재는 계속 진행하되 전체 준비가 필요한 변경은 적용하지 않는다.
13. source_id가 달라도 같은 도메인일 수 있다. 기존의 도메인별 동시 요청 1개·최소 1초 간격은 출처별 활성 작업 제약과 별도로 조정해야 한다. 다중 Crawler에서는 개별 프로세스의 sleep만으로 보장되지 않는다. Analysis·Crawler가 공통 Redis의 정규화 호스트별 임대와 요청 간격을 공유한다. 구체적 임대·종료 조건은 19.7을 따른다. 드문 동일 메시지 중복 실행까지 엄격하게 사이트 요청 동시성을 보장하려면 이 실행 조정도 필요하므로, 중복 실행 비용을 허용한다는 합의가 절대적인 동시 요청 금지까지 보장한다는 뜻은 아니다.

### 11.1 Analysis·Crawler 실행 선점 내부 HTTP

서비스 신원을 인증한 Analysis·Crawler만 Control 내부 경로를 호출한다. 사용자 API나 새 큐는 추가하지 않는다.

- `POST /internal/v1/jobs/{job_id}/attempts:claim`: `{ "attempt_id": "<uuid>" }`를 보낸다. DB 행을 잠그고 현재 상태·시작 기한·lease를 판단한다. `200`의 `status`는 `ACQUIRED`, `BUSY`, `TERMINAL`, `EXPIRED` 중 하나이며 `ACQUIRED`에는 `lease_expires_at`, `BUSY`에는 현재 `lease_expires_at`을 포함한다. 같은 유효 시도의 재요청은 기존 선점을 그대로 반환해 응답 유실에 대응한다. 다른 시도가 유효하면 BUSY다. 이미 종료된 작업은 TERMINAL, 시작 기한이 지난 미시작 작업은 EXPIRED다. Control 장애는 503이며 소유 여부를 추정하지 않는다.
- 선점 응답 예: `{ "status": "ACQUIRED", "lease_expires_at": "2026-11-06T00:02:00Z" }`. BUSY에도 만료 시각을 넣고 TERMINAL·EXPIRED에서는 `lease_expires_at`을 null로 반환한다. 응답은 캐시하지 않는다.
- `POST /internal/v1/jobs/{job_id}/attempts/{attempt_id}:heartbeat`: `{}`를 보낸다. DB 시간 기준 현재 시도·실행 상태·미만료 lease와 별도 실행 종료 기한을 함께 검사해 조건부 갱신한다. 성공 `200`은 `lease_expires_at`, 이전 시도·만료·취소·종료는 `409`를 반환한다. 장애나 응답 유실 시 유효성을 확인할 수 없으면 새 사이트 요청과 결과 발행을 멈추고 재확인하며 소유권을 가정하지 않는다. lease 길이·heartbeat 간격·SQS visibility는 운영 설정이되 실행 중 갱신한다.
- BUSY 수신자는 즉시 재호출하는 대신 해당 SQS 수신 핸들의 visibility를 연장하며 삭제하지 않는다. 이미 채택·취소된 TERMINAL 또는 EXPIRED는 실행하지 않고 해당 수신 핸들을 삭제한다. Control의 주기 점검은 command가 이미 삭제됐거나 중복 수신이 DLQ에 갔어도 만료된 미완료 작업을 원장에서 찾아 시작 기한 내 동일 command를 Outbox로 재발행한다. 유효한 다른 시도나 이미 끝난 작업은 재발행하지 않는다. 재발행과 선점은 원장 상태로 직렬화한다. 같은 job의 재발행 대기 Outbox는 한 건만 두고 반복 점검에서 중복 생성하지 않는다. 메시지가 발행된 뒤에도 새 실행이 시작되지 않으면 재전달 상태를 점검해 다시 발행할 수 있다.
- 유효 lease와 결과 큐 지연은 별개다. 결과 발행 후에도 Control 수신 전 lease가 만료되면 결과는 거부될 수 있고 점검이 새 시도를 예약한다. SQS 발행 성공은 Control 채택 확인이 아니다. 유효한 lease의 시도가 결과를 완성했더라도 수신 전에 만료되면 재실행할 수 있다. 동일 event_id의 채택된 재전달은 우선 중복 기록으로 처리한다. 0건 issue 등 중간 이벤트도 같은 시도 확인을 거쳐 즉시 반영한다. 완료 결과가 먼저 도착해 뒤늦은 issue가 거절되더라도 완료 결과에 같은 오류·0건 범위가 담겨 있어 Control이 문제 표시와 기존 중복 방지 알림을 처리한다.
- 결과 채택은 DB에서 job 행·analysis 후보 행 또는 대상별 revision 상태를 잠그고 DB 현재 시각으로 유효성을 검사하는 조건부 트랜잭션이다. revision이 변경된 Analysis 결과는 이전 후보를 덮어쓰지 않고 종료한다. 현재 후보에서 재검증이 필요한 변경만 기존 규칙에 따라 새 분석 작업을 요청하며, 검증을 승계할 수 있는 변경은 불필요하게 재검증하지 않는다. 공유 이동 복구는 모든 영향 프로젝트 기준·revision이 유효해야 적용한다. 외부 사이트 접근과 LLM 호출의 중복 실행 방지는 보장하지 않는다.

### 11.2 필수 장애·경합 검증 시나리오

1. A의 lease가 만료된 뒤 B가 선점하고 A가 먼저 S3에 결과를 저장해도 A는 채택되지 않고 B 결과는 채택된다.
2. 결과 발행 후 command 삭제를 마쳤으나 결과 큐 지연으로 lease가 만료되면, Control 점검이 시작 기한 내 같은 job을 재발행한다. 지연 결과는 채택하지 않는다.
3. 유효 결과를 DB에 채택한 뒤 Control이 결과 메시지 삭제 전 중단되면 재전달된 동일 event_id는 lease 만료 여부보다 먼저 중복 확인한다. 후속 Outbox는 한 번만 생성된다.
4. Analysis 실행 중 후보 revision이 수정되거나 취소되면 이전 결과·샘플 참조는 활성 후보를 덮지 않는다. 기존 검증 승계가 가능한 변경은 승계하고 필요한 경우에만 새 분석을 요청한다.
5. 완료 결과가 0건 issue보다 먼저 처리돼 늦은 issue가 거절되어도 완료 결과에서 같은 문제를 표시하고 이메일은 기존 중복 방지 규칙을 적용한다.
6. 동일 command의 한 사본이 DLQ로 가도 다른 유효 시도가 실행 중이라면 작업 전체를 종료하지 않는다. 모든 시도가 사라지면 Control 원장 기준으로 재요청 또는 기한 종료한다.
7. S3 저장·SQS 발행 실패, Control DB 커밋 전후 중단, 선점·heartbeat 응답 유실 시 허용된 실행만 최종 채택하고 필요한 작업이 다시 시작되는지 확인한다.

## 12 SQS로 만들지 않은 통신과 남은 범위

### 12.1 내부 HTTP 계약 초안

Control이 수집·분석·복구에 사용할 현재 공개 스키마는 Data 측 내부 조회로 확보한다. 요청은 프로젝트 식별자, 응답은 공개 schema_version, schema_metadata_revision과 필드 정의를 포함한다. 실패는 오류로 반환하며 빈 스키마를 발행하지 않는다. Ingestion의 POST /internal/v1/project-schemas:query로 묶음 조회한다. Data API는 공개 스키마를 Control에 요청하지 않고 Data DB에서 읽는다. 권한 확인은 기존 별도 경로를 따른다.

최신값 조회 경로는 기존 스펙의 내부 API와 일치시켰다. 삭제 영향 조회 경로는 이 문서의 제안이다. 기존 사용자 API 경로는 변경하지 않는다.

| 통신 | 경로 제안 | 요청·응답 |
|---|---|---|
| Control → Ingestion 최신값 조회 | POST /internal/v1/project-sources/latest-records:query | source_id와 targets의 project_id·project_source_id 쌍 → 대상별 최신 필드값 0~3건. 실패는 정상 빈 목록과 분리 |
| Control → Ingestion 삭제 영향 조회 | POST /internal/source-data/deletion-preview | project_id·project_source_id → 삭제 대상 레코드 수. 실제 삭제 아님 |
| Analysis 복구 기준 확보 | 위 최신값 조회를 Control이 이용 | Control이 조회 후 analysis.execute.recovery_baselines에 포함하는 안 |

최신값 조회 응답 예시:

```json
{
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "projects": [
    {
      "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "schema_version": 1,
      "dataset_revision": 18,
      "latest_records": [
        {
          "field_values": {
            "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "장학금 신청 안내"
          }
        },
        {
          "field_values": {
            "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "수강 신청 일정 안내"
          }
        },
        {
          "field_values": {
            "ba4f9e1e-ae8e-4a3c-9261-dca457848148": "도서관 운영 시간 변경"
          }
        }
      ]
    }
  ]
}
```

### 12.2 일반 운영 통신과 추가 계약의 위치

| 항목 | 현재 상태 | 필요한 결정 |
|---|---|---|
| 사용자 HTTP API | 기존 API 명세 존재 | 후보 수정·승인 요청에 기준 revision을 일관되게 사용 |
| Auth 이메일 인증·초기화 | 사용자 API와 6자리 인증번호 합의 | 이메일 서비스 호출 방식·전송 실패 재처리. 인증번호를 위 수집 메시지에 싣지 않음 |
| 회원 탈퇴 | 계정·소유 프로젝트·데이터 삭제 합의 | 15.17·16.5의 내부 HTTP 접수·완료 통지 계약을 사용한다. 탈퇴 중 원본 계정 상태 확인 및 인증 차단은 19.5를 따른다 |
| 프로젝트 삭제 | 사용자 기능과 데이터 전체 정리 필요 | 15.16·16.4에서 project-data.delete / project-data.deleted로 구체화. 새 큐는 추가하지 않음 |
| API 키 폐기·권한 캐시 | Data가 키 원본을 소유 | 폐기·조회 요청에서 Data DB 원본을 확인한다. 폐기 전달용 별도 큐는 두지 않는다. |
| 버전 전환 조회 | 새 데이터 공개 필요 | MVP에서 Data DB 동일 읽기 스냅샷을 사용하고 공개 스키마 캐시는 두지 않음 |
| 사용자 이메일 알림 | 대시보드·프로젝트별 이메일 설정 합의 | 발송 API/Outbox·중복 방지 구현. 이메일만을 위한 상시 서비스 추가는 필수 아님 |
| Scheduler 등록·정리 | Control 책임 | AWS API로 수행. 비활성화 후 이미 전달된 정기 신호는 Control이 무시 |

### 12.3 최종 확인이 필요한 항목

1. parser.code는 세 Java 서비스의 공통 파서 계약을 사용하며 별도 격리 없이 각 서비스 JVM에서 실행한다.
2. 생성 코드의 공통 Java 21 환경과 입출력 ABI 및 결과 일치를 구현·검증한다. 별도 격리·자원 강제 제한과 사용자 코드 편집 UI는 MVP에 추가하지 않는다.
3. 실패한 레코드는 부분 저장과 normal_extracted_record_count에서 제외한다. 반복 수집의 정상 결과는 적용할 수 있다. 출처 추가는 실패한 출처를 적용하지 않으며, 스키마 리비전은 전체 출처 준비가 성공해야 전환한다.
4. 20.2의 목록 종료·이동 실패 기준과 무한 스크롤의 새 항목 식별을 실제 사이트에서 통합 검증한다.
5. DB 1~2건은 전부 비교하며 0건의 승인 샘플이 부족하면 자동 적용하지 않는다. 후보 검증은 최초 포함 최대 3회다.
6. 공개 포인터는 Data DB가 소유하며 MVP에서 공개 스키마 캐시를 사용하지 않는다. Control과 Data DB를 한 번에 원자 갱신한다고 가정하지 않는다.
7. 비활성 출처·프로젝트 쓰기 차단 상태의 운영 보관 기간과 16.5의 계정 정리 HTTP 재처리 구현을 검증한다.
8. DLQ 담당은 Control이다. 기술 재전달 한도·작업 기한과 리비전/복구 큐 운영 값은 환경 설정이다.

## 13 참고 근거

- 흐름·그림 기준 문서: [시스템 흐름 설명서](design/시스템_흐름_설명서.docx)
- 상세 전달 순서: service-message-flows.md 3.1~3.20
- 제품·구성 보조 문서: [설계제안서](design/설계제안서.docx)
- 프로젝트 결정 로그: spec-decision-log.md
- AWS SQS visibility: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html
- AWS Outbox: https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
- AWS S3 조건부 쓰기: https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html
- Oracle Java 격리 관련 안내: https://docs.oracle.com/en/java/javase/24/security/security-manager-is-permanently-disabled.html

이 문서는 수집·분석·적재 메시지의 구현 출발점이다. 처리 서비스의 목표 기술 스택은 Java 21·Spring Boot이며 제안서의 기술표에도 반영한다. 현재 워커는 Java 연결 확인 골격이며 실제 메시지 소비와 파서는 아직 구현하지 않았다. 위의 미정 항목을 이미 구현되거나 기존에 합의된 결정으로 취급하지 않는다.

## 14 단순화 적용과 유지한 항목

### 14.1 이번 합의로 적용한 내용

| 항목 | 적용한 계약 |
|---|---|
| 파서 | parser.code만 전달하며 별도 파서 ID·버전·artifact 정보를 넣지 않는다. |
| 준비 결과 참조 | 준비 작업의 job_id로 식별한다. dataset.apply에서는 prepared_results[].prepared_job_id로 참조하며 별도 UUID를 생성하지 않는다. |
| 출처 삭제 | 삭제만 있는 변경도 dataset.apply / dataset.applied를 사용한다. 기존 두 삭제 전용 메시지는 제거했다. |
| 운영 통계 | 신규·중복·실패·삭제·전체 건수와 최신 기준 일치 건수는 선택 항목이다. 정상 추출 0건 판단에 필요한 normal_extracted_record_count와 작업 결과 status·error는 유지한다. |

### 14.2 유지하기로 한 내용

- parent_job_id는 바로 위 작업, change_job_id는 승인 변경 전체를 가리킨다. 수집→적재처럼 단계가 이어지면 다를 수 있으므로 유지한다.
- expires_at은 수신 서비스가 오래된 미실행 요청을 실행하지 않도록 판단하기 위해 유지한다. 작업 실행 시간 제한과 구분한다.
- 후보·커넥터·이동 revision과 schema_version·dataset_revision은 역할이 다르므로 유지한다.
- 실행한 버전 확인에 쓰는 메타데이터와 스냅샷의 불변 참조, 목록–상세 연결 정보는 유지한다.
- pagination 등 브라우저 세부 옵션은 실제 실행 구현에서 추가 검토하며 이번 단순화에서 임의로 바꾸지 않는다.

### 14.3 현재 메시지 목록

| 종류 | 송신 → 수신 | 설명서 흐름·그림 |
|---|---|---|
| collection.schedule.triggered | Scheduler → Control | 5, 20 |
| analysis.execute | Control → Analysis | 1, 2, 3, 8, 9, 10, 11 |
| analysis.completed | Analysis → Control | 1, 2, 3, 8, 9, 10, 11 |
| collection.execute | Control → Crawler | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| collection.completed | Crawler → Control | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| schema.prepare | Control → Ingestion | 4, 13 |
| schema.prepared | Ingestion → Control | 4, 13 |
| ingestion.execute | Control → Ingestion | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| ingestion.completed | Ingestion → Control | 4, 5, 6, 7, 8, 9, 10, 11, 13 |
| job.issue.detected | Crawler / Ingestion → Control | 7, 8, 9, 10 |
| dataset.apply | Control → Ingestion | 11, 12, 13 |
| dataset.applied | Ingestion → Control | 11, 12, 13 |
| project-data.delete | Control → Ingestion | 16, 17 |
| project-data.deleted | Ingestion → Control | 16, 17 |

분석·수집·적재·설정 적용의 기존 10종에 프로젝트 전체 데이터 삭제 2종과 스키마 준비 2종을 더해 현재 14종이다. 삭제만 있는 출처 변경은 여전히 dataset.apply로 통합한다. 15절은 HTTP·S3·DB·외부 서비스 통신까지 포함해 모든 상황을 연결한다. 미정 경로는 명시하며, 목록에 있다는 이유로 구현 또는 세부 결정이 완료되었다고 보지 않는다.

마지막 열은 설명서의 절·그림 번호이며, JSON 형식의 절 번호와 다르다. 전체 흐름 대응표는 17.1, 비동기 공통 처리는 설명서 19절·그림 19를 참고한다.

## 15 기능·예외별 전송 흐름

이 절의 각 행은 실제 전송 또는 전송 전후의 내부 처리 상황이다. 같은 메시지가 여러 기능에 반복 등장하는 것은 재사용을 뜻한다. 각 기능마다 새로운 큐·메시지를 만들라는 뜻이 아니다. 순서가 아닌 분기는 단계 번호에 a·b·c를 붙였다.

- [기존]: 기존 스펙·시퀀스 및 후속 합의를 연결한 동작.
- [제안]: 통신 경로·구현 계약을 이번 문서에서 보완한 동작. 기존 합의로 단정하지 않는다.
- [미정]: 전송 상황은 필요하지만 세부 방식을 정하지 않은 항목. 목록에서 생략하지 않는다.
- SQS의 모든 명령·결과는 앞의 공통 envelope를 사용한다. 표에는 payload의 핵심 조건만 적는다.
- 사용자 화면의 결과 표시는 상태 조회에 대한 HTTP 응답이다. 별도 푸시 서비스나 WebSocket을 추가한 것이 아니다.
- Analysis↔LLM과 사이트 접근·S3/DB 호출도 추적하지만, 이를 SQS 메시지 종류로 세지 않는다.
- 공통 반복은 15.4~15.6의 수집·적재, 15.3의 재검증·승인, 15.19의 재전달 처리에 연결한다.

### 15.1 초기 분석과 공통 스키마 추천

대응 설명서: 1절 「초기 분석과 공통 스키마 추천」·그림 1. 전달 순서: service-message-flows.md 3.1.
[기존] 공동 추천은 Analysis가 출처별 분석에 이어서 실행한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control | HTTP POST /control/v1/projects | Idempotency-Key, name, purpose, source_urls, email_enabled | 프로젝트와 URL별 ProjectSource 생성 |
| 2 | Control → 웹 | HTTP 201 | project_id | 프로젝트 ID 보관 후 출처 목록 조회 |
| 2a | 웹 ↔ Control | HTTP GET /control/v1/projects/{p}/sources | URL별 project_source_id | 분석 대상 출처 ID 확보 후 분석 시작 |
| 3 | 웹 → Control | HTTP POST /control/v1/projects/{p}/analyses | project_source_ids, mode=initial | analysis_id·후보 revision·job_id 예약. 진행 중 동일 분석은 기존 작업 반환 |
| 4 | Control → 웹 | HTTP 202 | analysis_id, job_id | 로딩 및 주기적 분석 조회. 생성 요청 처리와 분석 완료 응답을 분리 |
| 5 | Control → Analysis | SQS analysis.execute | purpose=INITIAL, sources, collection_purpose, revision | 대상 출처별 분석 시작 |
| 6 | Analysis ↔ 사이트 | 브라우저 요청·응답 | 시작 URL·목록·상세·다음 목록 후보 | 렌더링 DOM 확보. 추출·탐색 후보 검증 |
| 7 | Analysis ↔ LLM | 외부 API 요청·응답 | DOM의 필요한 정보·수집 목적·검증 실패 관찰 | 필드·선택자·이동·파싱 코드 후보 생성. 부적합하면 예산 내 재생성 |
| 8 | Analysis → S3 | 객체 저장 | 분석·출처별 중간 결과 | 성공 출처의 후보·샘플·매핑 정보를 모음 |
| 9 | Analysis ↔ S3·LLM | 객체 조회·외부 API | 출처별 중간 결과 | 공통 스키마 추천. Control에 별도 공동 추천 요청을 요구하지 않음 |
| 10 | Analysis ↔ 사이트 | 브라우저·HTTP 검증 | 공통 후보와 출처별 규칙 | 실제 목록·상세 이동·추출 검증 및 목록 HTTP 가능 여부 내부 확인. 상세는 브라우저 고정 |
| 11 | Analysis → S3 | 객체 저장 | analysis_id·revision·출처별 검증 샘플 | 저장 성공 후에만 승인 가능한 성공 결과 생성 |
| 12 | Analysis → Control | SQS analysis.completed | revision, candidate, source_results, sample_ref, intermediate_ref | 현재 분석·revision 대조 후 후보와 출처별 참조·상태 저장 |
| 13 | 웹 ↔ Control | HTTP GET /control/v1/projects/{p}/analyses/{a} | analysis_id | 진행 상태·후보·출처별 오류 조회 |
| 14 | Control ↔ S3 | 객체 조회·응답 | 현재 revision의 sample_ref | 분석 조회 응답에 샘플 포함 |
| 15a | Control → 웹 | HTTP 조회 응답 | status=READY (전체 또는 일부 성공); 출처별 status로 구분 | 성공 출처 후보와 실패 출처를 함께 표시. 수정·승인 또는 15.2 연결 |
| 15b | Control → 웹 | HTTP 조회 응답 | status=FAILED, 출처별 error | 확인 필요 표시. 재시도·제외 선택. 성공하지 않은 후보를 승인하지 않음 |

### 15.2 실패 출처 재시도와 제외

대응 설명서: 2절 「실패 출처 재시도와 제외」·그림 2. 전달 순서: service-message-flows.md 3.2.
[기존] 초기 분석 재시도와 운영 재확인의 공통 분석 부분을 재사용한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control | HTTP POST /control/v1/projects/{p}/analyses/{a}/sources/{s}/retry | 대상 project_source_id | 현재 후보·revision 확인. 동일 출처 진행 중이면 기존 job 반환, 아니면 새 작업 생성 |
| 2 | Control → 웹 | HTTP 202 | job_id | 작업·분석 조회로 상태 확인 |
| 3 | Control → Analysis | SQS analysis.execute | purpose=RETRY_SOURCE, current_candidate, sources, revision, intermediate_refs | 기존 출처별 참조와 해당 출처 재분석으로 현재 공동 후보에 맞춰 매핑·검증 |
| 4 | Analysis ↔ 사이트·LLM·S3 | 브라우저·외부 API·객체 저장 | 해당 출처·공동 후보·새 샘플 | 기존 사용자 수정 보존. Analysis 내부에서 공동 후보 반영안을 구성 |
| 5a | Analysis → Control | SQS analysis.completed | status=SUCCEEDED, candidate, source_results, revision | 현재 revision과 일치하면 대상 출처의 결과만 반영 |
| 5b | Analysis → Control | SQS analysis.completed | status=FAILED, source_results[].error | 실패 상태 유지. 사용자는 재시도 또는 제외 가능 |
| 5c | Control 내부 | 결과 적용 검사 | 반환 revision이 현재 후보와 다름 | 현재 후보를 덮지 않음. 최신 후보를 기준으로 새 작업·재검증 필요 |
| 6 | 웹 → Control | HTTP PATCH /control/v1/projects/{p}/analyses/{a}/candidate | expected_revision, excluded_project_source_ids | 제외를 후보에 반영하고 영향 범위에 필요한 검증을 서버가 판단 |
| 7 | Control → Analysis | 조건부 SQS analysis.execute | purpose=REVALIDATE, 새 revision | 필요한 경우만 제외 후 후보 검증. 검증 승계 가능하면 별도 메시지 없음 |
| 8 | 웹 ↔ Control | HTTP 분석 조회·approve | 현재 후보와 candidate_revision | 포함된 출처 결과를 확인하고 15.3 승인으로 진행 |

### 15.3 후보 수정과 재검증 및 승인

대응 설명서: 3절 「후보 수정과 재검증 및 승인」·그림 3. 전달 순서: service-message-flows.md 3.3.
[기존] 승인 가능한 revision 확인은 백엔드에서도 수행한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control | HTTP PATCH /control/v1/projects/{p}/analyses/{a}/candidate | expected_revision, fields, source_rules, excluded_project_source_ids | 조건부 DB 갱신으로 revision 증가. 동시 수정 충돌은 409 |
| 2a | Control → Analysis | SQS analysis.execute | purpose=REVALIDATE, 현재 후보, 영향 출처, 새 revision | 이동·추출·파싱·타입·HTTP 가능 여부를 검증 |
| 2b | Control 내부 | 검증 승계 | 재검증이 불필요한 변경 | 새 후보에 기존 검증을 승계하고 sample_revision·sample_ref와 승계 근거 유지. Analysis 메시지 없음 |
| 3 | Analysis → S3 | 객체 저장 | 새 revision의 검증 샘플 | 이전 샘플과 구분. 저장 실패는 검증 성공으로 반영하지 않음 |
| 4 | Analysis → Control | SQS analysis.completed | 목표 revision, validation_status, source_results | 현재 revision 일치 시에만 검증 결과 반영 |
| 5 | 웹 ↔ Control·S3 | HTTP 분석 조회 / Control의 S3 조회 | 현재 후보·샘플·출처별 실패 | 사용자에게 수정 결과 표시 |
| 6 | 웹 → Control | HTTP POST /control/v1/projects/{p}/analyses/{a}/approve | candidate_revision | 후보·검증 revision과 승인 조건을 원자적으로 확인 |
| 7a | Control → 웹 | HTTP 409 또는 422 | revision 충돌 또는 검증 미완료·실패 | 승인하지 않고 원인 표시. 수정·검증 흐름으로 복귀 |
| 7b | Control → 웹 | HTTP 202 | 승인·적용 상위 job_id | 승인 설정·샘플 연결 기록. 신규면 15.4, 출처 변경이면 15.11~12, 구조 변경이면 15.13 |

### 15.4 승인 후 최초 수집

승인된 v1 스키마를 Data DB에 프로젝트 최초 적용 작업 단위로 준비한 뒤 출처별 적재가 같은 정의를 사용한다. 성공 출처의 데이터는 공개하고 실패·0건 출처는 문제로 표시하며 성공 출처를 되돌리지 않는다. 스키마 준비는 기존 Ingestion 큐의 schema.prepare/schema.prepared를 사용한다. 최초 모든 출처가 실패하거나 정상 추출 0건이면 준비된 v1만으로 정상 운영 API를 공개하지 않고 확인 필요를 표시한다. 이후 한 출처라도 정상 적재되면 해당 성공 데이터를 공개한다. 공개 전 최초 복구는 공개 스키마 조회가 아니라 승인 v1 후보·커넥터와 승인 샘플을 적용 작업에서 확보한다.

대응 설명서: 4절 「승인 후 최초 수집」·그림 4. 전달 순서: service-message-flows.md 3.4.
[기존] 승인 후 즉시 수집·적재한다. 정기 일정의 등록·실행·정리는 설명서 20절·그림 20을 참고한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Control 내부 | 승인 설정·작업 생성 | source_id, 활성 프로젝트와 승인 규칙 | 실행 중 같은 출처가 있으면 새 연결 최초 수집을 후속 예약 |
| 1-BEGIN | Control → Ingestion | SQS dataset.apply BEGIN_CHANGE | 승인 change_job_id·대상·버전 | OPEN 확인 후 schema.prepare |
| 1-OPEN | Ingestion → Control | SQS dataset.applied | 시작 결과 | 성공 시 후속 수집·준비 |
| 1a | Control → Ingestion | SQS schema.prepare | 최초 승인 change_job_id·v1 스키마 정의 | Data DB에 비공개 스키마 준비 |
| 1b | Ingestion → Control | SQS schema.prepared | 준비 결과·change_job_id | 성공한 경우에만 최초 수집 요청; 실패 시 오류 처리 |
| 2 | Control → Crawler | SQS collection.execute | execution_reason=INITIAL, 신규 대상 collection_mode=FULL, latest_records=[] | 첫 목록부터 수집. FULL 대상은 최신값 일치 조기 종료 제외. 최대 300초 |
| 3 | Crawler ↔ 사이트 | HTTP·브라우저 | 승인된 목록·상세·다음 목록/스크롤 규칙 | 목록은 검증된 방식으로, 상세는 브라우저로 공유 수집. 오류는 15.7~10으로 분기 |
| 4 | Crawler → S3 | 시도별 manifest·객체 및 결과 저장 | 해당 시도의 불변 snapshot_ref·결과 이벤트 | 저장 자체는 채택이 아님. Control이 다음 단계에서 유효 시도를 확인해 채택 |
| 5 | Crawler → Control | SQS collection.completed | 종료 사유, project_results, snapshot_ref | 적재 가능한 대상별 작업 생성 |
| 6 | Control → Ingestion | SQS ingestion.execute | mode=APPEND, 최초 승인 change_job_id·프로젝트·출처·규칙·snapshot_ref | 신규 프로젝트 데이터 추출·검증·역순 저장 |
| 7 | Ingestion ↔ S3·DB | 객체 조회·DB 트랜잭션 | 스냅샷과 커넥터 | 기존값 비교·신규 저장·receipt·결과 Outbox 기록 |
| 8 | Ingestion → Control | SQS ingestion.completed | status, normal_extracted_record_count, dataset_revision | 프로젝트 출처 최초 수집 상태 갱신. 0건·실패는 기존 복구 흐름 |
| 9 | 웹 ↔ Control | HTTP 프로젝트·작업 조회 | 프로젝트·출처별 준비·완료·오류 | 대시보드 표시. 연결된 다른 프로젝트 정보는 숨김 |
| 종료 요청 | Control → Ingestion | SQS dataset.apply END_CHANGE | COMPLETED 또는 ABANDONED | 실제 커밋·CLOSED 확인 |
| 종료 확인 | Ingestion → Control | SQS dataset.applied | 종료 결과·실제 커밋 | 결과 동기화 후 다음 변경 허용 |


### 15.5 정기 반복 수집

작업 구성 전 Control은 Data 측에서 대상 프로젝트별 공개 스키마·버전을 조회하고 Control의 활성 커넥터와 결합한다. 조회 오류는 빈 스키마로 처리하지 않는다. 내부 조회 형식은 12.1의 project-schemas:query다.

대응 설명서: 5절 「정기 반복 수집」·그림 5. 전달 순서: service-message-flows.md 3.5.
[기존] 최신값 비교 기준은 모든 대상 프로젝트에 대해 확보한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Scheduler → Control | SQS collection.schedule.triggered | source_id, scheduled_at | 중복 신호·활성 연결·실행 중 출처 확인 |
| 2a | Control 내부 | 작업 조정 | 같은 출처가 이미 실행 중 | 새 논리 작업 중복 생성 방지. 포함되지 않은 신규 대상은 후속 예약 |
| 2b | Control → Ingestion | 내부 HTTP 최신값 조회 | source_id, targets(project_id, project_source_id) | 프로젝트·출처별 최신 저장 사용자 필드값 최대 3건 조회 |
| 3a | Ingestion → Control | HTTP 200 | projects[].latest_records, project_source_id, schema_version | 0건은 정상 응답이며 해당 대상 FULL 수집 |
| 3b | Ingestion → Control | HTTP 오류 응답 | error.code, message, details | 빈 목록으로 바꾸지 않음. 비교 기준 없이 임의 수집하지 않고 기술 오류 재처리 |
| 4 | Control → Crawler | SQS collection.execute | execution_reason=SCHEDULED, projects[].규칙·최신값 | 대상별 INCREMENTAL/FULL 기준으로 비교 |
| 5 | Crawler ↔ 사이트 | 목록·상세·스크롤 | 추출 순서 5개 묶음 또는 페이지 단위 | 프로젝트별 기준 충족 상태 유지. 모두 충족 또는 끝·시간 제한 종료 |
| 6a | Crawler → S3 | 객체 저장 | 최종 스냅샷과 manifest | 정상 확정된 참조 확보. 전혀 변경이 없으면 새 정상 세트로 교체하지 않음 |
| 6b | Crawler → Control | SQS collection.completed | 종료 사유, snapshot_ref, project_results | 적재 가능 대상만 다음 작업 생성 |
| 7 | Control → Ingestion | SQS ingestion.execute | mode=APPEND, 적재 가능한 프로젝트별 요청 | 한 공유 스냅샷에 프로젝트별 커넥터 적용 |
| 8 | Ingestion → Control | SQS ingestion.completed | 프로젝트별 결과, 정상 추출 건수, 데이터 revision | 자식 작업 집합을 기준으로 종료 집계. 부분 실패는 해당 대상만 복구 |

### 15.6 프로젝트와 출처의 수동 수집

Control은 반복 수집과 같이 Data 측 공개 스키마를 조회해 현재 버전의 커넥터와 결합한다. 조회 실패 시 작업 구성을 보류한다.

대응 설명서: 6절 「프로젝트와 출처의 수동 수집」·그림 6. 전달 순서: service-message-flows.md 3.6.
[기존] 수동 요청은 공유 출처 수집을 프로젝트별로 중복 수행하는 요청이 아니다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | 웹 → Control | HTTP POST /control/v1/projects/{p}/collect | 소유 프로젝트 | 프로젝트 대표 작업과 대상 공유 출처 작업 연결 |
| 1b | 웹 → Control | HTTP POST /control/v1/projects/{p}/sources/{s}/collect | 소유 프로젝트 출처 | 해당 공유 출처 수집을 요청 |
| 2a | Control 내부 | 실행 중 작업 확인 | 요청 프로젝트가 현재 작업에 포함 | 현재 실행 결과에 연결. 새 작업 중복 생성하지 않음 |
| 2b | Control 내부 | 후속 작업 예약 | 현재 작업에 요청 프로젝트가 없음 | 현재 수집·관련 적재 종료 뒤 해당 프로젝트를 포함한 작업 예약 |
| 2c | Control → Ingestion | 내부 HTTP 최신값 조회 | 수집할 출처의 전체 활성 프로젝트 | 15.5와 같은 정상 0건·조회 실패 구분 |
| 3 | Control → Crawler | 우선 큐 collection.execute | execution_reason=MANUAL, 규칙과 기준값 | 미실행 대기 작업에 우선순위 적용. 이미 실행 중인 정기 작업은 중단하지 않음 |
| 4a | Crawler → Control | SQS collection.completed | 수집 종료 사유·스냅샷·프로젝트별 적재 가능 여부 | 대상별 적재 요청 준비 |
| 4b | Control → Ingestion | SQS ingestion.execute | mode=APPEND, 대상 프로젝트·규칙·snapshot_ref | 프로젝트별 최종 추출·기존값 비교·저장 |
| 4c | Ingestion → Control | SQS ingestion.completed | status·normal_extracted_record_count·dataset_revision | 공유 결과를 요청자 소유 프로젝트 범위로만 집계 |
| 5 | Control → 웹 | HTTP 작업 조회 응답 | 대표 또는 출처별 job_id와 현재 상태 | 일부 출처가 실패해도 나머지를 처리하고 실패한 출처를 표시 |

수동 수집 요청의 HTTP 202는 접수 직후 job_id와 함께 반환한다. 마지막 행은 수집·적재 중 또는 이후의 상태 조회 응답이며, 작업 완료까지 처음 요청을 대기시키지 않는다.

### 15.7 접근과 시스템 오류 처리

대응 설명서: 7절 「접근과 시스템 오류 처리」·그림 7. 전달 순서: service-message-flows.md 3.7.
[기존] 내부 재시도와 SQS 재전달을 구분한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | Crawler → Control | SQS job.issue.detected 또는 collection.completed의 error | SITE_UNAVAILABLE, BROWSER_FAILED, SNAPSHOT_WRITE_FAILED | 문제 범위와 작업 종료 여부 기록 |
| 1b | Ingestion → Control | SQS 결과 | SNAPSHOT_READ_FAILED 등 DB에 결과를 기록할 수 있는 오류 | 유효한 스냅샷이 있는지 확인 |
| 1c | Ingestion / Control | 요청 재전달·DLQ·기한 점검 | DB 접속 불가로 receipt·Outbox 기록 불가 | 성공 삭제하지 않고 재처리; 결과 이벤트의 발행을 보장한다고 가정하지 않음 |
| 2a | 실행 서비스 → 외부 대상 | 제한된 내부 재시도 | 네트워크·408·429·5xx, 기존 2초/5초 최대 2회 | 동일 실행 예산 내 재시도. 개별 시도마다 새 Control 작업을 만들지 않음 |
| 2b | 실행 서비스 → Control | 최종 결과의 error | 401·403·캡차·명시적 차단 | 우회·사이트 접근 재시도 없이 중단. Control에 확인 필요 전달 |
| 3a | Control → Crawler | SQS collection.execute | 재수집이 필요한 일시 오류, 새 명시적 작업 | 스냅샷 확보부터 다시 수행. SQS 단순 재전달이면 기존 job_id 유지 |
| 3b | Control → Ingestion | SQS ingestion.execute | 같은 유효 snapshot_ref, 동일 규칙 | 화면 재방문 없이 읽기·적재 재시도. 기술 재전달은 같은 작업 |
| 4 | Crawler 또는 Ingestion → Control | 각 completed 이벤트 | 성공·실패, 영향 범위 | 성공하면 이후 수집/적재 계속. 재시도 한도 소진은 종료·문제 표시 |
| 5 | Control → 웹·이메일 | 상태 조회 응답·설정에 따른 이메일 | 최종 실패 원인 | 리비전이면 전체 적용 보류·기존 API 유지. 규칙 오류로 관찰되면 15.8~9 |

### 15.8 공유 이동 규칙 자동 복구

Control은 영향 대상마다 Data의 공개 스키마 또는 공개 전 승인 준비 v1을 조회하고 Control 커넥터·DB 최신값 또는 승인 S3 샘플을 Analysis 요청에 모은다. 조회 실패는 기준 없음이나 비공개로 바꾸지 않는다.

대응 설명서: 8절 「공유 이동 규칙 자동 복구」·그림 8. 전달 순서: service-message-flows.md 3.8.
[기존 + 구현 보완] 복구 기준은 Control이 조회해 분석 요청에 전달하는 안이다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Crawler → Control | job.issue.detected + collection.completed | scope=SOURCE, NAVIGATION_FAILED, 단계·영향 출처 | 공유 출처의 대상 수집 보류. 현재 실행 종료를 확인하고 후속 복구 조정 |
| 2a | Control → Ingestion | 내부 HTTP 최신값 조회 | source_id, targets(project_id, project_source_id) | 최신 저장 필드값을 조회 |
| 2b | Ingestion → Control | HTTP 200 또는 오류 | 대상별 latest_records 또는 정형 error | 조회 오류는 복구 기준 없음과 구분 |
| 3 | Control → Analysis | SQS analysis.execute | purpose=REPAIR_NAVIGATION, 현재 규칙, recovery_baselines, failure_context | 브라우저에서 새 이동 후보 실행·검증 |
| 4 | Analysis ↔ S3 | 승인 샘플 조회 | DB 0건 대상의 approved_sample_ref | 기준이 없거나 검증 불가이면 자동 적용하지 않음 |
| 5a | Analysis → Control | SQS analysis.completed | validation PASSED, schema_change_required=false, 모든 영향 대상 검증 | 규칙 revision 갱신 및 자동 적용. 사용자 승인 없이 재수집 |
| 5b | Analysis → Control | SQS analysis.completed | schema_change_required=true, 후보·샘플 | 해당 프로젝트별 사용자 검토·승인 흐름으로 연결 |
| 5c | Analysis → Control | SQS analysis.completed | FAILED 또는 UNVERIFIABLE, error | 확인 필요 상태 유지. 사용자 수정·재검증 안내 |
| 6a | Control → Crawler | SQS collection.execute | execution_reason=RECOVERY, 새 navigation_revision | 해당 규칙·대상으로 수집 실행 |
| 6b | Crawler → Control | SQS collection.completed | 작업 ID, snapshot_ref, 종료 사유·대상별 결과 | 영향 범위 재수집. 다른 프로젝트의 규칙을 임의로 변경하지 않음 |
| 7a | Control → Ingestion | SQS ingestion.execute | 새 규칙으로 확보한 스냅샷·대상별 커넥터 | 해당 적재·준비·적용·삭제 작업 실행 |
| 7b | Ingestion → Control | SQS ingestion.completed | 원래 job_id와 프로젝트·출처별 status·error, 해당 결과 필드 | 적재까지 성공해야 복구 완료 |
| 8 | Control → 웹·이메일 | 상태 조회 응답·필요한 이메일 | 완료 또는 확인 필요·승인 대기 | 재수집/적재 실패는 관찰된 오류 흐름으로 복귀 |

### 15.9 필드 커넥터와 파싱 오류 복구

Control은 대상의 공개 스키마 또는 공개 전 승인 준비 v1을 Data에서 조회하고 Control 커넥터·DB 최신값 또는 승인 S3 샘플과 함께 복구 요청에 담는다. 조회 실패는 비공개나 빈 기준이 아니다.

대응 설명서: 9절 「필드 커넥터와 파싱 오류 복구」·그림 9. 전달 순서: service-message-flows.md 3.9.
[기존] 정상 null과 출처에 원래 없는 필드는 오류로 만들지 않는다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | Crawler → Control | job.issue.detected 또는 collection.completed | scope=PROJECT_SOURCE, 임시 추출·parser 실패 | 해당 프로젝트·출처의 비교 중단. 다른 정상 프로젝트는 계속 |
| 1b | Ingestion → Control | job.issue.detected 또는 ingestion.completed | 최종 추출·변환·parser 실패 | DB/S3 기술 오류와 구분. 실패 레코드는 저장·정상 건수에서 제외. 비교·수집 불가한 규칙 관련 실패는 Control이 복구 요청; 복구 후보 검증은 최초 포함 최대 3회; 세부 실패 판정은 구현 검증 대상 |
| 2a | Control → Ingestion | 내부 HTTP 최신값 조회 | source_id, targets(project_id, project_source_id) | 최신 저장 필드값을 조회 |
| 2b | Ingestion → Control | HTTP 200 또는 오류 | 대상별 latest_records 또는 정형 error | 복구 기준 확보. DB 0건이면 승인 샘플 참조 준비 |
| 3 | Control → Analysis | SQS analysis.execute | purpose=REPAIR_CONNECTOR, 현재 스키마·커넥터·기준·실패 정보 | 같은 스키마를 유지하는 파싱·선택자 후보 생성·검증 |
| 4a | Analysis → Control | SQS analysis.completed | PASSED, schema_change_required=false | 대상 작업 보류 후 활성 또는 승인 변경 pending revision을 Data에 동기화; 성공 확인 후 Control 규칙 갱신 |
| 4c | Control → Ingestion | SQS dataset.apply SET_CONNECTOR_REVISION | project_id, project_source_id, schema_version, expected_connector_revision, connector_revision, guard_scope, CHANGE이면 change_job_id | Data DB의 유효 revision을 조건부 갱신. 10.6 |
| 4d | Ingestion → Control | SQS dataset.applied | 동기화 결과·현재 revision | 성공을 확인한 뒤 Control 커넥터 활성화·재수집. 미확인 시 보류·동일 job 재전달 |
| 4b | Analysis → Control | SQS analysis.completed | 스키마 변경 필요 또는 검증 실패·기준 부족 | 승인 대기 또는 확인 필요. 기존 정상 데이터 유지 |
| 5a | Control → Crawler | SQS collection.execute | execution_reason=RECOVERY, 새 커넥터 | 해당 규칙·대상으로 수집 실행 |
| 5b | Crawler → Control | SQS collection.completed | 작업 ID, snapshot_ref, 종료 사유·대상별 결과 | 영향 프로젝트에 필요한 화면 재수집 |
| 6a | Control → Ingestion | SQS ingestion.execute | 같은 승인 코드·규칙과 최종 스냅샷 | 해당 적재·준비·적용·삭제 작업 실행 |
| 6b | Ingestion → Control | SQS ingestion.completed | 원래 job_id와 프로젝트·출처별 status·error, 해당 결과 필드 | 정상 적재 후 대상 문제 상태 해소 |
| 7 | Control → 웹·이메일 | 상태 조회 응답·필요한 이메일 | 대상별 성공 또는 실패 원인 | 공유 출처 전체를 프로젝트 한 곳의 필드 오류로 실패 처리하지 않음 |

### 15.10 수집 결과 0건과 사용자 재확인

대응 설명서: 10절 「수집 결과 0건과 사용자 재확인」·그림 10. 전달 순서: service-message-flows.md 3.10.
[기존] DB 기존 데이터 0건·신규 저장 0건·최신값 일치 0건과 정상 추출 0건을 구분한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1a | Crawler → Control | job.issue.detected, code=EMPTY_LIST | scope=SOURCE, 목록 항목 0건 | 즉시 확인 필요·기존 데이터 유지 |
| 1b | Ingestion → Control | job.issue.detected, code=EMPTY_EXTRACTION | scope=PROJECT_SOURCE, 정상 추출 0건 | 즉시 대상 프로젝트 출처 문제 표시 |
| 2 | Control → 웹·이메일 | 대시보드 조회 응답·설정 시 이메일 | 0건 대상·원인 | 리비전이면 전체 적용 보류. 같은 문제 중복 이메일 제한 |
| 3a | 웹 → Control | HTTP POST /control/v1/projects/{p}/sources/{s}/recheck | 해당 project_source_id | 새 분석·작업 생성. 202 analysis_id·job_id 반환 |
| 4a | Control → Analysis | SQS analysis.execute | purpose=RECHECK_SOURCE, 현재 설정·실패 정보 | 해당 출처 재방문·추출·규칙 검증 |
| 5a | Analysis → Control | SQS analysis.completed | 정상 검증 또는 실패·0건 | 검증 성공이면 기존 승인 설정에 맞춰 10.7의 새 변경 또는 일반 복구로 연결. 실패면 확인 필요 유지 |
| 3b | 웹 → Control | HTTP 출처 삭제 후보·approve | analysis_id, expected_revision, candidate_revision | 삭제 영향 확인과 최종 승인 후 15.12 진행 |
| 6 | Crawler/Ingestion → Control | 완료 이벤트 | 현재 작업의 최종 결과 | 중간 0건 보고와 같은 문제를 중복 처리하지 않음 |

### 15.11 기존 프로젝트에 출처 추가

Control은 공개 시 현재 공개 스키마, 미공개 승인 v1만 있으면 prepared v1을 조회해 분석 기준으로 전달한다. 조회 실패 시 임의의 빈 스키마로 진행하지 않는다.

- 설명서: 11절·그림 11. 메시지 형식: 6.1, 6.2, 7.2, 7.3, 8.1, 8.2.
- 기존 스키마에 맞춰 분석·검증하고 사용자가 최종 후보를 승인한다. 구조가 변경되면 3.13의 리비전으로 진행한다.

| 순서 | 송신 → 수신 | 내용과 처리 |
|---|---|---|
| 1 | 웹 → Control → Analysis | list_url·analysis_id·expected_revision으로 기존 후보에 출처를 원자 연결하고 새 revision의 ADD_SOURCE 분석·수정·재검증을 시작한다. |
| 2 | Analysis → Control → 웹 | 후보와 샘플을 표시하고 사용자가 최신 검증 후보를 승인한다. |
| 승인 이후 | Control → Ingestion | dataset.apply BEGIN_CHANGE 및 dataset.applied 성공을 확인한다. |
| 3 | Control → Crawler | 새 출처를 INITIAL/FULL로 최대 300초 수집한다. |
| 4 | Crawler → Control | 결과·최종 snapshot_ref 또는 실패를 전달한다. |
| 5 | Control → Ingestion | 공개 프로젝트는 APPROVED_SOURCE_APPEND, 미공개 승인 v1은 새 INITIAL APPEND를 요청한다. 승인 change_job_id·snapshot_ref와 pending revision을 전달한다. |
| 6 | Ingestion → Control | 성공 시 데이터를 공개하고 완료 결과를 전달한다. 0건·실패는 부분 적재를 공개하지 않고 확인·복구한다. |
| 7 | Control | 결과를 멱등 반영해 출처를 활성화하고 상태를 표시한다. 이벤트 누락은 재발행으로 복구한다. |
| 종료 | Control → Ingestion → Control | END_CHANGE와 dataset.applied의 실제 커밋 결과를 확인하고 다음 변경을 허용한다. |

추가와 삭제가 함께 있으면 모든 추가가 성공한 뒤 3.12 삭제를 수행한다. 추가 실패 시 삭제하지 않으며 이미 성공한 출처는 유지한다. 삭제 실패 시 공개된 출처를 되돌리지 않고 삭제를 재시도하며 상태를 표시한다.


### 15.12 출처 삭제만 있는 변경

대응 설명서: 12절 「출처 삭제만 있는 변경」·그림 12. 전달 순서: service-message-flows.md 3.12.
[기존 + 구현 보완] 삭제만 있는 변경은 dataset.apply로 처리한다. 0건 확인 후 사용자가 삭제를 선택하는 진입 흐름은 설명서 10절·그림 10이다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control | HTTP DELETE /control/v1/projects/{p}/sources/{s} | analysis_id, expected_revision | 삭제 후보만 기록. 삭제 출처 전용 필드도 자동으로 스키마에서 지우지 않음 |
| 2 | Control → Ingestion | 내부 HTTP deletion-preview | project_id, project_source_id | 삭제 대상 건수 조회. 이 시점에는 레코드 삭제 안 함 |
| 3a | Ingestion → Control | HTTP 200 | project_source_id, record_count | Control이 최종 설정과 삭제 영향 표시 |
| 3b | Ingestion → Control | HTTP 오류 | 조회 실패 error | 0건 삭제로 오인하지 않고 표시·재조회. 영향 정보 확인 전 승인을 보류하고 조회 성공 후 재개 |
| 4 | 웹 → Control | HTTP approve | candidate_revision | 현재 검증·후보·삭제 승인 조건 확인 |
| 4-BEGIN | Control → Ingestion | SQS dataset.apply BEGIN_CHANGE | 승인 대상·버전 | OPEN 결과 확인 후 삭제 |
| 4-OPEN | Ingestion → Control | SQS dataset.applied | 시작 결과 | 성공 시 UPDATE_SOURCES |
| 5 | Control → Ingestion | SQS dataset.apply | operation=UPDATE_SOURCES, prepared_results=[], delete_project_source_ids | 남은 출처 재수집 없이 삭제. 프로젝트 쓰기 차단·멱등 적용 |
| 6 | Ingestion → Control | SQS dataset.applied | status, active_project_source_ids, dataset_revision | 성공하면 활성 연결 해제 완료 표시. 실패면 기존 상태 유지·기술 재처리 |
| 7 | Control → Scheduler·S3 | AWS API·객체 정리 | 삭제 후 공유 출처의 활성 연결이 0개인지 | 마지막 연결에만 일정 정리. 참조 중 스냅샷은 작업 종료 후 삭제 |
| 종료 요청 | Control → Ingestion | SQS dataset.apply END_CHANGE | COMPLETED 또는 ABANDONED | 실제 커밋·CLOSED 확인 |
| 종료 확인 | Ingestion → Control | SQS dataset.applied | 종료 결과·실제 커밋 | 결과 동기화 후 다음 변경 허용 |


### 15.13 스키마 변경과 복합 변경의 적용

승인된 v2 스키마 정의도 Data DB에 변경 작업 단위로 비공개 준비한다. 각 출처의 PREPARE는 동일 승인 정의를 참조하며 모든 최종 출처 성공 후에만 공개 전환한다. schema.prepare/schema.prepared를 사용하며 준비 결과는 동일 change_job_id에 한해서만 재사용한다.

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


### 15.14 회원 인증과 계정 기본 설정

대응 설명서: 14절 「회원 인증과 계정 기본 설정」·그림 14. 전달 순서: service-message-flows.md 3.14.
[기존] Auth API의 인증·계정 기본 설정 흐름이다. 이메일 공급자 호출 세부 방식은 미정이다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Auth | HTTP POST /auth/v1/email-verifications | email | 회원가입용 6자리 인증번호 생성·만료 관리 |
| 2 | Auth → 이메일 공급자 | 이메일 발송 API [공급자 미정] | 수신 이메일·인증번호·인증 목적 | 발송 결과 확인. 비밀번호·토큰은 수집 큐에 넣지 않음 |
| 3 | Auth → 웹 | HTTP 202 또는 오류 | 발송 접수 또는 발송 실패 | 사용자가 인증번호 입력. 정상 발송으로 오인하지 않게 오류 구분 |
| 4a | 웹 → Auth | HTTP POST /auth/v1/email-verifications/confirm | email, code → 검증 결과 | 만료·오류 시 재입력·재발송. 인증번호 5분·최대 5회; 발송 빈도는 운영 설정 |
| 4b | Auth → 웹 | HTTP 응답 | 가입 전용 verification_token 또는 오류 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 5a | 웹 → Auth | HTTP POST /auth/v1/signup | email, password, verification_token → user_id | 가입 전용 증명 확인·소비 후 계정 생성 |
| 5b | Auth → 웹 | HTTP 응답 | 201 user_id 또는 가입 실패 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 6 | 웹 → Auth ↔ Auth 저장소 | HTTP POST /auth/v1/login·DB | email, password | 비밀번호·계정 상태 검증 및 refresh session 기록 |
| 7 | Auth → 웹 | HTTP 200 | access_token, csrf_token, refresh 쿠키 | 이후 Bearer JWT로 Control·Data 요청. 실패면 인증 오류 |
| 8a | 웹 → Auth | HTTP POST /auth/v1/refresh | refresh 쿠키·X-CSRF-Token | 세션 회전·새 토큰 응답. 만료·폐기면 재로그인 |
| 8b | Auth → 웹 | HTTP 응답 | 새 access_token·csrf_token·refresh 쿠키 또는 인증 오류 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 9a | 웹 → Auth | HTTP POST /auth/v1/logout | refresh 쿠키·X-CSRF-Token | refresh 세션 폐기·204. 기존 access token의 짧은 잔여 유효시간 허용; 정확한 시간 미정 |
| 9b | Auth → 웹 | HTTP 응답 | 204 또는 인증·CSRF 오류 | 화면에 결과 반영. 인증 실패는 로그인·번호 확인 흐름으로 복귀 |
| 10a | 웹 → Auth | HTTP POST /auth/v1/password-reset-requests | email | 초기화용 6자리 인증번호 생성 |
| 10b | Auth → 이메일 공급자 | 이메일 발송 API [공급자 미정] | email, 초기화용 인증번호 | 발송 결과 확인 |
| 10c | Auth → 웹 | HTTP 202 또는 오류 | 초기화 요청 접수·오류; 202는 실제 발송을 보장하지 않음 | 번호 입력 화면 또는 재요청 안내; 계정 유무를 노출하지 않음 |
| 11a | 웹 → Auth | POST /auth/v1/password-reset-requests/confirm | email, code | 초기화용 번호 확인; 초기화 전용 일회용 reset_token 반환 |
| 11b | Auth → 웹 | HTTP 응답 | reset_token 또는 오류 | 실패 시 재입력·재발송 |
| 11c | 웹 → Auth | POST /auth/v1/password-resets | email, reset_token, new_password | reset_token 소비 후 비밀번호 변경과 모든 refresh 세션 폐기를 Auth DB에서 원자적으로 수행. 기존 access token은 만료까지 유효 |
| 11d | Auth → 웹 | HTTP 응답 | 변경 성공 또는 오류 | 성공 시 로그인, 실패 시 오류 표시 |
| 12 | 웹 ↔ Auth | HTTP GET/PATCH /auth/v1/me | Access JWT·name | 현재 계정 조회·이름 변경. 탈퇴는 15.17로 연결 |
| 13 | Control·Data → Auth | HTTP GET /auth/v1/jwks | 공개키 조회 | 공개키 캐시로 JWT 검증. 사용자 요청마다 Auth 로그인 확인 API를 호출하는 구조가 아님 |

### 15.15 웹과 외부 애플리케이션의 데이터 조회

대응 설명서: 15절 「웹과 외부 애플리케이션의 데이터 조회」·그림 15. 전달 순서: service-message-flows.md 3.15.
[확정] Control의 프로젝트 권한·상태 경로와 Auth의 계정 원본 상태 확인은 19.5를 따른다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹·외부 소비자 → Data | HTTP GET /api/projects/{p}/v{v}/records 또는 /{r} | 웹 Bearer JWT 또는 외부 X-API-Key, filter·sort·cursor·fields | 인증 정보 종류와 프로젝트 읽기 권한 구분 |
| 2 | Data ↔ Redis | 캐시 조회 | 호출 한도·마지막 사용 시각 등 | 키의 유효 여부는 Data DB 원본에서 확인 |
| 3a-요청 | Data → Control | 내부 HTTP GET /internal/v1/projects/{p}/access | principal, project_id, scope | 웹 소유권 또는 프로젝트 접근 가능 상태 확인. 외부 키의 해시·폐기 여부는 Data DB에서 확인 |
| 3a-응답 | Control → Data | 내부 HTTP 응답 | 권한 또는 정형 오류 | 유효한 경우 데이터 조회 진행, 불가하면 요청 거절 |
| 3b | Data → 소비자 | HTTP 400/401/403/410/429 | 입력·인증·권한·종료 버전·호출 한도 오류 | DB 데이터 조회 전에 오류 반환. 429는 Retry-After 포함 |
| 4 | Data ↔ RDS | DB 조회 | 공개 schema_version·dataset_revision·커서 조건 | 직전 완료된 공개 데이터만 조회 |
| 5a | Data → 소비자 | HTTP 409 | 커서의 dataset_revision 또는 조건 불일치 | 처음부터 다시 조회하도록 안내 |
| 5b | Data → 소비자 | HTTP 200 | data, meta(schema_version, dataset_revision, next_cursor, has_more, returned_count) | 필터·정렬·선택 필드 적용 결과 반환. 단건 없음은 404 |
| 6 | Data → Redis | 사용 시각 기록 [사용 가능 시] | key_id, last_used_at | 키의 유효성은 Data DB 원본을 확인. 공개 버전과 데이터는 Data DB에서 일관되게 조회 |

### 15.16 프로젝트 전체 삭제

Data DB의 API 키, 공개·준비 스키마 정의와 필드, 레코드 및 공개 상태가 정리 대상이다. Control DB의 후보·커넥터는 별도 정리하며 다른 프로젝트의 공유 출처는 유지한다.

대응 설명서: 16절 「프로젝트 전체 삭제」·그림 16. 전달 순서: service-message-flows.md 3.16.
[기존 기능 + 확정 통신] 사용자 API는 기존 스펙을 따른다. 데이터 정리 메시지 2종은 16.4 계약을 따른다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Control | HTTP DELETE /control/v1/projects/{p} | confirm=true, 소유권 | 프로젝트 삭제 작업 생성. 계정 삭제의 자식 작업으로도 재사용 |
| 2 | Control → 웹 | HTTP 202 | job_id | 삭제 진행 조회. 이 응답을 데이터 삭제 완료로 표시하지 않음 |
| 3 | Control 내부 | 실행·설정 변경 차단 | project_id, 삭제 작업 | 새 분석·수집·적재·변경 적용 발행 차단. 기존 실행의 늦은 결과도 적용 대상에서 제외 |
| 4 | Control → Ingestion | SQS project-data.delete | project_id | Data DB의 API 키·활성 데이터·비활성 준비 데이터·관련 데이터 메타데이터 전체 정리 |
| 5a | Ingestion → Control | SQS project-data.deleted | status=SUCCEEDED, project_id | Control이 프로젝트 설정·출처 연결·분석 후보 정리 조정. API 키는 Data DB 삭제 범위에 포함 |
| 5b | Ingestion → Control | SQS project-data.deleted | status=FAILED, error | 삭제 완료로 표시하지 않음. 기술 재처리·최종 실패 안내. 대상 프로젝트는 삭제 중 상태 유지 |
| 6 | Control → Redis·S3·Scheduler | 캐시 무효화·객체/일정 정리 | 프로젝트 전용 객체·공유 결과·큐 재처리 기간 | 다른 프로젝트는 유지하고 필요 시 공유 manifest에서 삭제 대상만 제외한 새 참조로 전환. 이전 불변 결과·메시지의 재처리 종료 후 정리 |
| 7 | 웹 ↔ Control | HTTP 작업 조회 | 삭제 작업 job_id | 전용 객체와 공유 결과·대기/DLQ 메시지 정리를 포함한 전체 필수 정리가 끝나면 완료. 큐 보존 기간만큼 지연될 수 있음 |

### 15.17 회원 탈퇴와 소유 프로젝트 정리

대응 설명서: 17절 「회원 탈퇴와 소유 프로젝트 정리」·그림 17. 전달 순서: service-message-flows.md 3.17.
[기존 기능 + 확정 통신] Auth↔Control 내부 HTTP 경로는 메시지 명세 16.5를 따른다. 일반 로그아웃은 refresh 세션을 폐기하고 이미 발급된 access token은 짧은 잔여 유효시간을 허용한다. 탈퇴 진행 계정은 유효한 기발급 access token이 있어도 서버에서 접근을 차단한다. 계정·키 원본 상태를 확인할 수 없으면 접근을 거절한다. token 유효시간은 운영 설정이다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 → Auth | HTTP DELETE /auth/v1/me | 탈퇴 확인·인증 | 계정 탈퇴 진행 상태 기록·refresh 세션 폐기. 로그인/갱신 차단 |
| 2 | Auth → Control | 내부 HTTP 계정 정리 접수 | owner_id | Control이 해당 소유자의 프로젝트·정리 상위 작업 생성 |
| 3 | Control → Auth | HTTP 202 | job_id, 정리 상태 | Auth는 소유자별 접수 상태를 기록. 응답 누락은 같은 소유자의 기존 작업으로 재접수 |
| 3a | Auth → 웹 | HTTP 202 탈퇴 접수 응답 | status=ACCEPTED, job_id | 정리 완료를 기다리지 않고 사용자 세션 정리. 탈퇴 진행 계정은 기존 JWT가 있어도 서버 접근 차단; 원본 계정 상태 확인 불가 시 503으로 거절 |
| 4a | Control → Ingestion | SQS project-data.delete | 소유 프로젝트마다 project_id와 개별 job_id | 프로젝트별 데이터 전체 삭제 실행 |
| 4b | Ingestion → Control | SQS project-data.deleted | 원래 job_id와 프로젝트·출처별 status·error, 해당 결과 필드 | 15.16 정리 흐름을 소유 프로젝트마다 수행. 다른 소유자의 공유 데이터 유지 |
| 5a | Control → Auth | 내부 HTTP 정리 완료 통지 | job_id, owner_id, status=SUCCEEDED | Auth가 계정 개인정보 최종 삭제. 식별 가능한 최소 작업 원장은 재처리 가능 기간에 유지 |
| 5b | Control → Auth | 내부 HTTP 정리 실패 통지 | status=FAILED, error | 삭제 작업을 끝났다고 처리하지 않음. 기존 작업 재처리·실패 원인 유지 |
| 6 | Auth → Control | HTTP 확인 응답 | 완료 통지 수신 결과 | 응답 유실이면 Control이 같은 결과를 재통지. 계정이 이미 삭제된 성공도 멱등 수신 |

### 15.18 프로젝트 설정과 키 및 알림 운영

대응 설명서: 18절 「프로젝트 설정과 키 및 알림 운영」·그림 18. 전달 순서: service-message-flows.md 3.18.
[기존] 프로젝트 설정·API 키·알림 운영의 통신이다. 미정 외부 연동은 명시적으로 표시한다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | 웹 ↔ Control | HTTP GET/PATCH /control/v1/projects/{p} | name, email_enabled | 프로젝트 설정 조회·변경. 목적은 조회만 가능. 변경만으로 수집 SQS를 발행하지 않음 |
| 2 | 웹 ↔ Control | HTTP 프로젝트 목록·출처·스키마·분석·작업 조회 | 소유자·프로젝트 범위 | 각 화면에 필요한 상태·샘플·필드 제공. HTTP 요청마다 별도 SQS 생성 없음 |
| 3 | 웹 ↔ Data | HTTP GET/POST /api/projects/{p}/keys | access token, 키 이름 → key_id·최초 1회 secret | Data가 Control에 소유권·프로젝트 상태를 확인하고 Data DB에 해시만 저장. 비밀키는 SQS에 넣지 않음 |
| 4 | 웹 → Data | HTTP DELETE /api/projects/{p}/keys/{k} | access token, 소유 프로젝트 키 | Control 소유권·상태 확인 후 Data DB 폐기 완료 시 응답 |
| 5 | Data → Data DB | API 키 원본 조회 | project_id, key_id, 검증값 | 외부 요청마다 원본의 해시·프로젝트·폐기 여부를 확인한다. 이미 검증을 마친 요청의 취소까지 보장하지 않는다. |
| 6 | Data → Data DB | 공개 스키마·데이터 조회 | project_id, schema_version, dataset_revision | MVP는 같은 읽기 스냅샷에서 조회하며 별도 공개 스키마 캐시·무효화 메시지를 사용하지 않음 |
| 7 | Control → 이메일 공급자 | 이메일 발송 API [공급자 미정] | 수신자·프로젝트·문제 출처·안내 종류·중복 방지 기준 | email_enabled일 때 발송. 0건 즉시, 복구 가능한 다른 오류는 복구 흐름 후 사용자 조치 필요 시 발송 |
| 8 | 이메일 공급자 → Control/Auth | 발송 API 응답 | 접수 성공·일시 오류·최종 실패 | 메일 실패로 성공한 데이터 적재를 롤백하지 않음. 메일 자체의 재시도·중복 제한 필요 |
| 9 | Control → Scheduler | AWS 일정 생성·변경·중지 API | 활성 공유 출처·12시간 분산 슬롯 | 최초 수집 요청과 별도로 미래 슬롯에 일정 등록. 수동 실행은 기존 정기 시각을 변경하지 않음. 상세 15.20 |
| 10 | Scheduler → Control | collection.schedule.triggered | 이미 해제된 출처 또는 중복 예정 시각 | 작업 원장·활성 연결 확인 후 무시. 비활성 신호 때문에 출처를 재생성하지 않음 |

### 15.19 중복 전달과 작업 중단의 복구

대응 설명서: 19절 「중복 전달과 작업 중단의 복구」·그림 19. 전달 순서: service-message-flows.md 3.19.
[기존 방향 + 구현 보완] 모든 비동기 흐름에 적용한다. 기존 요청·결과를 재사용하며, 그림의 처리 서비스는 Analysis·Crawler 또는 Ingestion이다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | SQS → Analysis/Crawler | 기존 command 전달 | 동일 job_id·동일 입력 | Control에 새 attempt_id로 선점 요청 |
| 2 | Analysis/Crawler ↔ Control | 내부 HTTP 선점·갱신 | job_id, attempt_id | 유효 lease를 얻은 시도만 실행; 중복은 지연 |
| 3 | Analysis/Crawler → S3 | 시도별 결과 저장 | attempt_id, event_id, 결과·불변 참조 | 시도끼리 덮어쓰지 않음; 저장만으로 채택되지 않음 |
| 4 | Analysis/Crawler → Control | completed | job_id, payload.attempt_id, event_id | SQS 발행 성공 후 요청 삭제. 같은 시도 재발행은 같은 event_id |
| 5 | Control → Control DB | 결과 채택 트랜잭션 | lease·attempt_id·작업 상태·필요 revision | 유효 시 결과·수신 기록·후속 Outbox를 함께 반영; 이전 시도는 소비 후 무시 |
| 6 | Control Outbox → SQS | 후속 command | 기록된 command | 중복 발행을 수신 측이 멱등 처리 |
| 7 | Control 내부 | lease·시작 기한 점검 | 미완료 작업·동일 입력 | lease 만료 시 동일 job 재발행; 시작 기한 경과 시 종료·확인 필요 |
| 8 | SQS → Control | DLQ | job_id·원장 상태 | 다른 유효 시도가 있으면 종료하지 않음; 없으면 복구·종료 판단 |
| 9 | Ingestion → DB·Outbox | 적재 트랜잭션 | receipt·데이터·결과 이벤트 | 한 번만 반영하고 결과 재발행 |
| 10 | 웹 → Control | 수정·승인 | expected_revision / candidate_revision | 조건부 갱신으로 충돌 방지 |
| 11 | Ingestion → DB | 삭제·적재·적용 | 프로젝트 쓰기 상태·버전·receipt | 동일 트랜잭션에서 유효성 검사 |
### 15.20 공유 출처의 일정 등록과 실행

대응 설명서: 20절 「공유 출처의 일정 등록과 실행」·그림 20. 전달 순서: service-message-flows.md 3.20.
[기존 정책 + 구체화] Control의 AWS 일정 관리와 정기 신호를 구분한다. 설정·Input 형식은 메시지 명세 7.1이다.

| 단계 | 보내는 쪽 → 받는 쪽 | 방식·메시지 | 필수 값 또는 조건 | 받은 뒤 처리·다음 단계 |
|---|---|---|---|---|
| 1 | Control 내부 | 공유 출처·일정 확인 | 승인된 source_id, 활성 연결, 일정 이름 | 새 공유 출처인지 기존 공유 출처 연결인지 구분 |
| 2a | Control 내부 | 기존 일정 재사용 | 기존 공유 출처에 새 프로젝트 연결 | 일정 중복 생성 없이 신규 프로젝트 최초 수집은 15.4로 진행 |
| 2b | Control 내부 | 새 일정 설정 준비 | 새 공유 출처, 미래 분산 슬롯 | 최초 수집은 따로 요청. rate(12 hours)·StartDate·큐·Input 구성 |
| 3 | Control → EventBridge Scheduler | AWS SDK CreateSchedule | Name, GroupName, ScheduleExpression, StartDate, Target, FlexibleTimeWindow | 출처별 정기 일정 등록. 스케줄러가 DB를 조회하는 구조가 아님 |
| 4a | EventBridge Scheduler → Control | AWS API 성공 응답 | ScheduleArn | Control이 등록 상태 반영 |
| 4b | EventBridge Scheduler → Control | AWS API 오류·응답 누락 | 고정 일정 이름·오류 | GetSchedule로 실제 상태 확인·설정 대조 후 재처리. 등록 완료로 오인하지 않음 |
| 5 | EventBridge Scheduler 내부 | 예정 시각 도래 | 저장된 Target.Input | source_id는 유지하고 scheduled-time 문맥 키를 해당 예정 시각으로 치환 |
| 6 | EventBridge Scheduler → 정기 요청 SQS | SendMessage | collection.schedule.triggered, source_id, scheduled_at | SQS에 정기 신호 저장. 전송 실패는 Scheduler 측 재시도·DLQ 정책 |
| 7 | 정기 요청 SQS → Control | 메시지 수신 | source_id, scheduled_at | 활성 연결·중복 신호·진행 중 작업 확인. 유효하면 15.5 최신값 조회와 Crawler 요청 진행 |
| 8a | Control 내부 | 규칙 변경 또는 수동 수집 | source_id·주기가 유지됨 | 일정 그대로 유지. 현재 규칙은 신호 수신 후 조회하고 수동 수집은 15.6으로 직접 처리 |
| 8b | Control → EventBridge Scheduler | GetSchedule·UpdateSchedule | 변경할 실행 시각·주기·대상과 유지할 전체 설정 | 설정 변경 성공 후 Control의 일정 상태 갱신. 실패면 재처리 |
| 8c | Control → EventBridge Scheduler | UpdateSchedule(DISABLED) 또는 DeleteSchedule | 마지막 활성 연결이 사라진 source_id의 일정 | 정기 발행 중지·정리. 다른 프로젝트가 사용하면 유지 |
| 9 | 정기 요청 SQS → Control | 지연된 기존 정기 신호 | 이미 비활성화된 출처 | 무시하며 출처·수집 작업을 재생성하지 않음 |

## 16 상황별 추가 payload와 내부 HTTP 형식

기존 공통 형식을 재사용하며, 예외 상황에서는 아래 값만 달라진다. 예제는 payload이며 공통 envelope는 별도다. 이 절은 기존 10종으로 표현되지 않았던 프로젝트 전체 삭제를 2종으로 보완하고, HTTP 통신도 요청·정상 응답·오류를 보여준다. 아래 예제 UUID는 기존 예제와 같은 프로젝트·출처를 가리킨다.

### 16.1 최신값 조회·삭제 영향 조회

기존 내부 경로: POST /internal/v1/project-sources/latest-records:query. 요청:

```json
{
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "targets": [{
    "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
    "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18"
  }]
}
```

정상 저장 데이터 0건 응답. 12.1의 최신값이 있는 응답과 같은 형식이며 FULL 수집 대상이다:

```json
{
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "projects": [
    {
      "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "schema_version": 1,
      "dataset_revision": 0,
      "latest_records": []
    }
  ]
}
```

조회 실패는 HTTP 503 등 오류로 반환한다. 실패한 대상의 latest_records=[]를 반환하지 않는다:

```json
{
  "error": {
    "code": "LATEST_RECORDS_QUERY_FAILED",
    "message": "최신 저장 데이터 조회에 실패했습니다.",
    "details": {
      "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
      "project_ids": [
        "71c46a95-e328-47a5-92e7-a9018e5a5de6"
      ]
    },
    "retryable": true
  }
}
```

HTTP 오류의 retryable은 기존 API 오류 형식의 힌트이며 정책 판단은 Control이 한다. 배치 조회가 일부라도 실패하면 전체 조회 오류를 반환한다.

삭제 영향 경로: POST /internal/source-data/deletion-preview. 요청:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18"
}
```

HTTP 200 응답:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "record_count": 120
}
```

이 record_count는 삭제 영향 표시를 위한 응답 계약이다. 이벤트의 선택 운영 통계 record_count와 다르므로 응답에서 필수다. 조회 실패는 기존 error 형식이며 건수 0으로 대체하지 않는다. 승인 시 실제 데이터가 조금 달라져 건수가 바뀔 수 있으며, 삭제 범위는 항상 승인된 프로젝트 출처 전체다.

### 16.2 실패·부분 성공 결과의 형식

```json
{
  "attempt_id": "43f5b110-f193-4157-91f2-1a16b8fb70d6",
  "analysis_id": "ef7ba73e-6843-4ecf-963a-599cc3442714",
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "purpose": "RETRY_SOURCE",
  "revision": 2,
  "status": "FAILED",
  "schema_change_required": false,
  "candidate": null,
  "source_results": [
    {
      "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
      "status": "FAILED",
      "validation_status": "FAILED",
      "sample_ref": null,
      "error": {
        "code": "NAVIGATION_FAILED",
        "stage": "ANALYSIS_NAVIGATION",
        "message": "다음 목록 이동 후보를 검증하지 못했습니다.",
        "details": {}
      },
      "intermediate_ref": null
    }
  ],
  "error": null
}
```

최상위 error는 작업 전체를 수행할 수 없을 때 사용한다. 출처별 실패만 명확하면 source_results[].error로 원인을 표현한다. PARTIAL도 동일한 결과 형식이며 성공·실패한 출처를 각각 담는다. 실패 시 검증 샘플 저장에 성공했다면 FAILED 출처도 sample_ref를 전달할 수 있다. Control은 sample_ref에서 match_count와 failure_raw_ref를 확인하여 샘플·실패 원본 정보를 조회하고 처리한다. 샘플이 생성되지 않는 탐색 실패는 sample_ref=null로 표현할 수 있다. 필수 샘플·sidecar의 S3 저장 장애는 정상 FAILED 결과로 확정하지 않고 SQS 재전달·기한·DLQ에 따라 기술 실패로 처리한다. 이미 저장된 참조 없는 sidecar는 7일 내 정리한다. source_results의 성공 항목 수를 전체 출처 수로 오인하지 않는다.

Ingestion 실패 예시:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "mode": "APPEND",
  "schema_version": 1,
  "connector_revision": 2,
  "change_job_id": null,
  "status": "FAILED",
  "normal_extracted_record_count": null,
  "dataset_revision": null,
  "error": {
    "code": "SNAPSHOT_READ_FAILED",
    "stage": "SNAPSHOT_READ",
    "message": "수집 스냅샷을 읽지 못했습니다.",
    "details": {}
  }
}
```

normal_extracted_record_count는 결과에 필수로 존재하지만 추출 전에 실패한 경우 null이다. 정상 추출을 실제 수행해 0건을 관찰했을 때만 0이다. 추출하지 못한 경우를 EMPTY_EXTRACTION으로 오인하지 않는다. dataset_revision도 실패로 미확정이면 null이다. status=SUCCEEDED인 적재·준비 결과에는 정상 추출 건수 정수가 있어야 한다.

일반 수집 PARTIAL은 collection.completed.project_results에 적재 가능한 성공 프로젝트와 실패 프로젝트를 각각 담는다. 기존 형식의 status·can_ingest·error를 사용하므로 별도 partial 전용 메시지는 추가하지 않는다.

### 16.3 복구 요청의 failure_context와 0건 이슈

```json
{
  "source_job_id": "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc",
  "scope": "SOURCE",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "project_id": null,
  "project_source_id": null,
  "field_ids": [],
  "error": {
    "code": "NAVIGATION_FAILED",
    "stage": "LIST_NAVIGATION",
    "message": "다음 목록 버튼을 찾지 못했습니다.",
    "details": {
      "page_url": "https://school.example/notices",
      "selector": ".pagination .next"
    }
  }
}
```

위 객체는 analysis.execute.failure_context에 넣는다. source_job_id는 실패가 발생한 원래 job_id의 참조이며 새로운 ID 종류가 아니다. REPAIR_NAVIGATION은 모든 영향 프로젝트의 규칙과 recovery_baselines를 함께 요구한다. project_id=null인 공유 복구 결과에서는 candidate.schema를 null로 둘 수 있고, source_configs와 source_results의 project_source_id로 검증 대상을 구분한다. 공통 스키마 변경이 필요한 대상은 별도 프로젝트의 승인 흐름으로 연결한다.

목록 0건의 job.issue.detected payload:

```json
{
  "attempt_id": "43f5b110-f193-4157-91f2-1a16b8fb70d6",
  "reporting_service": "CRAWLER",
  "scope": "SOURCE",
  "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
  "project_id": null,
  "project_source_id": null,
  "field_ids": [],
  "schema_version": null,
  "connector_revision": null,
  "navigation_revision": 3,
  "error": {
    "code": "EMPTY_LIST",
    "stage": "LIST_EXTRACTION",
    "message": "반복 목록 항목을 찾지 못했습니다.",
    "details": {
      "observed_count": 0
    }
  }
}
```

프로젝트 정상 추출 0건이면 scope=PROJECT_SOURCE, 해당 프로젝트·출처·버전을 넣고 error.code=EMPTY_EXTRACTION을 사용한다. 정상적으로 동일 기존 데이터만 추출한 경우에는 이 issue를 발행하지 않는다.

### 16.4 프로젝트 전체 데이터 삭제

두 메시지는 기존 Ingestion 작업 큐와 Control 결과 큐를 사용한다. 새 물리 큐는 추가하지 않는다. 출처 변경용 dataset.apply는 유지하며 프로젝트 전체 삭제에 억지로 확장하지 않는다.

project-data.delete payload:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6"
}
```

사용자 승인·소유권 확인은 Control이 작업 생성 전에 수행한다. 작업 ID·상위 계정 정리 ID는 공통 envelope의 job_id·parent_job_id로 연결한다. Ingestion은 해당 프로젝트의 API 키, 공개 데이터·모든 비활성 준비 결과와 관련 데이터 메타데이터를 Data DB에서 정리하며, 늦은 작업이 데이터를 재생성하지 못하도록 쓰기 차단 상태와 작업 receipt를 유지한다. 승인된 출처 연결을 추가/삭제하는 동작과 구분한다.

project-data.deleted 성공 payload:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "status": "SUCCEEDED",
  "error": null
}
```

실패 payload:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "status": "FAILED",
  "error": {
    "code": "DATABASE_UNAVAILABLE",
    "stage": "PROJECT_DATA_DELETE",
    "message": "프로젝트 데이터 삭제를 완료하지 못했습니다.",
    "details": {}
  }
}
```

같은 job_id의 삭제 재전달은 기존 결과를 반환한다. 이미 삭제된 데이터의 삭제는 성공으로 처리할 수 있지만 아직 남은 준비 데이터까지 확인한다. 사용자 레코드 삭제 이력을 보관한다는 뜻은 아니다. 작업·쓰기 차단 기록의 최소 보관 기간은 별도 운영 결정이다.

### 16.5 Auth와 Control의 계정 정리 HTTP

접수 경로: POST /internal/v1/account-deletions. 호출 주체는 Auth이며 사용자 토큰 대신 서비스 신원으로 인증한다. 요청:

```json
{
  "owner_id": "fbe8c792-99e8-4145-968e-4bcb5a947c85"
}
```

HTTP 202 응답:

```json
{
  "job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "status": "ACCEPTED"
}
```

Control은 같은 owner_id의 진행 중·완료된 탈퇴 정리 요청을 중복 생성하지 않는다. Auth가 응답을 놓쳤으면 같은 요청으로 기존 작업을 확인할 수 있어야 한다. 탈퇴한 사용자의 ID는 재사용하지 않는다. 접수 실패는 기존 HTTP error 형식을 사용하고 Auth는 성공적으로 정리가 시작됐다고 표시하지 않는다.

완료 통지 경로: POST /internal/v1/account-deletions/{job_id}/complete. 호출 주체는 Control이며 job_id는 Control이 반환한 계정 정리 상위 작업이다. body:

```json
{
  "owner_id": "fbe8c792-99e8-4145-968e-4bcb5a947c85",
  "status": "SUCCEEDED",
  "error": null
}
```

실패면 status=FAILED와 정형 error를 넣는다. Auth는 수신 내용을 기록하고 204를 반환한다. Control은 완료 통지 대상과 body를 Outbox 등 재전송 가능한 방식으로 남긴다. 같은 완료 통지 재전달에는 이미 계정이 삭제됐어도 정상 응답한다. 탈퇴 진행 계정의 서버 접근 차단은 결정됐다. 탈퇴 접수는 확인된 Control 정리 작업을 기준으로 하며 Auth 원본 계정 상태 확인 불가 시 접근을 거절한다.

## 17 문서 흐름 대응과 남은 결정

### 17.1 흐름 설명서와 메시지 명세의 대응

그림 번호는 모두 캡스톤_시스템_흐름_설명서.docx 기준이다. 설명서에서는 절 번호와 그림 번호가 같다. MD의 번호는 재사용 중인 형식 참조를 유지하기 위해 별도 체계를 사용한다.

| 설명서 절 | 흐름 이름 | 설명서 그림 | 전달 순서 MD | 이 MD의 전송 표 |
|---|---|---|---|---|
| 1 | 초기 분석과 공통 스키마 추천 | 그림 1 | 3.1 | 15.1 |
| 2 | 실패 출처 재시도와 제외 | 그림 2 | 3.2 | 15.2 |
| 3 | 후보 수정과 재검증 및 승인 | 그림 3 | 3.3 | 15.3 |
| 4 | 승인 후 최초 수집 | 그림 4 | 3.4 | 15.4 |
| 5 | 정기 반복 수집 | 그림 5 | 3.5 | 15.5 |
| 6 | 프로젝트와 출처의 수동 수집 | 그림 6 | 3.6 | 15.6 |
| 7 | 접근과 시스템 오류 처리 | 그림 7 | 3.7 | 15.7 |
| 8 | 공유 이동 규칙 자동 복구 | 그림 8 | 3.8 | 15.8 |
| 9 | 필드 커넥터와 파싱 오류 복구 | 그림 9 | 3.9 | 15.9 |
| 10 | 수집 결과 0건과 사용자 재확인 | 그림 10 | 3.10 | 15.10 |
| 11 | 기존 프로젝트에 출처 추가 | 그림 11 | 3.11 | 15.11 |
| 12 | 출처 삭제만 있는 변경 | 그림 12 | 3.12 | 15.12 |
| 13 | 스키마 변경과 복합 변경의 적용 | 그림 13 | 3.13 | 15.13 |
| 14 | 회원 인증과 계정 기본 설정 | 그림 14 | 3.14 | 15.14 |
| 15 | 웹과 외부 애플리케이션의 데이터 조회 | 그림 15 | 3.15 | 15.15 |
| 16 | 프로젝트 전체 삭제 | 그림 16 | 3.16 | 15.16 |
| 17 | 회원 탈퇴와 소유 프로젝트 정리 | 그림 17 | 3.17 | 15.17 |
| 18 | 프로젝트 설정과 키 및 알림 운영 | 그림 18 | 3.18 | 15.18 |
| 19 | 중복 전달과 작업 중단의 복구 | 그림 19 | 3.19 | 15.19 |
| 20 | 공유 출처의 일정 등록과 실행 | 그림 20 | 3.20 | 15.20 |

설명서는 주요 순서와 분기를 보여주고, 전송 표는 HTTP·SQS·S3·DB 호출과 예외를 더 세분화한다. 그림의 화살표 하나가 반드시 SQS 메시지 하나를 뜻하지 않는다. 공통 형식은 3절, 종류별 JSON은 6~10·16절을 참고한다.

### 17.2 전송 상황은 포함했지만 최종 결정되지 않은 부분

1. 세 Java 서비스의 공통 파서 모듈 및 동일 결과를 구현·검증한다. 생성 코드는 별도 격리 없이 서비스 JVM에서 실행한다. 이번 문서 변경이 실행기를 구현했다는 뜻은 아니다.
2. 승인 샘플이 부족하면 자동 적용하지 않으며 후보 검증은 최초 포함 최대 3회다. DB 1~2건은 모두 비교·일치해야 한다. 필드 실패 레코드는 부분 저장하지 않으며, 규칙 관련 실패로 비교·수집을 계속할 수 없으면 자동 복구한다. 실패 세부 판정은 구현 검증 대상이다.
3. 16.5 계정 정리 내부 HTTP와 탈퇴 상태 원본 확인을 구현하고, 삭제 작업 원장의 실제 재처리 보관 기간을 설정한다.
4. API 키 원본 조회·즉시 폐기와 버전 전환의 Data 캐시 일관성 구현 검증. 필요 전송 상황은 15.18에 표시했으며 임의의 새 큐·이벤트를 추가하지 않았다.
5. 이메일 공급자와 발송 빈도·재시도 운영 설정. 인증·문제 이메일의 발송 상황은 모두 포함했으나 공급자 API body는 미정이다.
6. SQS 기술 재전달 한도·작업 기한·Control DLQ 처리 구현·도메인 동시 요청 조정. 15.19에서 수신 후 최종 처리를 연결했다.
7. 데이터 공개 포인터 소유권과 Data 캐시의 일관성은 10절의 보완 제안을 따른다. Control DB와 Ingestion DB가 한 트랜잭션으로 동시에 변경된다고 설명하지 않는다.

표의 [제안]·[미정] 항목은 기존에 합의된 정책과 구분된다. 명세에는 전송이 필요한 상황을 모두 드러내되, 아직 선택하지 않은 구현을 이미 결정된 사실로 기록하지 않는다.

Scheduler 구현 근거:

- AWS 일정·SQS 대상: https://docs.aws.amazon.com/scheduler/latest/UserGuide/managing-targets-templated.html
- 실행 문맥 치환: https://docs.aws.amazon.com/scheduler/latest/UserGuide/managing-schedule-context-attributes.html
- 주기·StartDate·60초 정밀도: https://docs.aws.amazon.com/scheduler/latest/UserGuide/schedule-types.html
- 일정 변경의 전체 설정 갱신: https://docs.aws.amazon.com/scheduler/latest/APIReference/API_UpdateSchedule.html

## 18. 79개 흐름 점검 후 확정된 교환 계약

이 절은 새 결정에 따른 구체 형식이며 15절 상황 표와 함께 사용한다. 스키마 준비는 기존 Ingestion 작업 큐·Control 결과 큐를 재사용한다. 아래 예시는 payload이며 공통 envelope는 3절을 따른다.

### 18.1 schema.prepare / schema.prepared

Control → Ingestion `schema.prepare` payload:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "schema_version": 1,
  "schema": {"fields": [{"field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148", "api_name": "title", "display_name": "제목", "description": null, "display_order": 0, "type": "string", "nullable": true}]}
}
```

필드마다 description(null 허용)과 display_order(0부터 중복 없는 정수)는 필수이며 승인한 값을 저장한다. 순서와 설명을 생략해 임의로 초기화하지 않는다.

Ingestion → Control `schema.prepared` payload:

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "change_job_id": "20549fac-0901-42bd-be1d-4acad93965bc",
  "schema_version": 1,
  "status": "SUCCEEDED",
  "error": null
}
```

최초와 리비전 모두 기존 상위 승인 job_id를 change_job_id로 사용한다. 동일 job_id는 receipt·결과 Outbox를 재사용하며 서로 다른 정의를 덮어쓰지 않는다. 새 schema.prepare는 실제 스키마 쓰기 트랜잭션에서 프로젝트 상태 행을 잠그고 OPEN 가드의 project_id·change_job_id·change_type·target_schema_version 및 삭제 상태를 확인한다. CLOSED·대상 불일치는 쓰지 않고 오류로 반환한다. 이미 커밋된 동일 job_id는 기존 결과만 재발행한다. Data DB에 스키마 정의를 비공개 준비하고 첫 정상 출처 적재 이전에는 v1 API를 공개하지 않는다. Control은 준비 성공 후 수집을 시작한다. v2는 모든 승인 대상 PREPARE 성공 후에만 REPLACE한다.

### 18.2 내부 조회와 상태

- Control → Ingestion `POST /internal/v1/project-schemas:query`는 `{ "project_ids": ["<uuid>"] }`를 전송한다. 응답 `projects[]`에는 project_id, is_public, schema_version, schema_metadata_revision, schema.fields를 넣고 공개 전이면 is_public=false·schema_version/schema_metadata_revision/schema=null이다. 조회 실패는 503이고 빈 스키마로 바꾸지 않는다. 최초 공개 전 복구에는 승인 후보를 사용하며, 승인 후보를 다시 읽어야 하는 경우 19.3의 Data 준비 스키마 내부 조회를 사용한다.
- Control → Ingestion `POST /internal/v1/project-sources/latest-records:query` 요청은 `{ "source_id": "<uuid>", "targets": [{"project_id":"<uuid>","project_source_id":"<uuid>"}] }`다. 응답은 기존 16.1의 projects[] 구조이며 각 출처 최신 사용자 필드값 최대 3건이다. 부분 실패 시 전체 오류다. Control이 대상 연결을 확인한다.
- Data → Control `POST /internal/v1/project-sources:resolve`는 `{ "project_source_ids": ["<uuid>"] }`, 응답은 `{ "sources": [{"project_source_id":"<uuid>","source_id":"<uuid>"}] }`다. 권한은 서비스 신원으로 검증한다. 이미 승인되어 적용 중인 출처의 매핑은 활성 상태 전환 전에도 반환한다. 실제 공개 레코드의 존재 여부는 Data가 확인한다. 승인 전 후보, 삭제된 연결 및 조회 실패는 오류다. 공개 레코드 여부는 Data가 판단하며 Control 매핑만으로 후보 데이터를 공개하지 않는다. API 목록·단건의 source_id를 임의로 추정하지 않는다.
- Data는 키 사용 시 Redis `api-key:last-used:{key_id}`에 RFC3339 UTC 최대 시각을 기록하고 키 목록에 last_used_at을 반환한다. Redis 장애·사용 이력 없음은 null이며 실시간 정확성은 보장하지 않는다. 키 유효성은 Data DB 원본에서 확인한다.
- Control 작업 조회는 job_id·status·각 project_source_id·상태·error·last_success_at·next_scheduled_at·duration_ms·list_item_count·normal_extracted_record_count를 제공한다. 수집 또는 적재 이전/측정 불가 값은 null이며 0이 아니다. 프로젝트 목록/상세는 current_analysis_id·current_job_id를 제공한다. 다른 프로젝트의 결과는 숨긴다.

### 18.3 인증과 HTTP 경계

- POST /auth/v1/email-verifications/confirm `{email,code}` → `{verification_token}`; POST /auth/v1/signup `{email,password,verification_token}`. 인증번호 5분·최대 5회, 가입 증명 10분·일회용이다.
- POST /auth/v1/password-reset-requests/confirm `{email,code}` → `{reset_token}`; POST /auth/v1/password-resets `{email,reset_token,new_password}`. 별도 요청이며 초기화 증명도 목적·이메일 범위·10분·일회용이다. DB 실패나 응답 유실로 사용 여부가 불확실하면 재인증한다. 비밀번호 변경과 모든 refresh 세션 폐기는 같은 Auth DB 트랜잭션이며 기존 access token은 만료까지 유효하다. 초기화와 로그인·refresh는 같은 계정 행을 잠가 직렬화하고 잠금 후 현재 비밀번호·세션 상태를 검증한다. 번호·증명은 로그·SQS에 넣지 않는다.
- POST /auth/v1/email-verifications의 가입 202는 공급자 접수 확인을 뜻한다. 초기화는 계정 존재 여부를 숨기기 위해 계정이 없어도 동일 202를 반환할 수 있으므로 해당 응답은 실제 발송을 보장하지 않는다. 재발송은 기존 번호·증명을 무효화하고, 새 발송 실패 시 해당 시도 번호를 무효화한다. 초기화는 계정 존재를 응답으로 드러내지 않는다.
- GET /auth/v1/csrf는 refresh 쿠키와 허용 Origin으로 세션 결합 csrf_token을 반환한다. refresh·logout에는 X-CSRF-Token을 요구한다. 웹은 access/CSRF를 메모리에 두고 새로고침 후 csrf 조회와 refresh를 수행한다. 쿠키는 HttpOnly Secure며 교차 사이트 시 SameSite=None; Secure, 자격 증명 CORS는 허용 Origin만 허용한다.
- POST /control/v1/projects는 Idempotency-Key UUID를 사용한다. 같은 소유자·키·본문은 동일 project_id를 반환하고 다른 본문은 409다. 생성 후 GET sources의 project_source_id 목록을 POST analyses에 넣는다.
- PATCH /control/v1/projects/{p}/analyses/{a}/candidate는 expected_revision 및 fields·source_rules의 부분 변경, delete_field_ids, excluded_project_source_ids를 받는다. 생략 항목은 보존하고 제외 ID는 전달 시 전체 교체한다. 409에는 current_revision을 반환한다. GET 분석 응답은 추천 필드의 recommendation_reason을 포함하고 사용자 추가 필드는 null을 반환한다.

### 18.4 전환·동시성·실패

- Control은 프로젝트 일반 쓰기를 보류하고 진행 중 쓰기 종료 후 Data 전환·Control 커넥터 활성화를 완료하고 재개한다. 프로젝트 상태 행 잠금, change_job_id·schema_version 검사, receipt·Outbox를 동일 Data DB 트랜잭션으로 적용한다. Control과 Data를 단일 트랜잭션으로 가정하지 않는다.
- Analysis·Crawler는 Control의 job_id·attempt_id lease를 선점·갱신하고 시도별 S3 결과를 발행한다. Control이 유효 시도·lease·revision을 DB에서 확인해 채택하며 미완료 lease 만료 시 동일 job을 재발행한다. Ingestion은 DB receipt와 결과 Outbox를 원자 처리한다.
- 일반 수집의 정상 레코드는 적재하고 실패 레코드는 제외한다. 비교를 계속할 수 없으면 해당 프로젝트 출처를 복구한다. 승인 출처 추가 실패는 부분 쓰기 미공개, 리비전 일부 실패·0건은 전체 보류다. 복구 후보 검증은 최초 포함 최대 3회이며 모든 기준이 일치하지 않거나 기준 부족이면 확인 필요다. 공유 규칙은 모든 영향 프로젝트 통과 시 적용한다.
- Control이 DLQ 처리·기한 점검을 담당해 최종 상태를 남긴다. 수신 기록·S3 시도별 result.json은 재전달·DLQ 재처리 가능 기간 이상 보관하며 보존 기간의 수치는 환경 설정이다. Control 주기 정리는 참조와 실제 실행 종료를 확인하고 삭제 확정과 새 참조 등록을 직렬화한다.

- 삭제 영향 조회가 실패하면 확인 불가로 표시하고 승인을 보류한다. 복구 후 다시 조회해 실제 영향 범위를 확인한다.
- Control DB에서 유효 lease 확인과 결과 채택을 원자화한다. S3 시도별 저장은 그 채택과 별도이며 중복 수집 및 사용되지 않은 S3 객체 발생은 허용한다.

### 18.5 API 키 및 원문 URL 경계

- 키 관리 API는 `/api/projects/{p}/keys`의 GET·POST와 `/api/projects/{p}/keys/{k}`의 DELETE다. Data가 사용자 JWT를 검증하고 Control의 소유권·프로젝트 상태를 확인한다. 외부 X-API-Key는 Data DB의 해시와 해당 project_id·폐기 상태를 확인한다. 키 원문은 최초 발급 응답에만 포함하며 SQS·로그에 넣지 않는다. 프로젝트별 키 발급 개수 제한은 두지 않으며 기존 읽기 전용 정책은 유지한다.
- Data는 폐기 성공 응답 전에 Data DB에 폐기 상태를 커밋하고 이후 키 인증마다 DB 원본을 확인한다. 원본을 확인할 수 없으면 거절한다. 이미 인증을 마친 진행 중 요청은 취소를 보장하지 않는다. 프로젝트·계정 삭제로 인한 접근 차단은 별도 원본 상태 확인과 삭제 흐름을 따른다.
- 레코드·분석 샘플의 `source_url`은 해당 항목에 직접 접근할 수 있다고 확인한 절대 URL 또는 null이다. 목록 URL과 상세 화면의 관찰 URL은 이 값과 구분한다. 항목에 유일하게 접근할 URL이 없는 클릭·모달도 상세 브라우저 수집은 가능하다.

### 적용 상태와 지연 쓰기의 경계

승인된 동일 스키마 출처 추가는 정상 적재가 끝난 출처부터 공개한다. Control이 완료 이벤트를 아직 반영하지 않았더라도 Data는 이미 공개한 레코드의 승인된 적용 중 출처 매핑을 조회할 수 있어야 한다. 승인 전 후보를 공개한다는 뜻은 아니다.

출처 삭제·프로젝트 삭제·스키마 전환 후 늦은 작업의 재적재는 Data DB의 프로젝트별 쓰기 상태와 삭제된 출처 차단 정보를 실제 데이터 쓰기와 같은 트랜잭션에서 확인해 막는다. 승인 변경의 시작·종료는 기존 dataset.apply의 BEGIN_CHANGE·END_CHANGE로 Data DB에 기록하며 10.5를 따른다.

## 19. 흐름 재점검 후 확정한 계약

### 19.1 미공개 출처의 커넥터 자동 복구

공개된 출처는 10.6의 활성 SET_CONNECTOR_REVISION을 사용한다. INITIAL·APPROVED_SOURCE_APPEND·PREPARE의 승인 준비 출처는 활성 가드와 별도의 OPEN 변경별 pending revision을 사용하며 19.8의 SET_CONNECTOR_REVISION `guard_scope=CHANGE`로 Data에 먼저 동기화한다. 이미 승인된 스키마 구조를 유지하는 복구 후보는 승인된 샘플·DB 기준을 사용하여 기존 최대 3회 검증한다. 통과하면 Control은 현재 OPEN 변경·이전 revision·검증 결과를 대조하고 대상의 신규 발행을 보류한다. 기존 `dataset.apply`의 CHANGE 범위 SET_CONNECTOR_REVISION을 보내 Data pending revision을 조건부 갱신한다. 성공 확인 후에만 Control 실행 규칙을 활성화하고 이전 자식 job을 무효화해 새 FULL 수집·적재를 요청한다. 같은 승인 스키마와 change_job_id는 유지한다. Data는 모든 승인 출처별 쓰기의 커밋 트랜잭션에서 OPEN·대상·버전과 pending revision을 함께 재확인하여 늦은 이전 revision을 거절한다. 동기화 전에 커밋된 결과는 receipt와 END 결과로 확인·반영한다. 첫 정상 공개는 유효 pending revision을 활성 SOURCE_WRITE_GUARD로 옮기는 것과 같은 트랜잭션이다. 이미 공개된 출처의 현재 활성 규칙은 이 경로로 바꾸지 않는다. 같은 출처의 SCHEMA_REVISION 준비 규칙은 CHANGE 범위 pending으로 별도 갱신하며 기존 활성 규칙은 REPLACE 전까지 유지한다. 구조 변경이 필요하면 기존 사용자 승인 흐름을 거친다.

### 19.2 종료된 변경의 스키마 재준비

이전 변경이 CLOSED이고 REPLACE가 커밋되지 않았는지 END_CHANGE 결과로 확인한 후 새 change_job_id로 BEGIN한다. Ingestion은 schema.prepare에서 Data 프로젝트 상태 행과 이전 미공개 정의를 잠그고 승인된 target_schema_version·정의를 확인한다. 이전 CLOSED 변경의 미공개 v2 정의·필드와 이전 준비 레코드를 같은 잠금 경계에서 정리하고 새 OPEN 변경의 change_job_id로 정의를 준비한다. 서로 다른 승인 정의를 무심코 재사용하지 않는다. 이전 작업 receipt와 결과 Outbox는 재처리 기간 동안 유지한다. 지연된 이전 요청은 CLOSED 검사로 거절하며 이미 완료된 동일 job 재전달은 기존 결과만 재발행한다. 공개 v1·기존 데이터는 보존한다. 최초 비공개 v1은 기존 승인 정의와 완전히 같고 이전 변경이 CLOSED인 경우 새 OPEN INITIAL에 준비 정의의 change_job_id를 이전해 참조할 수 있다. 기존 v1이 공개된 경우 원본 정의의 change_job_id를 바꾸지 않으며 SOURCE_CHANGE에서 공개 스키마를 참조한다. 새 SCHEMA_REVISION의 모든 최종 출처는 새 변경의 PREPARE를 다시 수행한다.

### 19.3 공개 전 삭제와 마지막 출처 삭제

삭제 후보는 현재 analysis_id·expected_revision으로 생성하고 삭제 영향 조회를 확인한 뒤 승인한다. 마지막 출처만 삭제해 최종 대상이 없어도 삭제 전용 후보의 승인을 허용한다. 기존 OPEN 변경이 있으면 END_CHANGE 결과를 먼저 확인한다. 이후 SOURCE_CHANGE BEGIN에 delete_project_source_ids를 담고, 공개 전이면 expected_schema_version=null·target_schema_version=1을 사용한다. 이 예외는 INITIAL에서 v1 정의를 승인하고 준비한 공개 전 프로젝트에만 허용한다. UPDATE_SOURCES에는 같은 expected=null·target=1과 삭제 목록·남은 출처 목록을 담는다. Ingestion은 Data 프로젝트 행과 OPEN guard·승인된 삭제 대상을 잠그고 해당 출처의 미공개 준비 데이터 제거, 출처 삭제 차단 표시, receipt·Outbox를 원자적으로 기록한다. 공개 전이면 active_schema_version과 dataset_revision은 null을 유지한다. dataset.applied도 schema_version=null·dataset_revision=null과 active_project_source_ids=[]를 반환한다. 준비된 승인 v1 정의는 유지한다. END 결과 확인 후 남은 승인 출처가 있으면 새 INITIAL로 처음부터 수집하고, 없으면 프로젝트와 승인 v1 정의를 둔 채 빈 출처 상태로 대기한다. 새 출처는 Ingestion 내부 조회로 Data DB의 승인·미공개 v1 정의를 읽어 이를 기준으로 분석·승인하고 새 INITIAL로 수집한다. 첫 정상 성공 시 v1을 공개한다. 공개 전 승인 v1 조회는 서비스 인증된 `GET /internal/v1/projects/{project_id}/schemas/{schema_version}/prepared`를 사용한다. Ingestion은 Data DB에서 승인되어 준비된 v1만 반환하고 프로젝트 소유·변경 이력을 Control이 확인한다. 정의가 없거나 삭제된 프로젝트면 404, 저장소 오류면 503으로 처리하며 공개 Data API에는 노출하지 않는다.

이미 공개된 프로젝트의 삭제는 expected_schema_version=target_schema_version=현재 공개 버전이다. 마지막 출처여도 final_project_source_ids=[]를 허용하며 해당 출처의 RECORD 삭제·차단·dataset_revision 갱신을 커밋한다. 프로젝트·공개 스키마·API·키는 유지하고 목록은 빈 data 배열을 반환한다. 삭제로 빈 상태가 된 것은 수집 0건이 아니므로 0건 알림을 발송하지 않는다. 새 출처는 활성 스키마로 분석하고 APPROVED_SOURCE_APPEND로 공개한다. 공유 출처의 다른 프로젝트 데이터와 객체는 유지하며 마지막 활성 연결이면 일정 중지·안전한 스냅샷 정리를 진행한다.

### 19.4 공개 스키마 표시 정보 갱신

기존 Ingestion 작업 큐에 dataset.apply를 재사용한다. 필드 구조가 그대로인 경우에만 사용자 승인 후 Control이 다음 payload를 발행한다. API 이름·타입·nullable·필드 추가/삭제는 이 작업으로 변경하지 않는다.

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "operation": "UPDATE_SCHEMA_METADATA",
  "change_job_id": null,
  "expected_schema_version": 1,
  "expected_metadata_revision": 4,
  "fields": [{"field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148", "display_name": "공지 제목", "description": null, "display_order": 0}]
}
```

fields는 승인된 전체 필드의 표시 정보이고 field_id 집합은 현재 공개 스키마와 같아야 한다. Ingestion은 프로젝트 상태 행을 잠그고 프로젝트 삭제 상태·현재 공개 schema_version·expected_metadata_revision·field_id 집합을 확인한다. 해당 프로젝트에 충돌하는 OPEN 승인 변경이 있으면 거절하고 Control이 종료 후 재요청한다. 승인된 표시명·설명·순서와 schema_metadata_revision 1 증가, 작업 receipt·결과 Outbox를 한 Data DB 트랜잭션으로 기록한다. API schema_version과 레코드·dataset_revision은 바꾸지 않는다. MVP에서 Data의 스키마 조회는 Data DB 원본을 읽는다. 같은 job의 기술적 재전달에는 기존 결과를 재발행한다. 기대 revision이 다르면 실패 결과를 남기고 Control은 최신 스키마를 조회해 사용자에게 충돌을 알린다.

성공 결과는 기존 dataset.applied envelope와 공통 필드를 사용하며 metadata_revision을 추가한다. change_job_id는 null이고 dataset_revision과 활성 출처 목록은 변경 전 값을 그대로 반환한다.

```json
{
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "operation": "UPDATE_SCHEMA_METADATA",
  "change_job_id": null,
  "schema_version": 1,
  "dataset_revision": 19,
  "active_project_source_ids": ["95f7d7de-ec00-4fbe-b430-fa3a79763c18"],
  "metadata_revision": 5,
  "status": "SUCCEEDED",
  "error": null
}
```

### 19.5 탈퇴 계정 원본 상태 조회

Control과 Data는 매 사용자 Bearer JWT 요청의 서명·만료·issuer·audience 검증 이후 Auth 원본 계정 상태를 서비스 신원으로 조회한다. 캐시된 ACTIVE로 생략하지 않는다.

```text
GET /internal/v1/accounts/{user_id}/access-status
```

```json
{"user_id":"6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc","access_status":"ACTIVE"}
```

응답 access_status는 ACTIVE, WITHDRAWING, DELETED다. Auth는 현재 계정이 탈퇴 진행이면 WITHDRAWING, 계정이 없거나 정리 완료면 DELETED를 반환하며 사용자 존재 정보가 외부에 노출되지 않도록 서비스 신원만 허용한다. ACTIVE만 통과하고 WITHDRAWING/DELETED는 403, Auth 통신·원본 조회 실패는 503이다. 이미 권한 검사가 끝난 요청의 취소는 보장하지 않는다. API 키 요청은 Data의 키 원본 검사 외에 Control의 프로젝트 상태 확인이 필요하며, Control은 그 프로젝트 소유자에 대해 같은 Auth 조회를 수행한다. 비밀번호 초기화는 account_status를 바꾸지 않으므로 기존 access token은 만료까지 유효하다. 로그아웃의 기존 token 정책도 유지한다.

### 19.6 source_id 필터와 정렬의 매핑

Data는 source_id 필터·정렬이 있으면 요청 프로젝트 범위의 활성 및 승인된 적용 중 연결과 revision을 Control에서 조회한다. 출력 시 기존 project-sources:resolve도 사용할 수 있다.

```text
GET /internal/v1/projects/{project_id}/source-mappings
```

```json
{"project_id":"71c46a95-e328-47a5-92e7-a9018e5a5de6","mapping_revision":7,"sources":[{"project_source_id":"95f7d7de-ec00-4fbe-b430-fa3a79763c18","source_id":"1c226765-48b1-4518-ad16-057e8998eb84"}]}
```

Control은 활성 연결과 승인된 APPLYING 연결의 매핑을 반환하고 승인 전 후보·삭제된 연결은 반환하지 않는다. Data는 이 매핑을 실제 공개 레코드와 결합해 공개 여부를 결정한다. 따라서 Data의 APPEND 완료와 Control의 활성 상태 갱신 사이에도 누락되지 않고, 미공개 데이터는 노출하지 않는다. Data는 해당 매핑을 이용해 source_id 필터를 project_source_id 집합으로 변환하고 정렬 시 source_id와 record_id의 안정된 순서를 적용한 뒤 페이지를 자른다. mapping_revision은 Control이 승인된 APPLYING·활성 매핑을 추가·변경·삭제할 때 증가시키며 source_id 조건의 cursor에 포함한다. 다음 요청에서 mapping_revision 또는 dataset_revision이 바뀌면 409, 매핑 조회가 불가능하거나 해당 공개 레코드의 매핑이 누락되면 503이다. 빈 매핑은 정상적인 빈 프로젝트와 구별 가능한 성공 응답이다. Control의 PROJECT.source_mapping_revision은 승인된 적용 중·활성 매핑을 바꾸는 트랜잭션에서 함께 증가한다. 삭제 시 Data의 삭제 커밋이 확인될 때까지 기존 조회용 매핑을 유지한다. Data는 조회를 일관된 Data DB 스냅샷에서 수행하고 응답 직전 Control 매핑 revision을 다시 확인한다. 달라졌으면 409로 재시도하며 검증할 수 없으면 503이다. 최초 공개 시 schema_metadata_revision은 0이다.

### 19.7 분석 경합·도메인 요청·재전달

동일 프로젝트의 최초 분석은 Control의 PROJECT 행을 잠근 뒤 현재 진행 분석 확인·ANALYSIS/JOB 생성·요청 Outbox 저장을 같은 트랜잭션으로 수행한다. 요청의 project_source_ids 집합이 같으면 기존 analysis_id와 job_id를 반환한다. 다른 집합이면 새 분석을 만들지 않고 409 ANALYSIS_IN_PROGRESS와 현재 analysis_id·job_id를 반환한다. 상세 retry와 서로 다른 분석 목적은 각 작업 정책을 따른다.

Analysis와 Crawler는 정규화된 대상 호스트별 Redis 임대를 얻어 동시 요청 1개·시작 간격 최소 1초를 지킨다. HTTP와 브라우저가 대상 호스트에 보내는 요청에 적용하며 리다이렉트 대상은 각각 검사한다. 유효 임대·Redis 상태를 확인할 수 없으면 새 요청을 중단한다. 기존 요청은 소유권 상실 시 취소하고 요청 타임아웃·프로세스 종료가 확인되기 전에는 재진입하지 않는다. Redis lease만으로 네트워크 분리 중 이전 요청의 물리적 종료를 증명할 수 없으므로, 임대 만료와 요청 강제 종료·대기 유예를 함께 구현하고 통합 시험한다. 사이트 정책과 안전한 URL 검증은 기존대로 적용한다.

REPLACE의 기술적 재전달은 원래 **요청 envelope·job_id·payload**를 사용한다. event_id는 작업 요청 필드가 아니라 결과 이벤트 식별자다. Ingestion은 receipt와 결과 Outbox의 원래 dataset.applied **event_id·payload**를 재발행하고 Control은 10.8의 재조정을 수행한다.


### 19.8 승인 변경 중 출처 revision 가드

BEGIN_CHANGE는 수집·준비 대상마다 승인된 초기 revision을 `target_connector_revisions`에 포함한다. 삭제만 하는 대상은 포함하지 않으며, 목록이 필요한 승인 수집 대상 집합과 정확히 일치해야 한다. Ingestion은 OPEN 가드와 `(change_job_id, project_source_id, schema_version, connector_revision)` pending 논리 기록을 같은 트랜잭션에 저장한다. 활성 SOURCE_WRITE_GUARD와는 별도다. 예: `"target_connector_revisions":[{"project_source_id":"95f7d7de-ec00-4fbe-b430-fa3a79763c18","schema_version":1,"connector_revision":2}]`. 삭제 전용 변경은 빈 배열이다.

복구 시 기존 dataset.apply payload를 확장한다.

```json
{"project_id":"71c46a95-e328-47a5-92e7-a9018e5a5de6","project_source_id":"95f7d7de-ec00-4fbe-b430-fa3a79763c18","operation":"SET_CONNECTOR_REVISION","guard_scope":"CHANGE","change_job_id":"20549fac-0901-42bd-be1d-4acad93965bc","schema_version":1,"expected_connector_revision":2,"connector_revision":3}
```

Ingestion은 프로젝트·변경 행과 pending 대상을 잠그고 OPEN·대상·버전·expected revision을 확인해 조건부 변경하고 receipt/기존 dataset.applied Outbox를 같은 트랜잭션에 기록한다. 불일치·CLOSED는 실패, 같은 job 재전달은 receipt를 재발행한다. 출처가 그 사이 이미 공개되어 pending 적용 범위가 바뀌었다면 CHANGE 갱신을 거절하고 Control은 먼저 적재 receipt를 확인한 뒤 활성 가드 동기화로 전환한다. 이미 완료된 이전 PREPARE는 전환에 재사용하지 않고 새 revision으로 다시 준비한다. Control은 성공 또는 같은 job 결과 재확인 후에만 새 규칙을 활성화한다. 요청이 불확실하면 새 job이나 수집을 발행하지 않는다. INITIAL APPEND·APPROVED_SOURCE_APPEND·PREPARE는 실제 쓰기 직전 이 pending revision을 비교한다. 최초 공개와 추가 공개는 pending을 활성 가드로, REPLACE는 최종 PREPARE receipt에 대응하는 pending을 활성 가드로 같은 전환 트랜잭션에서 반영한다. 다른 활성 출처의 기존 가드는 덮지 않는다. 일반 반복 APPEND는 계속 활성 가드를 확인한다. END_CHANGE는 pending을 CLOSED로 만들며 늦은 요청을 차단한다.

### 19.9 후보·작업 HTTP 본문

분석 조회는 200 `{ "analysis_id":"<uuid>", "status":"READY", "candidate_revision":2, "validation_status":"PASSED", "candidate":{"fields":[{"field_id":"<uuid>","api_name":"title","display_name":"제목","description":null,"display_order":0,"type":"string","nullable":true,"recommendation_reason":"목록 항목의 제목을 나타냅니다"}],"source_rules":[{"project_source_id":"<uuid>","connector_revision":1,"navigation":{"list_url":"https://example.com/list","initial_actions":[],"list_access":{"method":"HTTP","wait_selector":null},"item_selector":".item","pagination":{"type":"NONE"}},"field_connectors":[]}],"excluded_project_source_ids":[]}, "sources":[{"project_source_id":"<uuid>","status":"SUCCEEDED","validation_revision":2,"sample_revision":2,"samples":[],"error":null}] }` 형식이다. 모든 출처 규칙·커넥터 및 제외 목록을 반환한다. 내부 candidate.schema.fields는 fields, source_configs는 source_rules로 변환한다. validation_revision과 sample_revision은 없으면 null이다. 샘플에는 S3의 현재 유효 revision 내용이 들어가며 S3 조회 실패를 빈 샘플로 바꾸지 않고 503으로 반환한다. 해당 없는 candidate·validation_status는 null이다.

PATCH는 `{ "expected_revision":2, "fields":[{"field_id":"<uuid>","display_name":"새 제목"}], "source_rules":[{"project_source_id":"<uuid>","field_connectors":[{"field_id":"<uuid>","location":"LIST","selector":".title","extract":{"type":"TEXT","attribute":null},"parser":null,"detail_navigation":null}]}], "delete_field_ids":[], "excluded_project_source_ids":[] }`와 같다. fields는 field_id, source_rules는 project_source_id, field_connectors는 field_id로 부분 병합한다. 새 필드는 클라이언트가 UUIDv4를 만들어 field_id와 완전한 필수 정의를 전달한다. 삭제는 delete_field_ids를 쓰고 제외 배열은 전달 시 전체 교체다. 생략은 유지한다. 명시적 null 허용 여부는 스키마 필드값의 nullable과 무관하게 해당 DTO 속성 규칙으로 검사한다. location을 LIST 또는 ABSENT로 바꿀 때 detail_navigation=null을 보내 이전 상세 규칙을 제거할 수 있고 DETAIL에서는 유효한 detail_navigation이 필수다. 성공은 202 `{ "analysis_id":"<uuid>","candidate_revision":3,"validation_status":"PENDING" }`, 검증 승계는 200과 PASSED다. 여기의 PENDING은 HTTP 후보 검증 진행 상태이며 Analysis 완료 이벤트의 validation_status(PASSED·FAILED·UNVERIFIABLE)와 구분한다. 경합은 409 `{ "code":"REVISION_CONFLICT","current_revision":3 }`, 유효하지 않은 입력은 422 `{ "code":"INVALID_CANDIDATE","errors":[{"path":"source_rules[0].field_connectors[0].selector","message":"선택자를 확인하세요"}] }`, 의존 저장소 장애는 503이다. 실패 출처 재시도는 당시 revision의 대상 출처 매핑·샘플만 반영하고 사용자 편집과 다른 출처를 보존한다. 공통 스키마 변경은 별도 후보 수정과 영향 출처 재검증을 거친다.

수동 수집 202는 `{ "job_id":"<대표 또는 단일 요청 job UUID>", "status":"ACCEPTED" }`를 반환한다. project_sources가 없으면 작업 없이 409 `{ "code":"NO_ACTIVE_SOURCES" }`다. 단일 요청도 요청 범위의 job을 반환하고 기존 공유 실행과의 연결은 Control 내부 관계로 관리한다. 작업 조회는 소유 프로젝트 범위에서 `{ "job_id":"<uuid>","status":"RUNNING","sources":[{"project_source_id":"<uuid>","status":"RUNNING","error":null}] }`를 반환한다.


## 20 재점검 후 확정한 실행 계약

### 20.1 후보 및 최초 필드

Analysis의 최초 공동 추천 결과를 유효 revision으로 채택할 때 Control은 누락된 description을 null로, display_order를 추천 fields 배열의 0부터 시작하는 순서로 한 번 초기화해 후보에 저장한다. 기존 값과 사용자 편집값은 덮지 않는다. GET은 이 승인 전 후보를 제공하고 승인 후 schema.prepare에 같은 값을 보낸다. 표시 순서는 중복 없는 정수다. Analysis의 필수 필드·커넥터 누락을 기본값으로 숨기지 않는다.

PATCH source_rules의 navigation은 해당 출처의 완전한 navigation 객체로 교체하며 생략이면 유지한다. field_connectors는 field_id별 부분 병합한다. 반환 GET은 목록 이동·상세 접근·복귀 전체와 excluded_project_source_ids를 포함한다. 검증 상태 및 해당 출처의 validation_revision·sample_revision은 승계된 원본과 현재 후보를 구별한다.

### 20.2 이동 실행과 공유 동일성

DETAIL의 open에 target=CURRENT_TAB, NEW_TAB 또는 MODAL을 필수로 둔다. CURRENT_TAB은 HISTORY_BACK, NEW_TAB은 CLOSE_TAB, MODAL은 CLOSE 및 모달 내부 닫기 selector를 요구한다. LINK/CLICK은 해당 대상에 실제 열린 화면을 관찰하고 access.wait_selector를 그 상세 화면·모달에서 확인한다. 다른 탭, 예상과 다른 대상, 중복 팝업, 대기·복귀 실패는 탐색 오류다. 목록 항목은 복귀 후 안전하게 재식별한다. 상세 접근은 항상 BROWSER다. 예: `{"type":"CLICK","selector":"button.title","target":"NEW_TAB"}`. 상세 정보가 같은 대상·동작·대기·복귀 규칙인 필드만 방문을 공유한다.

NONE은 첫 화면만 검증한다. 다음 링크가 없거나 명시적으로 비활성이면 END_OF_LIST다. CLICK_NEXT가 되돌아온 페이지와 동일한 식별 가능한 구간을 반복하면 NAVIGATION_FAILED다. LOAD_MORE/SCROLL은 동작과 완료 대기 후 신규 항목이 연속 2회 없으면 END_OF_LIST이며, 대기 실패는 NAVIGATION_FAILED다. 시간 만료는 TIME_LIMIT이다. 기존 5개 묶음·최신값 비교와 사용자 수용의 뒤쪽 변경 누락은 그대로다.

동일 공유 출처 비교는 URL 스킴·호스트 대소문자와 기본 포트만 정규화하고 fragment는 제거한다. path·query 및 순서·의미 있는 값은 임의 변경하지 않는다. list_url과 초기 동작 순서·접근·item_selector·pagination의 완전한 기본값을 채운 구조가 정확히 같을 때만 공유한다. 모호하면 별도 출처다. 브라우저 네트워크 요청은 요청별로 호스트 임대를 획득·해제하고 바깥 탐색에 같은 임대를 잡은 채 하위 요청을 기다리지 않는다. 리다이렉트와 하위 요청도 동일 제한을 지키며 실패·중단 시 해제한다.

### 20.3 수집·적재 집계와 복구 경합

Control은 발행 직전 최신값의 schema_version과 공개 schema_version 및 현재 승인 커넥터 버전을 확인한다. 전환이 진행 중이거나 버전이 다르면 일반 작업 발행을 보류하고 조회부터 재시도한다. 출처별 단일 논리 작업 보류와 Data 쓰기 시 가드 검사는 유지한다.

수동 요청은 별도 요청 job이며 동일 실행 대상이면 기존 collection에 연결한다. 중복 사용자 요청은 새 요청 job을 만들어 기존 실행에 연결하고, 취소 API는 MVP에서 제공하지 않는다. JOB_EXECUTION_LINK는 collection_job_id와 project_source_id로 연결되는 ingestion job_id를 저장한다. 수집 실패는 그 출처 실패, 수집 성공 후 적재 필요 시 해당 ingestion 완료까지 RUNNING, 적재 불필요 정상 NO_CHANGE는 성공이다. 모든 대상 종료 후 모두 성공이면 SUCCEEDED, 혼합이면 PARTIAL, 모두 실패면 FAILED다. 늦게 연결한 요청도 Control의 수집·적재 작업 원장을 조회하고 미확인 결과는 원래 ingestion.execute를 같은 job_id와 payload로 재전달하여 receipt의 기존 결과 재발행을 받아 동일 상태로 수렴한다. 다른 프로젝트 결과를 반환하지 않는다.

공개 전 복구는 준비된 승인 정의와 승인 샘플, 공개 후는 공개 스키마와 DB 최신값을 사용한다. CHANGE 동기화가 공개 경합으로 거절되면 dataset.applied.error.code=SOURCE_ALREADY_PUBLISHED와 details의 project_source_id·schema_version·connector_revision·ingestion_job_id를 반환한다. Control은 기존 ingestion.execute의 원래 job_id·envelope·payload를 재전달하고 Ingestion은 receipt의 기존 ingestion.completed와 event_id를 재발행한다. Control은 해당 수집·출처에 속하는 성공 결과를 확인한 뒤 ACTIVE로 다시 동기화한다. 재확인 전에는 새 규칙을 활성화하지 않는다. 일반 revision 불일치·CLOSED는 각기 REVISION_CONFLICT·CHANGE_CLOSED이며 자동으로 성공 처리하지 않는다.

### 20.4 내부 HTTP와 인증

`GET /internal/v1/projects/{project_id}/access`는 서비스 신원 인증과 `X-Principal-Type: USER|API_KEY`, `X-Principal-Id`(USER는 user_id, API_KEY는 Data가 검증한 key_id), `X-Access-Scope: READ|MANAGE`를 받는다. 키 원문·해시는 보내지 않는다. Control은 프로젝트 존재·상태와 USER 소유권 또는 Data가 검증한 API_KEY의 프로젝트 범위 및 Auth 소유자 상태를 확인한다. API_KEY는 READ만 허용한다. 200은 `{ "project_id":"<uuid>","allowed":true }`, 권한 부재는 403, 의존 상태 확인 실패는 503이다. 내부 서비스 신원은 인증 계층이 검증하며 외부 클라이언트가 이 헤더를 직접 신뢰받지 못한다.

API 키 발급 개수는 제한하지 않는다. 발급과 프로젝트 삭제의 경합을 막기 위해 Data는 프로젝트 상태 행을 유일 제약으로 생성 또는 확인하고 잠근 뒤 삭제 차단 상태 확인과 키 저장을 같은 트랜잭션에서 처리한다. 삭제 차단 기록은 기존 재처리 기간 동안 유지하며 삭제된 상태를 다시 열지 않는다. Redis 제한 계수를 읽거나 갱신할 수 없으면 외부 API 키 요청을 503으로 거절한다. last_used_at 기록 실패만으로 성공 요청을 실패시키지 않으며 목록에는 null을 허용한다. JWT 요청에는 API 키별 제한·사용 시각을 적용하지 않는다. MVP에서 Data는 공개 스키마와 레코드를 같은 DB 읽기 스냅샷으로 조회하고 공개 스키마 캐시 및 무효화 이벤트를 사용하지 않는다.

로그아웃은 정상 CSRF·요청 범위를 검증한 뒤 이미 폐기된 같은 세션도 204를 반환한다. 계정 정리 요청은 Auth가 탈퇴 상태와 재시도 대상을 DB에 기록한 뒤 같은 owner_id로 재요청하며 Control은 기존 상위 job을 반환한다. Control은 완료 통지를 재전송 가능하게 보관하고 Auth는 중복 수신에 204를 반환한다. 내부 삭제 영향 조회와 project-data.delete/deleted는 16절의 기존 경로·payload를 정식 계약으로 사용한다.

초기 분석 대상이 다른 동시 요청은 409로 처리한다. 가입 번호의 202는 메일 공급자 접수, 비밀번호 초기화 번호의 202는 계정 유무를 숨기는 요청 접수이며 실제 발송을 보장하지 않는다. 사용자 표시명은 trim 후 1~50자이며 API name은 DB display_name에 매핑하고 잘못된 값은 422 INVALID_NAME이다.


### 20.5 목록 진행·재처리·알림

- 목록 이동은 URL만 비교하지 않고 이동 전후의 확인 가능한 항목 구간과 실제 진행을 함께 본다. 동일 구간으로 되돌아가거나 실질적 진행 없이 반복되면 NAVIGATION_FAILED다. 스크롤·더 보기에서 대기 후 새 항목이 연속 2회 없으면 기존 END_OF_LIST를 유지한다. 순환 판별의 구체 구현은 실제 사이트에서 검증하며 정렬 변화·고정글에 따른 누락은 허용한다.
- item_selector로 항목을 분리하고 각 필드·상세를 그 항목과 연결하는지 Analysis가 실행 검증한다. 일반 누적 DOM에서 이미 처리한 항목을 다시 열거하지 않는다. MVP는 가상화 DOM의 제거·재사용 항목을 복원하거나 완전한 중복 판별을 보장하지 않으며 누락을 허용한다. 확인된 manifest 항목과 상세를 다른 항목에 임의로 연결하지 않는다.
- 초기 분석 5분 안에서 HTTP 목록 검증을 수행한다. 동일성 검증에 실패하거나 예산이 부족하면 미검증 HTTP 규칙을 채택하지 않고 검증 가능한 브라우저 규칙을 사용한다. 시간 수치는 운영 설정이다.
- 문제 알림은 해당 프로젝트·출처의 같은 미해결 문제에 중복 발송하지 않도록 상태를 보관하며 기술 발송 실패는 제한 재시도한다. 수집·적재를 되돌리지 않는다. 공급자 응답이 유실된 경우 정확히 한 번 전달을 보장하지 않고 공급자의 멱등 기능이 있으면 같은 발송 식별자를 사용한다. 공급자와 재시도 수치는 운영 설정이다.
- receipt·이벤트 수신 기록·Outbox와 같은 시도 S3 결과의 보관 기간은 관련 SQS·DLQ 재처리 가능 기간 이상으로 구성한다. 참조 및 실제 실행 종료 이전 삭제를 금지하는 S3 정책은 유지한다. 수치와 토큰·lease·heartbeat·visibility는 운영 설정이다.

### 20.9 79개 흐름 점검 후 화면·동시성 보완

**분석 조회 상태.** `GET /projects/{p}/analyses/{a}`의 status는 `RUNNING`, `READY`, `FAILED`, `CANCELLED`다. Control이 유효한 analysis.completed의 `SUCCEEDED` 또는 `PARTIAL`을 채택하면 HTTP는 모두 `READY`이며 성공·실패의 구분은 sources의 결과로 표시한다. 전체 실패는 `FAILED`, 승인 전 취소는 `CANCELLED`다. 실행·결과 처리 중은 `RUNNING`이다. 이는 메시지 이벤트 status와 별도의 HTTP 값이다. 초기 분석 중에는 전체 분석 진행만 표시한다. 개별 출처 결과는 해당 작업의 완료 이벤트가 채택된 뒤에만 표시하며 임의의 출처별 진행률이나 단계를 만들지 않는다. 후보 재검증 중은 analysis 상태와 별도인 validation_status=PENDING을 따른다.

**실패 출처 재시도와 승인.** Control은 동일 analysis_id·project_source_id의 진행 중 retry가 있으면 새 job을 발급하지 않고 기존 job_id를 202로 반환한다. 대상·현재 revision 확인과 JOB·Outbox 생성은 같은 Control 트랜잭션에서 직렬화한다. 결과 수신 시 현재 revision과 요청 revision을 잠금 또는 조건부 갱신으로 비교한다. 달라졌거나 후보가 승인·취소됐다면 결과를 반영하지 않으며 최신 후보에서 다시 시도한다. 일치하면 대상 출처 결과·샘플을 병합하면서 후보 revision을 한 번 증가시키고, 영향 출처의 검증을 새 revision에서 확인한다. 영향 없는 기존 검증만 11절 기준으로 승계하며 sample_revision은 바꾸지 않는다. 결과 채택·revision 증가·이벤트 중복 기록과 필요 재검증 Outbox를 한 트랜잭션에 기록한다. 승인도 같은 후보 행·revision을 검사하므로 사용자가 이전 revision을 승인하면 409로 후보를 다시 조회한다. 실패 결과가 후보 내용을 바꾸지 않으면 실패 상태만 기록한다.

**기존 변경 후보에 출처 추가.** `POST /control/v1/projects/{p}/sources`는 `{ "list_url":"https://example.com/list", "analysis_id":"<uuid>", "expected_revision":2 }`를 받는다. 대상은 소유 프로젝트의 승인 전 편집 후보여야 한다. Control은 PROJECT와 해당 ANALYSIS 후보를 잠그고 상태·revision을 확인해 PROJECT_SOURCE 후보와 후보 연결·revision 증가, ADD_SOURCE job·요청 Outbox를 원자적으로 저장한다. `201 { "project_source_id":"<uuid>", "analysis_id":"<uuid>", "candidate_revision":3, "job_id":"<uuid>" }`를 반환한다. ADD_SOURCE analysis.execute의 analysis_id·revision은 생성된 동일 후보와 새 revision이며 기존 공동 스키마를 사용한다. 이후 결과 병합에도 위 revision 경계를 적용한다. revision이 다르면 409 REVISION_CONFLICT·current_revision이며 어떠한 출처도 생성하지 않는다. 최초 프로젝트 생성은 POST /projects의 source_urls로 처리하며 이 추가 API와 구분한다.

**인증 예외.** 비밀번호 초기화 메일 요청은 계정 존재 여부나 개별 수신자 발송 결과에 관계없이 동일 202를 반환한다. 전체 시스템 장애처럼 계정 유무와 무관하게 탐지 가능한 공통 장애는 동일 503을 반환할 수 있다. 내부 실패를 기록하고 유효한 경우 재시도하되 외부 202는 실제 수신 보장이 아니다. 가입 결과가 불확실하면 재인증 또는 로그인을 안내한다. reset_token 소비 후 DB 실패·응답 유실이면 새 인증번호 확인부터 다시 진행하며 단순 입력 오류는 화면에서 수정한다. 탈퇴 접수는 Control의 202 확인 후 웹에 202 status=ACCEPTED와 job_id를 반환하고 프로젝트 정리는 비동기로 계속한다. Control 접수 여부 불확실 시 차단 유지·멱등 재확인하고 계정 사용 가능을 단정하지 않는다.

**수집 종료와 화면 표시.** 일반적으로 유효한 `SUCCEEDED + END_OF_LIST`는 completed, `SUCCEEDED + NO_CHANGE`는 completed(조기 종료), `SUCCEEDED + TIME_LIMIT`은 time_limit_reached다. PARTIAL·FAILED·CANCELLED는 작업 status를 유지하고 stop_reason과 각 출처 오류를 함께 표시한다. stop_reason만으로 실패를 성공으로 바꾸지 않는다. 정상 추출 건수 null은 미측정으로 표시하고 실측 0만 0건으로 표시한다.
