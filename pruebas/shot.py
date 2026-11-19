"""Convierte un documento con los conversores Java, arma la página con Html.kt y la fotografía.

uso: shot.py <documento> <carpeta_salida> [ancho] [js_extra] [dark]
"""
import os
import re
import subprocess
import sys

from playwright.sync_api import sync_playwright

R = "/home/claude/lector-de-documentos"
H = os.path.dirname(os.path.abspath(__file__))
doc, out = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
width = int(sys.argv[3]) if len(sys.argv) > 3 else 400
extra = sys.argv[4] if len(sys.argv) > 4 else ""
dark = len(sys.argv) > 5 and sys.argv[5] == "dark"

subprocess.run(["java", "-cp", "/tmp/lector-cls", "TestMain", doc, out], check=True, capture_output=True)
body = open(os.path.join(out, "body.html"), encoding="utf-8").read()

kt = open(R + "/app/src/main/java/com/jnieves/lector/Html.kt", encoding="utf-8").read()


def const(name):
    m = re.search(r"const val " + name + r' = """(.*?)"""', kt, re.S)
    return m.group(1)


fonts = const("FONT_CSS").replace("file:///android_asset/fonts/", "file://" + R + "/app/src/main/assets/fonts/")
kind = doc.lower().rsplit(".", 1)[1]
cls = {"docx": "docx", "xlsx": "sheet", "pptx": "pptx"}[kind] + " bars" + (" dark" if dark else "")
script = const("SCROLL_SCRIPT") + const("BARS_SCRIPT") + const("SEARCH_SCRIPT")
if kind == "xlsx":
    script += const("SHEET_SCRIPT")
if kind == "pptx":
    script += const("SLIDES_SCRIPT")
fake = "window.Android=new Proxy({}, {get:function(){return function(){return 0;};}});"
html = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        "<style>" + fonts + const("CSS") + "</style></head><body class=\"" + cls + "\"><div class=\"spt\"></div>" + body
        + "<div class=\"spb\"></div><script>" + fake + script + "</script></body></html>")
page_path = os.path.join(out, "vista.html")
open(page_path, "w", encoding="utf-8").write(html)

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": width, "height": 800}, device_scale_factor=1.5)
    pg.goto("file://" + page_path)
    pg.wait_for_timeout(600)
    if extra:
        for i, step in enumerate(extra.split(";;")):
            pg.evaluate(step)
            pg.wait_for_timeout(400)
            pg.screenshot(path=os.path.join(out, f"shot{i}.png"), full_page=True)
    else:
        pg.screenshot(path=os.path.join(out, "shot.png"), full_page=True)
    errs = pg.evaluate("window.__errs||[]")
    b.close()
print("listo", out)
