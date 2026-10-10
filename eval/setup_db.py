"""初始化评测数据库：建库、建登录名、重建 dbo.inout 并写入合成数据。

用法（先启动 docker-compose.dev.yml 中的 SQL Server）：
    eval/.venv/bin/python -I eval/setup_db.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent / "data"))

from common import connect, load_env  # noqa: E402
from generate_inout import generate  # noqa: E402

TABLE_DESCRIPTION = "出入库单据明细流水：每行是一张单据中的一个商品批次，按单据日期记录入库/出库数量、金额与结存。"

COLUMNS = [
    ("id", "BIGINT IDENTITY(1,1) PRIMARY KEY", "自增主键，越大表示越晚写入"),
    ("按日期", "NVARCHAR(10) NOT NULL", "单据日期，文本格式 yyyy-MM-dd，比较或按月统计时需 CAST 为 DATE"),
    ("单据类型", "NVARCHAR(20) NOT NULL", "取值：采购入库、销售出库、销售退货、采购退货、调拨出库、调拨入库、盘点调整"),
    ("单据编号", "NVARCHAR(30) NOT NULL", "单据号，同一单据可包含多行；调拨的出库行和入库行共用同一单据号"),
    ("仓库名称", "NVARCHAR(30) NOT NULL", "取值：长沙中心仓、株洲前置仓、湘潭前置仓"),
    ("供应商名称", "NVARCHAR(50) NOT NULL", "该商品的供应商（所有单据类型都填写）"),
    ("客户名称", "NVARCHAR(50) NULL", "仅销售出库和销售退货填写，其余为 NULL"),
    ("商品编号", "NVARCHAR(20) NOT NULL", "商品编码，文本，如 YP1001"),
    ("商品名称", "NVARCHAR(50) NOT NULL", "商品通用名"),
    ("商品规格", "NVARCHAR(30) NOT NULL", "包装规格"),
    ("商品类别", "NVARCHAR(20) NOT NULL", "取值：西药、中成药、中药饮片、医疗器械、医用耗材、保健食品"),
    ("批号", "NVARCHAR(20) NOT NULL", "生产批号"),
    ("入库数量", "DECIMAL(18,2) NOT NULL", "入库方向数量（采购入库、销售退货、调拨入库、盘盈），否则为 0"),
    ("出库数量", "DECIMAL(18,2) NOT NULL", "出库方向数量（销售出库、采购退货、调拨出库、盘亏），否则为 0"),
    ("单价", "DECIMAL(18,2) NOT NULL", "销售类单据为售价，其余为进价"),
    ("开单金额", "DECIMAL(18,2) NOT NULL", "数量 × 单价"),
    ("库存数量", "DECIMAL(18,2) NOT NULL", "本行单据发生后，该仓库该商品的结存数量（流水余额）"),
    ("现有库存", "DECIMAL(18,2) NULL", "该仓库该商品截至数据导出日（2026-09-30）的当前库存；只填在该仓库该商品的最后一行，其余行为 NULL"),
    ("有效期至", "NVARCHAR(10) NOT NULL", "批次有效期，文本格式 yyyy-MM-dd"),
]


def main() -> None:
    env = load_env()
    sa = connect(env, as_sa=True, database="master")
    cur = sa.cursor()
    cur.execute("IF DB_ID(N'myai') IS NULL CREATE DATABASE [myai] COLLATE Chinese_PRC_CI_AS")
    password = env["DB_PASSWORD"].replace("'", "''")
    cur.execute(f"""
        IF SUSER_ID(N'myai_user') IS NULL
            CREATE LOGIN [myai_user] WITH PASSWORD = N'{password}', CHECK_POLICY = OFF;
        ELSE
            ALTER LOGIN [myai_user] WITH PASSWORD = N'{password}';
    """)
    sa.close()

    sa = connect(env, as_sa=True, database="myai")
    cur = sa.cursor()
    cur.execute("""
        IF USER_ID(N'myai_user') IS NULL CREATE USER [myai_user] FOR LOGIN [myai_user];
        IF IS_ROLEMEMBER(N'db_owner', N'myai_user') = 0 EXEC sp_addrolemember N'db_owner', N'myai_user';
    """)
    cur.execute("IF OBJECT_ID(N'dbo.inout', N'U') IS NOT NULL DROP TABLE dbo.inout")
    cur.execute("CREATE TABLE dbo.inout (" + ", ".join(f"[{n}] {t}" for n, t, _ in COLUMNS) + ")")
    cur.execute(
        "EXEC sp_addextendedproperty N'MS_Description', %s, N'SCHEMA', N'dbo', N'TABLE', N'inout'",
        (TABLE_DESCRIPTION,),
    )
    for name, _, desc in COLUMNS:
        cur.execute(
            "EXEC sp_addextendedproperty N'MS_Description', %s, N'SCHEMA', N'dbo', N'TABLE', N'inout', N'COLUMN', %s",
            (desc, name),
        )

    rows = generate()
    names = [n for n, _, _ in COLUMNS if n != "id"]
    sql = "INSERT INTO dbo.inout (" + ", ".join(f"[{n}]" for n in names) + ") VALUES (" + ", ".join(["%s"] * len(names)) + ")"
    cur.executemany(sql, [tuple(r[n] for n in names) for r in rows])
    cur.execute("SELECT COUNT(*), MIN([按日期]), MAX([按日期]) FROM dbo.inout")
    count, first, last = cur.fetchone()
    print(f"dbo.inout 已写入 {count} 行（{first} ~ {last}）")
    sa.close()


if __name__ == "__main__":
    main()
