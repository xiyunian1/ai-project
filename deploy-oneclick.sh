#!/usr/bin/env bash
set -euo pipefail

APP_NAME="my-ai"
SERVICE_NAME="my-ai"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${PROJECT_DIR}/my-ai"
FRONTEND_DIR="${PROJECT_DIR}/ai-frontend"

DEPLOY_ROOT="/opt/${APP_NAME}"
BACKEND_DEPLOY_DIR="${DEPLOY_ROOT}/backend"
FRONTEND_DEPLOY_DIR="/var/www/${APP_NAME}"

DEFAULT_ENV_FILE="/etc/${APP_NAME}/${APP_NAME}.env"

DOMAIN="_"
BACKEND_PORT="8081"
API_BASE_URL="__AUTO__"
RESOLVED_API_BASE_URL=""
ENV_FILE="${DEFAULT_ENV_FILE}"
INSTALL_DEPS="false"

log() {
  echo "[INFO] $*"
}

warn() {
  echo "[WARN] $*"
}

die() {
  echo "[ERROR] $*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
一键部署脚本（Ubuntu / Debian）

用法：
  sudo ./deploy-oneclick.sh [选项]

选项：
  --install-deps           自动安装依赖（Java17、Nginx、Node.js 20）
  --domain <domain>        Nginx server_name，默认 _
  --backend-port <port>    后端端口，默认 8081
  --api-base-url <url>     前端构建时 API 基地址，默认自动使用 http://<domain>
  --env-file <path>        后端环境变量文件路径，默认 /etc/my-ai/my-ai.env
  -h, --help               显示帮助

说明：
  1) 首次执行会自动生成环境变量模板并退出；
  2) 修改完 ENV 文件后，再次执行即可完成部署。
EOF
}

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    die "请使用 sudo 执行该脚本。"
  fi
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --install-deps)
        INSTALL_DEPS="true"
        shift
        ;;
      --domain)
        [[ $# -ge 2 ]] || die "--domain 需要参数"
        DOMAIN="$2"
        shift 2
        ;;
      --backend-port)
        [[ $# -ge 2 ]] || die "--backend-port 需要参数"
        BACKEND_PORT="$2"
        shift 2
        ;;
      --api-base-url)
        [[ $# -ge 2 ]] || die "--api-base-url 需要参数"
        API_BASE_URL="$2"
        shift 2
        ;;
      --env-file)
        [[ $# -ge 2 ]] || die "--env-file 需要参数"
        ENV_FILE="$2"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "未知参数: $1"
        ;;
    esac
  done
}

install_dependencies() {
  command_exists apt-get || die "仅支持 apt-get（Ubuntu/Debian）。"

  log "安装系统依赖..."
  apt-get update
  apt-get install -y curl ca-certificates gnupg nginx openjdk-17-jdk

  local need_node_install="false"
  if ! command_exists node; then
    need_node_install="true"
  else
    local node_major
    node_major="$(node -v | sed 's/^v//' | cut -d. -f1)"
    if [[ "${node_major}" -lt 20 ]]; then
      warn "当前 Node 版本过低：$(node -v)，将升级到 Node 20。"
      need_node_install="true"
    fi
  fi

  if [[ "${need_node_install}" == "true" ]]; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y nodejs
  fi
}

validate_environment() {
  [[ -d "${BACKEND_DIR}" ]] || die "缺少目录: ${BACKEND_DIR}"
  [[ -d "${FRONTEND_DIR}" ]] || die "缺少目录: ${FRONTEND_DIR}"
  [[ -f "${BACKEND_DIR}/pom.xml" ]] || die "缺少文件: ${BACKEND_DIR}/pom.xml"
  [[ -f "${FRONTEND_DIR}/package.json" ]] || die "缺少文件: ${FRONTEND_DIR}/package.json"

  [[ "${BACKEND_PORT}" =~ ^[0-9]{1,5}$ ]] || die "--backend-port 必须是 1-65535 之间的数字"
  (( BACKEND_PORT >= 1 && BACKEND_PORT <= 65535 )) || die "--backend-port 必须是 1-65535 之间的数字"

  if [[ "${DOMAIN}" == *"://"* ]]; then
    die "--domain 只需填写域名或 IP，不要包含 http:// 或 https://"
  fi

  command_exists java || die "未检测到 java，请先安装 Java 17。"
  command_exists npm || die "未检测到 npm，请先安装 Node.js 20。"
  command_exists nginx || die "未检测到 nginx，请先安装。"
  command_exists systemctl || die "未检测到 systemctl。"

  local node_major
  node_major="$(node -v | sed 's/^v//' | cut -d. -f1)"
  [[ "${node_major}" -ge 20 ]] || die "Node 版本需 >= 20，当前：$(node -v)"

  if [[ "${API_BASE_URL}" == "__AUTO__" && "${DOMAIN}" == "_" ]]; then
    die "未设置 --domain，且 --api-base-url 也未指定，无法自动推导前端 API 地址。请至少提供其一。"
  fi
}

ensure_env_file() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    mkdir -p "$(dirname "${ENV_FILE}")"

    local cors_default
    if [[ "${DOMAIN}" == "_" ]]; then
      cors_default="http://your-domain.example,https://your-domain.example"
    else
      cors_default="http://${DOMAIN},https://${DOMAIN}"
    fi

    cat > "${ENV_FILE}" <<EOF
JWT_SECRET=replace-with-at-least-32-bytes-secret
JWT_EXPIRATION_MS=86400000

DB_URL=jdbc:jtds:sqlserver://127.0.0.1:1433/myai
DB_DRIVER=net.sourceforge.jtds.jdbc.Driver
DB_DIALECT=org.hibernate.community.dialect.SQLServerLegacyDialect
DB_USERNAME=myai_user
DB_PASSWORD=change-me

CORS_ALLOWED_ORIGINS=${cors_default}
CORS_ALLOW_NULL_ORIGIN=false

LLM_API_KEY=
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
LLM_MOCK_ENABLED=true
LLM_REQUEST_TIMEOUT_SECONDS=20
LLM_MOCK_DELAY_MS=500

DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat

CODEX_RAW_FILE_ENABLED=false
ANALYSIS_CUSTOM_DB_ENABLED=false

NL2SQL_ENABLED=true
NL2SQL_PROVIDER=openai-compatible
NL2SQL_MAX_ROWS=200
NL2SQL_ALLOWED_TABLES=dbo.inout,aisql.dbo.inout,inout
EOF
    chmod 600 "${ENV_FILE}"
    log "已生成环境变量模板：${ENV_FILE}"
    log "请先修改该文件中的密钥/数据库配置后，再次执行脚本完成部署。"
    exit 0
  fi
}

validate_env_file_values() {
  grep -Eq '^JWT_SECRET=replace-with-at-least-32-bytes-secret$' "${ENV_FILE}" \
    && die "检测到 JWT_SECRET 仍是默认占位值，请先修改 ${ENV_FILE}" || true

  grep -Eq '^DB_PASSWORD=change-me$' "${ENV_FILE}" \
    && die "检测到 DB_PASSWORD 仍是默认占位值，请先修改 ${ENV_FILE}" || true
}

build_backend() {
  log "构建后端..."
  if [[ -f "${BACKEND_DIR}/mvnw" ]]; then
    chmod +x "${BACKEND_DIR}/mvnw"
    (
      cd "${BACKEND_DIR}"
      ./mvnw -DskipTests clean package
    )
  else
    command_exists mvn || die "未找到 mvn/mvnw。"
    (
      cd "${BACKEND_DIR}"
      mvn -DskipTests clean package
    )
  fi

  local jar_path
  jar_path="$(find "${BACKEND_DIR}/target" -maxdepth 1 -type f -name '*.jar' ! -name '*original*' ! -name '*sources*' ! -name '*javadoc*' | sort | head -n 1)"
  [[ -n "${jar_path}" ]] || die "后端构建完成但未找到 jar 包。"

  mkdir -p "${BACKEND_DEPLOY_DIR}"
  cp "${jar_path}" "${BACKEND_DEPLOY_DIR}/app.jar"
}

build_frontend() {
  log "构建前端..."

  local resolved_api_base
  if [[ "${API_BASE_URL}" == "__AUTO__" ]]; then
    resolved_api_base="http://${DOMAIN}"
  else
    resolved_api_base="${API_BASE_URL%/}"
  fi
  [[ -n "${resolved_api_base}" ]] || die "前端 API 基地址不能为空，请通过 --api-base-url 指定有效地址。"
  RESOLVED_API_BASE_URL="${resolved_api_base}"

  cat > "${FRONTEND_DIR}/.env.production" <<EOF
VITE_API_BASE_URL=${resolved_api_base}
EOF

  (
    cd "${FRONTEND_DIR}"
    npm ci
    npm run build -- --base /
  )

  [[ -d "${FRONTEND_DIR}/dist" ]] || die "前端构建失败，未找到 dist 目录。"

  mkdir -p "${FRONTEND_DEPLOY_DIR}"
  find "${FRONTEND_DEPLOY_DIR}" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  cp -a "${FRONTEND_DIR}/dist/." "${FRONTEND_DEPLOY_DIR}/"
}

write_systemd_service() {
  log "写入 systemd 服务..."
  local java_bin
  java_bin="$(command -v java)"

  cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<EOF
[Unit]
Description=My AI Backend Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${BACKEND_DEPLOY_DIR}
EnvironmentFile=${ENV_FILE}
Environment=SERVER_PORT=${BACKEND_PORT}
ExecStart=${java_bin} -jar ${BACKEND_DEPLOY_DIR}/app.jar
SuccessExitStatus=143
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
}

write_nginx_config() {
  log "写入 Nginx 配置..."

  local nginx_conf
  if [[ -d "/etc/nginx/sites-available" ]]; then
    nginx_conf="/etc/nginx/sites-available/${APP_NAME}.conf"
  else
    nginx_conf="/etc/nginx/conf.d/${APP_NAME}.conf"
  fi

  cat > "${nginx_conf}" <<EOF
server {
    listen 80;
    server_name ${DOMAIN};
    client_max_body_size 20m;

    root ${FRONTEND_DEPLOY_DIR};
    index index.html;

    location / {
        try_files \$uri \$uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:${BACKEND_PORT}/api/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}
EOF

  if [[ -d "/etc/nginx/sites-available" ]]; then
    ln -sf "${nginx_conf}" "/etc/nginx/sites-enabled/${APP_NAME}.conf"
    rm -f "/etc/nginx/sites-enabled/default"
  fi
}

restart_services() {
  log "重载并启动服务..."
  systemctl daemon-reload
  systemctl enable "${SERVICE_NAME}"
  systemctl restart "${SERVICE_NAME}"

  nginx -t
  systemctl enable nginx
  systemctl restart nginx
}

print_summary() {
  local access_hint
  if [[ "${DOMAIN}" == "_" ]]; then
    access_hint="http://<服务器IP>"
  else
    access_hint="http://${DOMAIN}"
  fi

  cat <<EOF

部署完成。

- 前端目录: ${FRONTEND_DEPLOY_DIR}
- 后端服务: ${SERVICE_NAME}
- 后端端口: ${BACKEND_PORT}
- 环境变量文件: ${ENV_FILE}
- 访问方式: ${access_hint}
- 前端 API 基地址: ${RESOLVED_API_BASE_URL}

常用检查命令：
  systemctl status ${SERVICE_NAME} --no-pager
  journalctl -u ${SERVICE_NAME} -n 100 --no-pager
  nginx -t
EOF
}

main() {
  parse_args "$@"
  require_root

  if [[ "${INSTALL_DEPS}" == "true" ]]; then
    install_dependencies
  fi

  validate_environment
  ensure_env_file
  validate_env_file_values
  build_backend
  build_frontend
  write_systemd_service
  write_nginx_config
  restart_services
  print_summary
}

main "$@"
