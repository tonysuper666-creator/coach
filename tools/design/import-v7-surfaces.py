"""Generate the CSS surface shadows omitted by CoachV7.kt, from the approved static HTML."""
from pathlib import Path
import re
import sys
root = Path(__file__).resolve().parents[2]
source = Path(sys.argv[1])
lines = ["// Generated from approved V7 HTML; do not hand-edit.", "package coach.design.tokens", "", "import androidx.compose.ui.graphics.Color", "import androidx.compose.ui.unit.Dp", "import androidx.compose.ui.unit.dp", "", "data class V7SurfaceShadow(val x: Dp, val y: Dp, val blur: Dp, val spread: Dp, val color: Color, val inset: Boolean)", "object V7Surfaces {"]
keys = {"sheet": "sheet-shadow", "menu": "menu-shadow", "card": "card-shadow", "option": "opt-edge", "button": "btn-edge"}
for mode in ["light", "dark"]:
    text = (source / f"html/inner-read-{mode}.html").read_text(encoding="utf-8")
    variables = dict(re.findall(r"--([a-z-]+):([^;{}]+);", text))
    lines.append(f"    object {mode.title()} {{")
    for key, css in keys.items():
        shadows = []
        for shadow in re.split(r",(?![^()]*\))", variables[css]):
            color = re.search(r"rgba\(([^)]+)\)", shadow)
            rgba = [float(n.strip()) for n in color.group(1).split(",")]
            rgb = ''.join(f'{round(n):02X}' for n in rgba[:3])
            argb = f'{round(rgba[3] * 255):02X}{rgb}'
            geometry = shadow[:color.start()] + shadow[color.end():]
            values = re.findall(r"-?[\d.]+", geometry)
            values += ["0"] * (4 - len(values))
            shadows.append("V7SurfaceShadow(" + ", ".join(f"{v}.dp" for v in values[:4]) + f", Color(0x{argb}), {str('inset' in shadow).lower()})")
        lines.append(f"        val {key} = listOf({', '.join(shadows)})")
    lines.append("    }")
lines += ["    fun shadows(kind: String, dark: Boolean): List<V7SurfaceShadow> = when (kind) {"]
for key in keys:
    lines.append(f'        "{key}" -> if (dark) Dark.{key} else Light.{key}')
lines += ["        else -> if (dark) Dark.card else Light.card", "    }", "}", ""]
(root / "shared/ui/src/commonMain/kotlin/coach/design/tokens/V7Surfaces.kt").write_text('\n'.join(lines), encoding="utf-8", newline="\n")
print("Imported CSS surface shadows from approved V7 HTML.")
