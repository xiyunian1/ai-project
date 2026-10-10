#!/usr/bin/env bash
# macOS / Linux 本地启动后端：读取 my-ai/.env 后运行 Spring Boot。
# 数据库先用 docker-compose.dev.yml 启动，并执行 eval/setup_db.py 初始化。
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f .env ]; then
  echo "缺少 my-ai/.env，请参考 .env.example 创建" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
. ./.env
set +a
exec ./mvnw -q spring-boot:run "$@"
