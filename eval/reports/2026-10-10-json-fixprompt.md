# Text-to-SQL Agent 评测报告：json-fixprompt

- 时间：2026-10-10T22:21:32
- 模型：deepseek-flash
- Agent 模式：json-fixprompt
- 用例：100 条（sql_cases.jsonl），并发 4

## 总览

| 指标 | 数值 |
|---|---|
| **执行准确率**（最后一条 SQL 的结果与 gold 一致） | **57/100 = 57.0%** |
| 回答准确率（另计入：SQL 结果不一致但回答文本包含全部 gold 值） | 74/100 = 74.0% |
| 平均 SQL 执行次数 | 1.33 |
| 平均模型调用次数 | 2.27 |
| 平均输入 / 输出 token | 2463 / 1343 |
| 耗时 P50 / P95 | 4.8s / 14.6s |
| 运行出错（异常中断） | 6 条 |

## 分类准确率

| 类别 | 通过 | 准确率 |
|---|---|---|
| 单表查找 | 12/14 | 85.7% |
| 聚合统计 | 11/16 | 68.8% |
| 分组排名 | 7/18 | 38.9% |
| 时间口径 | 6/16 | 37.5% |
| 库存与效期 | 6/14 | 42.9% |
| 复杂组合 | 7/12 | 58.3% |
| 多轮追问 | 3/5 | 60.0% |
| 需要澄清 | 2/2 | 100.0% |
| 安全拦截 | 3/3 | 100.0% |

## 未通过用例

| ID | 问题 | 原因 | 最后执行的 SQL |
|---|---|---|---|
| A10 | 湘潭前置仓服务的客户有哪些？ | ANSWER_ONLY:结果不一致：gold 2 行，Agent 1 行 | `SELECT 仓库名称, COUNT(*) AS 记录数, COUNT(DISTINCT 客户名称) AS 客户数 FROM dbo.inout WHERE 仓库名称 LIKE '%湘潭%' GROUP BY 仓库名称 ORDER BY 记录数 DESC` |
| A11 | 批号 250621J 是哪个商品的批次？有效期到什么时候？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 9 行 | `SELECT TOP 20 批号, 商品编号, 商品名称, 商品规格, 有效期至, 按日期, 单据类型 FROM dbo.inout WHERE 批号 = ? ORDER BY 按日期 DESC /* params=[250621J] */` |
| B02 | 今年一共有多少张销售出库单？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 2 行 | `SELECT 单据类型, COUNT(DISTINCT 单据编号) AS 单据数 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) = YEAR(GETDATE()) GROUP BY 单据类型` |
| B03 | 今年销售出库的总金额是多少？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库总金额 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) = YEAR(GETD` |
| B09 | 今年的净销售额是多少？（销售出库金额减去销售退货金额） | ANSWER_ONLY:结果不一致：gold 1 行，Agent 2 行 | `SELECT 单据类型, COUNT(DISTINCT 单据编号) AS 单据数, SUM(CASE WHEN ISNUMERIC(开单金额)=1 THEN CAST(开单金额 AS decimal(18,2)) ELSE 0 END) AS 开单金额合计, SUM(CASE WHEN ISNUMERIC(出库数量)=` |
| B14 | 今年从华康医药有限公司采购入库的金额是多少？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 采购入库金额 FROM dbo.inout WHERE 供应商名称 = ? AND 单据类型 LIKE ? AND YEAR(按日期) = Y` |
| B15 | 第三人民医院今年的销售出库金额是多少？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT SUM(CAST(开单金额 AS DECIMAL(18,2))) AS 销售出库金额 FROM dbo.inout WHERE 客户名称 = ? AND 单据类型 LIKE ? AND YEAR(按日期) = YEAR(GETDATE()) /* params=[第三人民医院, %销售%] */` |
| C02 | 各仓库今年的销售出库金额分别是多少？ | 结果不一致：gold 3 行，Agent 3 行 | `SELECT 仓库名称, SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) = YEAR` |
| C03 | 各商品类别今年的销售出库金额是多少？ | 结果不一致：gold 6 行，Agent 6 行 | `SELECT 商品类别, SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) = YEAR` |
| C05 | 按客户统计今年的销售出库金额，从高到低排列。 | 结果不一致：gold 8 行，Agent 8 行 | `SELECT 客户名称, SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) = YEAR` |
| C06 | 各供应商今年的采购入库金额是多少？ | 结果不一致：gold 7 行，Agent 7 行 | `SELECT 供应商名称, SUM(CASE WHEN ISNUMERIC(开单金额)=1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 采购入库金额 FROM dbo.inout WHERE 单据类型 LIKE '%采购%' AND YEAR(按日期) = YEAR(` |
| C09 | 今年销售出库单数最多的前 3 个客户是谁？从多到少。 | 结果不一致：gold 3 行，Agent 3 行 | `SELECT TOP 3 客户名称, COUNT(DISTINCT 单据编号) AS 销售出库单数 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND 客户名称 IS NOT NULL AND YEAR(按日期) = YEAR(GETDATE()) GROUP BY 客户名称 ORDER` |
| C10 | 各商品类别当前的库存总量是多少？ | 运行出错: Analysis failed: Error converting data type varchar to numeric. | `` |
| C11 | 今年销售出库金额最低的 5 个商品是哪些？从低到高。 | 结果不一致：gold 5 行，Agent 5 行 | `SELECT TOP 5 商品编号, 商品名称, 商品规格, SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AN` |
| C12 | 每个客户今年买过多少种不同的商品？ | 结果不一致：gold 8 行，Agent 1 行 | `SELECT COUNT(DISTINCT 商品编号) AS 全部商品种类数 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) = YEAR(GETDATE())` |
| C15 | 今年哪个商品的销售退货数量最多？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 10 行 | `SELECT TOP 10 商品编号, 商品名称, 商品规格, SUM(CASE WHEN ISNUMERIC(入库数量)=1 THEN CAST(入库数量 AS DECIMAL(18,4)) ELSE 0 END) AS 退货数量合计, COUNT(DISTINCT 单据编号) AS 退货单据数 FROM dbo.i` |
| C17 | 西药类商品中，今年销售出库金额最高的 3 个商品是哪些？从高到低。 | 结果不一致：gold 3 行，Agent 3 行 | `SELECT TOP 3 商品编号, 商品名称, 商品规格, SUM(CASE WHEN ISNUMERIC(开单金额)=1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND ` |
| C18 | 每个仓库当前库存数量最多的商品分别是哪个？ | ANSWER_ONLY:结果不一致：gold 3 行，Agent 9 行 | `WITH Latest AS (SELECT 仓库名称, 商品编号, 商品名称, 商品规格, 库存数量, 现有库存, 按日期, ROW_NUMBER() OVER (PARTITION BY 仓库名称, 商品编号 ORDER BY 按日期 DESC, id DESC) rn FROM dbo.inout WHERE 仓` |
| D01 | 2026 年每个月的销售出库金额是多少？ | 结果不一致：gold 9 行，Agent 1 行 | `SELECT MIN(按日期) AS 最早日期, MAX(按日期) AS 最晚日期, COUNT(DISTINCT MONTH(按日期)) AS 月份数 FROM dbo.inout WHERE YEAR(按日期) = ? AND 单据类型 LIKE ? /* params=[2026, %销售%] */` |
| D02 | 2026 年第二季度（4 到 6 月）的销售出库总金额是多少？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT CAST(SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS DECIMAL(18,2)) AS 销售出库总金额 FROM dbo.inout WHERE 按日期 >= ? AND 按日期 < ` |
| D03 | 2026 年 7 月销售出库数量最多的商品是哪个？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 5 行 | `SELECT TOP 5 商品编号, 商品名称, 商品规格, SUM(CASE WHEN ISNUMERIC(出库数量) = 1 THEN CAST(出库数量 AS FLOAT) ELSE 0 END) AS 出库数量合计 FROM inout WHERE 单据类型 LIKE '%销售%' AND YEAR(按日期) ` |
| D04 | 藿香正气水在 2026 年 1 月和 7 月的销售出库数量分别是多少？ | ANSWER_ONLY:结果不一致：gold 2 行，Agent 7 行 | `SELECT MONTH(按日期) AS 月份, SUM(CASE WHEN ISNUMERIC(出库数量) = 1 THEN CAST(出库数量 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库数量 FROM dbo.inout WHERE 按日期 >= ? AND 按日期 < ? AND ` |
| D06 | 2026 年每个月分别有多少张销售退货单？ | 结果不一致：gold 9 行，Agent 12 行 | `WITH months AS (     SELECT 1 AS 月份     UNION ALL SELECT 2     UNION ALL SELECT 3     UNION ALL SELECT 4     UNION ALL SELECT 5     UNION ALL SELECT 6     UNION` |
| D07 | 2026 年 8 月 15 日到 8 月 31 日，株洲前置仓的销售出库金额是多少？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额, COUNT(DISTINCT 单据编号) AS 单据数, SUM(CASE WHEN ISNUMERIC(出库数量) = 1 ` |
| D10 | 金银花最后一次采购入库是哪一天？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 5 行 | `SELECT TOP 20 按日期, 单据类型, 单据编号, 商品编号, 商品名称, 商品规格, 入库数量, 供应商名称 FROM dbo.inout WHERE 商品名称 LIKE ? AND 单据类型 LIKE ? ORDER BY 按日期 DESC /* params=[%金银花%, %采购%入库%] */` |
| D11 | 医用雾化器最近一次销售出库是哪一天？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 2 行 | `SELECT TOP 5 按日期, 单据类型, 单据编号, 客户名称, 商品名称, 商品规格, 出库数量, 开单金额 FROM dbo.inout WHERE 商品名称 LIKE ? AND 单据类型 LIKE ? AND 出库数量 > 0 AND 按日期 = ? ORDER BY 单据编号 /* params=[%雾` |
| D12 | 哪些商品从 2026 年 8 月 1 日起再也没有采购入库过？ | ANSWER_ONLY:结果不一致：gold 3 行，Agent 1 行 | `SELECT MIN(按日期) AS 最早日期, MAX(按日期) AS 最晚日期, SUM(CASE WHEN 按日期 >= '2026-08-01' AND 单据类型 LIKE '%采购%' THEN 1 ELSE 0 END) AS 八月后采购记录数 FROM dbo.inout` |
| D13 | 2026 年 3 月的销售出库金额比 2 月多了多少？ | 运行出错: Analysis failed: Error converting data type varchar to numeric. | `` |
| E02 | 当前库存低于 10 的有哪些仓库的哪些商品？列出仓库、商品和库存。 | 结果不一致：gold 47 行，Agent 3 行 | `WITH latest AS (   SELECT 仓库名称, 商品编号, 商品规格, 现有库存, 按日期, id,          ROW_NUMBER() OVER (PARTITION BY 仓库名称, 商品编号, 商品规格 ORDER BY 按日期 DESC, id DESC) AS rn   FROM db` |
| E05 | 哪些批次的有效期在 2026 年 12 月 31 日之前，而且目前还有库存？列出仓库、商品、批号和剩余数量。 | 结果不一致：gold 3 行，Agent 26 行 | `WITH LatestBatch AS (   SELECT 仓库名称, 商品名称, 批号, 库存数量,          ROW_NUMBER() OVER (PARTITION BY 仓库名称, 商品名称, 批号 ORDER BY 按日期 DESC, id DESC) AS rn   FROM dbo.inout ` |
| E07 | 长沙中心仓还有库存的批次里，有效期最早的是哪个商品的哪个批号？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 10 行 | `SELECT TOP 10 商品编号, 商品名称, 商品规格, 批号, 有效期至, SUM(CASE WHEN ISNUMERIC(现有库存) = 1 THEN CAST(现有库存 AS decimal(18,4)) ELSE 0 END) AS 库存合计 FROM dbo.inout WHERE 仓库名称 = ? A` |
| E08 | 截至 2026 年 6 月 30 日，长沙中心仓阿莫西林胶囊的库存是多少？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 20 行 | `SELECT TOP 20 按日期, 单据类型, 单据编号, 仓库名称, 商品编号, 商品名称, 商品规格, 入库数量, 出库数量, 库存数量, 现有库存 FROM dbo.inout WHERE 按日期 <= ? AND 仓库名称 LIKE ? AND 商品名称 LIKE ? ORDER BY 按日期 DESC /*` |
| E09 | 哪些商品在三个仓库都已经没有库存了？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 3 行 | `SELECT 仓库名称, MAX(按日期) AS 最新日期, SUM(CASE WHEN ISNUMERIC(现有库存) = 1 THEN CAST(现有库存 AS DECIMAL(18,4)) ELSE 0 END) AS 现有库存合计 FROM dbo.inout WHERE 商品编号 = ? GROUP BY 仓` |
| E10 | 湘潭前置仓当前库存按进价计算总共值多少钱？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT 仓库名称, SUM(CASE WHEN ISNUMERIC(现有库存)=1 THEN CAST(现有库存 AS DECIMAL(18,4)) ELSE 0 END) AS 库存合计, SUM(CASE WHEN ISNUMERIC(现有库存)=1 AND ISNUMERIC(单价)=1 THEN CAST` |
| E12 | 维生素D3软胶囊目前各个批次的剩余数量是多少？（三个仓库合计，只列有剩余的批次） | 结果不一致：gold 2 行，Agent 6 行 | `WITH latest AS (SELECT 仓库名称, 批号, 库存数量, ROW_NUMBER() OVER (PARTITION BY 仓库名称, 批号 ORDER BY id DESC) AS rn FROM dbo.inout WHERE 商品名称 = ? AND 批号 IS NOT NULL AND 批号 ` |
| E13 | 医用外科口罩在长沙中心仓还有剩余的批次有哪些？各剩多少？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 5 行 | `WITH ranked AS (SELECT 批号, 商品规格, 有效期至, 按日期, 库存数量, id, ROW_NUMBER() OVER (PARTITION BY 批号 ORDER BY 按日期 DESC, id DESC) AS rn FROM dbo.inout WHERE 商品名称 LIKE ? AND ` |
| F02 | 今年买过医疗器械类商品的客户有几个？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 8 行 | `SELECT DISTINCT 客户名称 FROM dbo.inout WHERE 单据类型 LIKE '%销售%' AND 商品类别 LIKE '%医疗器械%' AND YEAR(按日期) = 2026 AND 客户名称 IS NOT NULL` |
| F03 | 每个商品类别的销售出库金额占全部销售出库金额的百分之多少？（用百分数数值表示，例如 12.34 表示 12.34%） | 结果不一致：gold 6 行，Agent 6 行 | `WITH sales AS ( SELECT 商品类别, SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS amt FROM dbo.inout WHERE 单据类型 LIKE '%销售%' GROUP B` |
| F04 | 哪个供应商的商品今年销售出库金额最高？ | ANSWER_ONLY:结果不一致：gold 1 行，Agent 7 行 | `SELECT TOP 10 供应商名称, SUM(CASE WHEN 开单金额 IS NOT NULL AND ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS DECIMAL(18,2)) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 单据类型 LIK` |
| F06 | 每个客户购买数量最多的商品分别是哪个？（按销售出库数量） | 运行出错: Analysis failed: Only SELECT queries are allowed. | `` |
| F07 | 今年采购入库金额比销售出库金额多（或少）多少？请给出采购入库金额减去销售出库金额的差。 | 运行出错: Analysis failed: Error converting data type varchar to numeric. | `` |
| G02 | 那布洛芬缓释胶囊呢？ | 运行出错: Analysis failed: Error converting data type varchar to numeric. | `` |
| G03 | 8 月呢？ | 结果不一致：gold 1 行，Agent 1 行 | `SELECT SUM(CASE WHEN ISNUMERIC(开单金额) = 1 THEN CAST(开单金额 AS FLOAT) ELSE 0 END) AS 销售出库金额 FROM dbo.inout WHERE 按日期 >= ? AND 按日期 < ? AND 单据类型 LIKE ? /* params=[202` |
