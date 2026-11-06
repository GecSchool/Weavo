"""명시적으로 요청한 경우 로컬 AWS 리소스 연결을 확인합니다."""

import argparse

from .aws import create_client
from .config import Settings, resource_name


def main() -> None:
    parser = argparse.ArgumentParser(description="Weavo crawler 서비스 환경 확인")
    parser.add_argument("--check", action="store_true", help="SQS 큐와 S3 버킷을 확인합니다")
    args = parser.parse_args()
    if not args.check:
        parser.print_help()
        return
    settings = Settings.from_env()
    sqs = create_client("sqs", settings)
    for variable, default in [
        ("WEAVO_PRIORITY_QUEUE", "collection-priority-jobs"),
        ("WEAVO_SCHEDULED_QUEUE", "collection-scheduled-jobs"),
        ("WEAVO_RESULT_QUEUE", "control-results"),
    ]:
        queue = resource_name(variable, default, settings)
        sqs.get_queue_url(QueueName=queue)
        print(f"SQS 큐 확인: {queue}")
    bucket = resource_name("WEAVO_S3_BUCKET", "weavo-local-artifacts", settings)
    create_client("s3", settings).head_bucket(Bucket=bucket)
    print("S3 버킷 확인 완료")


if __name__ == "__main__":
    main()
