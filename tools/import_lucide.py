"""Vendor pinned Lucide SVGs and generate offline Android VectorDrawables.

Run --fetch only when refreshing the checked-in upstream files. Ordinary builds
use the generated res/drawable XMLs and do not need Python or network access.
"""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
REVISION = "c1a02a04d4f3f7049d44567d62b8b06cff0b9103"
ICONS = [
    ("BACK", "arrow-left"), ("FORWARD", "arrow-right"),
    ("REFRESH", "refresh-cw"), ("HOME", "house"), ("STAR", "star"),
    ("MENU", "menu"), ("CLOSE", "x"), ("TABS", "panels-top-left"),
    ("DOWN", "download"), ("SEARCH", "search"), ("CLOCK", "clock"),
    ("SHARE", "share-2"), ("DESKTOP", "monitor"), ("MOON", "moon"),
    ("EXPAND", "maximize"), ("TRASH", "trash"), ("POWER", "power"),
    ("SLIDERS", "sliders-horizontal"), ("SETTINGS", "settings"),
    ("NEW_TAB", "square-plus"), ("ABOUT", "info"),
    ("READER", "book-open-text"), ("NO_IMAGE", "image-off"),
    ("SHIELD", "shield-check"), ("ERASER", "eraser"),
    ("COLLAPSE", "minimize"), ("CHEVRON_LEFT", "chevron-left"),
    ("CHEVRON_RIGHT", "chevron-right"), ("PLUS", "plus"),
    ("FILE", "file"), ("FILE_TEXT", "file-text"), ("FILE_IMAGE", "image"),
    ("FILE_VIDEO", "film"), ("FILE_AUDIO", "file-music"), ("APK", "package"),
    ("MINUS", "minus"), ("CHEVRON_DOWN", "chevron-down"),
    ("CHEVRON_UP", "chevron-up"), ("GLOBE", "globe"),
    ("UPDATE", "circle-arrow-down"),
]
VENDOR = ROOT / "third_party" / "lucide"
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)

def fetch(relative):
    url = f"https://raw.githubusercontent.com/lucide-icons/lucide/{REVISION}/{relative}"
    request = urllib.request.Request(url, headers={"User-Agent": "car-browser-lucide-import"})
    try:
        return urllib.request.urlopen(request, timeout=40).read()
    except Exception:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({
            "http": "http://127.0.0.1:7897", "https": "http://127.0.0.1:7897"}))
        return opener.open(request, timeout=40).read()

def n(value):
    return format(float(value), ".8g")

def normalize_path(data):
    # SVG arc flags are single characters (e.g. "0022" means 0, 0, 22).
    # Android PathParser treats adjacent flags as one float; separate all tokens.
    counts = {"M":2, "L":2, "H":1, "V":1, "C":6, "S":4, "Q":4, "T":2, "A":7}
    number = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
    result, command, pos = [], None, 0
    while pos < len(data):
        while pos < len(data) and data[pos] in " ,\t\r\n": pos += 1
        if pos == len(data): break
        if data[pos].isalpha():
            command = data[pos]; pos += 1
            if command.upper() == "Z":
                result.append(command); command = None; continue
        if command is None or command.upper() not in counts:
            raise ValueError(f"Invalid SVG command at {pos}: {data}")
        values = []
        for index in range(counts[command.upper()]):
            while pos < len(data) and data[pos] in " ,\t\r\n": pos += 1
            if command.upper() == "A" and index in (3, 4):
                if pos == len(data) or data[pos] not in "01": raise ValueError("Invalid SVG arc flag")
                values.append(data[pos]); pos += 1
            else:
                match = number.match(data, pos)
                if not match: raise ValueError(f"Missing coordinate at {pos}: {data}")
                values.append(match.group()); pos = match.end()
        result.append(command + " " + " ".join(values))
        if command == "M": command = "L"
        elif command == "m": command = "l"
    return " ".join(result)

def geometry(node):
    tag = node.tag.rsplit("}", 1)[-1]
    a = node.attrib
    if any(key in a for key in ("transform", "stroke-width", "fill", "opacity")):
        raise ValueError(f"Unsupported per-shape style: {a}")
    if tag == "path":
        return normalize_path(a["d"])
    if tag == "line":
        return f'M{a["x1"]},{a["y1"]}L{a["x2"]},{a["y2"]}'
    if tag in ("polyline", "polygon"):
        return "M" + a["points"] + ("Z" if tag == "polygon" else "")
    if tag in ("circle", "ellipse"):
        cx, cy = float(a["cx"]), float(a["cy"])
        rx, ry = (float(a["r"]), float(a["r"])) if tag == "circle" else (float(a["rx"]), float(a["ry"]))
        return f"M{n(cx-rx)},{n(cy)}a{n(rx)},{n(ry)} 0 1,0 {n(2*rx)},0a{n(rx)},{n(ry)} 0 1,0 {n(-2*rx)},0"
    if tag == "rect":
        x, y, w, h = (float(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        rx = min(float(a.get("rx", a.get("ry", 0))), w/2)
        ry = min(float(a.get("ry", a.get("rx", 0))), h/2)
        if not rx or not ry:
            return f"M{n(x)},{n(y)}h{n(w)}v{n(h)}h{n(-w)}Z"
        return (f"M{n(x+rx)},{n(y)}H{n(x+w-rx)}a{n(rx)},{n(ry)} 0 0,1 {n(rx)},{n(ry)}"
                f"V{n(y+h-ry)}a{n(rx)},{n(ry)} 0 0,1 {n(-rx)},{n(ry)}"
                f"H{n(x+rx)}a{n(rx)},{n(ry)} 0 0,1 {n(-rx)},{n(-ry)}"
                f"V{n(y+ry)}a{n(rx)},{n(ry)} 0 0,1 {n(rx)},{n(-ry)}Z")
    raise ValueError(f"Unsupported SVG node: {tag}")

def write_vector(name, raw):
    svg = ET.fromstring(raw)
    assert svg.attrib["viewBox"] == "0 0 24 24", name
    assert svg.attrib.get("stroke-width") == "2", name
    assert svg.attrib.get("fill") == "none", name
    root = ET.Element("vector", {f"{{{ANDROID}}}{key}": value for key, value in {
        "width":"24dp", "height":"24dp", "viewportWidth":"24", "viewportHeight":"24"}.items()})
    for node in svg:
        if not isinstance(node.tag, str):
            continue
        ET.SubElement(root, "path", {f"{{{ANDROID}}}{key}": value for key, value in {
            "pathData":geometry(node), "fillColor":"#00000000", "strokeColor":"#FFFFFFFF",
            "strokeWidth":"2", "strokeLineCap":"round", "strokeLineJoin":"round"}.items()})
    ET.indent(root, space="    ")
    result = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Lucide; ISC/MIT notices in assets/licenses/lucide.txt. Generated by tools/import_lucide.py. -->\n' + ET.tostring(root, encoding="unicode") + "\n"
    target = ROOT / "res" / "drawable" / ("ic_lucide_" + name.replace("-", "_") + ".xml")
    target.write_text(result, encoding="utf-8", newline="\n")
    return hashlib.sha256(target.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fetch", action="store_true")
    args = parser.parse_args()
    (VENDOR / "icons").mkdir(parents=True, exist_ok=True)
    if args.fetch:
        def download(item):
            _, name = item
            raw = fetch(f"icons/{name}.svg")
            (VENDOR / "icons" / (name + ".svg")).write_bytes(raw)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(download, ICONS))
        (VENDOR / "LICENSE").write_bytes(fetch("LICENSE"))
    rows = []
    for number, (constant, name) in enumerate(ICONS):
        raw = (VENDOR / "icons" / (name + ".svg")).read_bytes()
        vector_sha = write_vector(name, raw)
        rows.append({"id":number, "constant":constant, "icon":name,
                     "svgSha256":hashlib.sha256(raw).hexdigest(), "vectorSha256":vector_sha})
    license_raw = (VENDOR / "LICENSE").read_bytes()
    licenses = ROOT / "assets" / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    (licenses / "lucide.txt").write_bytes(license_raw)
    manifest = {"project":"Lucide", "repository":"https://github.com/lucide-icons/lucide",
                "revision":REVISION, "viewBox":"0 0 24 24", "strokeWidth":2,
                "licenseSha256":hashlib.sha256(license_raw).hexdigest(), "icons":rows}
    (VENDOR / "source.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8", newline="\n")
    print(json.dumps({"icons":len(rows), "revision":REVISION, "status":"LUCIDE_IMPORT_PASS"}))

if __name__ == "__main__":
    main()
