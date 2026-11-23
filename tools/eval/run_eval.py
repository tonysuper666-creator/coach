"""CLI runner for 9-group evaluation, live smoke test, and blind packet anonymization."""
from __future__ import annotations

import argparse
import asyncio
from collections import defaultdict
import json
from pathlib import Path
import random
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from config import EVAL_GROUPS, MODEL_CATALOG, compute_call_cost_usd
from gates import run_objective_gate
from models import EvalRunnerClients
from prompts import PROMPT_BUILDERS

MODEL_LEAK_PATTERNS = (
    "claude",
    "haiku",
    "sonnet",
    "opus",
    "gpt-",
    "luna",
    "terra",
    "gpt-6",
    "astra",
    "deepseek",
    "codebuddy",
    "anthropic",
    "openai",
)

BLIND_RUBRIC = """# Blind Judging Rubric (1.0 - 5.0 scale)

Score each anonymized candidate (A, B, C, ...) independently from 1 (unusable) to 5 (excellent) on:
- G1 讲解与选中就问: accuracy to source, plain-language clarity, faithfulness (no guessing).
- G2 整门课与跨书问答: completeness, exact page/block grounding, cross-material conflict awareness.
- G3 章节伴读与六学法: pedagogical depth, exam focus, natural Chinese without placeholders/copying.
- G4 书结构与目录识别: front-matter skipping, chapter granularity matching original book, clean titles.
- G5 出题与答案键: conceptual depth, plausible non-ambiguous distractors, self-consistent key/explanation.
- G6 逐点批改与诊断: fair/accurate scoring, concise misconception diagnosis, zero ghostwriting.
- G7 课程与模考规划: logical week/topic organization, weight alignment, no omitted/invented items.
- G8a 课件对齐与主题图: cross-book insightfulness and filtering of boilerplate/stopwords.
- G8b 视觉看图与扫描OCR: character/math accuracy, formula fidelity, Traditional/Simplified preservation.
- G9 数学核验与修复: catching coefficient/sign errors and producing exact valid repairs.

Output JSON strictly in format:
{"judge": "<vendor>", "scores": [{"sample_id": "...", "ratings": {"A": {"overall": 4.5, "note": "..."}, ...}}]}
"""


def _load_existing_keys(jsonl_path: Path) -> set[tuple[str, str]]:
    done: set[tuple[str, str]] = set()
    if not jsonl_path.exists():
        return done
    for line in jsonl_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("status") == "ok":
            done.add((row["sample_id"], row["model"]))
    return done


def _sample_prompt_preview(sample: dict) -> str:
    keys = ("question", "quote", "kp", "book_title", "chapter_title", "question_stem", "collection_title", "subtask")
    parts = [f"{k}={str(sample[k])[:140]}" for k in keys if sample.get(k)]
    return " | ".join(parts)


async def run_samples(
    samples: list[dict],
    raw_dir: Path,
    *,
    smoke: bool = False,
) -> list[Path]:
    raw_dir.mkdir(parents=True, exist_ok=True)
    clients = EvalRunnerClients(load_keys=True)
    written_files: set[Path] = set()
    jobs: list[tuple[dict, str, Path]] = []

    if smoke:
        # Pick 1 text sample (G1-01) for all non-opus/non-astra + opus/astra, plus 1 vision sample (G8b) for haiku & terra
        g1_sample = next((s for s in samples if s["group"] == "G1"), samples[0])
        g8b_sample = next((s for s in samples if s["group"] == "G8b"), None)
        smoke_path = raw_dir / "smoke.jsonl"
        done = _load_existing_keys(smoke_path)
        for m in MODEL_CATALOG:
            if (g1_sample["sample_id"], m) not in done:
                jobs.append((g1_sample, m, smoke_path))
        if g8b_sample:
            for m in ("claude-haiku-5-5", "gpt-6.1-terra"):
                if (f"{g8b_sample['sample_id']}-vis", m) not in done:
                    vis_copy = dict(g8b_sample, sample_id=f"{g8b_sample['sample_id']}-vis")
                    jobs.append((vis_copy, m, smoke_path))
        written_files.add(smoke_path)
    else:
        for s in samples:
            gid = s["group"]
            split = s.get("split", "core")
            out_path = raw_dir / f"{gid}_{split}.jsonl"
            written_files.add(out_path)
            done = _load_existing_keys(out_path)
            for m in EVAL_GROUPS[gid].candidates:
                if (s["sample_id"], m) not in done:
                    jobs.append((s, m, out_path))

    if not jobs:
        print("All jobs already completed.")
        return sorted(written_files)

    lock = asyncio.Lock()

    async def _run_one(sample: dict, model_name: str, out_path: Path) -> None:
        gid = sample["group"]
        sys_p, user_p = PROMPT_BUILDERS[gid](sample)
        max_tok = int(sample.get("max_tokens") or (4096 if gid in ("G3", "G4", "G7", "G9") else 2048))
        res = await clients.call(
            model_name,
            sys_p,
            user_p,
            image_path=sample.get("image_path"),
            max_tokens=max_tok,
        )
        gate = run_objective_gate(gid, sample, res.text) if res.status == "ok" else {"passed": False, "score": 0.0, "defects": [res.error or "call_failed"], "metrics": {}}
        cost_usd = compute_call_cost_usd(model_name, res.input_tokens, res.output_tokens, res.cached_tokens)
        row = {
            "group": gid,
            "split": sample.get("split", "core"),
            "sample_id": sample["sample_id"],
            "lang": sample.get("lang", "en"),
            "model": model_name,
            "status": res.status,
            "served_by": res.served_by,
            "ttft_ms": res.ttft_ms,
            "elapsed_ms": res.elapsed_ms,
            "input_tokens": res.input_tokens,
            "output_tokens": res.output_tokens,
            "cached_tokens": res.cached_tokens,
            "cost_usd": round(cost_usd, 6),
            "retries_429": res.retries_429,
            "error": res.error,
            "gate": gate,
            "text": res.text,
            "prompt_preview": _sample_prompt_preview(sample),
        }
        async with lock:
            with out_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        print(
            f"[{gid}/{sample['sample_id']}] {model_name:20s} status={res.status} ttft={res.ttft_ms}ms dur={res.elapsed_ms}ms tok={res.input_tokens}/{res.output_tokens} gate={gate['passed']}({gate['score']})",
            flush=True,
        )

    await asyncio.gather(*(_run_one(s, m, p) for s, m, p in jobs))
    return sorted(written_files)


def scan_blind_leaks(blind_dir: Path) -> list[str]:
    leaks: list[str] = []
    for p in sorted(blind_dir.glob("group_*.json")):
        data = json.loads(p.read_text(encoding="utf-8"))
        for item in data.get("items", []):
            for cand in item.get("candidates", []):
                cid = str(cand.get("option_id", "")).lower()
                if any(pat in cid for pat in MODEL_LEAK_PATTERNS):
                    leaks.append(f"{p.name}:{item['sample_id']}:option_id={cid}")
            meta_str = json.dumps(
                {k: v for k, v in item.items() if k not in ("candidates", "input_prompt")},
                ensure_ascii=False,
            ).lower()
            for pat in MODEL_LEAK_PATTERNS:
                if pat in meta_str:
                    leaks.append(f"{p.name}:{item['sample_id']}:meta={pat}")
            ip = str(item.get("input_prompt") or "").lower()
            if re.search(r"\b(?:claude-|gpt-6|deepseek-|codebuddy)", ip):
                leaks.append(f"{p.name}:{item['sample_id']}:input_prompt_model_id")
    return leaks


def export_blind_packets(
    raw_files: list[Path],
    samples_file: Path,
    blind_dir: Path,
    blind_key_path: Path,
    *,
    seed: int = 20261123,
) -> None:
    blind_dir.mkdir(parents=True, exist_ok=True)
    blind_key_path.parent.mkdir(parents=True, exist_ok=True)
    samples_raw = json.loads(samples_file.read_text(encoding="utf-8"))
    sample_list = samples_raw["samples"] if isinstance(samples_raw, dict) else samples_raw
    sample_meta = {s["sample_id"]: s for s in sample_list}

    by_group_sample: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    for rf in raw_files:
        if not rf.exists():
            continue
        for line in rf.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            if r.get("status") != "ok":
                continue
            by_group_sample[r["group"]][r["sample_id"]].append(r)

    rng = random.Random(seed)
    key_map: dict[str, dict[str, str]] = {}
    labels = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    for gid, sdict in sorted(by_group_sample.items()):
        items = []
        for sid, cand_rows in sorted(sdict.items()):
            # Deduplicate by model (keep latest)
            latest_by_model = {r["model"]: r for r in cand_rows}
            models_sorted = sorted(latest_by_model.keys())
            rng.shuffle(models_sorted)
            key_map[sid] = {}
            candidates = []
            for idx, m in enumerate(models_sorted):
                opt_id = labels[idx]
                key_map[sid][opt_id] = m
                candidates.append({
                    "option_id": opt_id,
                    "response": latest_by_model[m]["text"],
                })
            meta = sample_meta.get(sid.removesuffix("-vis"), {})
            input_prompt = ""
            if meta and gid in PROMPT_BUILDERS:
                try:
                    input_prompt = PROMPT_BUILDERS[gid](meta)[1][:4500]
                except Exception:
                    input_prompt = ""
            items.append({
                "sample_id": sid,
                "split": meta.get("split", "core"),
                "lang": meta.get("lang", "en"),
                "title": meta.get("title") or meta.get("prompt_preview") or sid,
                "judge_context": meta.get("judge_context") or _sample_prompt_preview(meta) if meta else "",
                "input_prompt": input_prompt,
                "image_path": meta.get("image_path"),
                "candidates": candidates,
            })
        (blind_dir / f"group_{gid}.json").write_text(
            json.dumps({"group": gid, "items": items}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    (blind_dir / "RUBRIC.md").write_text(BLIND_RUBRIC, encoding="utf-8")
    blind_key_path.write_text(json.dumps(key_map, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", required=True)
    ap.add_argument("--raw-dir", required=True)
    ap.add_argument("--blind-dir", default="")
    ap.add_argument("--blind-key", default="")
    ap.add_argument("--group", default="all", help="Comma-separated groups or 'all'")
    ap.add_argument("--split", default="all", choices=("all", "core", "holdout"))
    ap.add_argument("--smoke", action="store_true")
    ap.add_argument("--export-blind", action="store_true")
    ap.add_argument("--check-leaks", action="store_true")
    args = ap.parse_args()

    data_dir = Path(args.data_dir)
    raw_dir = Path(args.raw_dir)
    samples_path = data_dir / "samples.json"
    all_samples = json.loads(samples_path.read_text(encoding="utf-8"))["samples"]

    if args.group != "all":
        wanted = {g.strip() for g in args.group.split(",") if g.strip()}
        all_samples = [s for s in all_samples if s["group"] in wanted]
    if args.split != "all":
        all_samples = [s for s in all_samples if s.get("split") == args.split]

    if not args.export_blind or args.smoke:
        asyncio.run(run_samples(all_samples, raw_dir, smoke=args.smoke))

    if args.export_blind:
        blind_dir = Path(args.blind_dir)
        blind_key = Path(args.blind_key)
        raw_files = [raw_dir / "smoke.jsonl"] if args.smoke else sorted(p for p in raw_dir.glob("*.jsonl") if p.name != "smoke.jsonl")
        export_blind_packets(raw_files, samples_path, blind_dir, blind_key)
        leaks = scan_blind_leaks(blind_dir)
        if leaks:
            raise SystemExit(f"BLIND LEAK DETECTED: {leaks}")
        print(f"Exported blind packets cleanly to {blind_dir} (0 leaks).")
    elif args.check_leaks:
        leaks = scan_blind_leaks(Path(args.blind_dir))
        if leaks:
            raise SystemExit(f"BLIND LEAK DETECTED: {leaks}")
        print("Blind leak scan passed (0 leaks).")


if __name__ == "__main__":
    main()
