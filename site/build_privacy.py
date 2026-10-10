"""wheel.ranax.co/privacy, made from PRIVACY.md so the policy has one source.

Handles what the policy uses: headings, paragraphs, lists, **bold**, _italic_, `code` and links.
Run after editing PRIVACY.md: python site/build_privacy.py
"""

import html
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "PRIVACY.md"
CONTACT = "ranasatyamraj@gmail.com"


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r"`(.+?)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"(?<![\w*])_(.+?)_(?![\w*])", r"<i>\1</i>", text)
    text = re.sub(r"\[(.+?)\]\((.+?)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"(?<![\"/>])\b([\w.+-]+@[\w-]+\.[\w.]+)\b", r'<a href="mailto:\1">\1</a>', text)
    return text


def convert(md):
    out, para, items, title, updated = [], [], [], None, None

    def flush():
        nonlocal para, items
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
            para = []
        if items:
            out.append("<ul>" + "".join("<li>" + inline(i) + "</li>" for i in items) + "</ul>")
            items = []

    for line in md.splitlines():
        s = line.strip()
        if not s:
            flush()
        elif s.startswith("# "):
            title = s[2:]
        elif s.startswith("## "):
            flush()
            out.append("<h2>" + inline(s[3:]) + "</h2>")
        elif s.startswith("- "):
            if para:
                flush()
            items.append(s[2:])
        elif s.startswith("_") and s.endswith("_") and updated is None:
            updated = s.strip("_")
        elif items and line.startswith("  "):
            items[-1] += " " + s
        else:
            para.append(s)
    flush()
    return title, updated, "\n".join(out)


def main():
    title, updated, body = convert(SOURCE.read_text(encoding="utf-8"))
    page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Privacy · Wheel</title>
<meta name="description" content="What Wheel does with your data.">
<meta name="theme-color" content="#000000">
<link rel="icon" href="img/favicon.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="style.css?v=1">
</head>
<body>
<div class="wrap">
  <header class="top"><a class="brand" href="./"><img src="img/icon-192.png" alt="">Wheel</a></header>
</div>
<article class="doc">
  <div class="kicker">Privacy</div>
  <h1>{html.escape(title or "Privacy")}</h1>
  <p class="updated">{html.escape(updated or "")}</p>
{body}
</article>
<div class="wrap">
  <footer class="foot">
    <a href="./">Home</a>
    <a href="mailto:{CONTACT}">Contact</a>
    <span class="made">Made by <a href="https://ranax.co">Rana</a></span>
  </footer>
</div>
</body>
</html>
"""
    (HERE / "privacy.html").write_text(page, encoding="utf-8", newline="\n")
    print("privacy.html", len(page), "bytes")


if __name__ == "__main__":
    main()
