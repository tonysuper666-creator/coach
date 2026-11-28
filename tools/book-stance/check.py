#!/usr/bin/env python3
"""路线 1 自动检查脚本（TON-1976 §6 A1–A8，三张卡共用同一套按题目字段判定的规则）。

用法：
  python3 tools/book-stance/check.py tools/book-stance/out/ask
  python3 tools/book-stance/check.py tools/book-stance/out/explain

退出码 0 表示 A1–A8 全部通过；任意一项不过即退出码非 0。
注意：标准输出只报数字和题号/检查项代码，不打印任何原书原句。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

OUTSIDE_PREFIX = "书外 · 未对照原文"
GUESS_PREFIX = "推想 · 书外 · 未对照原文"
ESOTERIC_NOTICE = "按本书体系看，不是对现实的断言"
HIGH_RISK_BOUNDARY = "high_risk"
HIGH_RISK_NOTICE = "看病、法律、投资这类具体决定不从书里推，下面只讲书里怎么说。"
NO_BOOK_DIRECT = "这本书里没有直接讲到这一点。"
NO_BOOK_FICTION = "读到这里，书里还没讲到这一点。"

LOCATOR_RE = re.compile(r"(?:PDF\s*(?:page|第)|(?:page|页码)\s*[:：]?\s*\d|第\s*\d+\s*页)", re.IGNORECASE)
EN_ABBREV_RE = re.compile(
    r"(?:^|[\s(（])(?:mr|mrs|ms|dr|prof|st|vs|etc|fig|eq|no|ch|vol|ed|pp|p|e\.g|i\.e|u\.s|u\.k|a\.m|p\.m|2e)$",
    re.IGNORECASE,
)
FIRST_PERSON_EN_I_RE = re.compile(r"(?<![A-Za-z])(?:I|I'[a-z]+)(?![A-Za-z])")
FIRST_PERSON_EN_OTHER_RE = re.compile(r"\b(?:my|me|mine|myself|we|our|ours)\b", re.IGNORECASE)
ANGLE_NOTE_RE = re.compile(r"〈([^〉]{4,})〉")
FUAN_RE = re.compile(r"(?:^|[（(\s\u3000])(復案[：:][^）\n]*)")
SOYIN_RE = re.compile(r"【(?:索隱述贊|索隱|正義|集解)】[^\n]*")


def count_sentences(raw: str) -> int:
    text = raw.strip()
    if not text:
        return 0
    count = 0
    has_content = False
    paren_depth = 0
    quote_depth = 0
    in_ascii_double = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in "（([【《〈":
            paren_depth += 1
            has_content = True
        elif ch in "）)]】》〉":
            if paren_depth > 0:
                paren_depth -= 1
        elif ch in "「『“‘":
            quote_depth += 1
            has_content = True
        elif ch in "」』”’":
            if quote_depth > 0:
                quote_depth -= 1
        elif ch == '"':
            in_ascii_double = not in_ascii_double
            has_content = True
        elif ch in "。！？!?":
            if paren_depth == 0 and quote_depth == 0 and not in_ascii_double:
                if has_content:
                    count += 1
                    has_content = False
                while i + 1 < n and text[i + 1] in "。！？!?.」』”’）)":
                    nxt = text[i + 1]
                    if nxt in "」』”’" and quote_depth > 0:
                        quote_depth -= 1
                    if nxt in "）)" and paren_depth > 0:
                        paren_depth -= 1
                    i += 1
            else:
                has_content = True
        elif ch == ".":
            prev = text[i - 1] if i > 0 else ""
            nxt = text[i + 1] if i + 1 < n else ""
            is_ellipsis = prev == "." or nxt == "."
            is_decimal = prev.isdigit() and nxt.isdigit()
            prefix = text[max(0, i - 10):i]
            is_abbrev = bool(EN_ABBREV_RE.search(prefix))
            ends_or_spaced = (not nxt) or nxt.isspace() or nxt in '"”’」』)）'
            if (
                not is_ellipsis
                and not is_decimal
                and not is_abbrev
                and ends_or_spaced
                and paren_depth == 0
                and quote_depth == 0
                and not in_ascii_double
            ):
                if has_content:
                    count += 1
                    has_content = False
            else:
                has_content = True
        else:
            if not ch.isspace():
                has_content = True
        i += 1
    if has_content:
        count += 1
    return count


def utf16_slice(text: str, start_utf16: int, end_utf16: int) -> str | None:
    if start_utf16 < 0 or end_utf16 <= start_utf16:
        return None
    raw = text.encode("utf-16-le")
    total_units = len(raw) // 2
    if end_utf16 > total_units:
        return None
    return raw[start_utf16 * 2 : end_utf16 * 2].decode("utf-16-le", errors="strict")


def utf16_index(text: str, char_pos: int) -> int:
    return len(text[:char_pos].encode("utf-16-le")) // 2


def is_commentary_span(page_text: str, start_utf16: int, end_utf16: int) -> bool:
    """判断原页 [start_utf16, end_utf16) 是否落在古籍注文/按语区间内。"""
    commentary_ranges: list[tuple[int, int]] = []
    for m in ANGLE_NOTE_RE.finditer(page_text):
        inner = m.group(1).strip()
        if inner.startswith("一作") or inner.startswith("一無"):
            continue
        commentary_ranges.append((utf16_index(page_text, m.start()), utf16_index(page_text, m.end())))
    for m in FUAN_RE.finditer(page_text):
        commentary_ranges.append((utf16_index(page_text, m.start(1)), utf16_index(page_text, m.end(1))))
    for m in SOYIN_RE.finditer(page_text):
        commentary_ranges.append((utf16_index(page_text, m.start()), utf16_index(page_text, m.end())))
    for cs, ce in commentary_ranges:
        if start_utf16 >= cs and end_utf16 <= ce:
            return True
    return False


def has_first_person(text: str) -> bool:
    return (
        "我" in text
        or bool(FIRST_PERSON_EN_I_RE.search(text))
        or bool(FIRST_PERSON_EN_OTHER_RE.search(text))
    )


def check_item(item: dict[str, Any], pages: dict[str, str], expected_source: str, entry: str) -> dict[str, list[str]]:
    failures: dict[str, list[str]] = {f"A{k}": [] for k in range(1, 9)}
    failures["SOURCE"] = []

    qid = item["id"]
    meta = item["meta"]
    resp = item.get("response") or {}
    book_hash = item.get("bookHash", "")
    guards = set(meta.get("guards") or [])
    expected_stance = meta.get("stance")
    expect_boundary = bool(meta.get("expectBoundary", False))
    allow_no_book = bool(meta.get("allowNoBook", False))
    is_classical = bool(meta.get("isClassical", False))
    strict_single_page = bool(meta.get("strictSinglePage", False))
    selection_page = item.get("selectionPage")
    effective_rtp = item.get("effectiveReadThroughPage")

    actual_source = item.get("stanceSource")
    if expected_source and actual_source != expected_source:
        failures["SOURCE"].append(f"{qid}: stanceSource={actual_source} expected={expected_source}")

    if item.get("error"):
        failures["A8"].append(f"{qid}: request_error={item['error']}")
        return failures

    layers = resp.get("layers")
    if not isinstance(layers, list):
        failures["A8"].append(f"{qid}: missing_layers")
        return failures

    boundary = resp.get("boundary")
    high_risk_notice = resp.get("highRiskNotice")
    system_notice = resp.get("systemNotice")
    no_book_notice = resp.get("noBookNotice")
    summary_text = (resp.get("text") or "").strip()
    first_non_empty_line = next((ln.strip() for ln in summary_text.splitlines() if ln.strip()), "")
    missing_record_notices = resp.get("missingRecordNotices") or []
    gate = resp.get("gate") or {}
    fiction_books = item.get("fictionBooks") or {}
    missing_book_hash = item.get("missingRecordBookHash")
    missing_book_title = item.get("missingRecordBookTitle")

    # A4: 题头立场等于题目 stance 字段；若为换立场题且有 initialResponse，旧卡题头不变
    if expected_stance and expected_stance != "mixed":
        if resp.get("stance") != expected_stance:
            failures["A4"].append(f"{qid}: stance={resp.get('stance')} expected={expected_stance}")
    init_resp = item.get("initialResponse")
    init_stance = item.get("initialStance")
    if init_stance and isinstance(init_resp, dict):
        if init_stance != "mixed" and init_resp.get("stance") != init_stance:
            failures["A4"].append(f"{qid}: initial_stance={init_resp.get('stance')} expected={init_stance}")

    # A5: 高危直接回答（TON-1976 §6 新 A5 / TON-1982）与体系提示行
    if entry in ("ask", "explain", "collection"):
        if expected_stance == "esoteric":
            if system_notice != ESOTERIC_NOTICE:
                failures["A5"].append(f"{qid}: missing_esoteric_system_notice")
        elif expected_stance != "mixed":
            if system_notice:
                failures["A5"].append(f"{qid}: unexpected_system_notice_for_{expected_stance}")

    if expect_boundary:
        if boundary != HIGH_RISK_BOUNDARY:
            failures["A5"].append(f"{qid}: expected_boundary_high_risk got={boundary!r}")
        if isinstance(boundary, dict) or "actionLabel" in resp or "boundaryCard" in resp:
            failures["A5"].append(f"{qid}: forbidden_intercept_card_field")
        if high_risk_notice != HIGH_RISK_NOTICE:
            failures["A5"].append(f"{qid}: missing_or_wrong_high_risk_notice")
        if first_non_empty_line != HIGH_RISK_NOTICE:
            failures["A5"].append(f"{qid}: first_line_not_high_risk_notice")
        if any(l.get("layer") != "book" for l in layers):
            failures["A5"].append(f"{qid}: high_risk_must_have_no_outside_or_guess")
        # 换立场高危题（如 X2）：初始卡同样应为高危直接回答且玄学立场带体系提示行
        if isinstance(init_resp, dict):
            init_boundary = init_resp.get("boundary")
            if init_boundary != HIGH_RISK_BOUNDARY or isinstance(init_boundary, dict):
                failures["A5"].append(f"{qid}: initial_expected_boundary_high_risk got={init_boundary!r}")
            if init_resp.get("highRiskNotice") != HIGH_RISK_NOTICE:
                failures["A5"].append(f"{qid}: initial_missing_high_risk_notice")
            if any(l.get("layer") != "book" for l in (init_resp.get("layers") or [])):
                failures["A5"].append(f"{qid}: initial_high_risk_has_non_book")
            if init_stance == "esoteric" and init_resp.get("systemNotice") != ESOTERIC_NOTICE:
                failures["A5"].append(f"{qid}: initial_missing_esoteric_system_notice")
    else:
        if boundary is not None:
            failures["A5"].append(f"{qid}: unexpected_boundary got={boundary!r}")
        if high_risk_notice:
            failures["A5"].append(f"{qid}: unexpected_high_risk_notice")

    # 遍历所有层检查 A1, A2, A3, A6, A7
    valid_book_sentences = 0
    book_labels: list[str] = []
    book_texts: list[str] = []
    outside_texts: list[str] = []
    cited_book_hashes: set[str] = set()

    for l_idx, layer in enumerate(layers):
        l_kind = layer.get("layer")
        l_label = layer.get("label") or ""
        if l_kind == "book":
            book_labels.append(l_label)
            sents = layer.get("sentences")
            if not isinstance(sents, list) or not sents:
                failures["A1"].append(f"{qid}: empty_book_layer_{l_idx}")
                continue
            for s_idx, sent in enumerate(sents):
                s_text = (sent.get("text") or "").strip()
                cites = sent.get("citations")
                if not s_text:
                    failures["A1"].append(f"{qid}: empty_sentence_{l_idx}_{s_idx}")
                    continue
                if count_sentences(s_text) != 1:
                    failures["A1"].append(f"{qid}: multi_sentence_{l_idx}_{s_idx}_count={count_sentences(s_text)}")
                if not isinstance(cites, list) or not cites:
                    failures["A1"].append(f"{qid}: uncited_sentence_{l_idx}_{s_idx}")
                    continue
                sent_ok = True
                cite_kinds: set[str] = set()
                sent_books: set[str] = set()
                for c_idx, cite in enumerate(cites):
                    doc_id = cite.get("docId") or ""
                    pdf_page = cite.get("pdfPage")
                    start_u16 = cite.get("startUtf16")
                    end_u16 = cite.get("endUtf16")
                    quote = cite.get("quote") or ""
                    c_book_hash = cite.get("bookId") or (doc_id.split(":p")[0] if ":p" in doc_id else book_hash)
                    sent_books.add(c_book_hash)
                    cited_book_hashes.add(c_book_hash)
                    page_key = f"{c_book_hash}:{pdf_page}"
                    page_text = pages.get(page_key)
                    if page_text is None:
                        failures["A1"].append(f"{qid}: missing_page_text_{l_idx}_{s_idx}_{c_idx}")
                        sent_ok = False
                    else:
                        sliced = utf16_slice(page_text, int(start_u16), int(end_u16)) if isinstance(start_u16, int) and isinstance(end_u16, int) else None
                        if not quote or sliced != quote:
                            failures["A1"].append(f"{qid}: quote_offset_mismatch_{l_idx}_{s_idx}_{c_idx}")
                            sent_ok = False
                        elif is_classical:
                            cite_kinds.add("note" if is_commentary_span(page_text, start_u16, end_u16) else "main")
                    # A6: fiction 防剧透页码边界
                    if "fiction" in guards and isinstance(pdf_page, int):
                        if entry == "collection":
                            if c_book_hash in fiction_books:
                                f_rtp = fiction_books[c_book_hash]
                                if f_rtp is not None and pdf_page > int(f_rtp):
                                    failures["A6"].append(f"{qid}: fiction_page_exceeds_rtp_{pdf_page}_gt_{f_rtp}")
                        else:
                            if strict_single_page and selection_page is not None and pdf_page != selection_page:
                                failures["A6"].append(f"{qid}: x3_page_out_of_selection_{pdf_page}_vs_{selection_page}")
                            elif effective_rtp is not None and pdf_page > effective_rtp:
                                failures["A6"].append(f"{qid}: fiction_page_exceeds_rtp_{pdf_page}_gt_{effective_rtp}")
                if entry == "collection" and len(sent_books) > 1:
                    failures["A1"].append(f"{qid}: cross_book_mixed_sentence_{l_idx}_{s_idx}")
                if is_classical and len(cite_kinds) > 1:
                    failures["A7"].append(f"{qid}: classic_mixed_main_and_note_{l_idx}_{s_idx}")
                if sent_ok:
                    valid_book_sentences += 1
                    book_texts.append(s_text)
        elif l_kind == "outside":
            text = (layer.get("text") or "").strip()
            outside_texts.append(text)
            if not l_label.startswith(OUTSIDE_PREFIX):
                failures["A2"].append(f"{qid}: outside_bad_label_{l_idx}")
            if layer.get("citations"):
                failures["A2"].append(f"{qid}: outside_has_citations_{l_idx}")
            if LOCATOR_RE.search(text):
                failures["A2"].append(f"{qid}: outside_has_page_locator_{l_idx}")
            if expected_stance == "original":
                failures["A6"].append(f"{qid}: original_has_outside_{l_idx}")
        elif l_kind == "guess":
            text = (layer.get("text") or "").strip()
            if not l_label.startswith(GUESS_PREFIX) or ("推想" not in l_label) or (OUTSIDE_PREFIX not in l_label):
                failures["A3"].append(f"{qid}: guess_bad_label_{l_idx}")
            if layer.get("citations"):
                failures["A3"].append(f"{qid}: guess_has_citations_{l_idx}")
            if LOCATOR_RE.search(text):
                failures["A3"].append(f"{qid}: guess_has_page_locator_{l_idx}")
            if expected_stance not in ("life", "mixed"):
                failures["A3"].append(f"{qid}: guess_disallowed_in_{expected_stance}")
            if expected_stance == "original":
                failures["A6"].append(f"{qid}: original_has_guess_{l_idx}")
            if has_first_person(text):
                failures["A3"].append(f"{qid}: guess_has_first_person_{l_idx}")
        else:
            failures["A1"].append(f"{qid}: unknown_layer_{l_kind}")

    if missing_book_hash:
        if missing_book_hash in cited_book_hashes:
            failures["A6"].append(f"{qid}: missing_record_book_was_cited")
        expected_missing_msg = f"《{missing_book_title}》还没有阅读记录，没有用它。"
        combined_text = (resp.get("text") or "") + "\n" + "\n".join(missing_record_notices)
        if expected_missing_msg not in combined_text and "还没有阅读记录，没有用它。" not in combined_text:
            failures["A6"].append(f"{qid}: missing_no_reading_record_notice")

    # A6 越界计数与初始响应（如 X1 原文立场）
    if "fiction" in guards:
        if int(gate.get("outOfRange", 0)) != 0:
            failures["A6"].append(f"{qid}: gate_outOfRange={gate.get('outOfRange')}")
        if isinstance(init_resp, dict) and init_stance == "original":
            init_layers = init_resp.get("layers") or []
            if any(l.get("layer") != "book" for l in init_layers):
                failures["A6"].append(f"{qid}: initial_original_has_non_book")
            if int((init_resp.get("gate") or {}).get("outOfRange", 0)) != 0:
                failures["A6"].append(f"{qid}: initial_outOfRange_nonzero")

    # A7 字段化古籍核验（不按题号写死）
    req_label = meta.get("requireBookLabel")
    if req_label and req_label not in book_labels:
        failures["A7"].append(f"{qid}: missing_required_book_label")
    forbid_book = meta.get("forbidBookContains")
    if forbid_book and any(forbid_book in t for t in book_texts):
        failures["A7"].append(f"{qid}: forbidden_term_in_book_layer")
    req_outside = meta.get("requireOutsideContains")
    if req_outside and not any(req_outside in t for t in outside_texts):
        failures["A7"].append(f"{qid}: missing_required_term_in_outside_layer")

    # A8 非空与 allowNoBook 规则（含高危题 A5 兜底检查）
    has_any_content = bool(layers) or bool(no_book_notice)
    if not has_any_content:
        failures["A8"].append(f"{qid}: empty_answer")
    elif expect_boundary:
        expected_hr_notice = NO_BOOK_FICTION if ("fiction" in guards and entry != "collection") else NO_BOOK_DIRECT
        if valid_book_sentences == 0 and no_book_notice != expected_hr_notice:
            failures["A5"].append(f"{qid}: high_risk_zero_book_missing_fallback")
    elif valid_book_sentences == 0:
        if not allow_no_book:
            failures["A8"].append(f"{qid}: zero_book_sentences_not_allowed")
        else:
            expected_notice = NO_BOOK_FICTION if "fiction" in guards else NO_BOOK_DIRECT
            if no_book_notice != expected_notice:
                failures["A8"].append(f"{qid}: missing_or_wrong_noBookNotice")

    return failures


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python3 tools/book-stance/check.py <out_dir>", file=sys.stderr)
        return 2
    out_dir = Path(sys.argv[1])
    run_meta_path = out_dir / "run_meta.json"
    pages_path = out_dir / "pages.json"
    answers_dir = out_dir / "answers"
    if not run_meta_path.is_file() or not answers_dir.is_dir():
        print(f"Missing run outputs under {out_dir}", file=sys.stderr)
        return 2

    run_meta = json.loads(run_meta_path.read_text(encoding="utf-8"))
    pages = json.loads(pages_path.read_text(encoding="utf-8")) if pages_path.is_file() else {}
    expected_source = run_meta.get("expectedStanceSource", "")
    entry = run_meta.get("entry", "ask")

    answer_files = sorted(answers_dir.glob("*.json"))
    if not answer_files:
        print(f"No answer files found under {answers_dir}", file=sys.stderr)
        return 2

    totals: dict[str, list[str]] = {f"A{k}": [] for k in range(1, 9)}
    totals["SOURCE"] = []
    total_book_sentences = 0
    total_dropped = 0
    total_out_of_range = 0
    total_boundary = 0

    for path in answer_files:
        item = json.loads(path.read_text(encoding="utf-8"))
        resp = item.get("response") or {}
        gate = resp.get("gate") or {}
        total_dropped += int(gate.get("droppedSentences", 0))
        total_out_of_range += int(gate.get("outOfRange", 0))
        if resp.get("boundary") is not None:
            total_boundary += 1
        for layer in resp.get("layers") or []:
            if layer.get("layer") == "book":
                total_book_sentences += len(layer.get("sentences") or [])
        item_failures = check_item(item, pages, expected_source, entry)
        for k, v in item_failures.items():
            totals[k].extend(v)

    total_fail = sum(len(v) for v in totals.values())
    print(
        f"CHECK entry={entry} stanceSource={expected_source} items={len(answer_files)} "
        f"bookSentences={total_book_sentences} boundary={total_boundary} "
        f"droppedSentences={total_dropped} outOfRange={total_out_of_range} failures={total_fail}"
    )
    for k in ["SOURCE", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8"]:
        status = "PASS" if not totals[k] else f"FAIL({len(totals[k])})"
        print(f"  {k}: {status}")
        for msg in totals[k][:15]:
            print(f"    - {msg}")
    return 0 if total_fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
