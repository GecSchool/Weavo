# analysis 서비스

Java 21과 Gradle Wrapper를 사용합니다. 현재는 환경 설정과 SQS·S3 리소스 연결 확인 기능만 구현되어 있습니다. 분석·수집, 메시지 소비와 결과 발행은 아직 구현하지 않았습니다. DB 연결은 사용하지 않습니다.

분석 요청과 완료 이벤트의 메시지 모델은 `job.infrastructure.messaging`에, 분석에 필요한 이동·필드 규칙은 `job.domain`에 둡니다. JSON 구조와 필수값의 기준은 `docs/service-message-contracts.md`입니다. 메시지를 실제로 소비·발행하는 기능은 별도로 구현합니다. 동일 이벤트 재발행에는 역직렬화한 객체가 아닌 보관된 최초 메시지 전체를 그대로 사용해야 합니다. 구조화된 메시지 형식은 `docs/contracts/schemas/`도 함께 확인합니다.

## 실행 및 테스트

```bash
./gradlew test
./gradlew spotlessCheck
./gradlew bootRun
```

현재 워커는 처리할 메시지 리스너가 없어 일반 실행 시 별도 작업을 하지 않고 종료될 수 있습니다. 리소스를 확인하려면 로컬 인프라를 시작한 후 다음 명령을 사용합니다.

```bash
WEAVO_ENV=local WEAVO_AWS_ENDPOINT_URL=http://localhost:4566 ./gradlew bootRun --args='--check'
```

확인 대상은 `analysis-jobs`, `control-results` 큐와 `weavo-local-artifacts` 버킷입니다. 큐 URL 조회와 S3 버킷 확인만 수행하며 메시지를 받거나 삭제하지 않습니다. 연결 실패 시 오류로 종료합니다.

`WEAVO_ENV`는 `local`, `dev`, `prod`이며 기본은 `local`입니다. 로컬에서는 `WEAVO_AWS_ENDPOINT_URL`이 필수입니다. AWS 환경에서는 endpoint를 지정하지 않으며 `WEAVO_S3_BUCKET`과 해당 큐 이름을 명시합니다. `AWS_REGION` 기본값은 `ap-northeast-2`입니다. AWS 인증은 기본 자격 증명 체인을 사용합니다. 예시는 `.env.example`을 참고하되 해당 파일은 자동으로 읽지 않으므로 환경 변수로 직접 설정해야 합니다.

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

실제 기능을 구현할 때 기능별 패키지를 먼저 나누고 필요한 책임만 추가합니다. 공통 Java 파서 모듈과 생성 코드의 격리 실행은 아직 구현하지 않았습니다.
