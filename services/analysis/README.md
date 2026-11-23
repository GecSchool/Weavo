# analysis 서비스

Java 21과 Gradle Wrapper를 사용합니다. 리소스 연결 확인과 SQS 작업 수신·선점·결과 발행을 구현했습니다. `worker-test` 프로필에서 고정 결과로 실행 경로를 검증합니다. DB 연결은 사용하지 않습니다.

분석 요청과 완료 이벤트의 메시지 모델은 `job.infrastructure.messaging`에, 분석에 필요한 이동·필드 규칙은 `job.domain`에 둡니다. JSON 구조와 필수값의 기준은 `docs/service-message-contracts.md`입니다. 동일 이벤트 재발행에는 역직렬화한 객체가 아닌 보관된 최초 메시지 전체를 그대로 사용해야 합니다. 구조화된 메시지 형식은 `docs/contracts/schemas/`도 함께 확인합니다.

## 실행 및 테스트

```bash
./gradlew test
./gradlew spotlessCheck
./gradlew :worker-runtime:spotlessCheck :worker-runtime:test
./gradlew bootRun
```

`worker-test` 프로필을 켜지 않으면 고정 결과 워커는 시작되지 않습니다. 리소스를 확인하려면 로컬 인프라를 시작한 후 다음 명령을 사용합니다.

```bash
WEAVO_ENV=local WEAVO_AWS_ENDPOINT_URL=http://localhost:4566 ./gradlew bootRun --args='--check'
```

확인 대상은 `analysis-jobs`, `control-results` 큐와 `weavo-local-artifacts` 버킷입니다. 큐 URL 조회와 S3 버킷 확인만 수행하며 메시지를 받거나 삭제하지 않습니다. 연결 실패 시 오류로 종료합니다.

`WEAVO_ENV`는 `local`, `dev`, `prod`이며 기본은 `local`입니다. 로컬에서는 `WEAVO_AWS_ENDPOINT_URL`이 필수입니다. AWS 환경에서는 endpoint를 지정하지 않으며 `WEAVO_S3_BUCKET`과 해당 큐 이름을 명시합니다. `AWS_REGION` 기본값은 `ap-northeast-2`입니다. AWS 인증은 기본 자격 증명 체인을 사용합니다. 예시는 `.env.example`을 참고하되 해당 파일은 자동으로 읽지 않으므로 환경 변수로 직접 설정해야 합니다.

## 고정 결과 워커 실행

내부 Control의 선점·heartbeat API(또는 같은 계약으로 응답하는 테스트 대역)와 로컬 SQS·S3를 먼저 준비합니다. 테스트 작업으로만 실행하고, `WEAVO_SERVICE_TOKEN`에는 이 서비스용 내부 인증 값을 환경 변수로 공급합니다.

```bash
WEAVO_ENV=local WEAVO_AWS_ENDPOINT_URL=http://localhost:4566 \
WEAVO_CONTROL_URL=http://localhost:8082 \
./gradlew bootRun --args='--spring.profiles.active=worker-test'
```

analysis-jobs에서 요청을 받고 Control이 해당 시도를 선점한 경우에만 고정 처리기를 실행합니다. 고정 처리기는 실제 분석을 수행하지 않았다는 실패 결과를 반환합니다. 시도별 `result.json`에 저장한 동일 본문을 `control-results`에 발행하고, 성공한 뒤에 수신 메시지를 삭제합니다.

`weavo.execution.heartbeat-interval`, `visibility`, `completion-grace`, `control-timeout`, `aws-timeout` 기본값은 각각 5초, 30초, 30초, 3초, 15초입니다. 작업 본문의 `max_duration_seconds`는 본 작업 예산이며, 그 뒤의 저장·발행은 별도 완료 유예와 Control lease 안에서 처리합니다.

## Playwright Chromium

```bash
./gradlew installChromium
./gradlew browserSmokeTest
```

기본 `test`에서는 브라우저를 실행하지 않습니다. 브라우저 테스트는 명시적으로 실행하며 설치·실행에 필요한 OS 라이브러리는 환경에 맞게 준비해야 합니다.

## 구조

```text
src/main/java/com/weavo/analysis/
├── job/                          # 분석 작업의 메시지 경계와 규칙 모델
├── AnalysisApplication.java  # Spring Boot 진입점 및 --check
├── WorkerSettings.java             # 환경 및 리소스 설정 검증
└── AwsResourceChecker.java         # SQS·S3 읽기 전용 연결 확인
```

`job.application`의 처리기는 작업 목적에 따라 실행을 분기합니다. `AnalysisJobProcessor`는 JSON 메시지를 읽고 결과를 변환합니다. 두 서비스에서 동일한 폴링·lease 관리·결과 전달은 `services/shared/worker-runtime`의 코드로 실행합니다. 공통 Java 필드 파서는 `services/shared/field-extraction`에 구현되어 있습니다. Analysis의 후보 검증에 격리 파서 실행기를 연결하는 작업은 남아 있습니다.

## 초기 분석 후보와 외부 호출

`OPENAI_API_KEY`를 Analysis 프로세스의 환경 변수로 설정합니다. 기본 모델은
`WEAVO_ANALYSIS_MODEL=gpt-6.1-terra`입니다. 변경할 때 후보 형식과 브라우저 검증은 동일하게 적용합니다.
`WEAVO_PARSER_URL`에는 격리 파서 게이트웨이 주소를, `WEAVO_PARSER_TOKEN`에는 내부 호출
Bearer 토큰을 설정합니다. 서비스에서 생성된 Java 코드는 해당 게이트웨이로 전달합니다.

실제 Terra 호출을 확인하려면 환경 변수에 키를 설정하고
`WEAVO_RUN_OPENAI_SMOKE=1 ./gradlew test --tests '*AnalysisHttpClientsTest.realTerraAcceptsTheCandidateSchemaAndReturnsUsableListRules'`를
실행합니다. 이 검사는 고정 HTML 샘플을 API에 보내며 API 사용료가 발생합니다.
