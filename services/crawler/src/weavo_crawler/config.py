"""환경에서 AWS 연결 설정을 읽습니다."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    environment: str
    region: str
    endpoint_url: str | None

    @classmethod
    def from_env(cls) -> "Settings":
        environment = os.getenv("WEAVO_ENV", "local")
        if environment not in {"local", "dev", "prod"}:
            raise ValueError("WEAVO_ENV must be local, dev or prod")
        endpoint = os.getenv("WEAVO_AWS_ENDPOINT_URL") or None
        if environment == "local" and not endpoint:
            raise ValueError("로컬 환경에는 WEAVO_AWS_ENDPOINT_URL이 필요합니다.")
        if environment != "local" and endpoint:
            raise ValueError("AWS 환경에서는 로컬 endpoint를 사용할 수 없습니다.")
        return cls(environment, os.getenv("AWS_REGION", "ap-northeast-2"), endpoint)


def resource_name(name: str, local_default: str, settings: Settings) -> str:
    """로컬 기본값 또는 명시적으로 지정한 AWS 자원 이름을 돌려줍니다."""
    value = os.getenv(name)
    if value:
        return value
    if settings.environment == "local":
        return local_default
    raise ValueError(f"{name} 환경 변수가 필요합니다.")
