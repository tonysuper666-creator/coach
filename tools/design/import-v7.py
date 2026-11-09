"""Import approved V7 tokens and OFL fonts without retyping values.

Usage: python tools/design/import-v7.py D:/agent/design/ui-v7
FontChain's Android R references are implemented in V7Fonts.android.kt.
"""
from pathlib import Path
import json
import re
import sys
import subprocess
from fontTools import subset
from fontTools.ttLib import TTFont

root = Path(__file__).resolve().parents[2]
source = Path(sys.argv[1])

def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")

common = root / "shared/ui/src/commonMain/kotlin/coach/design/tokens"
token = (source / "tokens/CoachV7.kt").read_text(encoding="utf-8")
write(common / "CoachV7.kt", token[:token.index("    object FontChain")] + "}\n")
subprocess.run([sys.executable, str(Path(__file__).with_name("import-v7-surfaces.py")), str(source)], check=True)
data = json.loads((source / "tokens/V7.json").read_text(encoding="utf-8"))
write(root / "tools/design/V7.json", json.dumps(data, ensure_ascii=False, indent=2) + "\n")
metrics = ["// Generated from approved V7.json; rerun tools/design/import-v7.py.",
           "package coach.design.tokens", "", "import androidx.compose.ui.unit.dp", "", "object V7Windows {"]
for name in ("inner", "outerL", "outer"):
    metrics.append(f"    object {name[0].upper() + name[1:]} {{")
    for key, value in data["size"][name]["layout"].items():
        camel = key.split("-")[0] + "".join(p.title() for p in key.split("-")[1:])
        metrics.append(f"        val {camel} = {value}.dp")
    metrics.append("    }")
metrics += ["}", ""]
write(common / "V7Windows.kt", "\n".join(metrics))
paragraph = float(data["paragraph"].removesuffix("em"))
display = re.search(r"--fs-display:([0-9.]+)px", (source / "html/inner-week-light.html").read_text(encoding="utf-8")).group(1)
cover_display = re.search(r"\.d-outer \.wkn\{font-size:([0-9.]+)px\}", (source / "variants.mjs.ref").read_text(encoding="utf-8")).group(1)
write(common / "V7Reading.kt", f"""// Generated from approved V7.json and HTML; do not hand-edit.
package coach.design.tokens

import androidx.compose.ui.unit.sp

object V7Reading {{
    const val paragraphEm = {paragraph}f
    val weekDisplay = {display}.sp
    val coverDisplay = {cover_display}.sp
}}
""")
res = root / "shared/ui/src/androidMain/res/font"
res.mkdir(parents=True, exist_ok=True)
fonts = {"literata": "Literataopsz-wght.ttf", "ibm_plex_sans": "IBMPlexSanswdth-wght.ttf",
         "ibm_plex_mono": "ibmplexmono_IBMPlexMono-Regular.ttf",
         "noto_serif_sc": "NotoSerifSC-subset.ttf", "noto_sans_sc": "NotoSansSC-subset.ttf",
         "lxgw_wenkai": "LXGWWenKai-subset.ttf"}
for name, original in fonts.items():
    output = res / f"{name}.ttf"
    output.write_bytes((source / "fonts" / original).read_bytes())
    if name in ("literata", "ibm_plex_sans", "ibm_plex_mono"):
        font = TTFont(output, recalcTimestamp=False)
        excluded = {0xB7, 0x2014, *range(0x2018, 0x2020), 0x2026}
        options = subset.Options()
        options.name_IDs = ["*"]
        sub = subset.Subsetter(options=options)
        sub.populate(unicodes=set(font.getBestCmap()) - excluded)
        sub.subset(font)
        # Respect OFL reserved names for these modified, app-specific subsets.
        family = {"literata": "Coach V7 Reading", "ibm_plex_sans": "Coach V7 Sans", "ibm_plex_mono": "Coach V7 Mono"}[name]
        for record in font["name"].names:
            if record.nameID in (1, 4, 6, 16):
                renamed = family.replace(" ", "") if record.nameID == 6 else family
                record.string = renamed.encode(record.getEncoding(), errors="replace")
        font.save(output)
licenses = root / "shared/ui/src/androidMain/assets/font-licenses"
licenses.mkdir(parents=True, exist_ok=True)
for file in (source / "fonts").glob("*_OFL.txt"):
    (licenses / file.name).write_bytes(file.read_bytes())
for mode, directory in [("light", "values"), ("dark", "values-night")]:
    background = data["color"][mode]["sheet"]["hex"]
    parent = "Theme.Material.Light.NoActionBar" if mode == "light" else "Theme.Material.NoActionBar"
    write(root / f"androidApp/src/main/res/{directory}/themes.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Generated from V7.json by tools/design/import-v7.py. -->
    <style name="Theme.Coach" parent="@android:style/{parent}">
        <item name="android:windowBackground">{background}</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
""")
print("Imported V7 tokens, derived typography, six fonts and supplied OFL licenses.")
