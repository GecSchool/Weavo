import pytest

from weavo_analysis.config import Settings, resource_name


def test_local_requires_explicit_endpoint(monkeypatch):
    monkeypatch.setenv("WEAVO_ENV", "local")
    monkeypatch.delenv("WEAVO_AWS_ENDPOINT_URL", raising=False)
    with pytest.raises(ValueError, match="WEAVO_AWS_ENDPOINT_URL"):
        Settings.from_env()


def test_aws_environment_rejects_local_endpoint(monkeypatch):
    monkeypatch.setenv("WEAVO_ENV", "dev")
    monkeypatch.setenv("WEAVO_AWS_ENDPOINT_URL", "http://localhost:4566")
    with pytest.raises(ValueError):
        Settings.from_env()


def test_nonlocal_resource_requires_explicit_name(monkeypatch):
    monkeypatch.delenv("WEAVO_S3_BUCKET", raising=False)
    settings = Settings("dev", "ap-northeast-2", None)
    with pytest.raises(ValueError, match="WEAVO_S3_BUCKET"):
        resource_name("WEAVO_S3_BUCKET", "weavo-local-artifacts", settings)
