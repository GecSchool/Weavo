# auth 서비스

Java 21과 Docker Compose의 로컬 PostgreSQL이 필요합니다. 저장소 루트의 로컬 환경 안내에 따라 인프라를 먼저 시작하세요.

```bash
./gradlew bootRun
./gradlew test
./gradlew spotlessCheck
```

기본 포트는 8081이며 `GET /actuator/health`로 상태를 확인합니다. 로컬 설정은 `src/main/resources/application-local.properties`에 있습니다. 환경 변수 `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `SERVER_PORT`로 변경할 수 있습니다. 아직 도메인 로직과 메시지 소비자는 구현하지 않았습니다.

## 코드 구조

기능별 패키지를 먼저 나누고 그 안에 필요한 책임을 둡니다. 아래 `account`는 계정과 인증 관련 코드를 배치할 출발점이며, 빈 디렉터리만 만들어 둔 상태입니다. 실제 클래스와 하위 패키지는 구현에 맞춰 추가합니다.

```text
src/main/java/com/weavo/auth/
├── AuthApplication.java
└── account/
    ├── api/  # HTTP 요청·응답 및 DTO
    ├── application/  # 유스케이스 실행과 처리 순서 조정
    ├── domain/  # 업무 규칙과 도메인 모델
    └── infrastructure/  # DB·SQS·S3 등 외부 연동 구현
```

- 계층은 필요한 만큼만 사용합니다. DTO, Repository, 이벤트 리스너 등은 관련 기능과 책임에 맞춰 추가합니다.
- `infrastructure`에 외부 시스템 연결을 두고 애플리케이션 로직과 구분합니다. 공통 코드는 여러 기능에서 실제로 사용할 때 분리합니다.
- 테스트는 `src/test/java`에 해당 기능과 대응하는 패키지로 작성합니다. 학습용 코드는 [EXAMPLE.md](EXAMPLE.md)를 참고하세요.

## 파일 배치 예시

아래는 **이메일 인증·계정 관리 기능을 구현할 때의 가상 예시**입니다. 이 이름의 파일이나 실제 API·메시지 수신 기능이 이미 존재한다는 뜻은 아닙니다.

```text
account/
├── api/
│   ├── AccountController.java
│   └── AccountResponse.java
├── application/
│   └── AccountService.java
├── domain/
│   └── Account.java
└── infrastructure/
    └── AccountRepository.java
```

HTTP 요청 → `api`의 Controller → `application`의 Service → 필요한 도메인 규칙과 저장소 → 응답 DTO 순서로 이어질 수 있습니다. 구현이 단순하면 불필요한 클래스를 만들지 않습니다. 다른 기능은 같은 서비스 패키지 아래 별도의 기능 폴더로 추가할 수 있습니다.

테스트는 예를 들어 `src/test/java/com/weavo/auth/account/`에 대응시킵니다. Controller는 HTTP 경계, Service는 처리 결과, 저장소는 실제 쿼리·DB 동작을 검증하며 필요한 범위의 테스트를 선택합니다. 현재 존재하는 학습용 코드는 [EXAMPLE.md](EXAMPLE.md)에 따로 정리되어 있습니다.

## DB 스키마 변경

이 서비스가 사용하는 DB의 테이블 구조는 `src/main/resources/db/migration/`의 Flyway SQL로 관리합니다. Spring 실행 시 적용되지 않은 SQL을 순서대로 실행합니다. 아직 업무용 테이블이나 마이그레이션은 없습니다.

예: 최초 테이블을 만들 때 `V1__create_auth_tables.sql`을, 이후 컬럼을 추가할 때 `V2__alter_auth_tables.sql`을 추가합니다. 파일 이름과 SQL은 실제 설계를 기준으로 작성합니다. 이미 공유 환경에 적용된 파일은 수정하지 않고 새 버전을 추가합니다.

`spring.jpa.hibernate.ddl-auto=none`으로 유지하며 테이블 자동 생성은 하지 않습니다. 로컬 PostgreSQL은 프로젝트 루트의 `docs/local-setup.md`를 참고하세요.
