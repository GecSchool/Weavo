#!/bin/sh
set -eu
root=$(git rev-parse --show-toplevel)
existing=$(git -C "$root" config --local --get core.hooksPath || true)
if [ -n "$existing" ] && [ "$existing" != .githooks ]; then
  printf "이미 설정된 Git 훅 경로가 있어 변경하지 않았습니다: %s\n" "$existing" >&2
  exit 1
fi
git -C "$root" config --local core.hooksPath .githooks
printf '%s\n' 'Weavo Git 훅을 활성화했습니다. 비활성화: git config --local --unset core.hooksPath'
