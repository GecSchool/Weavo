# 격리 파서 로컬 실행기

로컬 개발용 HTTP 게이트웨이는 localhost의 8095 포트에서 메시지 명세 12.1의
`POST /internal/v1/parser:execute`를 받습니다. 호출자별 토큰은
`WEAVO_PARSER_ALLOWED_TOKENS`에 쉼표로 구분해 지정합니다. 각 서비스는 할당받은
토큰을 `WEAVO_PARSER_TOKEN`에 둡니다.

```sh
docker pull eclipse-temurin:21-jdk
./gradlew -p ../parser-executor installDist
../parser-executor/build/install/parser-executor/bin/parser-executor
```

두 번째와 세 번째 명령은 Analysis 서비스 디렉터리에서 실행합니다.
기본 Java 21 이미지와 포트는 `WEAVO_PARSER_IMAGE`, `WEAVO_PARSER_PORT`로 바꿀 수 있습니다.
Docker에 접근할 수 있는 신뢰된 로컬 프로세스가 게이트웨이를 실행합니다.
Java 코드는 항목별 일회용 컨테이너 내부에서 실행합니다. 네트워크를 연결하지 않으며,
읽기 전용 JDK와 실행기 라이브러리만 제공하고 CPU·메모리·프로세스 수·실행 시간을 제한합니다.
게이트웨이만 호출자 인증을 처리합니다. 컨테이너에는 호출자 토큰, AWS 자격 증명,
Docker 소켓 및 DB 주소를 전달하지 않습니다.

ECS의 격리 실행 환경과 컨테이너 간 컴파일 결과 캐시는 운영 검증 대상입니다.
