"""用大模型生成 20 篇与测试问题无关的公司制度，作为"知识库规模变大"场景下的干扰文档。

生成结果已提交到本目录，评测直接使用；只有需要重新生成时才运行本脚本：
    eval/.venv/bin/python -I eval/knowledge_distractors/generate_distractors.py
"""
from __future__ import annotations

import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from common import load_env  # noqa: E402

TOPICS = [
    "财务费用报销管理办法", "差旅管理办法", "车辆使用管理规定", "信息系统账号与权限管理规定", "数据备份与恢复管理规定",
    "办公用品申领管理办法", "合同管理办法", "印章使用管理规定", "档案管理制度", "突发事件应急预案",
    "包装材料管理规定", "门禁与访客管理规定", "发票管理办法", "应收账款管理办法", "废弃物处理规定",
    "节能降耗管理规定", "会议管理办法", "固定资产管理办法", "保密管理制度", "客户档案管理办法",
]

PROMPT = """请为一家医药流通企业撰写一份内部制度文件《{topic}》。
要求：
1. 使用 Markdown，一级标题为文件名，用"## 第一章 总则"这样的二级标题分章，章内用"### 1.1 目的"这样的三级标题分条，共 5-6 章。
2. 正文 900-1300 字，内容具体，包含明确的时限、金额、比例、审批部门等数字和规定。
3. 不要涉及以下内容：温湿度、药品有效期或效期、退货、盘点、调拨、安全库存或补货、供应商分级或评审、入库验收、员工培训、年终奖或薪酬、年假或休假、叉车或设备保养。
4. 不要写公司名称，统一称"公司"。只输出制度正文，不要任何解释。"""


def generate(topic: str, env: dict) -> str:
    r = requests.post(
        env.get("LLM_BASE_URL", "https://api.deepseek.com").rstrip("/") + "/chat/completions",
        headers={"Authorization": f"Bearer {env['LLM_API_KEY']}"},
        json={"model": env.get("LLM_MODEL", "deepseek-flash"), "temperature": 0.7,
              "messages": [{"role": "user", "content": PROMPT.format(topic=topic)}]},
        timeout=300,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip()


def main() -> None:
    env = load_env()
    out = Path(__file__).resolve().parent
    todo = [t for t in TOPICS if not (out / f"{t}.md").exists()]
    with ThreadPoolExecutor(max_workers=5) as pool:
        for topic, text in zip(todo, pool.map(lambda t: generate(t, env), todo)):
            (out / f"{topic}.md").write_text(text.removeprefix("```markdown").removesuffix("```").strip() + "\n", encoding="utf-8")
            print(topic, len(text))


if __name__ == "__main__":
    main()
