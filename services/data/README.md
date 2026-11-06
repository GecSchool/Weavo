# data 서비스

Java 21과 Docker Compose의 로컬 PostgreSQL이 필요합니다. 저장소 루트의 로컬 환경 안내에 따라 인프라를 먼저 시작하세요.

```bash
./gradlew bootRun
./gradlew test
./gradlew spotlessCheck
```

기본 포트는 8083이며 `GET /actuator/health`로 상태를 확인합니다. 로컬 설정은 `src/main/resources/application-local.properties`에 있습니다. 환경 변수 `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `SERVER_PORT`로 변경할 수 있습니다. 아직 도메인 로직과 메시지 소비자는 구현하지 않았습니다.

## 코드 구조

기능별 패키지를 먼저 나누고 그 안에 필요한 책임을 둡니다. 아래 `record`는 데이터 조회 관련 코드를 배치할 출발점이며, 빈 디렉터리만 만들어 둔 상태입니다. 실제 클래스와 하위 패키지는 구현에 맞춰 추가합니다.

```text
src/main/java/com/weavo/data/
├── DataApplication.java
└── record/
    ├── api/  # HTTP 요청·응답 및 DTO
    ├── application/  # 유스케이스 실행과 처리 순서 조정
    ├── domain/  # 업무 규칙과 도메인 모델
    └── infrastructure/  # DB·SQS·S3 등 외부 연동 구현
```

- 계층은 필요한 만큼만 사용합니다. DTO, Repository, 이벤트 리스너 등은 관련 기능과 책임에 맞춰 추가합니다.
- `infrastructure`에 외부 시스템 연결을 두고 애플리케이션 로직과 구분합니다. 공통 코드는 여러 기능에서 실제로 사용할 때 분리합니다.
- 테스트는 `src/test/java`에 해당 기능과 대응하는 패키지로 작성합니다. 학습용 코드는 [EXAMPLE.md](EXAMPLE.md)를 참고하세요.

## 파일 배치 예시

아래는 **프로젝트 데이터 조회 기능을 구현할 때의 가상 예시**입니다. 이 이름의 파일이나 실제 API·메시지 수신 기능이 이미 존재한다는 뜻은 아닙니다.

```text
record/
├── api/
│   ├── RecordController.java
│   └── RecordResponse.java
├── application/
│   └── RecordService.java
├── domain/
│   └── Record.java
└── infrastructure/
    └── RecordRepository.java
```

HTTP 요청 → `api`의 Controller → `application`의 Service → 필요한 도메인 규칙과 저장소 → 응답 DTO 순서로 이어질 수 있습니다. 구현이 단순하면 불필요한 클래스를 만들지 않습니다. 다른 기능은 같은 서비스 패키지 아래 별도의 기능 폴더로 추가할 수 있습니다.

테스트는 예를 들어 `src/test/java/com/weavo/data/record/`에 대응시킵니다. Controller는 HTTP 경계, Service는 처리 결과, 저장소는 실제 쿼리·DB 동작을 검증하며 필요한 범위의 테스트를 선택합니다. 현재 존재하는 학습용 코드는 [EXAMPLE.md](EXAMPLE.md)에 따로 정리되어 있습니다.

## DB 스키마 변경

Data와 Ingestion은 `weavo_data`를 공유합니다. 테이블 변경 SQL과 Flyway 실행은 Ingestion에서만 관리합니다. Data에는 Flyway 실행 설정을 두지 않으며 DB 구조가 준비된 후 그 구조에 맞춰 조회 기능을 구현합니다.
