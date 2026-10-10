"""资料问答（RAG）离线评测。

两部分：
1. 检索评测：对每种检索模式调用 /api/settings/knowledge-documents/search，
   看返回的分块里是否包含标注的依据句，计算 Hit@1/3/5 和 MRR@10。
2. 端到端评测：调用 /api/ai/chat/knowledge/trace，比较"整篇拼接"和"检索"两种上下文方式下，
   回答是否包含标准答案的关键词；知识库中没有答案的问题，要求回答明确说没找到。

语料规模：small = eval/knowledge（10 篇），large = 再加 eval/knowledge_distractors（20 篇无关制度）。
每次运行先清空评测账号的资料，再按固定随机顺序上传，保证可复现。

用法：
    eval/.venv/bin/python -I eval/run_rag_eval.py --corpus small --label rag-small
"""
from __future__ import annotations

import argparse
import json
import random
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import load_env  # noqa: E402
from run_sql_eval import login  # noqa: E402

EVAL_DIR = Path(__file__).resolve().parent
KB_API = "/api/settings/knowledge-documents"
RETRIEVAL_MODES = ["keyword", "vector", "hybrid", "vector_rerank", "hybrid_rerank"]
UPLOAD_SEED = 20261010


def norm(text: str) -> str:
    return "".join((text or "").split()).replace("～", "~").replace("—", "-").replace("–", "-")


def corpus_files(corpus: str) -> list[Path]:
    files = sorted((EVAL_DIR / "knowledge").glob("*.md"))
    if corpus == "large":
        files += sorted((EVAL_DIR / "knowledge_distractors").glob("*.md"))
    random.Random(UPLOAD_SEED).shuffle(files)
    return files


def sync_corpus(base: str, headers: dict, files: list[Path]) -> dict:
    for doc in requests.get(base + KB_API, headers=headers, timeout=30).json():
        requests.delete(f"{base}{KB_API}/{doc['id']}", headers=headers, timeout=30).raise_for_status()
    for path in files:
        # 逐个上传并间隔 1 秒，保证 updatedAt 顺序稳定（整篇拼接按更新时间倒序取文档）
        with path.open("rb") as fh:
            r = requests.post(base + KB_API, headers=headers, files={"file": (path.name, fh, "text/markdown")}, timeout=60)
        r.raise_for_status()
        time.sleep(1)
    return requests.post(f"{base}{KB_API}/reindex", headers=headers, timeout=600).json()


def evidence_rank(chunks: list[dict], evidence: list[str]) -> int | None:
    targets = [norm(e) for e in evidence]
    for rank, chunk in enumerate(chunks, 1):
        content = norm(chunk.get("content", ""))
        if any(t in content for t in targets):
            return rank
    return None


def keywords_ok(answer: str, groups: list[list[str]]) -> bool:
    text = norm(answer)
    return all(any(norm(alt) in text for alt in group) for group in groups)


def run_retrieval(base: str, headers: dict, cases: list[dict]) -> dict:
    answerable = [c for c in cases if c["expect"] == "answer"]
    results = {}
    for mode in RETRIEVAL_MODES:
        def one(case):
            r = requests.get(base + KB_API + "/search", params={"q": case["question"], "mode": mode, "k": 10},
                             headers=headers, timeout=120)
            r.raise_for_status()
            return case["id"], evidence_rank(r.json().get("chunks", []), case["evidence"])
        started = time.time()
        with ThreadPoolExecutor(max_workers=4) as pool:
            ranks = dict(pool.map(one, answerable))
        n = len(answerable)
        results[mode] = {
            "hit1": sum(1 for r in ranks.values() if r and r <= 1) / n,
            "hit3": sum(1 for r in ranks.values() if r and r <= 3) / n,
            "hit5": sum(1 for r in ranks.values() if r and r <= 5) / n,
            "mrr": sum(1 / r for r in ranks.values() if r) / n,
            "avgSeconds": (time.time() - started) / n * 4,
            "misses": sorted(cid for cid, r in ranks.items() if not r or r > 5),
            "ranks": ranks,
        }
        print(f"retrieval {mode}: hit@5={results[mode]['hit5']:.3f} mrr={results[mode]['mrr']:.3f}", flush=True)
    return results


def run_answers(base: str, headers: dict, cases: list[dict], knowledge_mode: str, concurrency: int) -> list[dict]:
    def one(case):
        r = requests.post(base + "/api/ai/chat/knowledge/trace", headers=headers,
                          json={"query": case["question"], "knowledgeMode": knowledge_mode}, timeout=300)
        trace = r.json() if r.status_code == 200 else {"error": f"HTTP {r.status_code}: {r.text[:200]}"}
        ok = not trace.get("error") and keywords_ok(trace.get("answer", ""), case["keywords"])
        return {"id": case["id"], "expect": case["expect"], "question": case["question"], "ok": ok, "trace": trace}
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(one, cases))
    print(f"answers {knowledge_mode}: {sum(r['ok'] for r in results)}/{len(results)}", flush=True)
    return results


def summarize_answers(results: list[dict]) -> dict:
    answerable = [r for r in results if r["expect"] == "answer"]
    unanswerable = [r for r in results if r["expect"] == "no_answer"]
    traces = [r["trace"] for r in results]
    latencies = sorted(t.get("latencyMs", 0) / 1000 for t in traces)
    return {
        "accuracy": sum(r["ok"] for r in answerable) / max(1, len(answerable)),
        "refusal": sum(r["ok"] for r in unanswerable) / max(1, len(unanswerable)),
        "contextChars": statistics.mean(t.get("contextChars", 0) for t in traces),
        "promptTokens": statistics.mean(t.get("promptTokens", 0) for t in traces),
        "p50": latencies[len(latencies) // 2],
        "failed": [r["id"] for r in results if not r["ok"]],
    }


def write_report(label: str, meta: dict, retrieval: dict, answers: dict[str, dict]) -> Path:
    lines = [f"# 资料问答（RAG）评测报告：{label}", "",
             f"- 时间：{meta['startedAt']}",
             f"- 语料：{meta['corpus']}（{meta['documents']} 篇，{meta['corpusChars']} 字），检索分块 {meta['chunks']} 个",
             f"- 用例：{meta['answerable']} 条可回答 + {meta['unanswerable']} 条知识库中没有答案",
             f"- 向量模型 / 重排模型：BAAI/bge-m3 / BAAI/bge-reranker-v2-m3；问答模型：{meta['model']}",
             "", "## 检索", "", "| 模式 | Hit@1 | Hit@3 | Hit@5 | MRR@10 | 未命中（前 5） |", "|---|---|---|---|---|---|"]
    for mode, r in retrieval.items():
        lines.append(f"| {mode} | {r['hit1']:.1%} | {r['hit3']:.1%} | {r['hit5']:.1%} | {r['mrr']:.3f} | {', '.join(r['misses']) or '-'} |")
    lines += ["", "## 端到端回答", "", "| 上下文方式 | 回答准确率 | 无答案时正确拒答 | 平均上下文字数 | 平均输入 token | 耗时 P50 | 未通过 |",
              "|---|---|---|---|---|---|---|"]
    for mode, s in answers.items():
        lines.append(f"| {mode} | {s['accuracy']:.1%} | {s['refusal']:.0%} | {s['contextChars']:.0f} | {s['promptTokens']:.0f} | "
                     f"{s['p50']:.1f}s | {', '.join(s['failed']) or '-'} |")
    path = EVAL_DIR / "reports" / f"{meta['startedAt'][:10]}-{label}.md"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--corpus", choices=["small", "large"], default="small")
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--answer-modes", default="stuff,hybrid_rerank")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--skip-sync", action="store_true")
    args = parser.parse_args()

    env = load_env()
    headers = {"Authorization": f"Bearer {login(args.base_url, env)}"}
    cases = [json.loads(l) for l in (EVAL_DIR / "rag_cases.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    files = corpus_files(args.corpus)
    sync = {} if args.skip_sync else sync_corpus(args.base_url, headers, files)
    print("corpus synced:", sync, flush=True)

    meta = {"label": args.label, "startedAt": datetime.now().isoformat(timespec="seconds"), "corpus": args.corpus,
            "documents": len(files), "corpusChars": sum(len(f.read_text(encoding="utf-8")) for f in files),
            "chunks": sync.get("totalChunks", "?"), "model": env.get("LLM_MODEL", ""),
            "uploadOrder": [f.name for f in files],
            "answerable": sum(c["expect"] == "answer" for c in cases), "unanswerable": sum(c["expect"] == "no_answer" for c in cases)}

    retrieval = run_retrieval(args.base_url, headers, cases)
    answer_runs = {m: run_answers(args.base_url, headers, cases, m, args.concurrency) for m in args.answer_modes.split(",")}
    answers = {m: summarize_answers(r) for m, r in answer_runs.items()}
    path = write_report(args.label, meta, retrieval, answers)
    raw = EVAL_DIR / "reports" / "raw" / f"{meta['startedAt'][:10]}-{args.label}.json"
    raw.write_text(json.dumps({"meta": meta, "retrieval": retrieval, "answers": answer_runs}, ensure_ascii=False, indent=1), encoding="utf-8")
    print("报告：", path)


if __name__ == "__main__":
    main()
