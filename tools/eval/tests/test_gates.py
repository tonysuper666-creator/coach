"""Offline unit tests for eval gates, SymPy verification, pricing, and blind anonymization."""
from __future__ import annotations

import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from aggregate import recommend_group  # noqa: E402
from config import compute_call_cost_usd  # noqa: E402
from gates import (  # noqa: E402
    check_g1,
    check_g2,
    check_g3,
    check_g4,
    check_g5,
    check_g6,
    check_g8a,
    check_g8b,
    check_g9,
    verify_sympy_check,
)
from run_eval import export_blind_packets, scan_blind_leaks  # noqa: E402


def test_g1_gate_accepts_clean_and_rejects_quotes():
    sample = {"subtask": "explain", "pages": [{"id": "S1", "pdf_page": 10, "text": "Demand rises."}]}
    ok_out = '{"paragraphs":[{"text":"这里说明需求上升的原理。","anchors":["S1"]}]}'
    bad_quotes = '{"paragraphs":[{"text":"这里说明“需求”上升。","anchors":["S1"]}]}'
    bad_page = '{"paragraphs":[{"text":"见第10页需求上升。","anchors":["S1"]}]}'
    assert check_g1(sample, ok_out)["passed"] is True
    assert check_g1(sample, bad_quotes)["passed"] is False
    assert check_g1(sample, bad_page)["passed"] is False


def test_g2_gate_checks_citations_and_conflict():
    sample = {
        "blocks": [
            {"blockId": "B1", "page": 12, "text": "The threshold is thirty dollars per visit."},
            {"blockId": "B2", "page": 14, "text": "In the tutorial key, the threshold rises to forty."},
        ],
        "gold_pages": [12, 14],
        "expected_conflict": True,
    }
    good = json.dumps({
        "sentences": [
            {"claim": "正文设定阈值为三十。", "blockId": "B1", "quoteAnchor": "threshold is thirty dollars"},
            {"claim": "习题课答案改为四十。", "blockId": "B2", "quoteAnchor": "threshold rises to forty"},
        ],
        "hasMaterialConflict": True,
        "conflictSummaryZh": "正文与习题答案阈值不同。",
        "matchedDiscrepancyIds": [],
    })
    assert check_g2(sample, good)["passed"] is True
    bad = good.replace("threshold rises to forty", "completely invented words")
    assert check_g2(sample, bad)["metrics"]["cite_rate"] == 0.5


def test_g3_placeholder_and_sympy_gate():
    sample = {"pages": [{"pdfPage": 34, "text": "Suppose sellers outnumber buyers by 150% so the multiplier is 5/2."}]}
    good = json.dumps({
        "coreQuestion": "核心问题是什么？",
        "keyPoints": [{"summary": "比例为 2.5 倍。", "pdfPage": 34, "quoteAnchor": "sellers outnumber buyers by 150%"}],
        "derivations": [{"title": "收益", "mathChecks": [{"kind": "identity", "lhs": "(5/2)*(P/2) - P", "rhs": "P/4"}]}],
    }, ensure_ascii=False)
    bad = good.replace("比例为 2.5 倍。", "[依原文公式]")
    assert check_g3(sample, good)["passed"] is True
    assert check_g3(sample, bad)["passed"] is False


def test_g4_structure_gate_rejects_prose_and_wrong_start():
    sample = {"gold_body_start_page": 9, "gold_chapter_count": 3, "gold_sample_titles": ["Chapter 1", "Chapter 2"]}
    good = json.dumps({
        "body_start_page": 9,
        "chapters": [
            {"index": 1, "title": "Chapter 1 Intro", "start_page": 9, "end_page": 20},
            {"index": 2, "title": "Chapter 2 Demand", "start_page": 21, "end_page": 35},
            {"index": 3, "title": "Chapter 3 Supply", "start_page": 36, "end_page": 50},
        ],
    })
    bad = json.dumps({
        "body_start_page": 1,
        "chapters": [{"index": 1, "title": "A" * 90, "start_page": 1, "end_page": 50}],
    })
    assert check_g4(sample, good)["passed"] is True
    assert check_g4(sample, bad)["passed"] is False


def test_g5_mcq_verbatim_gate():
    sample = {"subtask": "gen_mcq", "excerpt": "Adverse selection arises when buyers know more about their risk than insurers do."}
    good = json.dumps([{
        "stem": "What causes adverse selection in insurance markets?",
        "correct_text": "Buyers have private information about their risk.",
        "distractors": ["Insurers set zero premiums.", "Hospitals refuse treatment.", "Governments ban policies."],
        "explanation": "Private risk information drives adverse selection.",
        "source_quote": "buyers know more about their risk than insurers do",
    }])
    assert check_g5(sample, good)["passed"] is True


def test_g6_anti_ghostwrite_and_quote_gate():
    sample = {
        "subtask": "practice_grade",
        "rubric_criteria": [{"cid": "R1", "maxMarks": 5.0}],
        "student_answer": "Moral hazard increases utilization because marginal out of pocket cost falls.",
        "gold_awarded_total": 5.0,
    }
    good = json.dumps({
        "items": [{
            "cid": "R1",
            "verdict": "FULL",
            "awardedMarks": 5.0,
            "studentQuote": "marginal out of pocket cost falls",
            "feedbackNoteZh": "机制准确。",
        }]
    })
    ghost = json.dumps({
        "items": [{
            "cid": "R1",
            "verdict": "FULL",
            "awardedMarks": 5.0,
            "studentQuote": "marginal out of pocket cost falls",
            "feedbackNoteZh": "机制准确。",
        }],
        "rewrite": "Full rewritten model answer...",
    })
    assert check_g6(sample, good)["passed"] is True
    assert check_g6(sample, ghost)["passed"] is False


def test_g8_and_g9_sympy_gates():
    assert verify_sympy_check({"kind": "solve", "lhs": "(3/2)*(P+10)/2 - P", "rhs": "0", "variable": "P", "expected": "30"})[0] is True
    assert verify_sympy_check({"kind": "inequality", "inequality": "(3/2)*(P+10)/2 - P >= 0", "variable": "P", "expectedRelation": "<=", "expectedThreshold": "30"})[0] is True
    g8a_sample = {"subtask": "booklist_concept_align", "payload": {}, "gold_keep": {"p1": False, "p2": True}}
    g8a_out = json.dumps({
        "decisions": [
            {"pairId": "p1", "keep": False, "rejectReason": "boilerplate"},
            {"pairId": "p2", "keep": True, "relationType": "CONSISTENT_DEEPENING", "diffSummary": "两书深化同一概念。"},
        ]
    })
    assert check_g8a(g8a_sample, g8a_out)["passed"] is True
    g8b_sample = {"gold_terms": ["alpha", "beta"], "gold_trad_chars": ["國", "醫"]}
    assert check_g8b(g8b_sample, "This page discusses alpha and beta in 國醫研究体系中。")["passed"] is True
    assert check_g8b(g8b_sample, "This page discusses alpha and beta in 国医研究体系中。")["passed"] is False
    g9_sample = {"subtask": "BOOK_MATH_VERIFY", "gold_source_matches": {"item1": False}}
    g9_out = json.dumps({
        "itemAudits": [{
            "itemId": "item1",
            "sourceMatches": False,
            "sourceMismatchReason": "Should be 5/2 not 3/2",
            "sympyChecks": [{"kind": "ratio", "lhs": "5/2", "rhs": "5/2"}],
        }]
    })
    assert check_g9(g9_sample, g9_out)["passed"] is True


def test_pricing_and_blind_anonymization(tmp_path: Path):
    usd = compute_call_cost_usd("claude-haiku-5-5", 1_000_000, 1_000_000, cached_tokens=0)
    assert abs(usd - 0.60) < 1e-9

    raw_dir = tmp_path / "raw"
    blind_dir = tmp_path / "blind"
    priv_dir = tmp_path / "private"
    raw_dir.mkdir()
    rows = [
        {"group": "G1", "split": "core", "sample_id": "G1-01", "model": "claude-haiku-5-5", "status": "ok", "text": "Answer one", "prompt_preview": "Explain X"},
        {"group": "G1", "split": "core", "sample_id": "G1-01", "model": "gpt-6.1-terra", "status": "ok", "text": "Answer two", "prompt_preview": "Explain X"},
    ]
    (raw_dir / "G1_core.jsonl").write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    samples_file = tmp_path / "samples.json"
    samples_file.write_text(json.dumps({"samples": [{"group": "G1", "split": "core", "sample_id": "G1-01", "title": "Sample 1", "judge_context": "ctx"}]}))

    export_blind_packets([raw_dir / "G1_core.jsonl"], samples_file, blind_dir, priv_dir / "blind_key.json", seed=42)
    leaks = scan_blind_leaks(blind_dir)
    assert leaks == [], f"Unexpected leaks: {leaks}"
    key = json.loads((priv_dir / "blind_key.json").read_text())
    assert set(key["G1-01"].values()) == {"claude-haiku-5-5", "gpt-6.1-terra"}


def test_recommend_group_quality_speed_cost_and_cross_vendor_fallback():
    rows = {
        "claude-sonnet-5-5": {"provider": "anthropic", "judge_overall_mean": 4.4, "gate_score_mean": 1.0, "fail_rate": 0.0, "ttft_p50_ms": 1500.0, "elapsed_p50_ms": 3500.0, "within_ux_line": True, "avg_cost_usd": 0.01},
        "claude-haiku-5-5": {"provider": "anthropic", "judge_overall_mean": 4.35, "gate_score_mean": 1.0, "fail_rate": 0.0, "ttft_p50_ms": 700.0, "elapsed_p50_ms": 1500.0, "within_ux_line": True, "avg_cost_usd": 0.001},
        "gpt-6.2-sol": {"provider": "openai", "judge_overall_mean": 4.7, "gate_score_mean": 1.0, "fail_rate": 0.0, "ttft_p50_ms": 3500.0, "elapsed_p50_ms": 6000.0, "within_ux_line": False, "avg_cost_usd": 0.02},
        "gpt-6.1-terra": {"provider": "openai", "judge_overall_mean": 4.1, "gate_score_mean": 1.0, "fail_rate": 0.0, "ttft_p50_ms": 2200.0, "elapsed_p50_ms": 4000.0, "within_ux_line": True, "avg_cost_usd": 0.005},
    }
    rec = recommend_group("G1", rows, tie_margin=0.2)
    # Within UX line, sonnet & haiku tie on quality (within 0.2), so speed picks haiku
    assert rec["primary"] == "claude-haiku-5-5"
    assert rec["deciding_factor"] == "speed"
    # Cross-vendor fallback must be OpenAI, not Anthropic
    assert rec["fallback_provider"] == "openai"
    assert rec["fallback"] in {"gpt-6.2-sol", "gpt-6.1-terra"}
    # Over-UX-line stronger model must be surfaced transparently
    assert [x["model"] for x in rec["over_ux_line_stronger"]] == ["gpt-6.2-sol"]

