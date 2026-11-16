"""Self-authored OCR evaluation material; never opens a learner's library."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parent
FONTS = ROOT.parents[5] / "shared/ui/src/androidMain/res/font"
SAMPLES = [
    ("01-clean-serif", "noto_serif_sc.ttf", "清晰单栏 / clean scan", [
        "记忆与理解 Memory and understanding",
        "学习不是把一页文字抄进脑中，而是建立可以解释和使用的联系。阅读一段新材料时，先问它解决了什么问题，再用自己的话回答。主动回忆 active recall 是在不看答案的情况下尝试提取信息。",
        "例如，研究者把 120 名学生随机分为 A、B 两组。A 组反复阅读三次，B 组阅读一次后做两次回忆练习。七天后测量正确率 accuracy，而不是只询问学生觉得自己学会了多少。",
        "相关不等于因果。correlation 描述两个变量一起变化，causation 则表示一个变量的改变导致另一个变量变化。随机分配 random assignment 可以减少组间原有差异的影响。",
        "一个好的解释同时包含结论、证据和推理。若平均分从 62.5 提高到 78.0，差值为 15.5 分；但仍要考虑样本量、测量误差和结果是否能够重复。",
        "阅读后请合上材料：用两句话说明主动回忆的做法，再举一个生活中的例子。忘记一部分不意味着失败，发现缺口后回到原文核对，才是下一次练习的起点。",
    ]),
    ("02-two-columns", "noto_sans_sc.ttf", "双栏与术语 / columns", [
        "观察与证据 Observation and evidence",
        "观察 observation 是有目的地记录现象。测量 measurement 则借助明确的单位比较数量。温度从 18.2 摄氏度升到 21.6 摄氏度，变化为 3.4 摄氏度。记录单位可以避免把不同数量错误相加。",
        "对照组 control group 提供比较的起点。实验组 treatment group 接受研究中的处理。两组应当在其他重要条件上尽量相近，研究者还需要提前说明如何处理缺失的数据。",
        "假设 hypothesis 应提出可以被证据检验的预测。一次实验没有支持假设，不等于证明任何其他解释都成立。需要检查工具是否可靠、样本是否适当以及分析步骤是否事先确定。",
        "概率与误差 Probability and error",
        "概率 probability 描述某事件发生的可能性，取值从 0 到 1。样本 sample 是总体 population 的一部分。样本平均数与总体平均数之间的差异，不一定来自真正的变化，也可能来自抽样误差。",
        "设五次测量得到 12、14、13、16、15，平均值 mean 为 14。中位数 median 也是 14。若最后一个数误写成 150，平均值会明显改变，所以分析之前必须检查原始记录。",
        "置信区间 confidence interval 表达估计的不确定性。报告结果时不应只保留一个看起来精确的小数。请同时说明测量的方法、研究的限制和结论适用的人群。",
        "注 Note：这些文字由开发者专为识别测试编写，不属于任何学生的真实教材。",
    ]),
    ("03-camera-skew", "noto_serif_sc.ttf", "倾斜与压缩 / camera simulation", [
        "复习安排 Spaced practice",
        "间隔练习 spaced practice 把学习分散到不同日期，而不是把全部时间集中在一个晚上。复习时先尝试回答，再检查原文。每次只看熟悉的句子，可能增加流畅感，却没有增加独立解释的能力。",
        "一份示例计划安排在第 1、3、7、14 天复习。第一次回答正确率为 80%，第二次为 90%，这并不意味着以后永远不会忘记。时间间隔需要根据知识难度与实际表现调整。",
        "自我解释 self explanation 要求学习者说明每一步为什么成立。做一道选择题时，不仅要选出正确项，还应解释其他选项为什么不符合题目中的条件。",
        "举例 example：如果一项研究只观察了城市居民，结论能否推广到所有地区？请先区分已经观察到的事实、基于事实作出的推断，以及还需要收集的证据。",
        "识别可能出现错字。遇到公式、数字、姓名或关键术语时，应当返回扫描原页核对。原页是证据，识别文字只提供方便的阅读、检索和选段学习入口。",
    ]),
]

def wrap(draw, text, font, width):
    result, line = [], ""
    for char in text:
        if line and draw.textlength(line + char, font=font) > width:
            result.append(line)
            line = char
        else:
            line += char
    if line:
        result.append(line)
    return result

def main():
    manifest, images = [], []
    for name, font_name, description, blocks in SAMPLES:
        image = Image.new("L", (2200, 2400), 255)
        draw = ImageDraw.Draw(image)
        size = 38 if name == "02-two-columns" else 42
        font = ImageFont.truetype(str(FONTS / font_name), size)
        line_height = int(size * 1.65)
        columns = (blocks[:4], blocks[4:]) if name == "02-two-columns" else (blocks,)
        for column, paragraphs in enumerate(columns):
            x, y = (110 + 1090 * column), 130
            width = 910 if len(columns) == 2 else 1980
            for paragraph in paragraphs:
                for line in wrap(draw, paragraph, font, width):
                    draw.text((x, y), line, fill=20, font=font)
                    y += line_height
                y += line_height // 2
            assert y < 2270, (name, y)
        if name == "03-camera-skew":
            image = image.rotate(1.3, Image.Resampling.BICUBIC, fillcolor=255)
            image = image.filter(ImageFilter.GaussianBlur(0.55))
            # Deterministic simulated uneven illumination, without losing ink.
            shadow = Image.new("L", image.size)
            sd = ImageDraw.Draw(shadow)
            for x in range(image.width):
                level = int(218 + 37 * x / (image.width - 1))
                sd.line((x, 0, x, image.height), fill=level)
            from PIL import ImageChops
            image = ImageChops.multiply(image, shadow)
            image.save(ROOT / (name + ".jpg"), quality=60, dpi=(300, 300))
            image = Image.open(ROOT / (name + ".jpg")).convert("L")
        image.save(ROOT / (name + ".png"), dpi=(300, 300))
        truth = "\n\n".join(blocks)
        (ROOT / (name + ".truth.txt")).write_text(truth, encoding="utf-8")
        manifest.append({"name": name, "description": description, "image": name + ".png", "width": 2200, "height": 2400, "font": font_name, "font_size": size, "truth": name + ".truth.txt", "truth_characters": len(truth)})
        images.append(image.convert("RGB"))
    images[0].save(ROOT / "three-scanned-pages.pdf", save_all=True, append_images=images[1:], resolution=300)
    (ROOT / "samples.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
