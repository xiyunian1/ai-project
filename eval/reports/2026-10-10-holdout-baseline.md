# Text-to-SQL Agent 评测报告：holdout-baseline

- 时间：2026-10-10T22:35:08
- 模型：deepseek-flash
- Agent 模式：json
- 用例：30 条（sql_cases_holdout.jsonl），并发 3

## 总览

| 指标 | 数值 |
|---|---|
| **执行准确率**（最后一条 SQL 的结果与 gold 一致） | **20/30 = 66.7%** |
| 回答准确率（另计入：SQL 结果不一致但回答文本包含全部 gold 值） | 24/30 = 80.0% |
| 平均 SQL 执行次数 | 1.37 |
| 平均模型调用次数 | 2.30 |
| 平均输入 / 输出 token | 2365 / 1385 |
| 耗时 P50 / P95 | 5.2s / 15.5s |
| 运行出错（异常中断） | 2 条 |

## 分类准确率

| 类别 | 通过 | 准确率 |
|---|---|---|
| 单表查找 | 2/3 | 66.7% |
| 聚合统计 | 4/5 | 80.0% |
| 分组排名 | 3/4 | 75.0% |
| 时间口径 | 4/6 | 66.7% |
| 库存与效期 | 2/5 | 40.0% |
| 复杂组合 | 3/5 | 60.0% |
| 多轮追问 | 2/2 | 100.0% |

## 未通过用例

| ID | 问题 | 原因 | 最后执行的 SQL |
|---|---|---|---|
| X03 | 仁济诊所的货是从哪个仓库发出的？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 2 行 | `SELECT 仓库名称, 单据类型, COUNT(DISTINCT 单据编号) AS 单据数, SUM(CASE WHEN 出库数量 IS NOT NULL AND ISNUMERIC(出库数量) = 1 THEN CAST(出库数量 AS FLOAT) ELSE 0 END) AS 出库数量合计 FROM dbo.i` |
| X07 | 保健食品类今年的销售出库金额是多少？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额, SUM(CASE WHEN ISNUMERIC(出库数量) = 1 THEN CAST(出库数量 AS DECIMAL(18,` |
| X09 | 今年销售出库金额排名前 3 的商品类别是哪些？从高到低。 | 结果不一致：gold 3 行，Agent 3 行 | `SELECT TOP 3 商品类别, SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS decimal(18,2)) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) ` |
| X13 | 2026 年 5 月哪个客户的销售出库单数最多？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT TOP 1 客户名称, COUNT(DISTINCT 单据编号) AS 销售出库单数 FROM dbo.inout WHERE 按日期 >= ? AND 按日期 < ? AND 单据类型 LIKE ? AND 客户名称 IS NOT NULL AND 客户名称 <> '' GROUP BY 客户名称 OR` |
| X15 | 今年第三季度销售出库金额最高的商品是哪个？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 5 行 | `SELECT TOP 5 商品编号, 商品名称, SUM(CAST(开单金额 AS DECIMAL(18,2))) AS 销售金额 FROM dbo.inout WHERE 按日期 >= ? AND 按日期 <= ? AND 单据类型 = ? GROUP BY 商品编号, 商品名称 ORDER BY 销售金额 DESC` |
| X19 | 湘潭前置仓当前库存最多的 3 个商品是哪些？从多到少。 | 结果不一致：gold 3 行，Agent 3 行 | `WITH latest AS (SELECT 商品编号, 商品名称, 商品规格, 批号, 库存数量, ROW_NUMBER() OVER (PARTITION BY 商品编号, 批号 ORDER BY 按日期 DESC, id DESC) AS rn FROM dbo.inout WHERE 仓库名称 = ?) SEL` |
| X21 | 当前还有库存、且有效期在 2027 年 6 月 30 日之前的批次有多少个？（按仓库、商品、批号组合计数） | 运行出错: Analysis failed: Only SELECT queries are allowed. | `` |
| X22 | 医疗器械类商品当前在三个仓库的库存总量是多少？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 3 行 | `SELECT 仓库名称, SUM(CASE WHEN ISNUMERIC(现有库存) = 1 THEN CAST(现有库存 AS DECIMAL(18,2)) ELSE 0 END) AS 库存总量 FROM dbo.inout WHERE 商品类别 LIKE '%医疗器械%' GROUP BY 仓库名称 ORDER ` |
| X26 | 今年每个仓库的销售出库金额各占全部销售出库金额的百分之多少？（用百分数数值表示，例如 12.34） | 运行出错: Analysis failed: Incorrect syntax near 'ົ'. | `` |
| X27 | 有多少个客户在 2026 年 9 月份没有任何销售出库？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 1 行 | `SELECT COUNT(DISTINCT 客户名称) AS 全部客户数, COUNT(DISTINCT CASE WHEN 单据类型 LIKE '%销售%' AND 按日期 >= '2026-09-01' AND 按日期 < '2026-10-01' AND 出库数量 > 0 THEN 客户名称 END) AS 九月` |
