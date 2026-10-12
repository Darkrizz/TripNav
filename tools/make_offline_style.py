"""Builds TripNav's bundled offline map styles from the OpenFreeMap styles.

Writes app/src/main/assets/offline/{day,night}.json plus the sprite and the glyph ranges they need.
The vector source becomes a placeholder that the app points at the installed zone .pmtiles files.
Labels are switched to Latin names: MapLibre cannot shape Indic scripts, so Devanagari or Gujarati
labels would render as broken glyphs.

Usage: python tools/make_offline_style.py   (needs curl on PATH)
"""
import json
import os
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "style-src")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "offline")
SPRITE = "https://tiles.openfreemap.org/sprites/ofm_f384/ofm"
GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
# Basic Latin, Latin-1, Latin Extended A/B, Latin Extended Additional, general punctuation.
RANGES = ["0-255", "256-511", "512-767", "7680-7935", "8192-8447"]
LATIN_LABEL = ["coalesce", ["get", "name:latin"], ["get", "name:en"], ["get", "name"]]


def curl(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    subprocess.run(["curl", "-sSfL", "-o", dest, url], check=True)


def safe_font(name):
    """Space-free font stack names: asset:// URLs do not reliably round-trip spaces."""
    return name.replace(" ", "")


def uses_nonlatin(expr):
    return "name:nonlatin" in json.dumps(expr)


def transform(style):
    style["sources"] = {"openmaptiles": {"type": "vector", "url": "pmtiles://ZONE"}}
    style["sprite"] = "asset://offline/sprites/ofm"
    style["glyphs"] = "asset://offline/glyphs/{fontstack}/{range}.pbf"
    layers = []
    for layer in style["layers"]:
        if layer.get("source") not in (None, "openmaptiles"):
            continue  # drop the online-only raster hillshade
        layout = layer.get("layout", {})
        if isinstance(layout.get("text-font"), list):
            layout["text-font"] = [safe_font(f) for f in layout["text-font"]]
        if "text-field" in layout and uses_nonlatin(layout["text-field"]):
            layout["text-field"] = LATIN_LABEL
        layers.append(layer)
    style["layers"] = layers
    style["name"] = "TripNav offline (" + style.get("name", "") + ")"
    return style


def fonts_of(style):
    fonts = set()
    for layer in style["layers"]:
        f = layer.get("layout", {}).get("text-font")
        if isinstance(f, list) and all(isinstance(x, str) for x in f):
            fonts.add(",".join(f))
    return fonts


def main():
    os.makedirs(OUT, exist_ok=True)
    fonts = set()
    for name, out in (("liberty", "day"), ("dark", "night")):
        with open(os.path.join(SRC, name + ".json"), encoding="utf8") as fh:
            style = transform(json.load(fh))
        fonts |= fonts_of(style)
        with open(os.path.join(OUT, out + ".json"), "w", encoding="utf8") as fh:
            json.dump(style, fh, separators=(",", ":"))
    for suffix in ("", "@2x"):
        for ext in ("json", "png"):
            curl(f"{SPRITE}{suffix}.{ext}", os.path.join(OUT, "sprites", f"ofm{suffix}.{ext}"))
    for font in sorted(fonts):
        remote = {"NotoSansRegular": "Noto Sans Regular", "NotoSansBold": "Noto Sans Bold", "NotoSansItalic": "Noto Sans Italic"}[font]
        for r in RANGES:
            url = GLYPHS.replace("{fontstack}", remote.replace(" ", "%20")).replace("{range}", r)
            curl(url, os.path.join(OUT, "glyphs", font, r + ".pbf"))
    print("fonts:", sorted(fonts))


if __name__ == "__main__":
    main()
