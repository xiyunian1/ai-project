"""汇总多次评测运行，输出对比表（均值与波动范围）。

用法：
    eval/.venv/bin/python -I eval/compare_reports.py \
        "基线=baseline,baseline-r2,baseline-r3" "工具调用 v2=tools-v2,tools-v2-r2"
"""
from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

RAW = Path(__file__).resolve().parent / "reports" / "raw"


def load(label: str) -> dict:
    # 文件名形如 2026-10-10-<label>.json，按完整标签精确匹配，避免 baseline 匹配到 holdout-baseline
    matches = sorted(p for p in RAW.glob("*.json") if p.stem[11:] == label)
    if not matches:
        raise SystemExit(f"找不到运行记录：{label}")
    return json.loads(matches[-1].read_text(encoding="utf-8"))


def run_metrics(run: dict) -> dict:
    results = run["results"]
    traces = [r["trace"] for r in results]
    usage = [t.get("usage") or {} for t in traces]
    latencies = sorted(t.get("latencyMs", 0) / 1000 for t in traces)
    n = len(results)
    return {
        "n": n,
        "exec": sum(r["ok"] for r in results) / n,
        "answer": sum(r["ok"] or r["reason"].startswith("ANSWER_ONLY:") for r in results) / n,
        "errors": sum(1 for t in traces if t.get("error")),
        "calls": statistics.mean(u.get("llmCalls", 0) for u in usage),
        "prompt": statistics.mean(u.get("promptTokens", 0) for u in usage),
        "completion": statistics.mean(u.get("completionTokens", 0) for u in usage),
        "p50": latencies[len(latencies) // 2],
        "p95": latencies[min(n - 1, round(0.95 * (n - 1)))],
    }


def fmt_pct(values: list[float]) -> str:
    mean = statistics.mean(values) * 100
    if len(values) == 1:
        return f"{mean:.1f}%"
    return f"{mean:.1f}%（{min(values) * 100:.0f}–{max(values) * 100:.0f}）"


def main() -> None:
    rows = ["| 配置 | 运行次数 | 执行准确率 | 回答准确率 | 运行出错 | 模型调用次数 | 输入/输出 token | 耗时 P50 / P95 |",
            "|---|---|---|---|---|---|---|---|"]
    for group in sys.argv[1:]:
        name, labels = group.split("=", 1)
        metrics = [run_metrics(load(label.strip())) for label in labels.split(",")]
        avg = lambda key: statistics.mean(m[key] for m in metrics)  # noqa: E731
        rows.append(
            f"| {name} | {len(metrics)} × {metrics[0]['n']} 条 | {fmt_pct([m['exec'] for m in metrics])} | "
            f"{fmt_pct([m['answer'] for m in metrics])} | {avg('errors'):.1f} | {avg('calls'):.2f} | "
            f"{avg('prompt'):.0f} / {avg('completion'):.0f} | {avg('p50'):.1f}s / {avg('p95'):.1f}s |"
        )
    print("\n".join(rows))


if __name__ == "__main__":
    main()
