"""Build private 10-group (G1..G9, G8a/G8b) evaluation dataset from local materials.

Reads local books/slides/notes at runtime and writes private samples.json + rendered PNGs
strictly outside Git. Contains NO raw textbook passages in source code.
"""
from __future__ import annotations

import argparse
from bs4 import BeautifulSoup
import json
from pathlib import Path
import re
import subprocess
import unicodedata
import xml.etree.ElementTree as ET
import zipfile


def pdftotext_page(pdf_path: Path, page_num: int) -> str:
    out = subprocess.check_output(
        ["pdftotext", "-f", str(page_num), "-l", str(page_num), str(pdf_path), "-"],
        text=True,
        stderr=subprocess.DEVNULL,
    )
    return unicodedata.normalize("NFKC", out).strip()


def pdftoppm_render(pdf_path: Path, page_num: int, out_prefix: Path) -> Path:
    out_prefix.parent.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(
        ["pdftoppm", "-png", "-r", "150", "-singlefile", "-f", str(page_num), "-l", str(page_num), str(pdf_path), str(out_prefix)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return out_prefix.with_suffix(".png")


def extract_epub_chapters(epub_path: Path, max_items: int = 12) -> list[dict]:
    with zipfile.ZipFile(epub_path) as z:
        opf_candidates = [n for n in z.namelist() if n.endswith(".opf")]
        spine_files: list[str] = []
        if opf_candidates:
            opf_path = opf_candidates[0]
            opf_dir = str(Path(opf_path).parent)
            opf_root = ET.fromstring(z.read(opf_path))
            manifest = {}
            for item in opf_root.findall(".//{*}item"):
                manifest[item.attrib.get("id", "")] = item.attrib.get("href", "")
            for itemref in opf_root.findall(".//{*}itemref"):
                href = manifest.get(itemref.attrib.get("idref", ""), "")
                if href:
                    full = f"{opf_dir}/{href}" if opf_dir and opf_dir != "." else href
                    if full in z.namelist():
                        spine_files.append(full)
        if not spine_files:
            spine_files = sorted(n for n in z.namelist() if n.endswith((".xhtml", ".html", ".htm")))

        pages: list[dict] = []
        for idx, name in enumerate(spine_files[:max_items], 1):
            soup = BeautifulSoup(z.read(name).decode("utf-8", errors="replace"), "html.parser")
            txt = re.sub(r"\s+", " ", soup.get_text(" ", strip=True)).strip()
            if txt:
                pages.append({"page": idx, "file": name, "text": txt})
        return pages


def first_sentence(text: str, min_len: int = 30, max_len: int = 240) -> str:
    clean = re.sub(r"\s+", " ", text).strip()
    for seg in re.split(r"(?<=[\.\!\?。！？])\s+", clean):
        if len(seg) >= min_len:
            return seg[:max_len]
    return clean[:max_len]


def build_all(materials_dir: Path, ecnm_dir: Path, out_dir: Path) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    rendered_dir = out_dir / "rendered_pages"
    rendered_dir.mkdir(parents=True, exist_ok=True)

    he_pdf = ecnm_dir / "ECNM10119-mcq-src/参考资料/Bhattacharya-Hyde-Tu-2014-Health-Economics.pdf"
    hb_pdf = ecnm_dir / "ECNM10119-mcq-src/参考资料/ECNM10119-Module-Handbook-updated.pdf"
    raw_ref = ecnm_dir / "ECNM10119-raw/for-src/参考资料"
    cn_notes_dir = ecnm_dir / "ECNM10119-raw/for-src/期中复习"
    transcript_md = ecnm_dir / "ECNM10119-raw/for-src/课堂录音/Week4-16Oct-转写.md"

    w1_slides = raw_ref / "Week1/ECNM10119-Week1-Lecture-Slides.pdf"
    w2_slides = raw_ref / "Week2/ECNM10119-Week2-Lecture-Slides.pdf"
    w4_slides = raw_ref / "Week4/ECNM10119-Week4-Lecture-Slides.pdf"
    w5_slides = raw_ref / "Week5/ECNM10119-Week5-Lecture-Slides.pdf"
    w6_slides = raw_ref / "Week6/ECNM10119-Week6-Lecture-Slides.pdf"
    w8_slides = raw_ref / "Week8/ECNM10119-Week8-Lecture-Slides.pdf"
    w4_tut_key = raw_ref / "Week4/ECNM10119-Tutorial-Week4-KEY.pdf"
    arrow_pdf = raw_ref / "Required-Readings/Arrow1963_AER_WHO2004reprint.pdf"

    nahan_epub = materials_dir / "Nahan-LuXun.epub"
    nahan_utf8 = materials_dir / "Nahan-LuXun-utf8.txt"
    nahan_gbk = materials_dir / "P5-Nahan-GBK.txt"
    nahan_scan = materials_dir / "P5-scanned-Nahan-24p.pdf"
    smith_epub = materials_dir / "Wealth-of-Nations-Smith.epub"
    malthus_epub = materials_dir / "Principle-of-Population-Malthus.epub"
    ricardo_epub = materials_dir / "Principles-of-Political-Economy-Ricardo.epub"
    outline_epub = materials_dir / "Outline-of-Science-Vol1-illustrated.epub"
    large_pdf = materials_dir / "P5-very-large-classics.pdf"

    # Extract slices once
    he_pages = {p: pdftotext_page(he_pdf, p) for p in [32, 34, 42, 46, 62, 66, 160, 174, 178, 180, 181, 195, 236, 240, 311, 315]}
    hb_pages = {p: pdftotext_page(hb_pdf, p) for p in range(1, 5)}
    nahan_pages = extract_epub_chapters(nahan_epub, max_items=10)
    smith_pages = extract_epub_chapters(smith_epub, max_items=12)
    malthus_pages = extract_epub_chapters(malthus_epub, max_items=10)
    ricardo_pages = extract_epub_chapters(ricardo_epub, max_items=10)
    outline_pages = extract_epub_chapters(outline_epub, max_items=10)

    nahan_txt = nahan_utf8.read_text(encoding="utf-8", errors="replace")
    nahan_gbk_txt = nahan_gbk.read_bytes().decode("gb18030", errors="replace")
    cn_w2 = (cn_notes_dir / "02-Week2-Grossman与健康差异.md").read_text(encoding="utf-8")
    cn_w4 = (cn_notes_dir / "04-Week4-保险与Akerlof.md").read_text(encoding="utf-8")
    cn_w5 = (cn_notes_dir / "05-Week5-6-逆向选择与道德风险.md").read_text(encoding="utf-8")
    cn_form = (cn_notes_dir / "06-公式速查.md").read_text(encoding="utf-8")
    w4_trans = transcript_md.read_text(encoding="utf-8")[:5000] if transcript_md.exists() else ""

    samples: list[dict] = []

    def add(group: str, idx: int, lang: str, title: str, data: dict) -> None:
        split = "holdout" if idx > 8 else "core"
        row = {"group": group, "split": split, "sample_id": f"{group}-{idx:02d}", "lang": lang, "title": title}
        row.update(data)
        samples.append(row)

    # ================= G1: 讲解和选中就问 (8 core + 2 holdout) =================
    g1_specs = [
        ("en", "HealthEcon Ch.1 医疗需求不确定性", "explain", [{"id": "S1", "doc_id": "he", "pdf_page": 34, "text": he_pages[34][:2200]}], first_sentence(he_pages[34]), None, False),
        ("en", "HealthEcon Ch.2 RAND实验机制", "rephrase", [{"id": "S1", "doc_id": "he", "pdf_page": 42, "text": he_pages[42][:2200]}], first_sentence(he_pages[42], 40), None, False),
        ("en", "HealthEcon Ch.3 Grossman长难句拆句", "split", [{"id": "S1", "doc_id": "he", "pdf_page": 62, "text": he_pages[62][:2200]}], first_sentence(he_pages[62], 40), None, False),
        ("en", "HealthEcon Ch.8 逆向选择举例", "example", [{"id": "S1", "doc_id": "he", "pdf_page": 174, "text": he_pages[174][:2200]}], first_sentence(he_pages[174], 30), None, False),
        ("zh", "鲁迅《呐喊》正文选段讲讲", "explain", [{"id": "S1", "doc_id": "nahan", "pdf_page": 3, "text": nahan_gbk_txt[120:1800]}], nahan_gbk_txt[140:210], None, False),
        ("zh", "中文复习笔记：Grossman模型追问", "ask", [{"id": "S1", "doc_id": "cn_w2", "pdf_page": 1, "text": cn_w2[:1400]}], cn_w2[60:150], "为什么健康既有消费属性又有投资属性？", False),
        ("en", "国富论 分工章节讲解 (P4工资路径对比)", "explain", [{"id": "S1", "doc_id": "smith", "pdf_page": 6, "text": smith_pages[min(5, len(smith_pages)-1)]["text"][:2200]}], first_sentence(smith_pages[min(5, len(smith_pages)-1)]["text"], 40), None, False),
        ("en", "TEACH 实时纯文本追问 (道德风险)", "TEACH", [{"id": "S1", "doc_id": "he", "pdf_page": 236, "text": he_pages[236][:2200]}], "", "请用简单中文解释什么是 ex-ante moral hazard，并结合文本举例。", False),
        # Holdout
        ("en", "HealthEcon Ch.14 成本效果优势判定 (Holdout)", "explain", [{"id": "S1", "doc_id": "he", "pdf_page": 311, "text": he_pages[311][:2200]}], first_sentence(he_pages[311], 40), None, False),
        ("zh", "呐喊：依据不足拒答边界测试 (Holdout)", "ask", [{"id": "S1", "doc_id": "nahan", "pdf_page": 3, "text": nahan_gbk_txt[120:1000]}], nahan_gbk_txt[140:200], "请给出本文作者的出生年份和现代经济学回归系数。", True),
    ]
    for i, (lg, ttl, sub, pgs, qt, qst, exp_empty) in enumerate(g1_specs, 1):
        add("G1", i, lg, ttl, {"subtask": sub, "pages": pgs, "quote": qt, "question": qst, "expect_empty": exp_empty, "judge_context": f"Subtask={sub}; Target={ttl}"})

    # ================= G2: 整门课与跨书问答 (8 core + 2 holdout) =================
    w4_s5 = pdftotext_page(w4_slides, 5)
    w4_s12 = pdftotext_page(w4_slides, 12)
    w4_key1 = pdftotext_page(w4_tut_key, 1)
    w2_s6 = pdftotext_page(w2_slides, 6)
    w5_s6 = pdftotext_page(w5_slides, 6)
    w6_s5 = pdftotext_page(w6_slides, 5)
    arrow_p1 = pdftotext_page(arrow_pdf, 1)

    g2_specs = [
        ("en", "Akerlof柠檬市场讲义与教材跨页定位", "ECNM10119", "请解释二手车市场何时会发生完全崩溃（market unraveling），并注明页码依据。",
         [{"blockId": "B1", "bookId": "slides-w4", "docLabel": "Week 4 Slides", "page": 12, "authorityRank": 1, "text": w4_s12[:1800]},
          {"blockId": "B2", "bookId": "he", "docLabel": "Health Economics", "page": 180, "authorityRank": 2, "text": he_pages[180][:1800]}],
         [12, 180], False),
        ("en", "讲义与习题课答案系数分歧检测", "ECNM10119", "Akerlof 模型中买家相对卖家数量或估值比率在材料中有没有分歧或边界条件？",
         [{"blockId": "B1", "bookId": "slides-w4", "docLabel": "Week 4 Slides", "page": 5, "authorityRank": 1, "text": w4_s5[:1800]},
          {"blockId": "B2", "bookId": "tut-w4-key", "docLabel": "Week 4 Tutorial Key", "page": 1, "authorityRank": 1, "text": w4_key1[:1800]},
          {"blockId": "B3", "bookId": "he", "docLabel": "Health Economics", "page": 178, "authorityRank": 2, "text": he_pages[178][:1800]}],
         [1, 178], False),
        ("en", "Grossman模型：消费品 vs 投资品跨源合成", "ECNM10119", "Grossman 模型里健康为什么同时具备消费品和投资品双重属性？",
         [{"blockId": "B1", "bookId": "slides-w2", "docLabel": "Week 2 Slides", "page": 6, "authorityRank": 1, "text": w2_s6[:1800]},
          {"blockId": "B2", "bookId": "he", "docLabel": "Health Economics", "page": 62, "authorityRank": 2, "text": he_pages[62][:1800]}],
         [6, 62], False),
        ("en", "经典书单跨书对比：Smith vs Ricardo 分工与价值", "Classical Economics", "亚当·斯密和大卫·李嘉图对劳动与价值/分工的核心关注点各是什么？",
         [{"blockId": "B1", "bookId": "smith", "docLabel": "Wealth of Nations", "page": 6, "authorityRank": 2, "text": smith_pages[min(5, len(smith_pages)-1)]["text"][:1800]},
          {"blockId": "B2", "bookId": "ricardo", "docLabel": "Principles of Political Economy", "page": 5, "authorityRank": 2, "text": ricardo_pages[min(4, len(ricardo_pages)-1)]["text"][:1800]}],
         [5, 6], False),
        ("zh", "中文复习笔记：Akerlof 2.5倍 vs 1.5倍推导与课堂转写", "ECNM10119", "在柠檬市场模型中卖家数量与临界价格条件是如何推导的？课堂强调了什么？",
         [{"blockId": "B1", "bookId": "cn-w4", "docLabel": "中文复习Week4", "page": 1, "authorityRank": 4, "text": cn_w4[:1800]},
          {"blockId": "B2", "bookId": "trans-w4", "docLabel": "Week4 课堂转写", "page": 1, "authorityRank": 1, "timestampLabel": "00:14:20", "text": w4_trans[:1800]}],
         [1], False),
        ("en", "Rothschild-Stiglitz 混同均衡存在性", "ECNM10119", "为什么在 Rothschild-Stiglitz 模型中不存在稳定的混同均衡（pooling equilibrium）？",
         [{"blockId": "B1", "bookId": "slides-w5", "docLabel": "Week 5 Slides", "page": 6, "authorityRank": 1, "text": w5_s6[:1800]},
          {"blockId": "B2", "bookId": "he", "docLabel": "Health Economics", "page": 195, "authorityRank": 2, "text": he_pages[195][:1800]}],
         [6, 195], False),
        ("en", "Arrow(1963) 必读论文 vs 教材医疗市场特殊性", "ECNM10119", "Arrow(1963) 和教材第一章是如何说明医疗市场不同于普通商品市场的？",
         [{"blockId": "B1", "bookId": "arrow1963", "docLabel": "Arrow 1963", "page": 1, "authorityRank": 3, "text": arrow_p1[:1800]},
          {"blockId": "B2", "bookId": "he", "docLabel": "Health Economics", "page": 34, "authorityRank": 2, "text": he_pages[34][:1800]}],
         [1, 34], False),
        ("en", "道德风险：讲义与教材一致但含模拟冲突测试", "ECNM10119", "保险中的事前道德风险与事后道德风险有何区别？教材和讲义是否一致？",
         [{"blockId": "B1", "bookId": "slides-w6", "docLabel": "Week 6 Slides", "page": 5, "authorityRank": 1, "text": w6_s5[:1800]},
          {"blockId": "B2", "bookId": "he", "docLabel": "Health Economics", "page": 236, "authorityRank": 2, "text": he_pages[236][:1800]}],
         [5, 236], False),
        # Holdout
        ("en", "跨书对比：Malthus 人口原理与 Smith 工资增长 (Holdout)", "Classical Economics", "马尔萨斯和亚当·斯密对人口增长与生活资料之间关系的判断有何异同？",
         [{"blockId": "B1", "bookId": "malthus", "docLabel": "Principle of Population", "page": 5, "authorityRank": 2, "text": malthus_pages[min(4, len(malthus_pages)-1)]["text"][:1800]},
          {"blockId": "B2", "bookId": "smith", "docLabel": "Wealth of Nations", "page": 7, "authorityRank": 2, "text": smith_pages[min(6, len(smith_pages)-1)]["text"][:1800]}],
         [5, 7], False),
        ("zh", "中文笔记与教材数字核验冲突识别 (Holdout)", "ECNM10119", "关于医疗保险需求和价格弹性，不同材料给出的核心发现是什么？",
         [{"blockId": "B1", "bookId": "cn-w5", "docLabel": "中文复习Week5-6", "page": 1, "authorityRank": 4, "text": cn_w5[:1000]},
          {"blockId": "B2", "bookId": "he", "docLabel": "Health Economics", "page": 42, "authorityRank": 2, "text": he_pages[42][:1800]}],
         [1, 42], False),
    ]
    for i, (lg, ttl, ctitle, qst, blks, gpgs, conf) in enumerate(g2_specs, 1):
        add("G2", i, lg, ttl, {"collection_title": ctitle, "question": qst, "blocks": blks, "gold_pages": gpgs, "expected_conflict": conf, "judge_context": f"Q={qst}"})

    # ================= G3: 章节伴读和六种学法预生成 (8 core + 2 holdout) =================
    g3_specs = [
        ("en", "章节伴读Batch：HealthEcon Ch.8 Akerlof", "BOOK_SECTION_BATCH", None, "8 Adverse selection", "8.2 The market for lemons",
         [{"id": "b1", "pdfPage": 178, "text": he_pages[178][:2000]}, {"id": "b2", "pdfPage": 180, "text": he_pages[180][:2000]}]),
        ("en", "章节伴读Batch：HealthEcon Ch.3 Grossman", "BOOK_SECTION_BATCH", None, "3 Grossman model", "3.1 Production of health",
         [{"id": "b1", "pdfPage": 62, "text": he_pages[62][:2000]}, {"id": "b2", "pdfPage": 66, "text": he_pages[66][:2000]}]),
        ("en", "学法1 READ_THROUGH：Ch.7 保险需求", "STUDY_MODE", "READ_THROUGH", "7 Demand for insurance", "Demand for insurance",
         [{"pdfPage": 160, "text": he_pages[160][:2200]}]),
        ("en", "学法2 BOOK_MAP：Ch.1 为什么学卫生经济学", "STUDY_MODE", "BOOK_MAP", "1 Why health economics?", "Overview",
         [{"pdfPage": 32, "text": he_pages[32][:1600]}, {"pdfPage": 34, "text": he_pages[34][:1600]}]),
        ("en", "学法3 EXAM_FOCUS：Ch.9 Rothschild-Stiglitz", "STUDY_MODE", "EXAM_FOCUS", "9 Rothschild-Stiglitz", "Equilibrium",
         [{"pdfPage": 195, "text": he_pages[195][:2200]}]),
        ("en", "学法4 DIAGNOSTIC_SKIP：Ch.2 RAND实验", "STUDY_MODE", "DIAGNOSTIC_SKIP", "2 Demand for health care", "RAND HIE",
         [{"pdfPage": 42, "text": he_pages[42][:2200]}]),
        ("en", "学法5 PROBLEM_DRIVEN：Ch.8 柠檬市场计算", "STUDY_MODE", "PROBLEM_DRIVEN", "8 Adverse selection", "Market clearing",
         [{"pdfPage": 174, "text": he_pages[174][:1600]}, {"pdfPage": 180, "text": he_pages[180][:1600]}]),
        ("zh", "学法6 QUESTION_DRIVEN：鲁迅《呐喊》自序", "STUDY_MODE", "QUESTION_DRIVEN", "《呐喊》自序", "写作动因",
         [{"pdfPage": 3, "text": nahan_gbk_txt[120:2200]}]),
        # Holdout
        ("en", "章节伴读Batch：HealthEcon Ch.11 道德风险 (Holdout)", "BOOK_SECTION_BATCH", None, "11 Moral hazard", "11.1 Ex-ante and ex-post",
         [{"id": "b1", "pdfPage": 236, "text": he_pages[236][:2000]}, {"id": "b2", "pdfPage": 240, "text": he_pages[240][:2000]}]),
        ("en", "学法3 EXAM_FOCUS：Ch.14 HTA成本效果 (Holdout)", "STUDY_MODE", "EXAM_FOCUS", "14 Health technology assessment", "Cost-effectiveness",
         [{"pdfPage": 311, "text": he_pages[311][:1800]}, {"pdfPage": 315, "text": he_pages[315][:1800]}]),
    ]
    for i, (lg, ttl, sub, mode, cttl, sttl, data) in enumerate(g3_specs, 1):
        payload = {"subtask": sub, "book_title": "Health Economics" if lg == "en" else "呐喊", "chapter_num": i, "chapter_title": cttl, "section_title": sttl}
        if sub == "BOOK_SECTION_BATCH":
            payload["blocks"] = data
        else:
            payload["study_mode"] = mode
            payload["pages"] = data
        payload["judge_context"] = f"{sub} ({mode or 'section'}): {ttl}"
        add("G3", i, lg, ttl, payload)

    # ================= G4: 书的结构和目录识别 (8 core + 2 holdout) =================
    def pdf_snippets(pdf_p: Path, pages_list: list[int], max_chars: int = 260) -> list[dict]:
        res = []
        for p in pages_list:
            txt = pdftotext_page(pdf_p, p).replace("\n", " ")
            res.append({"page": p, "snippet": txt[:max_chars]})
        return res

    he_snips = pdf_snippets(he_pdf, [1, 2, 10, 12, 13, 14, 15, 19, 32, 39, 59, 82, 109, 131, 157, 172], 320)
    vl_snips = pdf_snippets(large_pdf, [1, 2, 3, 4, 5, 6, 7, 15, 25], 220)
    w1_snips = pdf_snippets(w1_slides, list(range(1, 12)), 220)
    nahan_utf8_snips = [{"page": i + 1, "snippet": nahan_txt[i * 1500 : i * 1500 + 260].replace("\n", " ")} for i in range(10)]
    nahan_gbk_snips = [{"page": i + 1, "snippet": nahan_gbk_txt[i * 1500 : i * 1500 + 260].replace("\n", " ")} for i in range(10)]

    g4_specs = [
        ("en", "620页 Health Economics 目录与正文起点", "pdf", 620, 20, he_snips, 32, 24, ["Why health economics", "Demand for health care", "Grossman"]),
        ("zh", "鲁迅《呐喊》EPUB (自带破损NCX测试)", "epub", len(nahan_pages), 2, [{"page": p["page"], "snippet": p["text"][:280]} for p in nahan_pages], 1, 5, ["自序", "狂人日记"]),
        ("zh", "鲁迅《呐喊》UTF-8 TXT (Gutenberg英文版权头)", "txt", 10, 0, nahan_utf8_snips, 2, 4, ["自序", "狂人日记"]),
        ("zh", "鲁迅《呐喊》GBK TXT (P5边缘样本)", "txt", 10, 0, nahan_gbk_snips, 1, 4, ["自序"]),
        ("en", "Wealth of Nations EPUB (跳过封面与目录)", "epub", len(smith_pages), 63, [{"page": p["page"], "snippet": p["text"][:280]} for p in smith_pages], 3, 8, ["INTRODUCTION", "DIVISION OF LABOUR"]),
        ("en", "Principle of Population Malthus EPUB", "epub", len(malthus_pages), 24, [{"page": p["page"], "snippet": p["text"][:280]} for p in malthus_pages], 5, 5, ["CHAPTER 1", "CHAPTER 2"]),
        ("en", "Outline of Science Vol1 (313项插图目录陷阱)", "epub", len(outline_pages), 313, [{"page": p["page"], "snippet": p["text"][:280]} for p in outline_pages], 7, 4, ["ROMANCE OF THE HEAVENS"]),
        ("en", "1349页超大PDF P5-very-large-classics 前9页", "pdf", 1349, 3, vl_snips, 1, 3, ["Wealth of Nations"]),
        # Holdout
        ("en", "Ricardo Political Economy EPUB (Holdout)", "epub", len(ricardo_pages), 58, [{"page": p["page"], "snippet": p["text"][:280]} for p in ricardo_pages], 6, 4, ["CHAPTER I"]),
        ("en", "ECNM10119 Week1 课件PDF (Holdout)", "pdf", 30, 0, w1_snips, 1, 3, ["Health Economics"]),
    ]
    for i, (lg, ttl, fmt, tpg, ocnt, snips, gstart, gcnt, gtitles) in enumerate(g4_specs, 1):
        add("G4", i, lg, ttl, {
            "book_title": ttl, "format": fmt, "total_pages": tpg, "outline_count": ocnt,
            "page_snippets": snips, "gold_body_start_page": gstart, "gold_chapter_count": gcnt, "gold_sample_titles": gtitles,
            "judge_context": f"Format={fmt}, GoldStart=p.{gstart}, GoldChapters~{gcnt}",
        })

    # ================= G5: 出题和答案键 (8 core + 2 holdout) =================
    g5_specs = [
        ("en", "MCQ出题：Ch.1 医疗不确定性", "gen_mcq", "Uncertainty in health care demand", he_pages[34][:1800], None, "English"),
        ("en", "MCQ出题：Ch.2 RAND实验道德风险", "gen_mcq", "RAND Health Insurance Experiment price elasticity", he_pages[42][:1800], None, "English"),
        ("en", "MCQ出题：Ch.7 预期效用与风险厌恶", "gen_mcq", "Expected utility and demand for insurance", he_pages[160][:1800], None, "English"),
        ("en", "MCQ出题：Ch.8 Akerlof 柠檬市场均衡", "gen_mcq", "Adverse selection market unraveling", he_pages[180][:1800], None, "English"),
        ("en", "MCQ出题：Ch.9 Rothschild-Stiglitz 契约线", "gen_mcq", "Separating vs pooling insurance contracts", he_pages[195][:1800], None, "English"),
        ("zh", "中文MCQ出题：Grossman 模型与健康资本", "gen_mcq", "Grossman健康生产模型", cn_w2[:1400], None, "Simplified Chinese"),
        ("en", "全真模考大题：Ch.8 柠檬市场计算与作图", "course_mock_item", "Akerlof equilibrium price & market breakdown", "", [{"docId": "he", "pdfPage": 174, "text": he_pages[174][:1400]}, {"docId": "he", "pdfPage": 180, "text": he_pages[180][:1400]}], "English"),
        ("en", "全真模考大题：Ch.11 道德风险政策分析", "course_mock_item", "Ex-ante vs ex-post moral hazard & coinsurance", "", [{"docId": "he", "pdfPage": 236, "text": he_pages[236][:1800]}], "English"),
        # Holdout
        ("en", "MCQ出题：Ch.14 增量成本效果比 (Holdout)", "gen_mcq", "Cost-effectiveness dominance and ICER", he_pages[311][:1800], None, "English"),
        ("zh", "中文MCQ出题：Akerlof 逆向选择推导 (Holdout)", "gen_mcq", "柠檬市场2.5倍条件", cn_w4[:1400], None, "Simplified Chinese"),
    ]
    for i, (lg, ttl, sub, kp, exc, pgs, qlang) in enumerate(g5_specs, 1):
        d = {"subtask": sub, "kp": kp, "question_lang": qlang, "count": 2, "judge_context": f"{sub}: {kp}"}
        if sub == "gen_mcq":
            d["excerpt"] = exc
        else:
            d["pages"] = pgs
            d["marks"] = 20
        add("G5", i, lg, ttl, d)

    # ================= G6: 逐点批改与诊断 (8 core + 2 holdout) =================
    rubric_akerlof = [
        {"cid": "R1", "slotLabel": "供给条件：仅质量低于P的车进入市场", "maxMarks": 4.0},
        {"cid": "R2", "slotLabel": "买方估值与均衡判定（平均质量P/2与价格对比）", "maxMarks": 6.0},
    ]
    rubric_grossman = [
        {"cid": "R1", "slotLabel": "健康作为消费品直接带来效用", "maxMarks": 5.0},
        {"cid": "R2", "slotLabel": "健康作为投资品减少病假增加工作收入", "maxMarks": 5.0},
    ]
    g6_specs = [
        ("en", "逐点批改：Akerlof部分正确作答", "practice_grade", "Explain why the used-car market can unravel in Akerlof's model.",
         rubric_akerlof, [{"pdfPage": 174, "text": he_pages[174][:1500]}, {"pdfPage": 180, "text": he_pages[180][:1500]}],
         "Sellers only offer cars worth less than the market price P. Buyers know the average quality is P/2, so if buyers value cars at twice the seller value, trade still happens.", 7.0, None),
        ("en", "逐点批改：Grossman漏答投资属性", "practice_grade", "Explain the dual role of health in the Grossman model.",
         rubric_grossman, [{"pdfPage": 62, "text": he_pages[62][:1600]}],
         "Health directly enters the utility function because people feel happier when they are healthy and avoid sick days.", 6.0, None),
        ("zh", "逐点批改：中文作答道德风险（防代写红线）", "practice_grade", "请说明保险中的事前道德风险与事后道德风险的区别。",
         [{"cid": "R1", "slotLabel": "事前道德风险：投保后降低预防努力", "maxMarks": 5.0}, {"cid": "R2", "slotLabel": "事后道德风险：生病后过度医疗消费", "maxMarks": 5.0}],
         [{"pdfPage": 236, "text": he_pages[236][:1500]}],
         "投保后大家更不注意锻炼和预防疾病，这就是事前道德风险；至于事后道德风险我记不太清了。", 5.0, None),
        ("en", "错因诊断：SIGN_OR_FORMULA (符号方向错误)", "practice_diagnose", "State the market participation rule for sellers in Akerlof's lemons model.",
         [], [{"pdfPage": 174, "text": he_pages[174][:1400]}],
         "A seller puts their car on the market only when its true quality value X is greater than the market price P (X > P).", 0.0, "SIGN_OR_FORMULA"),
        ("en", "错因诊断：CONCEPT_CONFUSION (混淆逆向选择与道德风险)", "practice_diagnose", "Why does insurance coverage lead to higher medical utilization after illness?",
         [], [{"pdfPage": 236, "text": he_pages[236][:1400]}],
         "This happens because of adverse selection: sicker individuals secretly hide their illness before buying insurance.", 0.0, "CONCEPT_CONFUSION"),
        ("en", "错因诊断：ARITHMETIC_OR_ALGEBRA (算术失误)", "practice_diagnose", "If seller values are uniform on [0, 100] and buyers value cars at 2.5x seller value, find expected buyer value when cutoff is P=40.",
         [], [{"pdfPage": 180, "text": he_pages[180][:1400]}],
         "Since uniform on [0, 40] has average seller value 40/2 = 20, the buyer valuation is 2.5 * 20 = 35.", 0.0, "ARITHMETIC_OR_ALGEBRA"),
        ("en", "错因诊断：NONE (等价正确表述不误杀)", "practice_diagnose", "Why does RAND HIE show moral hazard in medical care?",
         [], [{"pdfPage": 42, "text": he_pages[42][:1400]}],
         "Patients facing lower coinsurance rates consume significantly more medical services because their out-of-pocket price per visit is smaller.", 0.0, "NONE"),
        ("en", "错因诊断：GRAPH_SHIFT_OR_ROTATION (曲线移动方向错)", "practice_diagnose", "On the Rothschild-Stiglitz indifference map, how do high-risk and low-risk indifference curves differ?",
         [], [{"pdfPage": 195, "text": he_pages[195][:1400]}],
         "Low-risk individuals have steeper indifference curves than high-risk individuals because they are willing to pay more for extra coverage.", 0.0, "GRAPH_SHIFT_OR_ROTATION"),
        # Holdout
        ("en", "逐点批改：保险预期效用带拼写错误 (Holdout)", "practice_grade", "Why do risk-averse consumers buy full insurance at actuarially fair price?",
         [{"cid": "R1", "slotLabel": "凹效用函数体现风险厌恶", "maxMarks": 5.0}, {"cid": "R2", "slotLabel": "公平保费下完全保险消除不确定性提升期望效用", "maxMarks": 5.0}],
         [{"pdfPage": 160, "text": he_pages[160][:1500]}],
         "Because their utility function is concve, a guaranteed income gives higher expected utility than facing uncertain sick and helthy incomes at fair price.", 9.0, None),
        ("zh", "错因诊断：中文表述概念混淆 (Holdout)", "practice_diagnose", "为什么竞争市场中不存在混同均衡？",
         [], [{"pdfPage": 195, "text": he_pages[195][:1500]}],
         "因为道德风险的存在导致保险公司无法观察投保人之后的运动习惯，所以混同均衡会崩溃。", 0.0, "CONCEPT_CONFUSION"),
    ]
    for i, (lg, ttl, sub, stem, rub, spgs, sans, gtot, gcls) in enumerate(g6_specs, 1):
        add("G6", i, lg, ttl, {
            "subtask": sub, "question_stem": stem, "rubric_criteria": rub, "source_pages": spgs,
            "student_answer": sans, "gold_awarded_total": gtot, "gold_error_class": gcls,
            "judge_context": f"{sub}: {stem[:100]}",
        })

    # ================= G7: 课程规划、考前排程与模考规划 (8 core + 2 holdout) =================
    hb_txt = "\n".join(hb_pages[p] for p in sorted(hb_pages))
    topics_pool = [
        {"topicId": "t1", "title": "Week 1: Health Care Demand & RAND HIE", "examStatus": "EXAMINABLE", "pMastery": 0.65, "dominantErrorTags": []},
        {"topicId": "t2", "title": "Week 2: The Grossman Model", "examStatus": "EXAMINABLE", "pMastery": 0.42, "dominantErrorTags": ["SIGN_OR_FORMULA"]},
        {"topicId": "t3", "title": "Week 4: Adverse Selection & Akerlof", "examStatus": "EXAMINABLE", "pMastery": 0.35, "dominantErrorTags": ["ARITHMETIC_OR_ALGEBRA"]},
        {"topicId": "t4", "title": "Week 5: Rothschild-Stiglitz Model", "examStatus": "EXAMINABLE", "pMastery": 0.30, "dominantErrorTags": ["GRAPH_SHIFT_OR_ROTATION"]},
        {"topicId": "t5", "title": "Week 6: Moral Hazard", "examStatus": "EXAMINABLE", "pMastery": 0.55, "dominantErrorTags": ["CONCEPT_CONFUSION"]},
        {"topicId": "t6", "title": "Chapter 22: Obesity (Excluded)", "examStatus": "EXCLUDED", "pMastery": 0.10, "dominantErrorTags": []},
    ]
    for i in range(1, 11):
        lg = "zh" if i in (3, 7) else "en"
        if i % 2 == 1:
            # course_align
            payload = {
                "courseId": f"ecnm10119-{i}",
                "courseTitle": "ECNM10119 Health Economics" if lg == "en" else "卫生经济学课程规划",
                "materials": [
                    {"materialId": "m-hb", "title": "Module Handbook", "role": "SYLLABUS", "weekNum": None, "userLocked": True, "pageCount": 6, "previewText": hb_txt[:1100]},
                    {"materialId": "m-w2", "title": "Week 2 Lecture Slides", "role": "LECTURE_SLIDES", "weekNum": 2, "userLocked": True, "pageCount": 25, "previewText": w2_s6[:900]},
                    {"materialId": "m-w4", "title": "Week 4 Lecture Slides", "role": "LECTURE_SLIDES", "weekNum": None, "userLocked": False, "pageCount": 28, "previewText": w4_s12[:900]},
                    {"materialId": "m-cn", "title": "期中复习笔记", "role": "USER_NOTES", "weekNum": None, "userLocked": False, "pageCount": 4, "previewText": cn_w4[:800]},
                ],
                "textbookChapters": [
                    {"chapterNum": 1, "title": "Why health economics?"},
                    {"chapterNum": 3, "title": "Demand for health: Grossman"},
                    {"chapterNum": 8, "title": "Adverse selection: Akerlof"},
                    {"chapterNum": 22, "title": "Obesity (not in syllabus)"},
                ],
            }
            add("G7", i, lg, f"课程结构对齐样本 #{i}", {"subtask": "course_align", "payload": payload, "judge_context": f"course_align #{i}"})
        else:
            # course_mock_plan
            payload = {
                "courseId": f"ecnm10119-mock-{i}",
                "examProfile": {"durationMinutes": 90 + (i * 10) % 30, "closedBook": True, "calculatorAllowed": True, "examFormat": "Midterm: 1 Short Essay + 2 Analytical/Calculation Problems"},
                "topics": topics_pool[: (4 if i < 9 else 5)] + [topics_pool[-1]],
                "excludedTopicTitles": ["Chapter 22: Obesity (Excluded)", "Obesity"],
            }
            add("G7", i, lg, f"全真模考蓝图规划样本 #{i}", {"subtask": "course_mock_plan", "payload": payload, "excluded_titles": ["t6", "obesity", "chapter 22: obesity (excluded)"], "judge_context": f"course_mock_plan #{i}"})

    # ================= G8a: 课件对齐与书单主题图 (8 core + 2 holdout) =================
    for i in range(1, 11):
        if i <= 5 or i == 9:
            # booklist_concept_align (including boilerplate traps like #19!)
            lg = "zh" if i in (4, 5) else "en"
            cands = [
                {"pairId": "p1", "termA": "Project Gutenberg License", "ctxA": nahan_txt[:220], "termB": "Copyright Notice", "ctxB": smith_pages[0]["text"][:220]},
                {"pairId": "p2", "termA": "Division of Labour", "ctxA": smith_pages[min(4, len(smith_pages)-1)]["text"][:320], "termB": "Comparative Cost & Labor Value", "ctxB": ricardo_pages[min(4, len(ricardo_pages)-1)]["text"][:320]},
                {"pairId": "p3", "termA": "market", "ctxA": "The market price fluctuates daily.", "termB": "market", "ctxB": "He went to the market in town."},
            ]
            if i >= 4:
                cands.append({"pairId": "p4", "termA": "Population Check", "ctxA": malthus_pages[min(4, len(malthus_pages)-1)]["text"][:300], "termB": "Subsistence Wage", "ctxB": ricardo_pages[min(5, len(ricardo_pages)-1)]["text"][:300]})
            gkeep = {"p1": False, "p2": True, "p3": False}
            if len(cands) == 4:
                gkeep["p4"] = True
            add("G8a", i, lg, f"书单跨书概念对齐(含版权套话过滤) #{i}", {
                "subtask": "booklist_concept_align",
                "payload": {"collectionTitle": "Classical Economics & Literature", "candidates": cands},
                "gold_keep": gkeep,
                "judge_context": f"booklist_align #{i}: must reject p1/p3 boilerplate, keep p2",
            })
        else:
            # course_topic_map
            lg = "en" if i != 10 else "zh"
            blks = [
                {"bookId": "slides-w4", "bookTitle": "Week 4 Slides", "role": "LECTURE_SLIDES", "authorityRank": 1, "page": 12, "startUtf16": 0, "text": w4_s12[:1400]},
                {"bookId": "he", "bookTitle": "Health Economics", "role": "CORE_TEXTBOOK", "authorityRank": 2, "page": 180, "startUtf16": 0, "text": he_pages[180][:1400]},
            ]
            if i == 10:
                blks.append({"bookId": "cn-w4", "bookTitle": "中文复习", "role": "USER_NOTES", "authorityRank": 4, "page": 1, "startUtf16": 0, "text": cn_w4[:1200]})
            add("G8a", i, lg, f"多源课件-教材主题归并样本 #{i}", {
                "subtask": "course_topic_map",
                "payload": {"topicId": f"topic-{i}", "topicTitle": "Adverse Selection & Lemons", "blocks": blks},
                "judge_context": f"course_topic_map #{i}",
            })

    # ================= G8b: 视觉看图与扫描件OCR (8 core + 2 holdout) =================
    g8b_specs = [
        ("zh", "扫描件《呐喊》p.1 繁体/封面页", nahan_scan, 1, ["魯迅", "吶喊"], ["魯", "吶"]),
        ("zh", "扫描件《呐喊》p.2 自序繁体保留测试", nahan_scan, 2, ["自序", "夢", "寂寞"], ["夢", "時", "來"]),
        ("zh", "扫描件《呐喊》p.3 繁体正文识别", nahan_scan, 3, ["藥店", "父親", "櫃台"], ["藥", "櫃", "親"]),
        ("zh", "扫描件《呐喊》p.4 繁体正文识别", nahan_scan, 4, ["S會館", "槐樹", "古碑"], ["館", "樹", "寫"]),
        ("en", "HealthEcon p.178 图8.2 柠檬市场分布图", he_pdf, 178, ["Figure 8.2", "Adverse selection", "100"], []),
        ("en", "HealthEcon p.195 图9.3 Rothschild-Stiglitz无差异曲线", he_pdf, 195, ["Figure 9.3", "C1", "C2", "E"], []),
        ("en", "Week 4 讲义 p.12 Akerlof 均衡公式页", w4_slides, 12, ["Akerlof", "price", "quality"], []),
        ("en", "Week 2 讲义 p.6 Grossman 模型图式页", w2_slides, 6, ["Grossman", "health"], []),
        # Holdout
        ("zh", "扫描件《呐喊》p.5 繁体识别 (Holdout)", nahan_scan, 5, ["金心異", "鐵屋子"], ["鐵", "異"]),
        ("en", "HealthEcon p.62 表3.1 Grossman活动表 (Holdout)", he_pdf, 62, ["Table 3.1", "Grossman"], []),
    ]
    for i, (lg, ttl, pdf_src, pno, gterms, gtrad) in enumerate(g8b_specs, 1):
        img = pdftoppm_render(pdf_src, pno, rendered_dir / f"g8b_{i:02d}")
        add("G8b", i, lg, ttl, {
            "image_path": str(img),
            "gold_terms": gterms,
            "gold_trad_chars": gtrad,
            "judge_context": f"Vision OCR p.{pno} of {pdf_src.name}; terms={gterms}",
        })

    # ================= G9: 数学核验和修复 (8 core + 2 holdout) =================
    g9_specs = [
        ("en", "核验：植入Ch.8系数错误(3/2写成5/2)", "BOOK_MATH_VERIFY", [{"id": "b1", "pdfPage": 180, "text": he_pages[180][:1800]}],
         [{"itemId": "d1", "title": "Akerlof 1.5倍买方估值", "steps": ["教材说 buyers value cars 50% more than sellers，因此系数是 5/2 = 2.5 倍。"]}],
         {"d1": False}, None),
        ("en", "核验：Ch.8 正确 3/2 系数不误报", "BOOK_MATH_VERIFY", [{"id": "b1", "pdfPage": 180, "text": he_pages[180][:1800]}],
         [{"itemId": "d2", "title": "Akerlof 临界估值", "steps": ["买家估值是卖家50%溢价，即 (3/2)*(P/2) = 3P/4。"]}],
         {"d2": True}, None),
        ("en", "核验：植入Ch.8 2.5倍门槛临界P错误", "BOOK_MATH_VERIFY", [{"id": "b1", "pdfPage": 180, "text": he_pages[180][:1800]}],
         [{"itemId": "q1", "stem": "Suppose sellers outnumber buyers and buyers value at 1.5x of average quality (P+10)/2. For what P does trade hold?", "claimed": "P <= 20"}],
         {"q1": False}, None),
        ("zh", "核验：中文公式速查 价格弹性与边际成本一致性", "BOOK_MATH_VERIFY", [{"id": "b1", "pdfPage": 1, "text": cn_form}],
         [{"itemId": "c1", "title": "Akerlof 均衡", "steps": ["当买方估值为卖方 1.5 倍且最低质量为 0 时，均衡条件 (3/2)*(P/2) < P 导致市场崩溃。"]}],
         {"c1": True}, None),
        ("en", "修复：Ch.8 系数 3/2 错成 5/2 修复为唯一正确选项", "BOOK_MATH_REPAIR", [{"id": "b1", "pdfPage": 180, "text": he_pages[180][:1800]}],
         [{"itemId": "fix1", "rejectedReason": "Coefficient should reflect 50% more = 3/2 = 1.5, not 5/2. Recompute buyer expected value at P=40 when X~U[0,40] (avg=20): (3/2)*20 = 30."}],
         None, "30"),
        ("en", "修复：Grossman 折旧率与投资回报不等式修复", "BOOK_MATH_REPAIR", [{"id": "b1", "pdfPage": 62, "text": he_pages[62][:1800]}],
         [{"itemId": "fix2", "rejectedReason": "Compute net marginal return when MEC=0.12, depreciation delta=0.05, interest r=0.04: MEC - (r + delta) = 0.03."}],
         None, "0.03"),
        ("zh", "修复：中文保险期望效用与公平保费算式修复", "BOOK_MATH_REPAIR", [{"id": "b1", "pdfPage": 160, "text": he_pages[160][:1800]}],
         [{"itemId": "fix3", "rejectedReason": "生病概率 p=0.2，医疗损失 L=1000，公平保费为 p*L = 200，原题误写为 250，请按200重生成MCQ。"}],
         None, "200"),
        ("en", "核验：Ch.14 ICER 增量成本效果比分母符号错误", "BOOK_MATH_VERIFY", [{"id": "b1", "pdfPage": 311, "text": he_pages[311][:1800]}],
         [{"itemId": "ic1", "title": "ICER定义", "steps": ["ICER = (C1 - C0) / (E0 - E1)，当新疗法成本更高且效果更好时 ICER 为负。"]}],
         {"ic1": False}, None),
        # Holdout
        ("en", "核验：Ch.7 公平保费算式正确性 (Holdout)", "BOOK_MATH_VERIFY", [{"id": "b1", "pdfPage": 160, "text": he_pages[160][:1800]}],
         [{"itemId": "h1", "title": "Fair Premium", "steps": ["Actuarially fair premium equals expected payout p * L."]}],
         {"h1": True}, None),
        ("en", "修复：Akerlof Uniform[20, 60] 期望质量与 1.5倍买家出价 (Holdout)", "BOOK_MATH_REPAIR", [{"id": "b1", "pdfPage": 180, "text": he_pages[180][:1800]}],
         [{"itemId": "h2", "rejectedReason": "For X~U[20, 60], average seller value is (20+60)/2 = 40; with 50% premium (3/2), buyer max willingness to pay is (3/2)*40 = 60."}],
         None, "60"),
    ]
    for i, (lg, ttl, sub, blks, cands, gmatch, gans) in enumerate(g9_specs, 1):
        add("G9", i, lg, ttl, {
            "subtask": sub, "source_blocks": blks, "candidates": cands,
            "gold_source_matches": gmatch, "gold_expected_answer": gans,
            "judge_context": f"{sub}: {ttl}",
        })

    out_path = out_dir / "samples.json"
    out_path.write_text(json.dumps({"samples": samples}, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"total": len(samples), "path": str(out_path)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--materials-dir", required=True)
    ap.add_argument("--ecnm-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    args = ap.parse_args()
    res = build_all(Path(args.materials_dir), Path(args.ecnm_dir), Path(args.out_dir))
    print(f"Built {res['total']} samples -> {res['path']}")


if __name__ == "__main__":
    main()
