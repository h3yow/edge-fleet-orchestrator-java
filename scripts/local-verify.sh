#!/usr/bin/env bash
# 本地可重复验证：编译 + 全量测试，不依赖外部服务。
set -euo pipefail
cd "$(dirname "$0")/.."

MVN="./mvnw"
[ -x "$MVN" ] || MVN="mvn"

echo "==> [1/2] compile"
$MVN -q compile

echo "==> [2/2] run all tests (junit + spring mockmvc)"
$MVN -q test

echo "==> local verification passed"
