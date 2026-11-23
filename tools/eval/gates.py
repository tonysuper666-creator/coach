"""Deterministic format gates and objective checkers (SymPy, citations, rubric arithmetic, OCR)."""
from __future__ import annotations

import json
import re
import unicodedata
from typing import Any

import sympy as sp
from sympy.parsing.sympy_parser import (
    convert_xor,
    implicit_multiplication_application,
    parse_expr,
    standard_transformations,
)

QUOTES = '"“”「」『』‘’\''
PLACEHOLDERS = ("[依原文公式]", "〔依原文公式〕", "[CORE]", "TODO", "FIXME", "（简述）")
LEAKAGE_RE = re.compile(r"(?i)(?:past\s*paper|dec(?:ember)?\s*202[0-9]\s*(?:exam|paper)|真题详解|押题)")
TRANSFORMS = standard_transformations + (implicit_multiplication_application, convert_xor)


def parse_json_payload(text: str) -> Any | None:
    s = (text or "").strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", s, re.S)
    if fence:
        s = fence.group(1).strip()
    for open_c, close_c in (("{", "}"), ("[", "]")):
        i = s.find(open_c)
        j = s.rfind(close_c)
        if i >= 0 and j > i and (open_c == "{" and (s.find("[") == -1 or i < s.find("[")) or open_c == "[" and (s.find("{") == -1 or i < s.find("{"))):
            try:
                return json.loads(s[i : j + 1])
            except Exception:
                pass
    try:
        return json.loads(s)
    except Exception:
        return None


def norm_quote(text: str) -> str:
    cleaned = re.sub(r"[*_`#>]", "", text or "")
    cleaned = unicodedata.normalize("NFKC", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip().lower()
    return cleaned


def contains_verbatim_quote(quote: str, source: str, min_len: int = 4) -> bool:
    q = norm_quote(quote)
    if len(q) < min_len:
        return False
    return q in norm_quote(source)


def verify_sympy_check(check: dict) -> tuple[bool, str]:
    """Symbolically/numerically verify one math check spec via SymPy."""
    kind = (check.get("kind") or "").lower().strip()
    try:
        if kind == "identity":
            lhs = parse_expr(str(check["lhs"]), transformations=TRANSFORMS)
            rhs = parse_expr(str(check["rhs"]), transformations=TRANSFORMS)
            diff = sp.simplify(lhs - rhs)
            ok = diff == 0
            return ok, "ok" if ok else f"identity_diff={diff}"
        if kind == "ratio":
            lhs = sp.nsimplify(parse_expr(str(check["lhs"]), transformations=TRANSFORMS))
            rhs = sp.nsimplify(parse_expr(str(check["rhs"]), transformations=TRANSFORMS))
            ok = sp.simplify(lhs - rhs) == 0
            return ok, "ok" if ok else f"ratio_mismatch:{lhs}!={rhs}"
        if kind == "eval":
            expr = parse_expr(str(check["expression"]), transformations=TRANSFORMS)
            subs = {sp.Symbol(k): sp.sympify(v) for k, v in (check.get("variables") or {}).items()}
            val = sp.N(expr.subs(subs))
            expected = sp.N(sp.sympify(check["expected"]))
            ok = abs(float(val - expected)) <= 1e-4 * max(1.0, abs(float(expected)))
            return ok, "ok" if ok else f"eval_mismatch:{val}!={expected}"
        if kind == "solve":
            var = sp.Symbol(str(check["variable"]))
            lhs = parse_expr(str(check["lhs"]), transformations=TRANSFORMS)
            rhs = parse_expr(str(check["rhs"]), transformations=TRANSFORMS)
            sols = sp.solve(sp.Eq(lhs, rhs), var)
            expected = sp.N(sp.sympify(check["expected"]))
            ok = any(abs(float(sp.N(s) - expected)) <= 1e-4 for s in sols if getattr(sp.N(s), "is_real", True))
            return ok, "ok" if ok else f"solve_mismatch:{sols}!={expected}"
        if kind == "inequality":
            var = sp.Symbol(str(check["variable"]))
            raw_ineq = str(check["inequality"])
            for op_str, fn in ((">=", sp.Ge), ("<=", sp.Le), (">", sp.Gt), ("<", sp.Lt)):
                if op_str in raw_ineq:
                    a, b = raw_ineq.split(op_str, 1)
                    rel = fn(parse_expr(a, transformations=TRANSFORMS), parse_expr(b, transformations=TRANSFORMS))
                    solved = sp.reduce_inequalities(rel, var)
                    thresh = sp.N(sp.sympify(check["expectedThreshold"]))
                    exp_rel = check.get("expectedRelation", "")
                    # Test threshold boundary
                    lhs_v = parse_expr(a, transformations=TRANSFORMS) - parse_expr(b, transformations=TRANSFORMS)
                    boundary_ok = any(abs(float(sp.N(s) - thresh)) <= 1e-4 for s in sp.solve(lhs_v, var) if getattr(sp.N(s), "is_real", True))
                    # Test side matching expectedRelation
                    eps = 0.5
                    probe = thresh - eps if "<" in exp_rel else thresh + eps
                    side_ok = bool(rel.subs(var, probe))
                    ok = boundary_ok and side_ok
                    return ok, "ok" if ok else f"ineq_mismatch:{solved}"
            return False, "ineq_no_op"
        return False, f"unknown_kind:{kind}"
    except Exception as exc:
        return False, f"sympy_err:{type(exc).__name__}:{exc}"


def check_g1(sample: dict, text: str) -> dict:
    defects: list[str] = []
    if sample.get("subtask") == "TEACH":
        s = (text or "").strip()
        if not s:
            defects.append("empty")
        if re.search(r"\b[A-D]\s*[\.\)]", s):
            defects.append("option_letter_leak")
        return {"passed": not defects, "score": 1.0 if not defects else 0.0, "defects": defects, "metrics": {}}

    obj = parse_json_payload(text)
    if not isinstance(obj, dict) or set(obj.keys()) != {"paragraphs"}:
        return {"passed": False, "score": 0.0, "defects": ["bad_json_keys"], "metrics": {}}
    ps = obj["paragraphs"]
    valid_anchors = {p["id"] for p in sample["pages"]}
    expect_empty = bool(sample.get("expect_empty", False))
    if expect_empty:
        ok = isinstance(ps, list) and len(ps) == 0
        return {"passed": ok, "score": 1.0 if ok else 0.0, "defects": [] if ok else ["expected_empty"], "metrics": {}}
    if not isinstance(ps, list) or not (1 <= len(ps) <= 2):
        defects.append("paragraph_count")
    else:
        for idx, p in enumerate(ps):
            if not isinstance(p, dict) or set(p.keys()) != {"text", "anchors"}:
                defects.append(f"p{idx}_keys")
                continue
            t = p["text"]
            if not isinstance(t, str) or not t.strip() or "\n" in t:
                defects.append(f"p{idx}_text_format")
            if isinstance(t, str) and any(q in t for q in QUOTES):
                defects.append(f"p{idx}_quotes")
            if isinstance(t, str) and re.search(r"第\s*\d+\s*页|\bp\.\s*\d+", t):
                defects.append(f"p{idx}_fabricated_page")
            anc = p["anchors"]
            if not isinstance(anc, list) or not anc or any(a not in valid_anchors for a in anc) or len(set(anc)) != len(anc):
                defects.append(f"p{idx}_bad_anchors")
    return {"passed": not defects, "score": 1.0 if not defects else 0.0, "defects": defects, "metrics": {}}


def check_g2(sample: dict, text: str) -> dict:
    defects: list[str] = []
    obj = parse_json_payload(text)
    if not isinstance(obj, dict) or not isinstance(obj.get("sentences"), list):
        return {"passed": False, "score": 0.0, "defects": ["bad_json"], "metrics": {}}
    by_id = {b["blockId"]: b for b in sample["blocks"]}
    sents = obj["sentences"]
    if not (1 <= len(sents) <= 5):
        defects.append("sentence_count")
    verified = 0
    cited_pages: set[int] = set()
    for i, s in enumerate(sents):
        if not isinstance(s, dict):
            defects.append(f"s{i}_not_obj")
            continue
        bid = str(s.get("blockId") or "").strip()
        qa = str(s.get("quoteAnchor") or "").strip()
        blk = by_id.get(bid)
        if blk is None:
            defects.append(f"s{i}_unknown_block")
        elif not contains_verbatim_quote(qa, blk["text"], min_len=4):
            defects.append(f"s{i}_unverified_quote")
        else:
            verified += 1
            cited_pages.add(int(blk["page"]))
    cite_rate = verified / max(1, len(sents))
    gold_pages = set(sample.get("gold_pages") or [])
    page_hit = bool(cited_pages & gold_pages) if gold_pages else True
    if not page_hit:
        defects.append("missed_gold_page")
    conflict_ok = True
    if "expected_conflict" in sample:
        conflict_ok = bool(obj.get("hasMaterialConflict", False)) == bool(sample["expected_conflict"])
        if not conflict_ok:
            defects.append("conflict_mismatch")
    score = 0.5 * cite_rate + 0.25 * float(page_hit) + 0.25 * float(conflict_ok)
    return {
        "passed": not defects and cite_rate >= 0.75,
        "score": round(score, 4),
        "defects": defects,
        "metrics": {"cite_rate": round(cite_rate, 3), "page_hit": page_hit, "conflict_ok": conflict_ok},
    }


def _collect_anchors(obj: Any, out: list[tuple[int | None, str]]) -> None:
    if isinstance(obj, dict):
        if "quoteAnchor" in obj:
            pg = obj.get("pdfPage") or obj.get("bodyJumpPage") or obj.get("missJumpPdfPage")
            out.append((int(pg) if isinstance(pg, int) else None, str(obj["quoteAnchor"])))
        if "exerciseQuoteAnchor" in obj:
            pg = obj.get("exercisePdfPage")
            out.append((int(pg) if isinstance(pg, int) else None, str(obj["exerciseQuoteAnchor"])))
        for v in obj.values():
            _collect_anchors(v, out)
    elif isinstance(obj, list):
        for v in obj:
            _collect_anchors(v, out)


def check_g3(sample: dict, text: str) -> dict:
    defects: list[str] = []
    obj = parse_json_payload(text)
    if not isinstance(obj, dict):
        if any(ph in (text or "") for ph in PLACEHOLDERS):
            defects.append("placeholder_leak")
        return {"passed": False, "score": 0.0, "defects": defects + ["bad_json"], "metrics": {}}
    decoded_str = json.dumps(obj, ensure_ascii=False)
    if any(ph in (text or "") or ph in decoded_str for ph in PLACEHOLDERS):
        defects.append("placeholder_leak")
    pages = sample.get("pages") or sample.get("blocks") or []
    page_map = {int(p["pdfPage"]): p["text"] for p in pages}
    all_src = "\n".join(page_map.values())
    anchors: list[tuple[int | None, str]] = []
    _collect_anchors(obj, anchors)
    verified = 0
    for pg, qa in anchors:
        target = page_map.get(pg, all_src) if pg is not None else all_src
        if contains_verbatim_quote(qa, target, min_len=4):
            verified += 1
        else:
            defects.append(f"bad_anchor:p{pg}")
    anchor_rate = verified / max(1, len(anchors)) if anchors else 0.0
    if not anchors:
        defects.append("no_anchors")
    # Check sympy mathChecks if present
    math_checks: list[dict] = []
    for k in ("derivations", "optionalPractice"):
        for item in obj.get(k) or []:
            if isinstance(item, dict):
                math_checks.extend(item.get("mathChecks") or [])
    math_ok = 1.0
    if math_checks:
        passes = sum(1 for mc in math_checks if isinstance(mc, dict) and verify_sympy_check(mc)[0])
        math_ok = passes / len(math_checks)
        if math_ok < 1.0:
            defects.append("math_check_fail")
    score = 0.6 * anchor_rate + 0.2 * (0.0 if "placeholder_leak" in defects else 1.0) + 0.2 * math_ok
    return {
        "passed": "placeholder_leak" not in defects and anchor_rate >= 0.7 and math_ok == 1.0,
        "score": round(score, 4),
        "defects": defects,
        "metrics": {"anchor_rate": round(anchor_rate, 3), "anchors_total": len(anchors), "math_ok": math_ok},
    }


def check_g4(sample: dict, text: str) -> dict:
    defects: list[str] = []
    obj = parse_json_payload(text)
    if not isinstance(obj, dict) or not isinstance(obj.get("chapters"), list):
        return {"passed": False, "score": 0.0, "defects": ["bad_json"], "metrics": {}}
    pred_start = obj.get("body_start_page")
    gold_start = int(sample["gold_body_start_page"])
    start_err = abs(int(pred_start) - gold_start) if isinstance(pred_start, int) else 999
    if start_err > 1:
        defects.append(f"body_start_err:{pred_start}vs{gold_start}")

    chapters = obj["chapters"]
    gold_count = int(sample["gold_chapter_count"])
    if not chapters:
        defects.append("empty_chapters")
        return {"passed": False, "score": 0.0, "defects": defects, "metrics": {"start_err": start_err}}

    monotonic = True
    prev = 0
    long_titles = 0
    for c in chapters:
        if not isinstance(c, dict):
            monotonic = False
            continue
        title = str(c.get("title") or "")
        if len(title) > 80:
            long_titles += 1
        spg = c.get("start_page")
        epg = c.get("end_page")
        if not isinstance(spg, int) or not isinstance(epg, int) or spg < prev or epg < spg:
            monotonic = False
        else:
            prev = spg
    if not monotonic:
        defects.append("non_monotonic_pages")
    if long_titles > 0:
        defects.append(f"prose_title_leak:{long_titles}")

    count_score = max(0.0, 1.0 - abs(len(chapters) - gold_count) / max(1, gold_count))
    start_score = 1.0 if start_err == 0 else (0.7 if start_err == 1 else 0.0)
    title_norms = [norm_quote(str(c.get("title") or "")) for c in chapters if isinstance(c, dict)]
    gold_titles = [norm_quote(t) for t in sample.get("gold_sample_titles") or []]
    matched_titles = sum(1 for gt in gold_titles if any(gt in tn or tn in gt for tn in title_norms if tn))
    title_recall = matched_titles / max(1, len(gold_titles)) if gold_titles else 1.0
    score = 0.35 * start_score + 0.30 * count_score + 0.25 * title_recall + 0.10 * float(monotonic and long_titles == 0)
    return {
        "passed": start_err <= 1 and monotonic and long_titles == 0 and count_score >= 0.75,
        "score": round(score, 4),
        "defects": defects,
        "metrics": {"start_err": start_err, "pred_chapters": len(chapters), "gold_chapters": gold_count, "title_recall": round(title_recall, 3)},
    }


def check_g5(sample: dict, text: str) -> dict:
    defects: list[str] = []
    if LEAKAGE_RE.search(text or ""):
        defects.append("leakage_phrase")
    parsed = parse_json_payload(text)
    if sample.get("subtask") == "course_mock_item":
        if not isinstance(parsed, dict):
            return {"passed": False, "score": 0.0, "defects": ["bad_json"], "metrics": {}}
        pages = {int(p["pdfPage"]): p["text"] for p in sample["pages"]}
        all_src = "\n".join(pages.values())
        crit = parsed.get("rubricCriteria") or []
        sum_ok = abs(sum(float(c.get("maxMarks", 0)) for c in crit if isinstance(c, dict)) - float(parsed.get("marks", 0))) < 1e-4
        if not sum_ok:
            defects.append("rubric_sum_mismatch")
        anchors_ok = 0
        for c in crit:
            if isinstance(c, dict) and contains_verbatim_quote(str(c.get("quoteAnchor") or ""), pages.get(c.get("pdfPage"), all_src), 4):
                anchors_ok += 1
        quote_rate = anchors_ok / max(1, len(crit))
        if quote_rate < 0.8:
            defects.append("quote_anchor_miss")
        calc_ok = 1.0
        calcs = [sq for sq in (parsed.get("subQuestions") or []) if isinstance(sq, dict) and isinstance(sq.get("mathSpec"), dict)]
        if calcs:
            good = 0
            for sq in calcs:
                ms = sq["mathSpec"]
                ok, _ = verify_sympy_check({"kind": "eval", "expression": ms.get("expression"), "variables": {}, "expected": ms.get("expected")})
                if ok:
                    good += 1
            calc_ok = good / len(calcs)
            if calc_ok < 1.0:
                defects.append("math_spec_mismatch")
        score = 0.4 * float(sum_ok) + 0.3 * quote_rate + 0.3 * calc_ok
        return {"passed": not defects, "score": round(score, 4), "defects": defects, "metrics": {"quote_rate": quote_rate, "calc_ok": calc_ok}}

    if not isinstance(parsed, list) or not parsed:
        return {"passed": False, "score": 0.0, "defects": ["bad_json_array"], "metrics": {}}
    src = sample["excerpt"]
    ok_items = 0
    for idx, q in enumerate(parsed):
        if not isinstance(q, dict):
            defects.append(f"q{idx}_not_obj")
            continue
        stem = str(q.get("stem") or "").strip()
        corr = str(q.get("correct_text") or "").strip()
        dists = q.get("distractors") or []
        sq = str(q.get("source_quote") or "")
        opts = [corr] + [str(d).strip() for d in dists]
        item_bad = False
        if len(stem) < 15:
            defects.append(f"q{idx}_short_stem")
            item_bad = True
        if len(dists) != 3 or len({norm_quote(o) for o in opts if o}) != 4:
            defects.append(f"q{idx}_bad_options")
            item_bad = True
        if not contains_verbatim_quote(sq, src, min_len=8):
            defects.append(f"q{idx}_bad_source_quote")
            item_bad = True
        if not item_bad:
            ok_items += 1
    rate = ok_items / len(parsed)
    return {"passed": rate == 1.0 and not defects, "score": round(rate, 4), "defects": defects, "metrics": {"valid_items": ok_items, "total_items": len(parsed)}}


def check_g6(sample: dict, text: str) -> dict:
    defects: list[str] = []
    obj = parse_json_payload(text)
    if not isinstance(obj, dict):
        return {"passed": False, "score": 0.0, "defects": ["bad_json"], "metrics": {}}
    if sample.get("subtask") == "practice_diagnose":
        pred = str(obj.get("predicted") or "").strip().upper()
        valid_cls = {"NONE", "CONCEPT_CONFUSION", "SIGN_OR_FORMULA", "GRAPH_SHIFT_OR_ROTATION", "ARITHMETIC_OR_ALGEBRA"}
        if pred not in valid_cls:
            defects.append(f"invalid_class:{pred}")
        gold = sample["gold_error_class"]
        cls_ok = pred == gold
        if not cls_ok:
            defects.append(f"class_mismatch:{pred}!=gold:{gold}")
        span = str(obj.get("studentErrorSpan") or "")
        span_ok = True if not span else (span in sample["student_answer"])
        if not span_ok:
            defects.append("span_not_verbatim")
        score = 0.7 * float(cls_ok) + 0.3 * float(span_ok and pred in valid_cls)
        return {"passed": cls_ok and span_ok, "score": round(score, 4), "defects": defects, "metrics": {"class_ok": cls_ok}}

    # practice_grade
    if "rewrite" in obj:
        defects.append("anti_ghostwrite_rewrite_field")
    items = obj.get("items")
    if not isinstance(items, list):
        return {"passed": False, "score": 0.0, "defects": defects + ["missing_items"], "metrics": {}}
    rubric = {c["cid"]: c for c in sample["rubric_criteria"]}
    seen: set[str] = set()
    quote_ok = 0
    quote_req = 0
    total_awarded = 0.0
    for it in items:
        if not isinstance(it, dict):
            continue
        cid = str(it.get("cid") or "")
        if cid not in rubric or cid in seen:
            defects.append(f"bad_cid:{cid}")
            continue
        seen.add(cid)
        marks = float(it.get("awardedMarks", -1))
        max_m = float(rubric[cid]["maxMarks"])
        if not (0.0 <= marks <= max_m):
            defects.append(f"marks_oob:{cid}")
        total_awarded += max(0.0, marks)
        if marks > 0 or it.get("verdict") in ("FULL", "PARTIAL"):
            quote_req += 1
            sq = str(it.get("studentQuote") or "")
            if sq and sq in sample["student_answer"]:
                quote_ok += 1
            else:
                defects.append(f"student_quote_not_verbatim:{cid}")
        note = str(it.get("feedbackNoteZh") or "")
        if len(note) > 65:
            defects.append(f"feedback_too_long:{cid}")
    if seen != set(rubric.keys()):
        defects.append("rubric_coverage_incomplete")
    q_rate = quote_ok / max(1, quote_req) if quote_req else 1.0
    gold_total = float(sample["gold_awarded_total"])
    max_total = sum(float(c["maxMarks"]) for c in sample["rubric_criteria"])
    mae = abs(total_awarded - gold_total)
    score_acc = max(0.0, 1.0 - mae / max(1.0, max_total))
    score = 0.4 * float("anti_ghostwrite_rewrite_field" not in defects) + 0.3 * q_rate + 0.3 * score_acc
    return {
        "passed": "anti_ghostwrite_rewrite_field" not in defects and seen == set(rubric.keys()) and q_rate >= 0.8 and mae <= 2.0,
        "score": round(score, 4),
        "defects": defects,
        "metrics": {"awarded": total_awarded, "gold": gold_total, "mae": round(mae, 2), "student_quote_rate": round(q_rate, 3)},
    }


def check_g7(sample: dict, text: str) -> dict:
    defects: list[str] = []
    obj = parse_json_payload(text)
    if not isinstance(obj, dict):
        return {"passed": False, "score": 0.0, "defects": ["bad_json"], "metrics": {}}
    if sample.get("subtask") == "course_mock_plan":
        secs = obj.get("sections") or []
        excluded = {t.lower() for t in sample.get("excluded_titles") or []}
        item_types: set[str] = set()
        for s in secs:
            for sl in (s.get("slots") or []) if isinstance(s, dict) else []:
                if not isinstance(sl, dict):
                    continue
                item_types.add(str(sl.get("itemType") or ""))
                if str(sl.get("topicId") or "").lower() in excluded or str(sl.get("focusConcept") or "").lower() in excluded:
                    defects.append("included_excluded_topic")
        if "essay" not in item_types:
            defects.append("missing_essay")
        if not ({"multi_part_calc", "graph_analysis"} & item_types):
            defects.append("missing_calc_or_graph")
        return {"passed": not defects and len(secs) >= 1, "score": 1.0 if not defects else 0.3, "defects": defects, "metrics": {}}

    # course_align
    for key in ("examProfile", "materialUpdates", "topics"):
        if key not in obj:
            defects.append(f"missing_{key}")
    locked = {m["materialId"]: (m.get("weekNum"), m.get("role")) for m in sample["payload"].get("materials", []) if m.get("userLocked")}
    for mu in obj.get("materialUpdates") or []:
        if isinstance(mu, dict) and mu.get("materialId") in locked:
            w, r = locked[mu["materialId"]]
            if mu.get("weekNum") != w or mu.get("role") != r:
                defects.append(f"modified_locked:{mu['materialId']}")
    return {"passed": not defects, "score": 1.0 if not defects else 0.25, "defects": defects, "metrics": {}}


def check_g8a(sample: dict, text: str) -> dict:
    defects: list[str] = []
    obj = parse_json_payload(text)
    if not isinstance(obj, dict):
        return {"passed": False, "score": 0.0, "defects": ["bad_json"], "metrics": {}}
    if sample.get("subtask") == "course_topic_map":
        cites = obj.get("citations") or []
        blocks = { (b["bookId"], int(b["page"])): b["text"] for b in sample["payload"]["blocks"] }
        ok = sum(1 for c in cites if isinstance(c, dict) and contains_verbatim_quote(str(c.get("quote") or ""), blocks.get((c.get("bookId"), c.get("page")), ""), 4))
        rate = ok / max(1, len(cites)) if cites else 0.0
        if rate < 0.7:
            defects.append("citation_miss")
        return {"passed": rate >= 0.7 and len(cites) >= 1, "score": round(rate, 4), "defects": defects, "metrics": {"cite_rate": rate}}

    decs = obj.get("decisions")
    if not isinstance(decs, list):
        return {"passed": False, "score": 0.0, "defects": ["missing_decisions"], "metrics": {}}
    valid_rel = {"CONSISTENT_DEEPENING", "NOTATION_OR_BOUNDARY_DIFF", "EMPIRICAL_CHALLENGE", "OPPOSING_VIEW", "PREREQUISITE"}
    gold_keep = sample.get("gold_keep") or {}
    correct = 0
    for d in decs:
        if not isinstance(d, dict):
            continue
        pid = str(d.get("pairId") or "")
        keep = bool(d.get("keep"))
        if pid in gold_keep and keep == bool(gold_keep[pid]):
            correct += 1
        elif pid in gold_keep:
            defects.append(f"keep_mismatch:{pid}")
        if keep and d.get("relationType") not in valid_rel:
            defects.append(f"bad_relation:{pid}")
        if keep and len(str(d.get("diffSummary") or "")) > 60:
            defects.append(f"summary_too_long:{pid}")
    acc = correct / max(1, len(gold_keep)) if gold_keep else 1.0
    return {"passed": acc >= 0.8 and not any(x.startswith("bad_relation") for x in defects), "score": round(acc, 4), "defects": defects, "metrics": {"keep_acc": round(acc, 3)}}


def check_g8b(sample: dict, text: str) -> dict:
    defects: list[str] = []
    s = (text or "").strip()
    if len(s) < 20:
        return {"passed": False, "score": 0.0, "defects": ["empty_ocr"], "metrics": {}}
    gold_terms = sample.get("gold_terms") or []
    hits = sum(1 for t in gold_terms if t.lower() in s.lower())
    term_recall = hits / max(1, len(gold_terms)) if gold_terms else 1.0
    if term_recall < 0.7:
        defects.append(f"low_term_recall:{hits}/{len(gold_terms)}")
    trad_chars = sample.get("gold_trad_chars") or []
    trad_ok = True
    if trad_chars:
        trad_hits = sum(1 for ch in trad_chars if ch in s)
        trad_rate = trad_hits / len(trad_chars)
        if trad_rate < 0.7:
            trad_ok = False
            defects.append(f"trad_simplified_leak:{trad_hits}/{len(trad_chars)}")
    score = 0.7 * term_recall + 0.3 * float(trad_ok)
    return {
        "passed": term_recall >= 0.7 and trad_ok,
        "score": round(score, 4),
        "defects": defects,
        "metrics": {"term_recall": round(term_recall, 3), "trad_ok": trad_ok},
    }


def check_g9(sample: dict, text: str) -> dict:
    defects: list[str] = []
    obj = parse_json_payload(text)
    if not isinstance(obj, dict):
        return {"passed": False, "score": 0.0, "defects": ["bad_json"], "metrics": {}}
    if sample.get("subtask") == "BOOK_MATH_REPAIR":
        items = obj.get("repairedItems") or []
        if not items:
            return {"passed": False, "score": 0.0, "defects": ["empty_repaired"], "metrics": {}}
        sympy_pass = 0
        sympy_total = 0
        answer_ok = True
        for it in items:
            if not isinstance(it, dict):
                continue
            for mc in it.get("mathChecks") or []:
                sympy_total += 1
                ok, _ = verify_sympy_check(mc)
                if ok:
                    sympy_pass += 1
                else:
                    defects.append("repaired_sympy_fail")
            if sample.get("gold_expected_answer"):
                corr = str(it.get("correctOption") or "")
                if str(sample["gold_expected_answer"]) not in corr:
                    answer_ok = False
                    defects.append(f"wrong_repaired_answer:{corr}")
        s_rate = sympy_pass / max(1, sympy_total) if sympy_total else 0.0
        if sympy_total == 0:
            defects.append("no_sympy_checks")
        score = 0.5 * s_rate + 0.5 * float(answer_ok)
        return {"passed": answer_ok and sympy_total > 0 and s_rate == 1.0, "score": round(score, 4), "defects": defects, "metrics": {"sympy_rate": s_rate, "answer_ok": answer_ok}}

    # BOOK_MATH_VERIFY
    audits = obj.get("itemAudits") or []
    gold_matches = sample.get("gold_source_matches") or {}
    correct = 0
    sympy_pass = 0
    sympy_total = 0
    for a in audits:
        if not isinstance(a, dict):
            continue
        iid = str(a.get("itemId") or "")
        if iid in gold_matches and bool(a.get("sourceMatches")) == bool(gold_matches[iid]):
            correct += 1
        elif iid in gold_matches:
            defects.append(f"verdict_mismatch:{iid}")
        for mc in a.get("sympyChecks") or []:
            sympy_total += 1
            if verify_sympy_check(mc)[0]:
                sympy_pass += 1
    acc = correct / max(1, len(gold_matches)) if gold_matches else 1.0
    s_rate = sympy_pass / max(1, sympy_total) if sympy_total else 0.0
    score = 0.7 * acc + 0.3 * s_rate
    return {"passed": acc == 1.0 and sympy_total > 0 and s_rate >= 0.8, "score": round(score, 4), "defects": defects, "metrics": {"audit_acc": acc, "sympy_rate": s_rate}}


CHECKERS = {
    "G1": check_g1,
    "G2": check_g2,
    "G3": check_g3,
    "G4": check_g4,
    "G5": check_g5,
    "G6": check_g6,
    "G7": check_g7,
    "G8a": check_g8a,
    "G8b": check_g8b,
    "G9": check_g9,
}


def run_objective_gate(group_id: str, sample: dict, text: str) -> dict:
    return CHECKERS[group_id](sample, text)
