"""Aggregate raw eval runs, blind judge scores, objective gates, UX-line checks, and recommendations."""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import statistics

from config import EVAL_GROUPS, MODEL_CATALOG, compute_call_cost_usd


def percentile(vals: list[float], p: float) -> float:
    if not vals:
        return 0.0
    s = sorted(vals)
    k = (len(s) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return float(s[int(k)])
    return float(s[f] * (c - k) + s[c] * (k - f))


def load_jsonl(paths: list[Path]) -> list[dict]:
    rows: list[dict] = []
    for p in paths:
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def load_judges(judges_dir: Path) -> dict[str, dict[tuple[str, str], float]]:
    """Return {judge_name: {(sample_id, option_id): score}}."""
    out: dict[str, dict[tuple[str, str], float]] = {}
    if not judges_dir.exists():
        return out
    for p in sorted(judges_dir.glob("judge-*.json")):
        jname = p.stem.replace("judge-", "")
        data = json.loads(p.read_text(encoding="utf-8"))
        scores: dict[tuple[str, str], float] = {}
        for item in data.get("scores", []):
            sid = item["sample_id"]
            for opt_id, val in item.get("ratings", {}).items():
                if isinstance(val, dict):
                    val = val.get("overall") or val.get("score")
                if isinstance(val, (int, float)):
                    scores[(sid, str(opt_id))] = float(val)
        out[jname] = scores
    return out


def _effective_quality(row: dict) -> float:
    """Combined quality proxy (0..5 scale): blind mean when present + objective gate/reliability."""
    blind = row.get("judge_overall_mean")
    gate_score = float(row.get("gate_score_mean") or 0.0) * 5.0
    fail_penalty = float(row.get("fail_rate") or 0.0) * 2.0
    if blind is not None:
        return float(blind) * 0.7 + gate_score * 0.3 - fail_penalty
    return gate_score - fail_penalty


def _latency_metric_ms(gid: str, row: dict) -> float:
    gspec = EVAL_GROUPS.get(gid)
    if gspec and gspec.streaming:
        return float(row.get("ttft_p50_ms") or row.get("elapsed_p50_ms") or 1e9)
    return float(row.get("elapsed_p50_ms") or 1e9)


def recommend_group(group_id: str, model_rows: dict[str, dict], *, tie_margin: float = 0.25) -> dict:
    """Rank candidates by Quality > Speed (with UX reference line) > Cost, and pick cross-vendor fallback."""
    if not model_rows:
        return {}
    gspec = EVAL_GROUPS.get(group_id)
    ux_ttft = gspec.ux_ttft_ref_ms if gspec else None
    ux_total = gspec.ux_total_ref_ms if gspec else None
    is_interactive = bool(gspec and gspec.role_type == "interactive")

    ranked_all = sorted(
        model_rows.items(),
        key=lambda kv: (-_effective_quality(kv[1]), _latency_metric_ms(group_id, kv[1]), float(kv[1].get("avg_cost_usd") or 0.0)),
    )

    # For interactive groups, check candidates within UX line first, while flagging stronger slower models
    in_line = [(m, r) for m, r in ranked_all if r.get("within_ux_line", True)]
    pool = in_line if (is_interactive and in_line) else ranked_all

    # Pick primary inside pool with tie-breaking by speed then cost within tie_margin
    best_q = _effective_quality(pool[0][1])
    close_top = [(m, r) for m, r in pool if best_q - _effective_quality(r) <= tie_margin]
    if len(close_top) == 1:
        primary_m, primary_r = close_top[0]
        deciding_factor = "quality"
    else:
        by_speed = sorted(close_top, key=lambda kv: _latency_metric_ms(group_id, kv[1]))
        fast_ms = _latency_metric_ms(group_id, by_speed[0][1])
        speed_tied = [(m, r) for m, r in by_speed if _latency_metric_ms(group_id, r) - fast_ms <= 300.0]
        if len(speed_tied) == 1:
            primary_m, primary_r = speed_tied[0]
            deciding_factor = "speed"
        else:
            by_cost = sorted(speed_tied, key=lambda kv: float(kv[1].get("avg_cost_usd") or 0.0))
            primary_m, primary_r = by_cost[0]
            deciding_factor = "cost"

    primary_provider = MODEL_CATALOG[primary_m].provider if primary_m in MODEL_CATALOG else primary_r.get("provider", "unknown")

    # Runner-up (any other model)
    others = [(m, r) for m, r in pool if m != primary_m]
    runner_up_m = others[0][0] if others else None
    runner_up_r = others[0][1] if others else None

    # Mandatory cross-vendor fallback
    cross_vendor = [(m, r) for m, r in ranked_all if (MODEL_CATALOG[m].provider if m in MODEL_CATALOG else r.get("provider")) != primary_provider]
    fallback_m = cross_vendor[0][0] if cross_vendor else None

    over_line_stronger = []
    if is_interactive:
        p_q = _effective_quality(primary_r)
        for m, r in ranked_all:
            if not r.get("within_ux_line", True) and _effective_quality(r) > p_q + 0.05:
                over_line_stronger.append({
                    "model": m,
                    "quality": round(_effective_quality(r), 3),
                    "ttft_p50_ms": r.get("ttft_p50_ms"),
                    "elapsed_p50_ms": r.get("elapsed_p50_ms"),
                })

    return {
        "role_type": gspec.role_type if gspec else "one_time",
        "ux_ttft_ref_ms": ux_ttft,
        "ux_total_ref_ms": ux_total,
        "primary": primary_m,
        "primary_provider": primary_provider,
        "deciding_factor": deciding_factor,
        "runner_up": runner_up_m,
        "quality_delta_vs_runner_up": round(_effective_quality(primary_r) - _effective_quality(runner_up_r), 3) if runner_up_r else None,
        "speed_delta_ms_vs_runner_up": round(_latency_metric_ms(group_id, primary_r) - _latency_metric_ms(group_id, runner_up_r), 1) if runner_up_r else None,
        "over_ux_line_stronger": over_line_stronger,
        "fallback": fallback_m,
        "fallback_provider": MODEL_CATALOG[fallback_m].provider if fallback_m and fallback_m in MODEL_CATALOG else None,
    }


def aggregate(
    raw_files: list[Path],
    blind_key_path: Path,
    judges_dir: Path,
) -> dict:
    records = load_jsonl(raw_files)
    blind_key = json.loads(blind_key_path.read_text(encoding="utf-8")) if blind_key_path.exists() else {}
    judges = load_judges(judges_dir)

    judge_by_sm: dict[tuple[str, str], dict[str, float]] = defaultdict(dict)
    for jname, jmap in judges.items():
        for (sid, opt_id), score in jmap.items():
            model_name = blind_key.get(sid, {}).get(opt_id)
            if model_name:
                judge_by_sm[(sid, model_name)][jname] = score

    grouped: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    for r in records:
        grouped[r["group"]][r["model"]].append(r)

    summary_groups: dict[str, dict] = {}
    recommendations: dict[str, dict] = {}
    disagreements: list[dict] = []

    for gid, by_model in sorted(grouped.items()):
        gspec = EVAL_GROUPS.get(gid)
        model_rows = {}
        group_spreads: list[float] = []
        for model, items in sorted(by_model.items()):
            ok_items = [x for x in items if x.get("status") == "ok"]
            ttfts = [float(x["ttft_ms"]) for x in ok_items if x.get("ttft_ms") is not None]
            elaps = [float(x["elapsed_ms"]) for x in ok_items if x.get("elapsed_ms") is not None]
            costs = [
                compute_call_cost_usd(
                    model,
                    int(x.get("input_tokens") or 0),
                    int(x.get("output_tokens") or 0),
                    int(x.get("cached_tokens") or 0),
                )
                for x in ok_items
            ]
            gate_passes = [1.0 if (x.get("gate") or {}).get("passed") else 0.0 for x in items]
            gate_scores = [float((x.get("gate") or {}).get("score") or 0.0) for x in items]
            retries_429 = sum(int(x.get("retries_429") or 0) for x in items)

            per_judge: dict[str, list[float]] = defaultdict(list)
            all_j_scores: list[float] = []
            for x in items:
                jdict = judge_by_sm.get((x["sample_id"], model), {})
                for jn, sc in jdict.items():
                    per_judge[jn].append(sc)
                    all_j_scores.append(sc)
                if len(jdict) >= 2:
                    spread = max(jdict.values()) - min(jdict.values())
                    group_spreads.append(spread)
                    if spread >= 1.5:
                        disagreements.append({
                            "group": gid,
                            "sample_id": x["sample_id"],
                            "model": model,
                            "judge_scores": jdict,
                            "spread": round(spread, 2),
                        })

            ttft_p50 = round(percentile(ttfts, 50), 1)
            elap_p50 = round(percentile(elaps, 50), 1)
            within_ux = True
            if gspec and gspec.role_type == "interactive":
                if gspec.ux_ttft_ref_ms is not None and ttft_p50 > gspec.ux_ttft_ref_ms:
                    within_ux = False
                if gspec.ux_total_ref_ms is not None and elap_p50 > gspec.ux_total_ref_ms:
                    within_ux = False

            model_rows[model] = {
                "provider": MODEL_CATALOG[model].provider if model in MODEL_CATALOG else "unknown",
                "n": len(items),
                "ok_n": len(ok_items),
                "fail_rate": round(1.0 - len(ok_items) / max(1, len(items)), 4),
                "retries_429": retries_429,
                "gate_pass_rate": round(sum(gate_passes) / max(1, len(items)), 4),
                "gate_score_mean": round(statistics.mean(gate_scores), 4) if gate_scores else 0.0,
                "ttft_p50_ms": ttft_p50,
                "ttft_p95_ms": round(percentile(ttfts, 95), 1),
                "elapsed_p50_ms": elap_p50,
                "elapsed_p95_ms": round(percentile(elaps, 95), 1),
                "within_ux_line": within_ux,
                "avg_cost_usd": round(statistics.mean(costs), 6) if costs else 0.0,
                "total_cost_usd": round(sum(costs), 6),
                "judge_means": {jn: round(statistics.mean(vals), 3) for jn, vals in sorted(per_judge.items())},
                "judge_overall_mean": round(statistics.mean(all_j_scores), 3) if all_j_scores else None,
            }
        summary_groups[gid] = model_rows
        tie_m = max(0.25, statistics.mean(group_spreads)) if group_spreads else 0.25
        recommendations[gid] = recommend_group(gid, model_rows, tie_margin=tie_m)

    return {
        "groups": summary_groups,
        "recommendations": recommendations,
        "disagreements": sorted(disagreements, key=lambda d: -d["spread"]),
        "judges_loaded": sorted(judges.keys()),
        "total_records": len(records),
    }


def estimate_daily_cost(summary: dict, routing_choice: dict[str, str]) -> dict:
    """Estimate active user 1 hour/day cost for a routing assignment vs current baseline."""
    current_daily = 0.0
    chosen_daily = 0.0
    breakdown: dict[str, dict] = {}
    for gid, gspec in EVAL_GROUPS.items():
        rows = summary["groups"].get(gid, {})
        cur_m = gspec.current_model
        new_m = routing_choice.get(gid, cur_m)
        cur_call = rows.get(cur_m, {}).get("avg_cost_usd", 0.0)
        new_call = rows.get(new_m, {}).get("avg_cost_usd", 0.0)
        cur_d = cur_call * gspec.daily_calls_per_active_user
        new_d = new_call * gspec.daily_calls_per_active_user
        current_daily += cur_d
        chosen_daily += new_d
        breakdown[gid] = {
            "daily_calls": gspec.daily_calls_per_active_user,
            "current_model": cur_m,
            "current_daily_usd": round(cur_d, 5),
            "chosen_model": new_m,
            "chosen_daily_usd": round(new_d, 5),
            "delta_usd": round(new_d - cur_d, 5),
        }
    return {
        "current_daily_usd": round(current_daily, 5),
        "chosen_daily_usd": round(chosen_daily, 5),
        "daily_delta_usd": round(chosen_daily - current_daily, 5),
        "monthly_30d_current_usd": round(current_daily * 30, 3),
        "monthly_30d_chosen_usd": round(chosen_daily * 30, 3),
        "breakdown": breakdown,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw-dir", required=True)
    ap.add_argument("--blind-key", required=True)
    ap.add_argument("--judges-dir", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    raw_files = sorted(p for p in Path(args.raw_dir).glob("*.jsonl") if p.name != "smoke.jsonl")
    res = aggregate(raw_files, Path(args.blind_key), Path(args.judges_dir))
    Path(args.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Aggregated {res['total_records']} records across {len(res['groups'])} groups; judges={res['judges_loaded']}; disagreements={len(res['disagreements'])}")


if __name__ == "__main__":
    main()
