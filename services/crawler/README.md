# crawler 서비스

Java 21과 Gradle Wrapper를 사용합니다. 리소스 연결 확인, SQS 작업 수신·선점·결과 발행, 고정 규칙 수집과 S3 스냅샷 저장을 구현했습니다. 사이트 접근 조정에는 Analysis와 동일한 Redis와 PostgreSQL을 사용합니다.

수집 메시지 모델과 JSON 검증은 `collection.infrastructure.messaging`, 목록·필드 규칙은 `collection.domain.rule`에 둡니다. 계약의 기준은 `docs/service-message-contracts.md`와 `docs/s3-storage-contract.md`입니다. `ContractJson`은 payload의 누락과 null, 알 수 없는 필드를 구분하지만 알 수 없는 envelope 필드는 보존하지 않습니다. 동일 이벤트 재발행에는 최초 메시지 전체를 보관하여 그대로 사용해야 합니다. 구조화된 메시지 형식은 `docs/contracts/schemas/`도 함께 확인합니다.

## 실행 및 테스트

```bash
./gradlew test
./gradlew spotlessCheck
./gradlew bootRun
```

`worker` 또는 `worker-test` 프로필을 선택한 경우에만 SQS 소비기가 시작됩니다. 리소스를 확인하려면 로컬 인프라를 시작한 후 다음 명령을 사용합니다.

```bash
WEAVO_ENV=local WEAVO_AWS_ENDPOINT_URL=http://localhost:4566 ./gradlew bootRun --args='--check'
```

확인 대상은 `collection-priority-jobs`, `collection-scheduled-jobs`, `control-results` 큐와 `weavo-local-artifacts` 버킷입니다. 큐 URL 조회와 S3 버킷 확인만 수행하며 메시지를 받거나 삭제하지 않습니다. 연결 실패 시 오류로 종료합니다.

`WEAVO_ENV`는 `local`, `dev`, `prod`이며 기본은 `local`입니다. 로컬에서는 `WEAVO_AWS_ENDPOINT_URL`이 필수입니다. AWS 환경에서는 endpoint를 지정하지 않으며 `WEAVO_S3_BUCKET`과 해당 큐 이름을 명시합니다. `AWS_REGION` 기본값은 `ap-northeast-2`입니다. AWS 인증은 기본 자격 증명 체인을 사용합니다. 예시는 `.env.example`을 참고하되 해당 파일은 자동으로 읽지 않으므로 환경 변수로 직접 설정해야 합니다.

## 고정 규칙 수집 워커 실행

`worker` 프로필은 `FULL` 대상의 단일 목록(`pagination.type=NONE`, 초기 동작 없음)을 처리합니다. 목록은 HTTP 또는 브라우저로 열고, 상세는 브라우저 현재 탭에서 링크 또는 클릭으로 열어 기록한 뒤 목록으로 복귀합니다. 입력 설정에는 승인된 스키마, 목록 및 필드별 추출·이동 규칙이 필요합니다. 페이지네이션, 새 탭·모달, 증분 비교는 후속 이슈에서 연결합니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=worker'
```

실행 전 `.env.example`을 참고해 Control의 URL과 서비스 인증 값, Redis·PostgreSQL, SQS·S3 환경을 준비합니다. 수집 시간 제한에 도달하면 완료한 항목만 별도의 완료 기한 안에 저장합니다. 목록·상세 HTML을 먼저 저장하고 마지막에 manifest를 올립니다. 결과의 `snapshot_ref`는 이 manifest를 가리킵니다. 목록이 0건이면 출처 범위의 `job.issue.detected`와 실패 완료 결과를 발행합니다.

브라우저를 사용하는 고정 수집 테스트는 `./gradlew browserSmokeTest`에서 실행됩니다. 실행 가능한 로컬 LocalStack에 `weavo-local-artifacts` 버킷이 있을 때 `WEAVO_SNAPSHOT_TEST_S3=http://localhost:4566 ./gradlew browserSmokeTest`를 실행하면 저장된 manifest와 HTML을 S3에서 다시 읽는 경로도 검증합니다.

## 고정 결과 워커 실행

내부 Control의 선점·heartbeat API(또는 같은 계약으로 응답하는 테스트 대역)와 로컬 SQS·S3를 먼저 준비합니다. 테스트 작업으로만 실행하고, `WEAVO_SERVICE_TOKEN`에는 이 서비스용 내부 인증 값을 환경 변수로 공급합니다.

```bash
WEAVO_ENV=local WEAVO_AWS_ENDPOINT_URL=http://localhost:4566 \
WEAVO_CONTROL_URL=http://localhost:8082 \
./gradlew bootRun --args='--spring.profiles.active=worker-test'
```

collection-priority-jobs와 collection-scheduled-jobs에서 요청을 받고 Control이 해당 시도를 선점한 경우에만 고정 처리기를 실행합니다. 고정 처리기는 실제 수집을 수행하지 않았다는 실패 결과를 반환합니다. 시도별 `result.json`에 저장한 동일 본문을 `control-results`에 발행하고, 성공한 뒤에 수신 메시지를 삭제합니다.

두 입력 큐에 요청이 쌓이면 우선 큐 3건, 정기 큐 1건 순으로 수신합니다. 한쪽이 비어 있으면 다른 큐를 계속 처리합니다.

`weavo.execution.heartbeat-interval`, `visibility`, `completion-grace`, `control-timeout`, `aws-timeout` 기본값은 각각 5초, 30초, 30초, 3초, 15초입니다. 작업 본문의 `max_duration_seconds`는 본 작업 예산이며, 그 뒤의 저장·발행은 별도 완료 유예와 Control lease 안에서 처리합니다.

## Playwright Chromium

```bash
./gradlew installChromium
./gradlew browserSmokeTest
```

기본 `test`에서는 브라우저를 실행하지 않습니다. 브라우저 테스트는 명시적으로 실행하며 설치·실행에 필요한 OS 라이브러리는 환경에 맞게 준비해야 합니다.

## 구조

```text
src/main/java/com/weavo/crawler/
├── CrawlerApplication.java  # Spring Boot 진입점 및 --check
├── WorkerSettings.java             # 환경 및 리소스 설정 검증
├── AwsResourceChecker.java         # SQS·S3 읽기 전용 연결 확인
└── collection/                     # 수집 메시지와 이동·필드 규칙
```

`collection.application`의 처리기는 작업 목적에 따라 실행을 분기합니다. `CollectionJobProcessor`는 JSON 메시지를 읽고 결과를 변환합니다. 두 서비스에서 동일한 폴링·lease 관리·결과 전달은 `services/shared/worker-runtime`의 코드로 실행합니다. 고정 수집의 임시 추출은 `services/shared/field-extraction`의 공통 Java 파서 및 타입 변환을 사용합니다.
