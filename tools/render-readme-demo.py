#!/usr/bin/env python3
"""Build the bilingual README demos from real Classic White phone captures.

Requires Pillow: python -m pip install Pillow
Run: python tools/render-readme-demo.py
Font defaults are macOS system faces; pass the four --font-* paths elsewhere.
Sources in assets/demo/screens are cropped, resized production-app captures.
No app controls, word explanations or video images are synthesized.
"""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "assets/demo/screens"
SIZE = 800
INK = "#252a30"
MUTED = "#626a74"
BLUE = "#3267c8"
RULE = "#dce2ea"

COPY = {
    "en": [
        ("WATCH", "Let the ideas play.", "Real videos. Interactive captions.",
         "Tap a word.\nReplay the sentence."),
        ("EXPLAIN", "Catch the word. Keep the scene.", "An explanation that belongs to this moment.",
         "Get the meaning.\nStay with the video."),
        ("ACROSS APPS", "Take understanding with you.", "Drag Peekturn onto a word in another app.",
         "A word catches your eye.\nBring the lookup to it."),
        ("IN CONTEXT", "Meaning, right where you are.", "Capture the word. Understand it on the spot.",
         "The page stays put.\nThe meaning comes to you."),
    ],
    "zh": [
        ("看视频", "让知识，动起来。", "看真实内容，字幕里的生词随点随查。",
         "点开一个词，\n再听懂这一句。"),
        ("语境解释", "生词点开，思路不断。", "不只知道词义，也理解它在这一刻的意思。",
         "读懂这个词，\n接着看下去。"),
        ("跨 App 查词", "查词，跟着你走。", "在其他 App 阅读，也能把查词拖到眼前。",
         "遇到一个生词，\n把悬浮查词拖过去。"),
        ("就地理解", "词义，就在眼前。", "松手捕捉文字，直接在原来的页面看解释。",
         "页面留在原处，\n理解多走一步。"),
    ],
}


def load(name):
    with Image.open(SOURCES / name) as image:
        return image.convert("RGB")


def text(draw, xy, value, font, fill=INK, width=None, leading=None):
    width = SIZE - xy[0] - 42 if width is None else width
    for row, line in enumerate(value.split("\n")):
        assert draw.textlength(line, font=font) <= width, f"Caption overflows: {line}"
        draw.text((xy[0], xy[1] + row * (leading or font.size * 1.4)),
                  line, font=font, fill=fill, anchor="lt")


def phone(canvas, source, x=42, y=222, width=244):
    height = round(source.height * width / source.width)
    image = source.resize((width, height), Image.Resampling.LANCZOS)
    mask = Image.new("L", image.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), 17, fill=255)
    canvas.paste(image, (x, y), mask)
    ImageDraw.Draw(canvas).rounded_rectangle(
        (x, y, x + width - 1, y + height - 1), 17, outline=RULE, width=1)


def detail(canvas, source, box, y=268):
    image = source.crop(box)
    image = image.resize((428, round(image.height * 428 / image.width)), Image.Resampling.LANCZOS)
    canvas.paste(image, (330, y))


def masthead(canvas, locale, fonts):
    draw = ImageDraw.Draw(canvas)
    with Image.open(ROOT / "assets/images/icon.png") as icon:
        icon = icon.convert("RGBA").resize((34, 34), Image.Resampling.LANCZOS)
        canvas.paste(icon, (42, 26), icon)
    text(draw, (89, 34), "PEEKTURN" if locale == "en" else "憩页  /  PEEKTURN", fonts["small"])
    return draw


def scene(locale, index, source, fonts):
    canvas = Image.new("RGB", (SIZE, SIZE), "white")
    draw = masthead(canvas, locale, fonts)
    label, title, subtitle, note = COPY[locale][index]
    text(draw, (520, 34), f"0{index + 1}  /  {label}", fonts["small"], BLUE)
    text(draw, (40, 91), title, fonts["title"])
    text(draw, (42, 154), subtitle, fonts["body"], MUTED)
    phone(canvas, source)
    detail_label = (
        ("INTERACTIVE CAPTIONS", "WORD + CONTEXT", "DRAG TO LOOK UP", "OVER YOUR BROWSER")
        if locale == "en" else ("互动字幕", "词义与语境", "拖到单词上", "浏览器里的语境解释")
    )[index]
    text(draw, (330, 228), detail_label, fonts["small"], BLUE)
    if index == 0:
        detail(canvas, source, (0, 74, 540, 378))
        detail(canvas, source, (90, 637, 524, 753), y=529)
    elif index == 1:
        # Include the actual word, tabs, definition and context, not a recreated card.
        box = (16, 273, 524, 694) if locale == "en" else (16, 312, 524, 654)
        detail(canvas, source, box)
    elif index == 2:
        detail(canvas, source, (0, 305, 540, 565), y=305)
    else:
        box = (16, 377, 524, 690) if locale == "en" else (16, 377, 524, 656)
        detail(canvas, source, box)
    note_y = 666 if index == 0 else 650 if index == 1 else 610
    text(draw, (330, note_y), note, fonts["note"], leading=39)
    for step in range(4):
        x = 42 + step * 183
        draw.line((x, 760, x + 166, 760), fill=BLUE if step == index else RULE, width=3)
    footer = "WATCH  /  UNDERSTAND  /  KEEP GOING" if locale == "en" else "看见世界  /  理解词句  /  继续探索"
    text(draw, (42, 777), footer, fonts["footer"], MUTED)
    return canvas


def poster(locale, fonts):
    canvas = Image.new("RGB", (SIZE, SIZE), "white")
    draw = masthead(canvas, locale, fonts)
    title = "A bigger world, one word closer." if locale == "en" else "读懂一个词，走近更大的世界。"
    text(draw, (40, 91), title, fonts["title"])
    subtitle = "In the video. Over the page. Right beside you." if locale == "en" else "视频里，书页旁，手机上的每一次好奇。"
    text(draw, (42, 154), subtitle, fonts["body"], MUTED)
    phone(canvas, load(f"video-explain-{locale}.webp"), x=70, width=250)
    phone(canvas, load(f"float-explain-{locale}.webp"), x=480, width=250)
    text(draw, (70, 756), "IN THE VIDEO" if locale == "en" else "视频语境解释", fonts["small"], BLUE)
    text(draw, (480, 756), "ACROSS APPS" if locale == "en" else "跨 App 悬浮查词", fonts["small"], BLUE)
    return canvas


def render(locale, font_paths, output):
    serif, sans = map(str, font_paths)
    fonts = {
        "title": ImageFont.truetype(serif, 44),
        "body": ImageFont.truetype(sans, 24),
        "note": ImageFont.truetype(sans, 29),
        "small": ImageFont.truetype(sans, 17),
        "footer": ImageFont.truetype(sans, 13),
    }
    watch = sorted(SOURCES.glob(f"watch-{locale}-*.webp"))
    drag = sorted(SOURCES.glob("drag-*.webp"))
    assert len(watch) >= 4 and len(drag) >= 4, "Missing recorded motion sources"
    groups = [
        [(scene(locale, 0, load(path.name), fonts), 500) for path in watch],
        [(scene(locale, 1, load(f"video-explain-{locale}.webp"), fonts), 4600)],
        [(scene(locale, 2, load(path.name), fonts), 200) for path in drag],
        [(scene(locale, 3, load(f"float-explain-{locale}.webp"), fonts), 5200)],
    ]
    groups[0][-1] = (groups[0][-1][0], 1600)
    groups[2][0] = (groups[2][0][0], 1000)
    groups[2][-1] = (groups[2][-1][0], 800)
    # One shared palette keeps screenshot text stable between motion frames.
    palette_sheet = Image.new("RGB", (SIZE * 4, SIZE))
    for index, group in enumerate(groups):
        palette_sheet.paste(group[-1][0], (SIZE * index, 0))
    palette = palette_sheet.quantize(colors=160)
    frames, durations = [], []
    for index, group in enumerate(groups):
        for image, duration in group:
            frames.append(image)
            durations.append(duration)
        # Restrained horizontal reveals separate the edited phone journeys.
        current, following = group[-1][0], groups[(index + 1) % 4][0][0]
        for step in range(1, 4):
            edge = round(SIZE * (1 - (1 - step / 4) ** 3))
            frame = current.copy()
            frame.paste(following.crop((0, 0, edge, SIZE)), (0, 0))
            frames.append(frame)
            durations.append(70)
    frames = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in frames]
    suffix = "-zh" if locale == "zh" else ""
    gif = output / f"peekturn-intro{suffix}.gif"
    frames[0].save(gif, save_all=True, append_images=frames[1:], duration=durations,
                   loop=0, optimize=True, disposal=1)
    poster(locale, fonts).save(output / f"peekturn-intro-cover{suffix}.jpg", quality=88, optimize=True)
    assert gif.stat().st_size < 950_000, f"Demo exceeds size budget: {gif}"
    print(f"{gif.relative_to(ROOT)}: {gif.stat().st_size:,} bytes, {sum(durations) / 1000:.2f}s")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font-serif-en", type=Path, default=Path("/System/Library/Fonts/Palatino.ttc"))
    parser.add_argument("--font-sans-en", type=Path, default=Path("/System/Library/Fonts/Avenir.ttc"))
    parser.add_argument("--font-serif-zh", type=Path, default=Path("/System/Library/Fonts/Supplemental/Songti.ttc"))
    parser.add_argument("--font-sans-zh", type=Path, default=Path("/System/Library/Fonts/Hiragino Sans GB.ttc"))
    args = parser.parse_args()
    output = ROOT / "assets/video"
    render("en", (args.font_serif_en, args.font_sans_en), output)
    render("zh", (args.font_serif_zh, args.font_sans_zh), output)


if __name__ == "__main__":
    main()
