#!/bin/bash
set -euo pipefail
export AWS_DEFAULT_REGION=ap-northeast-2

# 3.3 큐 배치에 따른 논리 큐. 이름은 로컬 개발용이다.
queues=(
  collection-schedule-requests
  analysis-jobs
  collection-priority-jobs
  collection-scheduled-jobs
  ingestion-jobs
  control-results
)
for queue in "${queues[@]}"; do
  awslocal sqs create-queue --queue-name "${queue}-dlq" >/dev/null
  awslocal sqs create-queue --queue-name "$queue" >/dev/null
done

# SQS 기술 재전달 횟수와 DLQ 연결 정책은 미정이므로 redrive policy를 설정하지 않는다.
if ! awslocal s3api head-bucket --bucket weavo-local-artifacts >/dev/null 2>&1; then
  awslocal s3api create-bucket --bucket weavo-local-artifacts \
    --create-bucket-configuration LocationConstraint=ap-northeast-2 >/dev/null
fi
printf 'Weavo local queues, DLQs and S3 bucket are ready.\n'
