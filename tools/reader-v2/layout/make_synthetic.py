#!/usr/bin/env python3
"""Generate self-made synthetic PDFs (no real textbook content) for layout tests.

Needs reportlab; the scanned page also needs pypdfium2 + Pillow (Docling deps).
Output dir: first argument.  Files: single.pdf two_col.pdf figure_table.pdf
formula.pdf scanned.pdf rotated.pdf mixed.pdf (one page of each kind, for batch tests).
"""
import sys
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, Table, TableStyle
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib import colors

W, H = A4
SS = getSampleStyleSheet()
LOREM = ("The synthetic function f is continuous on the closed interval and therefore attains "
         "a maximum value; the argument uses a standard compactness property of the real line. ")


def para(c, text, x, y, w, style="BodyText"):
    p = Paragraph(text, SS[style])
    _, h = p.wrap(w, 800)
    p.drawOn(c, x, y - h)
    return y - h - 8


def page_single(c):
    y = para(c, "Chapter 1 Limits and Continuity", 72, H - 60, W - 144, "Heading1")
    for _ in range(5):
        y = para(c, LOREM * 3, 72, y, W - 144)


def page_two_col(c):
    para(c, "Two-column section", 60, H - 50, W - 120, "Heading2")
    for x in (60, 315):
        y = H - 90
        for _ in range(6):
            y = para(c, LOREM * 2, x, y, 220)


def page_figure_table(c):
    y = para(c, "Table 1. Squares and cubes", 72, H - 60, 400)
    t = Table([["n", "n^2", "n^3"]] + [[str(i), str(i * i), str(i ** 3)] for i in range(1, 7)], colWidths=[80] * 3)
    t.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.black)]))
    _, th = t.wrap(0, 0)
    t.drawOn(c, 72, y - th)
    y -= th + 30
    c.setFillColor(colors.lightblue); c.rect(72, y - 200, 300, 200, fill=1); c.setFillColor(colors.black)
    c.line(80, y - 190, 360, y - 20); c.line(80, y - 190, 360, y - 190)
    y = para(c, "Figure 1. A synthetic plot of a linear function.", 72, y - 210, 400)
    para(c, LOREM * 2, 72, y, W - 144)


def page_formula(c):
    y = para(c, "Definition. A sequence converges when", 72, H - 60, W - 144)
    c.setFont("Times-Italic", 16)
    c.drawString(200, y - 30, "lim (n->inf) a_n = L   and   x = (-b +/- sqrt(b^2 - 4ac)) / 2a")
    y -= 60
    para(c, LOREM * 2, 72, y, W - 144)


BUILDERS = {"single": page_single, "two_col": page_two_col,
            "figure_table": page_figure_table, "formula": page_formula}


def make(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in BUILDERS.items():
        c = canvas.Canvas(str(out / f"{name}.pdf"), pagesize=A4)
        fn(c); c.showPage(); c.save()
    # rotated page (/Rotate 90) + cropbox
    c = canvas.Canvas(str(out / "rotated.pdf"), pagesize=A4)
    page_single(c); c.setPageRotation(90); c.setCropBox((20, 20, W - 20, H - 20)); c.showPage(); c.save()
    # mixed 4-page doc
    c = canvas.Canvas(str(out / "mixed.pdf"), pagesize=A4)
    for fn in BUILDERS.values():
        fn(c); c.showPage()
    c.save()
    # scanned: rasterise single.pdf into an image-only PDF
    import pypdfium2 as pdfium
    img = pdfium.PdfDocument(str(out / "single.pdf"))[0].render(scale=1.5).to_pil().convert("L")
    img_path = out / "_scan.png"; img.save(img_path)
    c = canvas.Canvas(str(out / "scanned.pdf"), pagesize=A4)
    c.drawImage(str(img_path), 0, 0, W, H); c.showPage(); c.save()
    img_path.unlink()


if __name__ == "__main__":
    make(Path(sys.argv[1]))
