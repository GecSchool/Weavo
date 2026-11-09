# crawler 서비스

목표 기술 스택은 Java 21·Spring Boot와 Playwright for Java입니다. 현재 아래 명령은 전환 전 Python 골격에 대한 안내이며, 실행에는 Python 3.12 또는 3.13과 로컬 인프라가 필요합니다. Java 전환 후 실행·테스트 방법을 갱신합니다.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.lock
pip install --no-deps -e .
python -m playwright install chromium
pytest
ruff check .
```

로컬 리소스 연결을 확인할 때:

```bash
WEAVO_ENV=local WEAVO_AWS_ENDPOINT_URL=http://localhost:4566 python -m weavo_crawler --check
```

`--check`는 큐와 버킷의 존재만 조회하며 메시지를 수신하거나 삭제하지 않습니다. 비로컬 환경은 `WEAVO_ENV=dev`, AWS 인증과 `WEAVO_S3_BUCKET`, 각 큐 이름 환경 변수가 필요하고 로컬 endpoint는 지정하지 않습니다. 브라우저·메시지 소비·분석 및 크롤링 업무 로직은 아직 구현되지 않았습니다.

## 현재 파일 구조

```text
services/crawler/
├── pyproject.toml             # 프로젝트와 의존성 정의
├── requirements.lock          # 고정 의존성
├── .env.example               # 설정 예시
├── src/weavo_crawler/
│   ├── __main__.py            # --check 진입점과 자원 확인
│   ├── config.py              # 환경 설정과 자원 이름 검증
│   └── aws.py                 # SQS·S3 클라이언트 생성
└── tests/
    └── test_config.py         # 환경 설정 단위 테스트
```

예를 들어 `python -m weavo_crawler --check`를 실행하면 `__main__.py`가 `config.py`의 설정을 읽고 `aws.py`로 클라이언트를 만든 다음, 우선 수집 큐, 정기 수집 큐와 Control 결과 큐 및 S3 버킷의 존재를 확인합니다. 이 과정은 메시지를 읽거나 데이터를 적재하지 않습니다.

분석·수집과 메시지 처리 코드는 아직 없습니다. 실제 기능을 만들 때 요청, 외부 연동, 처리 로직과 결과 전달을 역할에 맞게 나누고, 테스트에서는 외부 시스템을 대역으로 교체합니다.
