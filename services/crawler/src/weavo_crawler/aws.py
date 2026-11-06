"""SQS·S3 연결을 만들며 메시지 처리는 수행하지 않습니다."""

import boto3

from .config import Settings


def create_client(service_name: str, settings: Settings):
    kwargs = {"region_name": settings.region}
    if settings.environment == "local":
        kwargs.update(
            endpoint_url=settings.endpoint_url,
            aws_access_key_id="test",
            aws_secret_access_key="test",
        )
    return boto3.client(service_name, **kwargs)
