# S3 저장 명세

- 상태: 구현 계약 초안. 저장 경로·본문·생성/조회·정리 조건을 정의한다. 수집·분석·적재 메시지는 [서비스 메시지 명세](service-message-contracts.md), 작업 순서는 [메시지 전달 흐름](service-message-flows.md)을 따른다.
- 이 명세는 **Analysis 중간 결과**, **후보 revision 검증 샘플**, **Crawler 수집 스냅샷**을 구분한다. 시도별 `result.json`은 결과 이벤트 재발행용 기록이며 최종 채택은 Control DB에서 결정한다.
- 아래 버킷명은 예시이며 환경별 설정이다. 파일의 실제 저장과 메시지 전송은 구현 대상이지 완료된 기능이 아니다.

## 1. 공통 규칙

### 1.1 참조와 식별

- 서비스 간 참조는 `{ "bucket": "...", "key": "..." }` 객체를 사용한다. 설정된 버킷과 허용 prefix만 접근한다. 인증정보·presigned URL은 작업 메시지에 싣지 않는다.
- `analysis_id`, `job_id`, `project_id`, `project_source_id`, `source_id`, `field_id`는 메시지 명세의 UUIDv4다. 경로에 넣을 때 소문자 하이픈 형식을 사용한다. `revision`은 양의 정수다.
- `attempt_id`는 **프로세스가 실행 시도마다 새로 생성하는 UUIDv4**다. 같은 `job_id`가 동시에 재전달되어도 충돌하지 않도록 한다. 업무상 새 작업 ID나 원본 게시물 ID가 아니다. Analysis·Crawler의 완료 payload와 Crawler의 중간 issue payload에 넣으며 공통 envelope에는 추가하지 않는다.
- JSON은 UTF-8과 `format_version: 1`, 시간은 RFC 3339 UTC를 사용한다. 파일은 완성 후 업로드하고 key를 덮어쓰지 않는다. 저장 실패 또는 완성되지 않은 객체를 성공 결과로 노출하지 않는다.
- 버킷은 환경별로 설정한다. 아래의 `weavo-artifacts`는 예시다. 경로 자체에 사용자 입력 URL·비밀번호·토큰을 포함하지 않는다.

### 1.2 시도별 결과 저장과 Control 채택

Analysis와 Crawler는 Control의 유효한 lease를 얻은 각 시도마다 중간 결과·샘플 또는 스냅샷을 불변 경로에 저장하고 아래 **시도별** 경로에 완성된 결과 이벤트를 저장한다.

```text
analysis/{analysis_id}/jobs/{job_id}/attempts/{attempt_id}/result.json
collections/{job_id}/attempts/{attempt_id}/result.json
```

이 파일은 동일 시도가 재발행할 `event_id`, `occurred_at`, `attempt_id`와 완전한 결과 envelope·payload다. 새 시도는 새 attempt_id·event_id를 사용하고 이전 시도의 파일을 재발행하거나 덮어쓰지 않는다. 동일 시도 내 재발행에서는 전체 결과를 그대로 보존한다. 각 파일의 존재나 선착순 생성은 결과 채택이 아니다. Crawler의 snapshot_ref는 같은 시도의 manifest이고 Analysis의 새 sample_ref·intermediate_ref는 해당 시도 소유다. RETRY_SOURCE의 명시적 기존 intermediate_refs와 검증 승계 sample_ref는 예외로 기존 소유·revision·승계 근거를 검증한다.

1. Analysis/Crawler는 유효한 job_id·attempt_id lease를 얻고 SQS visibility와 lease를 연장하며 객체와 시도 결과를 완성한다. 성공·업무 실패도 같은 절차다. 같은 시도의 재전송만 동일 event_id·본문을 사용한다.
2. 결과 이벤트를 SQS에 성공 발행한 뒤 해당 command의 수신 핸들을 삭제한다. S3 저장이나 SQS 발행이 실패하거나 발행 여부가 불확실하면 성공 삭제하지 않고 같은 시도의 이벤트를 재발행한다. 현재 lease를 잃으면 추가 발행·작업을 중단한다. 이미 SQS에 보낸 늦은 결과는 Control이 판정한다.
3. Control은 event_id 중복 확인을 먼저 하고, 미처리 결과는 DB 시간 기준 유효 lease·현재 attempt_id·작업 상태와 대상 revision/취소 여부를 한 트랜잭션에서 확인한다. 조건을 충족하면 작업 결과·참조·수신 기록·후속 Outbox를 함께 커밋한다. 중복·오래된 결과는 업무 상태를 변경하지 않고 안전하게 소비한다. 성공·실패 중 유효한 시도의 첫 채택만 인정한다.
4. 메시지 발행 후 Control 수신 전 lease가 만료되어 결과가 거부되거나 command가 이미 삭제된 경우에도 Control의 기한 점검이 미완료 작업을 찾아, 시작 기한 이내이면 동일 job_id·동일 payload를 Outbox로 다시 발행한다. 시작 기한 경과 시 새 시도를 시작하지 않는다. 이미 시작한 유효 시도는 lease·작업 상태·별도 실행 종료 기한에 따라 처리하고, 유효한 시도가 없고 재처리할 수 없으면 Control이 실패·확인 필요로 종료한다. 늦은 시도는 채택되지 않으며 재실행 비용은 허용한다.

수신된 결과 메시지 자체가 작업을 결정하는 근거이며 S3 기록은 같은 시도의 재발행과 추적용이다. 시도 결과·참조는 재전달·DLQ·후속 작업 가능 기간과 실제 실행 종료까지 보존하고 승인 샘플·최신 정상 스냅샷은 별도 보존한다. 구체적 기간은 운영 설정이다.

### 1.3 정리의 공통 조건

- S3 저장과 Control DB 변경은 하나의 트랜잭션이 아니다. 새 객체 저장 → 현재 revision/작업 대조 → DB 참조 확정 순으로 처리한다. DB 참조 갱신 전에 실패해도 기존 승인 샘플·최신 스냅샷 참조는 유지한다.
- 객체는 현재 후보·승인 설정·최신 정상 스냅샷·대기/진행 중 작업·재처리 가능한 최종 결과에서 참조하지 않을 때만 삭제한다. 단 실패 원본 sidecar는 3.2의 별도 7일 보관 정책이 우선하며 만료 후 샘플의 참조는 원본이 만료됐음을 나타낸다. 취소·기한 종료 표시만으로 실제 실행이 즉시 멈췄다고 가정하지 않는다.
- 참조 확인 직후 새 승인·재시도 작업이 같은 객체를 참조하는 경합도 막는다. 객체를 삭제 대상으로 확정하는 소유 작업의 상태 변경과 새 참조 등록을 직렬화하고, 삭제 대상으로 확정된 객체의 새 참조를 거부한다. 이미 시작된 실제 사용 작업이 끝난 후 삭제한다. 구체적 잠금·상태 표현은 Control/작업 구현에서 정한다.
- 7일은 **비승인 임시 객체나 미완성 객체를 정리 대상으로 검사하는 기준**이다. 생성 후 일괄 만료시키는 규칙이 아니며 참조 확인과 지연 작업 차단 후 정리한다. 승인 샘플·최신 스냅샷·재발행이 가능한 작업 결과에는 자동 만료를 적용하지 않는다.
- 정리 순서는 ① 재전달·DLQ 재처리 가능 기간과 실제 실행·후속 작업 종료를 확인 ② 최종 결과의 재발행 가능 상태를 종료 ③ 승인 샘플·현재 후보·최신 정상 스냅샷 등 독립된 활성 참조는 유지 ④ 나머지 객체와 최종 결과를 정리한다. 최종 결과는 다른 객체의 실제 삭제를 기다리지 않으므로 순환 대기하지 않는다.
- 위 임시 객체 7일은 실패 원본 문자열의 7일 보관, 로그의 30일 보관, DB 백업의 7일 보관 정책과 별개다. S3 수거 주체·주기 및 재전달 가능 기간의 구체 값은 운영 구현에서 정한다.

## 2. 출처별 분석 중간 결과

### 2.1 역할과 경로

Analysis가 출처별 DOM에서 필드·샘플·선택자·목록 및 상세 이동 후보를 분석한 후, 공동 스키마 추천의 입력으로 사용하는 임시 JSON이다. DOM 원본은 S3에 저장하지 않는다.

```text
analysis/{analysis_id}/intermediate/{project_source_id}/{job_id}/{attempt_id}/result.json
```

- **생성·조회:** Analysis. 초기 공동 추천은 같은 시도의 출처별 파일을 읽는다. `RETRY_SOURCE`는 Control이 전달한 기존 성공 출처의 `intermediate_refs`와 해당 출처의 새 결과를 읽는다. 기준 revision을 확인하고 기존 사용자 수정을 보존한다.
- **Control:** 중간 파일 본문을 읽거나 프론트에 노출하지 않는다. INITIAL/RETRY_SOURCE 결과의 성공 출처별 `intermediate_ref`만 현재 분석에 기록하고 다음 RETRY_SOURCE의 `intermediate_refs`로 전달한다. stale revision 결과나 미채택 시도의 참조는 활성 참조를 덮어쓰지 않는다. 형식은 메시지 명세 6.1·6.2를 따른다.
- **단위:** 분석·프로젝트 출처·작업·실행 시도별 UTF-8 JSON 한 개. 복수 샘플은 `sample_values`에 담는다.
- 출처 중간 후보는 공통 스키마 생성 전이므로 `candidate_key`는 이 파일 내부에서만 고유하다. 공통 스키마의 `field_id` 또는 실제 사이트의 게시물 ID가 아니다. 동일 의미를 판단한 뒤 공통 필드와 출처별 후보를 매핑한다. 매핑은 `(intermediate_ref, candidate_key) → field_id`로 분석 후보 메타데이터에 보관한다. 참조 자체에 분석·출처·시도가 포함되므로 다른 파일의 같은 키와 구분된다. 사용자 수정과 현재 revision을 우선하고 출처 재시도 시 매핑이 불명확하면 추측해 덮어쓰지 않고 재검증·사용자 확인 대상으로 둔다. 이 매핑은 분석 중에만 필요하며 런타임 필드 커넥터나 운영 레코드의 새 ID가 아니다.

### 2.2 파일 본문

```json
{
  "format_version": 1,
  "analysis_id": "ef7ba73e-6843-4ecf-963a-599cc3442714",
  "job_id": "4ad6b0fb-6031-4c38-a8a9-7f52185a6601",
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "list_url": "https://school.example/notices",
  "created_at": "2026-11-06T00:00:00Z",
  "fields": [
    {
      "candidate_key": "candidate-1",
      "name": "제목",
      "suggested_type": "string",
      "sample_values": ["장학금 안내", "수강 신청 안내"],
      "location": "LIST",
      "selector": ".notice-title",
      "extract": {"type": "TEXT", "attribute": null},
      "detail_navigation": null,
      "parser": null,
      "reason": "목록 항목의 제목",
      "validation_status": "PASSED"
    }
  ],
  "navigation": {
    "list_url": "https://school.example/notices",
    "initial_actions": [],
    "list_access": {"method": "HTTP", "wait_selector": null},
    "item_selector": ".notice-list .notice",
    "pagination": {"type": "NEXT_LINK", "selector": ".pagination .next", "url_attribute": "href", "wait_selector": null}
  },
  "detail_candidates": [],
  "validation_status": "PASSED"
}
```

`fields`에는 공동 추천에 사용할 출처별 후보·표본·추출 위치·선택자·필드별 상세 이동·추출·파싱 후보와 추천 근거를 둔다. `navigation`과 상세 후보의 구체 동작은 메시지 명세 5.1~5.2의 규칙 형식을 따른다. 상세 위치 필드의 `detail_navigation`에는 검증할 접근·이동·복귀 후보를 담고 필드 가공이 필요하면 `parser.code`를 사용한다. 성공한 후보만으로 유효성을 단정하지 않으며 각 후보와 출처 검증 결과를 보존한다. 최초 분석에서 성공한 출처가 하나 이상이면 그 결과로 공동 스키마 후보를 추천하고 실패 출처는 재시도·제외할 수 있다. 모든 출처가 실패하면 후보를 승인할 수 없다. 재시도 결과는 사용자 수정을 보존하고 필요한 검증을 수행한다.

### 2.3 정리

공동 추천 성공만으로 삭제하지 않는다. 해당 분석이 사용자 승인·취소 등으로 종료되어 후속 출처 재시도에 더 이상 필요하지 않고 해당 객체를 읽는 재시도·공동 추천 작업이 끝나면 삭제 대상이다. Control은 분석 종료 시 출처별 intermediate_ref를 해제한다. 실패·일부 성공으로 재분석이 필요한 동안은 유지한다. 7일이 지난 고아 임시 객체는 1.3의 참조 확인 뒤 정리한다. 이미 생성된 다른 출처의 중간 결과와 승인용 샘플은 별도다.

## 3. 후보 revision별 검증 샘플

### 3.1 역할과 경로

Analysis가 후보 revision을 검증한 추출값을 저장한다. Control은 이를 읽어 사용자에게 표시하며 승인한 샘플 참조를 저장한다. DB 0건 복구 시 Analysis는 승인된 샘플을 기준으로 사용한다. 후보 설정 원본과 승인 상태는 Control이 관리한다.

```text
analysis/{analysis_id}/revisions/{revision}/{project_source_id}/{job_id}/{attempt_id}/sample.json
```

- **생성:** Analysis. 사용자 승인 전에 저장한다. 저장 실패는 승인 가능한 검증 성공이 아니다. 실패 원본 sidecar가 필요한 경우 sample.json보다 먼저 저장하며 sidecar 저장 실패는 실패 원문 확보 실패로 기록하고 복구·재시도한다.
- **조회:** Control은 사용자 검토 시, Analysis는 승인된 샘플을 이용한 복구 검증 시 읽는다.
- **참조:** Analysis는 결과 이벤트의 `source_results[].sample_ref`에 정확한 불변 객체 참조를 전달한다. Control은 현재 후보에 유효한 검증 revision, 샘플이 실제 생성된 `sample_revision`과 참조를 함께 기록한다. 승인 시 해당 참조를 승인 설정에 연결한다. revision만으로 경로를 추측하거나 샘플 본문을 메시지에 넣지 않는다. 추출에 영향 없는 변경으로 검증을 승계하면 유효 검증 revision은 새 후보 revision으로 바꾸고 `sample_revision`과 불변 sample_ref는 기존 값을 유지하며 승계 근거를 함께 기록한다. 필드·매핑·이동·추출·파싱 또는 샘플 해석에 영향이 있으면 재검증한다.
- 공유 목록 복구의 상위 작업 `project_id`가 null이어도 개별 샘플은 실제 프로젝트와 `project_source_id`를 갖는다.

### 3.2 파일 본문

UTF-8 JSON 한 개는 프로젝트 출처 하나와 후보 revision 하나의 샘플 집합이다.

다음은 메시지 명세 6.2에서 참조하는 **성공 후보**의 샘플이다. 후보에 정의된 제목 필드만 포함한다.

```json
{
  "format_version": 1,
  "analysis_id": "ef7ba73e-6843-4ecf-963a-599cc3442714",
  "revision": 1,
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "captured_at": "2026-11-06T00:01:10Z",
  "samples": [
    {
      "sample_index": 0,
      "source_url": "https://school.example/notices/1",
      "fields": [
        {
          "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
          "raw_values": [
            "장학금 안내"
          ],
          "match_count": 1,
          "failure_raw_ref": null,
          "value": "장학금 안내",
          "status": "SUCCESS",
          "error": null,
          "failure_expires_at": null
        }
      ]
    }
  ]
}
```

다음은 다른 후보의 실패·부재 상태를 설명하기 위한 **별도 샘플 예시**다. 메시지 명세 6.2의 성공 sample_ref가 이 예시를 가리키는 것은 아니다.

```json
{
  "format_version": 1,
  "analysis_id": "ef7ba73e-6843-4ecf-963a-599cc3442714",
  "revision": 1,
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "captured_at": "2026-11-06T00:01:10Z",
  "samples": [
    {
      "sample_index": 0,
      "source_url": "https://school.example/notices/1",
      "fields": [
        {
          "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
          "raw_values": [
            "장학금 안내"
          ],
          "match_count": 1,
          "failure_raw_ref": null,
          "value": "장학금 안내",
          "status": "SUCCESS",
          "error": null,
          "failure_expires_at": null
        },
        {
          "field_id": "f1109132-a764-4415-a221-e0757bf0b112",
          "raw_values": [],
          "match_count": null,
          "failure_raw_ref": null,
          "value": null,
          "status": "ABSENT",
          "error": null,
          "failure_expires_at": null
        },
        {
          "field_id": "c47d2286-10a3-4071-a721-78bd567d6805",
          "raw_values": [],
          "match_count": 1,
          "failure_raw_ref": {
            "bucket": "capstone-artifacts",
            "key": "analysis/ef7ba73e-6843-4ecf-963a-599cc3442714/failures/95f7d7de-ec00-4fbe-b430-fa3a79763c18/4ad6b0fb-6031-4c38-a8a9-7f52185a6601/43f5b110-f193-4157-91f2-1a16b8fb70d6/sample-0/field-c47d2286-10a3-4071-a721-78bd567d6805.json"
          },
          "value": null,
          "status": "FAILED",
          "error": {
            "code": "TYPE_CONVERSION_FAILED",
            "stage": "ANALYSIS_VALIDATION",
            "message": "날짜 형식 변환에 실패했습니다.",
            "details": {}
          },
          "failure_expires_at": "2026-11-13T00:01:10Z"
        }
      ]
    }
  ]
}
```

| 필드 | 형식 및 의미 |
|---|---|
| `sample_index` | 이 파일의 0부터 시작하는 샘플 구분값. 사이트 ID나 DB record_id가 아님 |
| `source_url` | 해당 샘플 항목에 직접 접근할 수 있다고 확인한 절대 URL 또는 null. 목록 URL·브라우저에서 관찰한 URL과 구분하며 같은 URL을 동일 레코드로 단정하지 않음 |
| `field_id` | 해당 후보 스키마의 공통 필드 UUID |
| `raw_values` | selector에서 추출한 원문 문자열 배열. 원래 없는 필드와 실패한 필드는 빈 배열 |
| `match_count` | 샘플에서 선택자에 일치한 요소 수. 실행하지 못했거나 ABSENT면 null, 실행했지만 일치가 없으면 0 |
| `failure_raw_ref` | FAILED이면서 실패 원본이 확보된 경우에만 별도 임시 S3 sidecar 참조. 나머지는 null |
| `failure_expires_at` | sidecar 실패 시각 + 7일의 RFC 3339 UTC 시각. `failure_raw_ref`가 null이면 null |
| `value` | 공통 파싱·타입 변환 결과. string·number·boolean·null; date·datetime·url은 문자열 |
| `status` | `SUCCESS`, `ABSENT`, `FAILED`; 아래 조건 적용 |
| `error` | 성공·원래 없음은 null, 실패는 메시지 명세 1.4의 오류 객체 |

`SUCCESS`는 성공적으로 추출·파싱·타입 변환한 상태다. 정상적인 null도 `SUCCESS`와 `value: null`로 표현할 수 있다. `ABSENT`는 해당 출처에 원래 없는 필드로 정상 null이다. `FAILED`는 추출·파싱·변환 실패이며 `value: null`과 오류 원인을 함께 기록한다. 값이 null이라는 이유만으로 실패 또는 원래 없음으로 분류하지 않는다. 후보가 승인 가능한지는 기존 출처별 검증 결과와 필수 조건으로 판단하며 샘플 개별 상태만으로 자동 승인하지 않는다.

성공 필드의 원문과 변환값은 사용자가 소수의 승인 샘플을 비교하고 복구 검증을 할 수 있도록 함께 보관한다. 이 파일은 운영 레코드별 원문 보관 정책을 대체하지 않는다. 샘플 간 동일 레코드 여부는 `sample_index`·URL만으로 추론하지 않는다.

실패 원본의 7일 보관 정책과 승인 샘플의 장기 보관이 충돌하지 않도록 `FAILED`의 `raw_values`는 빈 배열이고 `error`의 message·details에도 원문을 싣지 않는다. 원문이 확보되면 Analysis가 다음 경로의 별도 임시 sidecar에 저장하고 해당 필드의 `failure_raw_ref`로 연결한다. 원문이 없으면 `failure_raw_ref`와 `failure_expires_at` 모두 null이다. sidecar가 있을 때 만료 시각을 샘플에 함께 저장하여 객체가 삭제된 이후에도 만료와 만료 전 누락을 구별한다.

```text
analysis/{analysis_id}/failures/{project_source_id}/{job_id}/{attempt_id}/sample-{sample_index}/field-{field_id}.json
```

```json
{
  "format_version": 1,
  "analysis_id": "ef7ba73e-6843-4ecf-963a-599cc3442714",
  "revision": 1,
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "sample_index": 0,
  "field_id": "c47d2286-10a3-4071-a721-78bd567d6805",
  "observed_at": "2026-11-06T00:01:10Z",
  "raw_values": ["변환 실패 원문"]
}
```

Control은 `sample_ref`로 샘플을 읽어 분석·revision·프로젝트 출처·sample_index·field_id·sidecar의 동일성을 검증하고 sidecar를 읽어 기존 검증 정보에 원문과 실패 시각을 기록한다. SQS에는 원문을 넣지 않는다. 이 원문은 마지막 실패 시각 기준 7일 보관 후 삭제한다. sidecar나 결과 재전달을 늦게 처리해도 만료 시각은 observed_at을 기준으로 계산하며 읽은 시각부터 다시 7일로 늘리지 않는다. 이미 만료된 sidecar는 DB에 복사하지 않는다. 같은 레코드임을 신뢰성 있게 확인할 수 있을 때만 후속 실패 원문으로 교체하며 URL이나 sample_index만으로 다른 실행의 동일 레코드임을 단정하지 않는다. 확인하지 못한 별도 실패는 각각의 보관 기한을 적용한다.

sidecar도 원래 실패 시각으로부터 7일을 넘겨 보관하지 않는다. 참조 중인 샘플이나 시도별 결과가 있어도 실패 원문은 예외적으로 만료시킨다. Control은 샘플의 `failure_expires_at`을 먼저 확인한다. 만료 시각 이후에는 원본 만료로 표시하고 sidecar 조회에 의존하지 않는다. 샘플·검증 자체가 사라진 것은 아니다. 만료 전에 S3 읽기 실패는 조회 오류이지 정상 빈 값이 아니며 Control은 재시도하고 사용자에게 확인 불가를 표시한다. 성공 필드의 샘플 원문은 승인 샘플의 일부다. 공동 추천 중간 결과와 결과 이벤트·운영 로그에도 실패 원문을 복사하지 않는다.

샘플 또는 sidecar 저장에 실패하면 같은 시도에서 저장을 재시도한다. S3 기술 오류가 해소되지 않아 완전한 샘플을 확정하지 못했다면 승인 가능한 결과를 발행하지 않고 요청을 성공 삭제하지 않는다. 기존 SQS 재전달·작업 기한·DLQ 정책에 따라 처리하며 Control은 기술 실패 또는 기한 초과를 표시한다. 이때 이미 저장한 sidecar는 샘플 참조가 없는 고아 객체로 7일 기한 이내 정리한다. 원문 확보 없이 규칙 오류를 판단할 수 있더라도 저장 장애를 정상 검증 성공으로 바꾸지 않는다.

### 3.3 Crawler·Ingestion 실패 원본

Crawler의 임시 추출과 Ingestion의 최종 추출·파싱에서 실패한 필드의 원본이 확보되면 해당 서비스가 아래 별도 sidecar를 저장한다. 스냅샷은 전체 화면 보관용이므로 실패 원본의 7일 보관을 대신하지 않는다. 원본이 없거나 필드 단위 오류가 아니면 sidecar를 만들지 않는다.

```text
failures/{reporting_service}/{job_id}/{attempt_id}/{project_source_id}/{field_id}/{failure_index}.json
```

`reporting_service`는 `CRAWLER` 또는 `INGESTION`, `attempt_id`는 각 실행 시도 UUIDv4, `failure_index`는 해당 시도에서 0부터의 번호다. 결과·issue 중 먼저 발행하는 메시지의 오류 객체에 아래 참조를 넣고 중복 통지 시 같은 참조를 재사용한다. 원본 문자열은 SQS와 로그에 넣지 않는다. 아래 값은 오류 객체 안의 선택 필드 `failure_evidence`의 형식이다.

```json
{
  "failure_evidence": [{
    "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
    "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
    "ref": {"bucket": "capstone-artifacts", "key": "failures/CRAWLER/6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc/43f5b110-f193-4157-91f2-1a16b8fb70d6/95f7d7de-ec00-4fbe-b430-fa3a79763c18/ba4f9e1e-ae8e-4a3c-9261-dca457848148/0.json"},
    "expires_at": "2026-11-13T00:01:10Z"
  }]
}
```

sidecar 본문은 다음과 같다. `observed_at`을 기준으로 만료하며 `expires_at`은 정확히 그로부터 7일 후다.

```json
{
  "format_version": 1,
  "reporting_service": "CRAWLER",
  "job_id": "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc",
  "attempt_id": "43f5b110-f193-4157-91f2-1a16b8fb70d6",
  "project_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6",
  "project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18",
  "field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148",
  "failure_index": 0,
  "observed_at": "2026-11-06T00:01:10Z",
  "raw_values": ["변환 실패 원문"]
}
```

Control은 작업·출처·필드·발생 서비스와 참조 범위를 확인하고, 만료 전 sidecar를 읽어 검증 정보에 원문 및 발생 시각을 보관한다. 만료 후에는 원문 만료로 처리하며, 만료 전 조회 오류는 재시도·확인 불가로 처리한다. DB 복사본도 같은 시각에 만료한다. 안정적인 동일 레코드 식별이 없는 경우 다른 항목을 동일하다고 가정하지 않는다. sidecar 저장에 필요한 S3가 실패한 경우 원문을 메시지에 대체 삽입하지 않고 기술 오류 재전달·DLQ 정책을 따른다. 단 SQS 최종 결과의 Ingestion DB Outbox 처리와 S3 저장은 한 트랜잭션이 아니므로 먼저 sidecar를 확정한 뒤 결과 및 참조를 DB·Outbox에 함께 기록한다. 실패 시 남은 sidecar는 7일 내 정리한다.

### 3.4 정리

- 승인된 샘플은 프로젝트 출처의 승인 설정과 연결되어 복구에 필요한 동안 보관한다. 최초 수집이 실패하여 DB가 0건이어도 유지한다.
- 새 revision이 저장되었다는 이유로 이전 승인 샘플을 지우지 않는다. 새 설정과 새 승인 샘플 참조의 확정 후 기존 승인 참조가 해제되고 해당 샘플을 읽는 복구·검증 작업이 끝났을 때 이전 샘플을 정리한다.
- 비승인 후보 샘플은 후보가 취소·대체되고 참조하는 검증·조회 작업이 끝난 뒤 삭제한다. 분석 진행 중 현재 후보가 참조하면 7일이 지나도 유지한다.
- 프로젝트 또는 프로젝트 출처를 삭제하면 승인 참조를 해제하고 관련 분석·복구 작업이 종료된 후 해당 범위의 샘플을 정리한다. 다른 프로젝트의 샘플은 지우지 않는다.

## 4. Crawler 수집 스냅샷

### 4.1 역할과 경로

선택자·접근·추출·파싱·타입 변환 실패가 하나라도 있는 레코드는 부분 저장하지 않고 정상 추출 건수에서도 제외한다. 실제 null과 원래 없는 ABSENT 필드는 정상이며 오류와 구분한다. 실패 원인과 확보 가능한 원문은 기존 검증·sidecar 정책에 따라 기록한다.

한 공유 출처 수집 실행의 목록·필요한 상세 화면 묶음이다. 중간 구간 스냅샷은 적재 요청으로 전달하지 않는다. Crawler는 최종 수집 결과를 Control에 알리고, Control이 대상 프로젝트별 Ingestion 작업에 **같은 불변 snapshot_ref**를 넣는다.

```text
collections/{collection_job_id}/attempts/{attempt_id}/
  lists/list-0001.html
  lists/list-0002.html
  details/detail-0001.html
  details/detail-0002.html
  manifest.json
  result.json
```

- **생성:** Crawler. 목록·상세 파일을 저장한 뒤 동일 시도의 manifest를 완성한다.
- **조회:** Ingestion. 메시지의 `snapshot_ref`가 가리키는 manifest와 내부 object_ref를 따라 HTML을 읽는다.
- **참조 관리:** Control이 출처별 최신 정상 스냅샷 참조 및 작업별 참조를 관리한다. 작업 메시지는 움직이는 최신 포인터가 아닌 채택된 시도의 구체적 manifest key를 전달한다.
- **HTML:** UTF-8 `.html`. 목록은 HTTP 응답 HTML 또는 브라우저 렌더링 DOM을 저장하고 상세는 브라우저 렌더링 DOM을 저장한다. 스크립트 실행 상태나 동적 행동 자체를 재현하는 브라우저 세션은 아니다.

### 4.2 manifest 본문

manifest의 필드와 다음 예시는 메시지 명세 5.3과 동일하다. `snapshot_id`, `item_index`, `sequence_index`, `record_key`, 상세 `consumers`가 목록 항목·상세 객체·프로젝트별 필드를 연결한다. 두 문서의 예시는 함께 갱신한다.

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

- `items[].source_urls`의 project_source_id 존재는 그 항목에 대한 프로젝트별 참여 표시다. 참여했으나 직접 URL이 없으면 null로 남기고, ID가 없으면 해당 프로젝트에서 범위 밖이므로 Ingestion은 추출하지 않는다. 프로젝트별 종료 구간 전체까지 표시하고 이후 항목은 표시하지 않는다. 실패한 항목은 참여 표시를 유지하고 작업 오류로 기록한다. 동일 ID 중복과 전체 collection.execute.projects에 없는 ID는 잘못된 manifest다. Crawler와 Control이 전체 대상 집합으로 검사한다. 개별 Ingestion은 자신의 project_source_id 참여만 추출하며 다른 합법적 참여 ID는 오류로 간주하지 않는다.
- `items[].source_urls`는 해당 항목의 프로젝트 출처별 직접 접근 URL을 전달한다. Crawler가 해당 항목을 수집한 각 project_source_id에 확인한 URL 또는 null을 기록하고 Ingestion이 일치하는 값을 RECORD.source_url에 저장한다. 화면의 관찰 `url`과 혼동하지 않는다.
- `item_selector`는 그 스냅샷의 저장된 DOM에서 Crawler가 목록 항목을 선택한 CSS다. Ingestion은 같은 선택자의 전체 일치 결과를 DOM 순서로 열거하고 `item_index`를 0부터 적용한다. 항목은 스크롤 5개 묶음의 상대 번호가 아니라 해당 파일의 전체 일치 결과 인덱스다. 일치 실패·범위 초과는 오류이며 다른 항목을 임의 선택하지 않는다.
- `item_selector`는 Crawler가 사용하는 브라우저 선택과 Ingestion HTML 파서 양쪽에서 같은 결과를 내는 공통 CSS만 허용한다. 분석 시 저장 DOM을 양쪽 실행기로 확인하고 순서·개수가 다르면 유효한 규칙으로 채택하지 않는다. 엔진 전용 선택자나 브라우저 런타임에만 존재하는 상태에 의존하는 선택자는 허용하지 않는다. 무한 스크롤의 가상화·DOM 재사용으로 보이지 않거나 이미 사라진 항목은 MVP에서 찾지 못할 수 있으며 누락을 허용한다. 다만 저장 DOM에서 이미 참조한 항목의 위치를 확인할 수 없으면 해당 참조를 다른 항목에 임의 매칭하지 않고 실패로 처리한다. 지원 CSS의 구체 범위는 양쪽 구현과 통합 테스트로 확정한다.
- `record_key`는 해당 스냅샷에서만 목록–상세를 연결하는 값이며 외부 게시물 ID나 DB record_id가 아니다.
- `sequence_index`는 수집 순서다. Ingestion은 각 프로젝트·출처의 새 결과를 뒤쪽부터 저장해 앞쪽 항목의 record_id가 커지도록 한다.
- 같은 목록 항목의 접근·이동·대기·복귀 규칙이 같은 상세는 한 번 저장하고 관련 프로젝트 출처·필드를 `consumers`에 연결한다. URL만 같은 클릭 동작을 무조건 합치지 않는다.
- `object_ref`는 같은 시도의 목록·상세 파일을 직접 가리킨다. S3에 저장한 순서가 아니라 manifest의 순서가 기준이다.
- 무한 스크롤 누적 DOM에서는 저장된 DOM 안에서 `item_index`가 정확히 무엇을 가리키는지 보존하고, 이전 구간에서 이미 처리한 항목을 중복 열거하지 않는다. 일반 누적 DOM의 새 항목 범위는 처리하되 가상화 DOM에서 사라진 항목 복원이나 완전한 중복 판별은 MVP에서 보장하지 않는다. 저장된 참조가 가리키는 항목은 Ingestion이 잘못 재선택하지 않도록 확인한다.
- 상세 접근 실패를 정상 null이나 데이터 일치로 바꾸지 않는다. 상세가 없는 항목의 `details`는 빈 배열이며, 실패한 경우 작업/프로젝트 결과의 오류와 범위로 전달한다. 일반 반복 수집에서 실패 레코드는 제외하고 정상 추출된 레코드는 적재할 수 있다. 실패로 최신 비교를 완료하지 못한 프로젝트 출처는 복구 대상으로 둔다. 승인 출처 추가 실패는 부분 쓰기를 공개하지 않고 리비전은 전체 전환을 보류한다.

### 4.3 최종 결과와 승격

1. Crawler가 목록·상세 객체와 같은 시도의 manifest를 저장한다.
2. Crawler는 attempt_id와 snapshot_ref를 포함한 `collection.completed` 이벤트를 같은 시도 경로의 result.json에 저장한 뒤 SQS에 발행한다.
3. Control은 현재 attempt_id·유효 lease·작업 상태·대상별 규칙을 DB에서 검사하고 성공한 경우에만 결과·스냅샷 참조·후속 Outbox를 함께 확정한다. 이전 시도 결과는 무시한다. 사이트 일시 오류는 기존 최대 2회·2초/5초 재시도를 따른다.
4. Control은 적재 가능한 대상에만 채택된 불변 manifest를 전달한다. Ingestion은 프로젝트별 커넥터로 최종 추출·검증·저장한다.

`NO_CHANGE`라도 앞에서 신규 데이터가 있으면 유효한 스냅샷·적재 대상이 있을 수 있다. **전혀 변경 없는 조기 종료**는 `snapshot_ref: null`, `can_ingest: false`로 기존 최신 정상 세트를 유지한다. 목록 0건, 접근 차단, 네트워크·렌더링·페이지 구조 검증 실패는 기존 최신 정상 세트를 덮어쓰지 않는다. 마지막 페이지 도달 또는 시간 제한 종료는 유효한 목록 항목 및 기존 정상 승격 조건을 만족하면 정상 결과가 될 수 있다.

### 4.4 삭제와 공유 결과의 경계

프로젝트 삭제 요청 시 Control은 해당 프로젝트의 새 작업과 지연 결과 반영을 차단하고 관련 실행을 종료·정리한다. 다른 프로젝트의 수집·데이터는 유지한다. 분석 결과·샘플·실패 원본 등 해당 프로젝트 전용 객체와 그 식별정보를 포함한 최종 결과 기록도 삭제 범위다.

공유 수집의 불변 manifest와 시도별 `result.json`은 여러 프로젝트를 함께 참조할 수 있으므로 통째로 즉시 삭제하거나 원본을 수정하지 않는다. 먼저 다른 프로젝트의 기존 결과 소비와 재처리를 끝내고, 최신 스냅샷이 계속 필요하면 삭제 대상의 `consumers`와 `source_urls` 및 그 밖의 프로젝트 전용 참조를 모두 제외한 새 manifest를 저장해 참조를 전환한다. 공유 HTML 객체는 다른 프로젝트가 계속 사용할 때 유지한다. 기존 manifest·최종 결과 및 프로젝트 정보가 담긴 전송 대기·DLQ 메시지는 재전달·재처리 가능 기간이 끝나고 소비가 끝난 뒤 정리한다. 삭제된 프로젝트에 대한 늦은 메시지는 차단한다. 이 정리가 완료되기 전에는 사용자에게 전체 삭제 완료로 표시하지 않는다. 큐에서 개별 메시지를 즉시 선택 삭제할 수 있다고 가정하지 않으며, 작업 결과·큐 보존 기간과 최종 삭제 지연의 구체 시간은 운영 설정으로 정한다.

### 4.5 정리

- 출처별 최신 정상 스냅샷 세트 하나를 유지한다. 신규·변경 데이터를 포함한 정상 실행이 승격되면 최신 참조를 교체한다. 새 스냅샷이 더 짧은 범위를 담을 수 있다.
- 이전 세트는 최신 참조 교체 후에도 해당 객체를 읽는 적재·재처리가 모두 끝날 때까지 유지한다. 해당 작업이 종료된 뒤 실제 객체를 정리한다.
- 중복 실행에서 채택되지 않은 시도, 업로드 실패·미완성 객체는 현재 실행이 더 이상 사용할 수 없고 결과·후속 작업에서 참조하지 않을 때 정리한다. 7일이 지난 고아 임시 객체는 1.3의 조건에 따라 검사한다.
- 마지막 활성 연결이 해제되면 일정과 공유 출처를 정리하되 실행 중 수집·적재가 끝나고 지연 결과 반영을 차단한 뒤 해당 출처 스냅샷을 삭제한다. 다른 프로젝트가 연결 중인 공유 출처는 유지한다.

## 5. 남은 구현 확인

1. 목록·상세 샘플을 실제로 저장해 Crawler의 manifest와 Ingestion의 선택자가 같은 항목을 재선택하는지 확인한다. 특히 무한 스크롤 누적 DOM과 클릭형 상세를 검증한다.
2. 시도별 result 저장·발행·요청 삭제·lease 만료·Control 채택 각 지점의 장애와 중복 실행을 테스트한다. 최종 결과 기록의 재처리 보장 기간을 SQS 정책과 함께 정한다.
3. S3 정리 주체와 주기, DB·S3 간 참조 검사 및 실제 실행 종료 확인 방법을 구현한다. 7일은 안전 조건 없는 자동 삭제 기한이 아니다.
4. 동일한 샘플에서 `SUCCESS`의 정상 null, `ABSENT`, `FAILED`를 Control 화면과 복구 검증에 동일하게 전달한다. 필드·오류 내용은 메시지 명세 및 UI 계약과 맞춘다.
5. 승인 전후 샘플 참조 교체, 실패·취소된 revision, 공유 출처 삭제 및 오래된 결과 도착을 테스트한다. HTTP 응답의 상세 본문 구조는 별도 사용자 API 계약에서 정한다.

## 6. 정리 실행과 작업 수명

Control이 주기적으로 정리 작업을 실행한다. 작업·DLQ 재처리 가능 기간과 실제 실행 종료를 확인하고 DB에서 삭제 대상 확정과 새 참조 등록을 직렬화한 뒤 객체를 삭제한다. 검사 주기·큐 보존 기간의 수치는 환경 설정이다. 재처리 중인 시도별 result.json과 승인 설정에서 참조하는 sample_ref는 보존하며 7일은 고아 후보 검사 기준이지 일괄 만료가 아니다. 작업별 attempt_id를 Control lease 및 S3 attempt 경로와 연결하고 결과는 Control DB에서 유효 시도와 revision을 검사해 채택한다. 실패 원본 sidecar와 DB 복사본은 기존 7일 정책을 유지한다.

### 항목 URL과 화면 스냅샷 URL의 차이

샘플 `source_url`은 사용자에게 해당 항목을 직접 열 수 있는 URL이 확인될 때만 저장한다. 클릭·모달이나 목록 화면처럼 개별 항목을 직접 열 URL이 없다면 null이다. 수집 manifest에서 HTML 객체와 연결된 화면 URL은 수집 당시 관찰한 목록·상세 화면 주소를 뜻하며, 이것만으로 항목의 `source_url`을 만들지 않는다. 상세 HTML은 항상 브라우저로 확보한다.

## 7. 공유 객체의 출처 제거

프로젝트 또는 프로젝트 출처 삭제 시 새 불변 manifest의 각 목록 항목에서 삭제 대상의 source_urls와 상세 consumers 등 project_id·project_source_id에 연결된 모든 전용 메타데이터를 제거한다. 남은 소비자가 없는 상세 객체 참조만 제거하고 다른 프로젝트의 항목·HTML·상세 연결은 보존한다. 새 manifest 저장과 참조 전환, 기존 실행·재전달 종료 후 이전 manifest/result.json 및 해당 프로젝트 전용 파일·메시지 정리를 기존 1.3·4.4 순서로 수행한다. 마지막 연결이 없으면 참조 사용 종료 뒤 공유 스냅샷을 정리한다.


## 8. 공유 manifest 정리본과 참가 범위

원본 채택 시도 prefix는 `collections/{collection_job_id}/attempts/{attempt_id}/`다. Control이 다른 소비·재처리를 확인한 후 같은 prefix의 `rewrites/{cleanup_job_id}/manifest.json`에 삭제 대상 ID를 제거한 불변 정리본을 쓴다. cleanup_job_id는 삭제 작업 UUID이며 같은 작업은 원본 manifest 참조와 정리 대상을 고정해 같은 본문과 key를 재사용한다. 정리본 저장 성공 후 응답 유실은 해당 key의 본문을 검증해 재사용한다. 서로 다른 정리 작업은 다른 key를 쓴다. 원본 manifest와 result.json은 수정하지 않는다.

정리본은 원본의 collection_job_id·attempt_id·source_id를 유지하고 남은 source_urls 및 detail consumers만 남긴다. 목록·상세 object_ref는 정리본의 상위 수집 attempt 아래 기존 HTML만 허용한다. Control은 삭제 직전 참조와 원본 시도가 예상과 같은지 조건부 DB 갱신으로 확인해 새 실제 snapshot_ref로 원자 전환한다. 조건 충돌이면 고정된 정리본을 채택하지 않고 재조회하며 새 cleanup_job_id로 다시 계산한다. 삭제 요청 자체는 계속 처리 중으로 유지한다. 새 manifest는 남은 대상에만 재처리 가능하며 이미 삭제된 대상의 지연 작업은 차단한다. 이전 manifest/result 및 대기·DLQ 메시지는 기존 재처리·보존 조건을 충족한 뒤 정리한다.

Crawler와 Control은 source_urls 및 consumers의 ID가 collection.execute의 전체 대상에 속하는지 검증한다. Ingestion은 요청 project_source_id의 참여·해당 상세 필드만 검증하고 다른 합법적 참가자를 무시한다. 참여 없는 항목은 해당 프로젝트 범위 밖이다. 데이터 권한을 확보하기 위한 project_source_id 소유 확인은 Control에서 수행한다.

### 후보 병합 시 샘플 참조

실패 출처 재시도 또는 신규 출처 분석의 결과는 해당 요청 revision과 현재 후보 revision이 일치할 때에만 Control이 채택한다. 성공 결과를 병합해 후보 revision을 증가시키더라도 sample_ref의 sample_revision은 실제 S3 객체를 생성한 revision으로 유지한다. 유효 검증 revision은 영향 범위 검증 또는 허용된 검증 승계를 거친 뒤 새 후보 revision에 연결한다. 오래된 결과의 S3 객체는 현재 후보에 연결하지 않으며 기존 고아 객체 정리 정책을 따른다. 실패 원문 보관은 observed_at 기준 만료를 유지한다.
