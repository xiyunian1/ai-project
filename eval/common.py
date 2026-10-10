"""评测脚本共用：读取 my-ai/.env、连接 SQL Server。"""
from __future__ import annotations

import os
from pathlib import Path

import pymssql

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / "my-ai" / ".env"


def load_env(path: Path = ENV_FILE) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    # 进程环境变量优先，便于 CI 覆盖
    values.update({k: v for k, v in os.environ.items() if k in values or k.startswith(("DB_", "MSSQL_", "LLM_", "EVAL_"))})
    return values


def connect(env: dict[str, str], *, as_sa: bool = False, database: str = "myai"):
    host = env.get("EVAL_DB_HOST", "127.0.0.1")
    port = int(env.get("EVAL_DB_PORT", "1433"))
    if as_sa:
        user, password = "sa", env["MSSQL_SA_PASSWORD"]
    else:
        user, password = env.get("DB_USERNAME", "myai_user"), env["DB_PASSWORD"]
    return pymssql.connect(
        server=host, port=port, user=user, password=password,
        database=database, charset="UTF-8", autocommit=True, login_timeout=10,
    )
