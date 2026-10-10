"""生成评测用的医药仓储出入库合成数据（固定随机种子，结果可复现）。

模拟 2026-01-01 至 2026-09-30 的单据流水：采购入库、销售出库、销售退货、采购退货、
调拨出库/调拨入库、盘点调整。批次按先进先出出库。所有名称均为虚构。

列语义（同时写入 SQL Server 的 MS_Description，供 Agent 读取）：
- 库存数量：本行单据发生后，该仓库该商品的结存数量（流水余额）
- 现有库存：该仓库该商品截至数据导出日的当前库存，只填在该仓库该商品的最后一行，其余行为 NULL
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date, timedelta

SEED = 20260510
START = date(2026, 1, 1)
END = date(2026, 9, 30)

CENTRAL = "长沙中心仓"
FRONT_WAREHOUSES = ["株洲前置仓", "湘潭前置仓"]
WAREHOUSES = [CENTRAL, *FRONT_WAREHOUSES]

SUPPLIERS = {
    "HK": "华康医药有限公司",
    "RF": "瑞丰药业有限公司",
    "HX": "恒信生物制药有限公司",
    "BCT": "百草堂中药饮片有限公司",
    "KY": "康源医疗器械有限公司",
    "AT": "安泰医用耗材有限公司",
    "HR": "和润健康食品有限公司",
}

CUSTOMERS = {
    "康宁大药房": CENTRAL,
    "仁济诊所": CENTRAL,
    "雨花区社区卫生服务中心": CENTRAL,
    "第三人民医院": CENTRAL,
    "惠民连锁药房": "株洲前置仓",
    "济世堂药店": "株洲前置仓",
    "安心大药房": "湘潭前置仓",
    "岳塘区社区卫生服务中心": "湘潭前置仓",
}

# 编号, 名称, 规格, 类别, 供应商, 进价, 售价, 保质期(月), 日均需求(全公司)
PRODUCTS = [
    ("YP1001", "阿莫西林胶囊", "0.25g*24粒", "西药", "HK", 6.50, 9.80, 24, 9),
    ("YP1002", "布洛芬缓释胶囊", "0.3g*20粒", "西药", "HK", 8.20, 12.50, 24, 8),
    ("YP1003", "头孢克肟分散片", "0.1g*6片", "西药", "RF", 15.00, 22.80, 24, 4),
    ("YP1004", "盐酸二甲双胍片", "0.5g*48片", "西药", "HX", 9.60, 14.50, 36, 6),
    ("YP1005", "苯磺酸氨氯地平片", "5mg*28片", "西药", "HX", 12.30, 18.90, 36, 6),
    ("YP1006", "奥美拉唑肠溶胶囊", "20mg*14粒", "西药", "HK", 7.80, 11.90, 24, 5),
    ("YP1007", "氯雷他定片", "10mg*12片", "西药", "RF", 10.50, 16.00, 36, 4),
    ("YP1008", "阿托伐他汀钙片", "20mg*7片", "西药", "HX", 18.60, 27.50, 24, 4),
    ("YP1009", "蒙脱石散", "3g*10袋", "西药", "HK", 9.90, 14.80, 36, 3),
    ("YP1010", "维生素C片", "0.1g*100片", "西药", "RF", 3.20, 5.50, 24, 7),
    ("YP1011", "硝苯地平控释片", "30mg*7片", "西药", "HX", 21.00, 31.50, 24, 3),
    ("YP1012", "左氧氟沙星片", "0.5g*4片", "西药", "RF", 11.40, 17.20, 24, 3),
    ("YP2001", "板蓝根颗粒", "10g*20袋", "中成药", "BCT", 8.80, 13.50, 18, 7),
    ("YP2002", "连花清瘟胶囊", "0.35g*24粒", "中成药", "RF", 12.60, 19.80, 24, 6),
    ("YP2003", "复方丹参滴丸", "27mg*180丸", "中成药", "BCT", 19.50, 29.00, 36, 3),
    ("YP2004", "藿香正气水", "10ml*10支", "中成药", "BCT", 6.40, 9.90, 24, 4),
    ("YP2005", "六味地黄丸", "200丸", "中成药", "BCT", 14.20, 21.50, 36, 3),
    ("YP2006", "感冒灵颗粒", "10g*9袋", "中成药", "RF", 7.20, 11.00, 24, 6),
    ("YP3001", "黄芪片", "500g", "中药饮片", "BCT", 32.00, 46.00, 12, 2),
    ("YP3002", "当归片", "250g", "中药饮片", "BCT", 38.00, 55.00, 12, 2),
    ("YP3003", "枸杞子", "500g", "中药饮片", "BCT", 45.00, 65.00, 12, 3),
    ("YP3004", "金银花", "250g", "中药饮片", "BCT", 52.00, 75.00, 12, 2),
    ("YP3005", "党参段", "500g", "中药饮片", "BCT", 40.00, 58.00, 12, 2),
    ("YP4001", "电子血压计", "臂式", "医疗器械", "KY", 128.00, 189.00, 60, 1),
    ("YP4002", "指夹式血氧仪", "标准型", "医疗器械", "KY", 68.00, 99.00, 60, 1),
    ("YP4003", "血糖试纸", "50片/盒", "医疗器械", "KY", 42.00, 62.00, 18, 3),
    ("YP4004", "医用雾化器", "压缩式", "医疗器械", "KY", 210.00, 299.00, 60, 1),
    ("YP4005", "红外体温计", "额温型", "医疗器械", "KY", 55.00, 85.00, 60, 1),
    ("YP5001", "医用外科口罩", "10只/包", "医用耗材", "AT", 3.50, 6.00, 24, 14),
    ("YP5002", "一次性使用无菌注射器", "5ml*100支", "医用耗材", "AT", 28.00, 42.00, 36, 2),
    ("YP5003", "医用棉签", "100支/包", "医用耗材", "AT", 2.10, 3.80, 36, 8),
    ("YP5004", "碘伏消毒液", "100ml", "医用耗材", "AT", 4.60, 7.50, 18, 6),
    ("YP5005", "医用纱布块", "10片/包", "医用耗材", "AT", 3.80, 6.20, 36, 5),
    ("YP5006", "医用检查手套", "100只/盒", "医用耗材", "AT", 22.00, 33.00, 36, 2),
    ("YP6001", "维生素D3软胶囊", "60粒", "保健食品", "HR", 36.00, 58.00, 18, 2),
    ("YP6002", "钙维生素D片", "100片", "保健食品", "HR", 42.00, 68.00, 24, 2),
    ("YP6003", "蛋白粉", "400g", "保健食品", "HR", 98.00, 158.00, 12, 1),
    ("YP6004", "鱼油软胶囊", "100粒", "保健食品", "HR", 65.00, 99.00, 18, 1),
]

# 感冒类冬春季走量，藿香正气水夏季走量
SEASONAL = {
    "YP1002": {1: 2.2, 2: 1.8, 3: 1.2, 12: 1.8},
    "YP2001": {1: 2.5, 2: 2.0, 3: 1.3},
    "YP2002": {1: 2.5, 2: 2.0, 3: 1.2},
    "YP2006": {1: 2.2, 2: 1.8, 3: 1.2},
    "YP5001": {1: 2.0, 2: 1.6},
    "YP2004": {6: 2.5, 7: 3.0, 8: 2.6},
}

# 8 月中旬起供应商断货、不再补货的商品，用于形成期末低库存
STOP_REORDER_AFTER = {"YP3004": date(2026, 8, 10), "YP4004": date(2026, 7, 20),
                      "YP1003": date(2026, 8, 20), "YP6003": date(2026, 8, 1)}

# 9 月补进的短效期批次（供应商清仓），用于近效期类问题：日期 -> (供应商, [(商品, 数量, 有效期)])
SPECIAL_PURCHASES = {
    date(2026, 9, 8): ("HR", [("YP6001", 60.0, date(2026, 12, 20))]),
    date(2026, 9, 9): ("KY", [("YP4003", 80.0, date(2026, 11, 30))]),
    date(2026, 9, 15): ("AT", [("YP5004", 100.0, date(2027, 1, 15))]),
}

DOC_PREFIX = {"采购入库": "CG", "销售出库": "XS", "销售退货": "XT", "采购退货": "CT", "调拨": "DB", "盘点调整": "PD"}


@dataclass
class Batch:
    batch_no: str
    expiry: date
    qty: float


@dataclass
class Product:
    code: str
    name: str
    spec: str
    category: str
    supplier: str
    cost: float
    price: float
    shelf_months: int
    daily: float


@dataclass
class Sim:
    rng: random.Random
    products: dict[str, Product]
    stock: dict[tuple[str, str], list[Batch]] = field(default_factory=dict)
    rows: list[dict] = field(default_factory=list)
    doc_seq: dict[tuple[str, date], int] = field(default_factory=dict)
    recent_sales: list[tuple[date, str, str, str, str, float]] = field(default_factory=list)
    batch_expiry: dict[tuple[str, str], date] = field(default_factory=dict)

    def next_doc(self, kind: str, day: date) -> str:
        key = (kind, day)
        self.doc_seq[key] = self.doc_seq.get(key, 0) + 1
        return f"{DOC_PREFIX[kind]}{day:%Y%m%d}{self.doc_seq[key]:03d}"

    def balance(self, wh: str, code: str) -> float:
        return sum(b.qty for b in self.stock.get((wh, code), []))

    def emit(self, day, doc_type, doc_no, wh, p: Product, batch: Batch, qty_in, qty_out, unit_price, customer=None):
        qty = qty_in or qty_out
        self.rows.append({
            "按日期": day.isoformat(),
            "单据类型": doc_type,
            "单据编号": doc_no,
            "仓库名称": wh,
            "供应商名称": SUPPLIERS[p.supplier],
            "客户名称": customer,
            "商品编号": p.code,
            "商品名称": p.name,
            "商品规格": p.spec,
            "商品类别": p.category,
            "批号": batch.batch_no,
            "入库数量": round(qty_in, 2),
            "出库数量": round(qty_out, 2),
            "单价": round(unit_price, 2),
            "开单金额": round(qty * unit_price, 2),
            "库存数量": round(self.balance(wh, p.code), 2),
            "现有库存": None,
            "有效期至": batch.expiry.isoformat(),
        })

    def receive(self, wh: str, code: str, batch: Batch):
        batches = self.stock.setdefault((wh, code), [])
        for existing in batches:
            if existing.batch_no == batch.batch_no:
                existing.qty += batch.qty
                return
        batches.append(Batch(batch.batch_no, batch.expiry, batch.qty))
        batches.sort(key=lambda b: (b.expiry, b.batch_no))

    def take_fifo(self, wh: str, code: str, qty: float) -> list[tuple[Batch, float]]:
        taken = []
        for b in self.stock.get((wh, code), []):
            if qty <= 0:
                break
            use = min(b.qty, qty)
            if use > 0:
                b.qty -= use
                qty -= use
                taken.append((b, use))
        self.stock[(wh, code)] = [b for b in self.stock.get((wh, code), []) if b.qty > 0]
        return taken


def demand_factor(code: str, day: date) -> float:
    return SEASONAL.get(code, {}).get(day.month, 1.0)


def new_batch(sim: Sim, p: Product, day: date, qty: float) -> Batch:
    produced = day - timedelta(days=sim.rng.randint(15, 150))
    expiry = produced + timedelta(days=int(p.shelf_months * 30.4))
    if p.code == "YP6003" and day.month == 5:
        expiry = date(2026, 11, 15)  # 折价采购的近效期蛋白粉
    batch_no = f"{produced:%y%m%d}{sim.rng.choice('ABC')}"
    # 同一商品同一批号的有效期必须一致
    expiry = sim.batch_expiry.setdefault((p.code, batch_no), expiry)
    return Batch(batch_no, expiry, qty)


def purchase(sim: Sim, day: date, supplier: str, needs: list[tuple[Product, float]],
             forced_expiry: dict[str, date] | None = None):
    doc = sim.next_doc("采购入库", day)
    for p, qty in needs:
        batch = new_batch(sim, p, day, qty)
        if forced_expiry and p.code in forced_expiry:
            expiry = forced_expiry[p.code]
            batch_no = f"{expiry - timedelta(days=int(p.shelf_months * 30.4)):%y%m%d}J"
            sim.batch_expiry[(p.code, batch_no)] = expiry
            batch = Batch(batch_no, expiry, qty)
        sim.receive(CENTRAL, p.code, batch)
        sim.emit(day, "采购入库", doc, CENTRAL, p, batch, qty, 0, p.cost)


def generate() -> list[dict]:
    rng = random.Random(SEED)
    products = {row[0]: Product(*row) for row in PRODUCTS}
    sim = Sim(rng=rng, products=products)

    # 期初建仓：1 月 2 日各供应商一次大批量采购，随后调拨到前置仓
    by_supplier: dict[str, list[tuple[Product, float]]] = {}
    for p in products.values():
        by_supplier.setdefault(p.supplier, []).append((p, float(round(p.daily * 45 + rng.randint(5, 30)))))
    for sup, needs in by_supplier.items():
        purchase(sim, date(2026, 1, 2), sup, needs)

    day = START
    while day <= END:
        weekday = day.weekday()

        # 每周一：中心仓向前置仓调拨，补到约 12 天用量
        if weekday == 0 or day == date(2026, 1, 3):
            for wh in FRONT_WAREHOUSES:
                lines = []
                for p in products.values():
                    target = p.daily * 0.3 * 12
                    gap = round(target - sim.balance(wh, p.code))
                    available = sim.balance(CENTRAL, p.code)
                    qty = min(gap, available * 0.5)
                    if qty >= 2:
                        lines.append((p, float(round(qty))))
                if lines:
                    doc = sim.next_doc("调拨", day)
                    for p, qty in lines:
                        for batch, used in sim.take_fifo(CENTRAL, p.code, qty):
                            sim.emit(day, "调拨出库", doc, CENTRAL, p, batch, 0, used, p.cost)
                            moved = Batch(batch.batch_no, batch.expiry, used)
                            sim.receive(wh, p.code, moved)
                            sim.emit(day, "调拨入库", doc, wh, p, moved, used, 0, p.cost)

        # 销售：每个客户按概率下单，从所属仓库按先进先出出库
        for customer, wh in CUSTOMERS.items():
            if rng.random() > (0.55 if wh == CENTRAL else 0.45):
                continue
            share = 0.4 / 4 if wh == CENTRAL else 0.3 / 2
            picks = rng.sample(list(products.values()), rng.randint(2, 6))
            doc = sim.next_doc("销售出库", day)
            emitted = False
            order_prob = 0.55 if wh == CENTRAL else 0.45
            pick_prob = 4 / len(products)
            for p in picks:
                # 按下单概率与选品概率折算，使长期平均销量约等于日均需求
                expected = p.daily * share * demand_factor(p.code, day) * rng.uniform(0.6, 1.4) / (order_prob * pick_prob)
                qty = min(float(max(1, round(expected))), sim.balance(wh, p.code))
                if qty < 1:
                    continue
                for batch, used in sim.take_fifo(wh, p.code, qty):
                    sim.emit(day, "销售出库", doc, wh, p, batch, 0, used, p.price, customer)
                    sim.recent_sales.append((day, customer, wh, p.code, batch.batch_no, used))
                    emitted = True
            if not emitted:
                sim.doc_seq[("销售出库", day)] -= 1

        # 销售退货：约 2% 的近期销售行在 3-10 天后部分退回
        keep = []
        for sale in sim.recent_sales:
            sold_day, customer, wh, code, batch_no, qty = sale
            age = (day - sold_day).days
            if 3 <= age <= 10 and rng.random() < 0.003:
                p = products[code]
                back = float(max(1, round(qty * rng.uniform(0.2, 0.6))))
                batch = Batch(batch_no, sim.batch_expiry[(code, batch_no)], back)
                sim.receive(wh, code, batch)
                sim.emit(day, "销售退货", sim.next_doc("销售退货", day), wh, p, batch, back, 0, p.price, customer)
            elif age <= 10:
                keep.append(sale)
        sim.recent_sales = keep

        if day in SPECIAL_PURCHASES:
            sup, items = SPECIAL_PURCHASES[day]
            purchase(sim, day, sup, [(products[c], q) for c, q, _ in items], {c: e for c, _, e in items})

        # 采购补货：中心仓低于 20 天全公司用量时，按供应商合并下单（工作日）
        if weekday < 5:
            needs_by_supplier: dict[str, list[tuple[Product, float]]] = {}
            for p in products.values():
                stop = STOP_REORDER_AFTER.get(p.code)
                if stop and day >= stop:
                    continue
                total = sum(sim.balance(wh, p.code) for wh in WAREHOUSES)
                if total < p.daily * 20 and sim.balance(CENTRAL, p.code) < p.daily * 14:
                    qty = float(round(p.daily * rng.uniform(30, 50)))
                    needs_by_supplier.setdefault(p.supplier, []).append((p, qty))
            for sup, needs in needs_by_supplier.items():
                purchase(sim, day, sup, needs)

        # 采购退货：每月 15 日退回一笔质量问题批次的一部分
        if day.day == 15:
            candidates = [(code, b) for (wh, code), bs in sim.stock.items() if wh == CENTRAL for b in bs if b.qty >= 10]
            if candidates:
                code, b = rng.choice(candidates)
                p = products[code]
                qty = float(round(b.qty * rng.uniform(0.1, 0.3)))
                if qty >= 1:
                    doc = sim.next_doc("采购退货", day)
                    b.qty -= qty
                    sim.emit(day, "采购退货", doc, CENTRAL, p, b, 0, qty, p.cost)

        # 月末盘点：随机 4 个仓库商品组合 ±1~3 的盘盈盘亏
        if (day + timedelta(days=1)).month != day.month:
            doc = sim.next_doc("盘点调整", day)
            keys = [k for k, bs in sim.stock.items() if bs]
            for wh, code in rng.sample(keys, min(4, len(keys))):
                p = products[code]
                delta = rng.choice([-3, -2, -1, 1, 2])
                batch = sim.stock[(wh, code)][-1]
                if delta > 0:
                    batch.qty += delta
                    sim.emit(day, "盘点调整", doc, wh, p, batch, float(delta), 0, p.cost)
                elif batch.qty >= -delta:
                    batch.qty += delta
                    sim.stock[(wh, code)] = [b for b in sim.stock[(wh, code)] if b.qty > 0]
                    sim.emit(day, "盘点调整", doc, wh, p, batch, 0, float(-delta), p.cost)

        day += timedelta(days=1)

    # 现有库存：只回填到每个仓库每个商品的最后一行
    last_index: dict[tuple[str, str], int] = {}
    for i, row in enumerate(sim.rows):
        last_index[(row["仓库名称"], row["商品编号"])] = i
    for (wh, code), i in last_index.items():
        sim.rows[i]["现有库存"] = round(sim.balance(wh, code), 2)
    return sim.rows


if __name__ == "__main__":
    rows = generate()
    print(f"rows={len(rows)}")
    from collections import Counter
    print(Counter(r["单据类型"] for r in rows).most_common())
    low = [(r["仓库名称"], r["商品名称"], r["现有库存"]) for r in rows if r["现有库存"] is not None and r["现有库存"] < 10]
    print(f"low_stock={len(low)}", low[:12])
