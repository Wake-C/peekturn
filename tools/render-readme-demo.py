#!/usr/bin/env python3
"""Render the complete, bilingual README journey from real white-theme captures.

Requires Pillow with WebP support: python -m pip install Pillow
Run: python tools/render-readme-demo.py --preview-dir /tmp/peekturn-preview
The README uses lossless animated WebP; GIF is a full 256-color fallback.
Source captures are lossless. App text, controls and video are never redrawn.
Font defaults are macOS system faces; pass the four --font-* paths elsewhere.
"""

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "assets/demo/screens"
SIZE = 800
INK = "#252a30"
MUTED = "#626a74"
BLUE = "#3267c8"
RULE = "#dce2ea"
STAGE = (40, 214, 760, 734)

CHAPTERS = {
    "en": ("Books", "Articles", "Video", "Across apps"),
    "zh": ("读书", "文章", "视频", "跨 App"),
}
TITLES = {
    "en": ("Make time for that book.", "Keep the articles worth reading.",
           "Let the ideas play.", "Take understanding with you."),
    "zh": ("那本想读的书，现在翻开。", "好文章，留在书架里。",
           "跟着字幕，看懂这一句。", "查词，跟着你走。"),
}
STEPS = {
    "book": (0, "An EPUB, your typography, your pace.", "打开 EPUB，用喜欢的排版慢慢读。"),
    "selection": (0, "Select a word, right on the page.", "选中生词，不必离开正在读的这一页。"),
    "book_explain": (0, "Meaning and context, beside the passage.", "词义、语境和例句，就在原文旁边。"),
    "article_browser": (1, "Open the full text. Import this page.", "打开全文，点一下「导入此页」。"),
    "article_page": (1, "A web article, now a page in your reader.", "网页变成书页，接着读、查词、听朗读。"),
    "watch": (2, "Real content. Captions you can follow.", "看真实内容，跟着字幕理解词句。"),
    "video_explain": (2, "Tap a word. Hear the sentence again.", "点字幕查词，再听懂这一句。"),
    "drag": (3, "Drag the floating lookup onto a word.", "在其他 App，拖动悬浮查词对准生词。"),
    "float_explain": (3, "Get the meaning over the page you are reading.", "解释出现在原来的页面上，查完继续读。"),
}


def load(name):
    with Image.open(SOURCES / name) as image:
        return image.convert("RGB")


def crop(source, box):
    """Crop in 540-wide reference coordinates, before any display resizing."""
    scale = source.width / 540
    return source.crop(tuple(round(value * scale) for value in box))


def fit(canvas, image, box=STAGE):
    x1, y1, x2, y2 = box
    scale = min((x2 - x1) / image.width, (y2 - y1) / image.height)
    size = (round(image.width * scale), round(image.height * scale))
    image = image.resize(size, Image.Resampling.LANCZOS)
    canvas.paste(image, (x1 + (x2 - x1 - size[0]) // 2, y1 + (y2 - y1 - size[1]) // 2))


def text(draw, xy, value, font, fill=INK, width=None):
    width = SIZE - xy[0] - 40 if width is None else width
    assert draw.textlength(value, font=font) <= width, f"Caption overflows: {value}"
    draw.text(xy, value, font=font, fill=fill, anchor="lt")


def masthead(canvas, locale, fonts, icon):
    canvas.paste(icon, (40, 26), icon)
    draw = ImageDraw.Draw(canvas)
    text(draw, (87, 34), "PEEKTURN" if locale == "en" else "憩页  /  PEEKTURN", fonts["small"])
    return draw


def scene(locale, step, source, fonts, icon, caption=None):
    chapter, english, chinese = STEPS[step]
    canvas = Image.new("RGB", (SIZE, SIZE), "white")
    draw = masthead(canvas, locale, fonts, icon)
    text(draw, (660, 34), f"0{chapter + 1} / 04", fonts["small"], BLUE)
    text(draw, (40, 91), TITLES[locale][chapter], fonts["title"])
    text(draw, (40, 154), english if locale == "en" else chinese, fonts["body"], MUTED)

    # Show one readable view, not a tiny phone beside a duplicate of its screen.
    if step in ("book", "selection"):
        fit(canvas, crop(source, (16, 155, 524, 545)))
    elif step == "book_explain":
        bottom = 790 if locale == "en" else 762
        fit(canvas, crop(source, (16, 433, 524, bottom)))
    elif step == "article_browser":
        fit(canvas, source, (40, 214, 760, 620))
        fit(canvas, caption, (40, 634, 760, 734))
    elif step == "article_page":
        fit(canvas, crop(source, (20, 55, 520, 460)))
    elif step == "watch":
        fit(canvas, source, (80, 218, 720, 570))
        fit(canvas, caption, (80, 580, 720, 736))
    elif step == "video_explain":
        box = (16, 273, 524, 694) if locale == "en" else (16, 312, 524, 654)
        fit(canvas, crop(source, box))
    elif step == "drag":
        fit(canvas, crop(source, (0, 0, 540, 420)))
    elif step == "float_explain":
        bottom = 690 if locale == "en" else 656
        fit(canvas, crop(source, (16, 377, 524, bottom)))
    else:
        raise ValueError(step)

    for index, label in enumerate(CHAPTERS[locale]):
        x = 40 + index * 184
        color = BLUE if index == chapter else RULE
        draw.line((x, 756, x + 168, 756), fill=color, width=3)
        text(draw, (x, 773), label, fonts["small"], BLUE if index == chapter else MUTED, width=168)
    return canvas


def poster(locale, fonts, icon):
    canvas = Image.new("RGB", (SIZE, SIZE), "white")
    draw = masthead(canvas, locale, fonts, icon)
    title = "Read. Watch. Understand." if locale == "en" else "读书、读文章，也看懂视频。"
    subtitle = "Books, articles, videos and the words between." if locale == "en" else "从书页到字幕，让好奇心继续往前走。"
    text(draw, (40, 91), title, fonts["title"])
    text(draw, (40, 154), subtitle, fonts["body"], MUTED)
    images = [
        crop(load("book-page.webp"), (16, 155, 524, 495)),
        crop(load("article-page.webp"), (20, 55, 520, 375)),
        load(f"watch-{locale}-01.webp"),
        crop(load(f"float-explain-{locale}.webp"), (16, 377, 524, 690 if locale == "en" else 656)),
    ]
    for index, image in enumerate(images):
        x, y = 40 + index % 2 * 380, 218 + index // 2 * 278
        text(draw, (x, y), CHAPTERS[locale][index], fonts["body"], BLUE, width=340)
        fit(canvas, image, (x, y + 40, x + 340, y + 254))
    return canvas


def render(locale, font_paths, output, preview_dir=None):
    serif, sans = map(str, font_paths)
    fonts = {
        "title": ImageFont.truetype(serif, 44),
        "body": ImageFont.truetype(sans, 24),
        "small": ImageFont.truetype(sans, 17),
    }
    with Image.open(ROOT / "assets/images/icon.png") as source:
        icon = source.convert("RGBA").resize((34, 34), Image.Resampling.LANCZOS)

    groups = []

    def still(step, name, duration, caption=None):
        groups.append((step, [(scene(locale, step, load(name), fonts, icon, caption), duration)]))

    still("book", "book-page.webp", 2800)
    still("selection", f"book-select-{locale}.webp", 1200)
    still("book_explain", f"book-explain-{locale}.webp", 4000)
    still("article_browser", f"article-browser-{locale}.webp", 2600, load(f"article-import-{locale}.webp"))
    still("article_page", "article-page.webp", 3400)

    watch = sorted(SOURCES.glob(f"watch-{locale}-*.webp"))
    drag = sorted(SOURCES.glob("drag-*.webp"))
    assert 10 <= len(watch) <= 25 and len(drag) >= 4, "Missing recorded motion sources"
    caption = load(f"video-caption-{locale}.webp")
    motion = [(scene(locale, "watch", load(path.name), fonts, icon, caption), 100) for path in watch]
    motion[-1] = (motion[-1][0], 3000 - 100 * (len(motion) - 1))
    groups.append(("watch", motion))
    still("video_explain", f"video-explain-{locale}.webp", 3500)
    motion = [(scene(locale, "drag", load(path.name), fonts, icon), 200) for path in drag]
    motion[0] = (motion[0][0], 800)
    motion[-1] = (motion[-1][0], 600)
    groups.append(("drag", motion))
    still("float_explain", f"float-explain-{locale}.webp", 4800)

    # Deliberate cuts preserve readable text. Motion comes from the real controls.
    frames = [image for _, group in groups for image, _ in group]
    durations = [duration for _, group in groups for _, duration in group]
    suffix = "-zh" if locale == "zh" else ""
    stem = output / f"peekturn-intro{suffix}"
    frames[0].save(stem.with_suffix(".webp"), save_all=True, append_images=frames[1:],
                   duration=durations, loop=0, lossless=True, method=6, minimize_size=True)

    # GIF has a 256-color ceiling. Give each frame its own full palette and
    # diffuse the quantization error instead of flattening gradients into bands.
    indexed = []
    for frame in frames:
        palette = frame.quantize(colors=256, method=Image.Quantize.MEDIANCUT)
        indexed.append(frame.quantize(palette=palette, dither=Image.Dither.FLOYDSTEINBERG))
    indexed[0].save(stem.with_suffix(".gif"), save_all=True, append_images=indexed[1:],
                    duration=durations, loop=0, optimize=True, disposal=1, include_color_table=True)
    poster(locale, fonts, icon).save(output / f"peekturn-intro-cover{suffix}.jpg", quality=95, subsampling=0, optimize=True)

    if preview_dir:
        preview_dir.mkdir(parents=True, exist_ok=True)
        timeline, elapsed = [], 0
        for step, group in groups:
            group[len(group) // 2][0].save(preview_dir / f"{locale}-{step}.png")
            duration = sum(duration for _, duration in group)
            timeline.append({"step": step, "start_ms": elapsed, "duration_ms": duration})
            elapsed += duration
        (preview_dir / f"{locale}-timeline.json").write_text(json.dumps(timeline, indent=2) + "\n")
    for extension in (".webp", ".gif"):
        path = stem.with_suffix(extension)
        print(f"{path.relative_to(ROOT)}: {path.stat().st_size:,} bytes, {sum(durations) / 1000:.1f}s")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font-serif-en", type=Path, default=Path("/System/Library/Fonts/Palatino.ttc"))
    parser.add_argument("--font-sans-en", type=Path, default=Path("/System/Library/Fonts/Avenir.ttc"))
    parser.add_argument("--font-serif-zh", type=Path, default=Path("/System/Library/Fonts/Supplemental/Songti.ttc"))
    parser.add_argument("--font-sans-zh", type=Path, default=Path("/System/Library/Fonts/Hiragino Sans GB.ttc"))
    parser.add_argument("--preview-dir", type=Path, help="Optional RGB scene previews and timings for visual verification")
    args = parser.parse_args()
    output = ROOT / "assets/video"
    render("en", (args.font_serif_en, args.font_sans_en), output, args.preview_dir)
    render("zh", (args.font_serif_zh, args.font_sans_zh), output, args.preview_dir)


if __name__ == "__main__":
    main()
