"""Arma el paquete de fuentes libres de la app (assets/fonts/extra y extra.css).

Toma cada familia, la recorta a los alfabetos latino y griego (español incluido,
con signos y símbolos comunes) y escribe una regla @font-face por archivo, más
las equivalencias de fuentes de Microsoft que no tienen copia libre.

uso: python3 armar_fuentes.py <google_fonts> <fuentes-externas> <salida_assets_fonts>

  <google_fonts>      copia del repositorio github.com/google/fonts (basta con
                      las carpetas de familias.txt, ver LEEME.md)
  <fuentes-externas>  carpeta fuentes-externas/ del repositorio (Selawik)
  <salida>            app/src/main/assets/fonts
"""
import os
import re
import shutil
import sys
from concurrent.futures import ProcessPoolExecutor

from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

AQUI = os.path.dirname(os.path.abspath(__file__))
TEXGYRE = "/usr/share/texmf/fonts/opentype/public/tex-gyre"
DEJAVU = "/usr/share/fonts/truetype/dejavu"

# Latino, latino extendido, griego, puntuación, monedas, letras, flechas,
# matemáticas, figuras y viñetas comunes
RANGOS = [(0x20, 0x24F), (0x250, 0x2FF), (0x300, 0x36F), (0x370, 0x3FF), (0x1E00, 0x1EFF),
          (0x2000, 0x206F), (0x2070, 0x209F), (0x20A0, 0x20CF), (0x2100, 0x214F), (0x2150, 0x218F),
          (0x2190, 0x21FF), (0x2200, 0x22FF), (0x2300, 0x23FF), (0x2460, 0x24FF), (0x2500, 0x257F),
          (0x25A0, 0x25FF), (0x2600, 0x26FF), (0x2700, 0x27BF), (0xFB00, 0xFB06), (0xFFFD, 0xFFFD)]
UNICODES = [c for a, b in RANGOS for c in range(a, b + 1)]

# Fuentes de Microsoft (u otras de pago) -> familia libre que la reemplaza.
# Las que tienen la misma medida van sin ajuste; en las parecidas el ajuste
# (size-adjust) es opcional y solo se pone cuando se conoce la proporción.
EQUIVALENCIAS = {
    "Aptos": "Akasia", "Aptos Display": "Akasia",
    "Georgia": "Gelasio",
    "Segoe UI": "Selawik", "Segoe UI Variable": "Selawik", "Segoe UI Variable Display": "Selawik",
    "Segoe UI Variable Text": "Selawik",
    "Century Gothic": "TeX Gyre Adventor", "ITC Avant Garde Gothic": "TeX Gyre Adventor",
    "Avant Garde": "TeX Gyre Adventor",
    "Book Antiqua": "TeX Gyre Pagella", "Palatino Linotype": "TeX Gyre Pagella", "Palatino": "TeX Gyre Pagella",
    "Bookman Old Style": "TeX Gyre Bonum", "Bookman": "TeX Gyre Bonum",
    "Century Schoolbook": "TeX Gyre Schola", "Century": "TeX Gyre Schola",
    "Comic Sans MS": "Comic Relief", "Comic Sans": "Comic Relief",
    "Verdana": "DejaVu Sans", "Tahoma": "DejaVu Sans Condensed",
    "Garamond": "EB Garamond", "Adobe Garamond Pro": "EB Garamond",
    "Impact": "Anton", "Arial Black": "Archivo Black",
    "Franklin Gothic": "Libre Franklin", "Franklin Gothic Book": "Libre Franklin",
    "Franklin Gothic Medium": "Libre Franklin", "Franklin Gothic Demi": "Libre Franklin",
    "Trebuchet MS": "Fira Sans",
    "Consolas": "Inconsolata",
    "Helvetica Neue": "Liberation Sans",
    "Lucida Console": "Liberation Mono",
    "Canva Sans": "Open Sans",
}
# Proporciones conocidas (ancho de la original / ancho de la libre)
AJUSTE = {
    "Consolas": "110%",  # avance 1126/2048 frente a 500/1000
}
# Si falta Akasia, Aptos usa Source Sans 3 al 97 % (Aptos ocupa 227 mm donde
# Source Sans ocupa 234 mm, medido por LibreOffice, bug 162872)
APTOS_RESPALDO = ("Source Sans 3", "97%")


def familias():
    with open(os.path.join(AQUI, "familias.txt"), encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip() and not l.startswith("#")]


def recortar(args):
    src, dst = args
    f = TTFont(src)
    o = Options()
    o.layout_features = ["*"]
    o.hinting = False
    o.name_IDs = ["*"]
    o.name_languages = ["*"]
    o.notdef_outline = True
    o.glyph_names = False
    s = Subsetter(o)
    s.populate(unicodes=UNICODES)
    s.subset(f)
    if "fvar" in f:
        # Solo se deja el eje de peso; los demás (ancho, tamaño óptico, grado) quedan fijos
        fijos = {ax.axisTag: ax.defaultValue for ax in f["fvar"].axes if ax.axisTag != "wght"}
        if fijos:
            f = instancer.instantiateVariableFont(f, fijos)
    f.save(dst)
    return dst


def estilo(path):
    """(peso o rango de pesos, 'normal'|'italic') leído del archivo."""
    f = TTFont(path, lazy=True)
    italic = bool(f["OS/2"].fsSelection & 1) or bool(f["head"].macStyle & 2) \
        or "italic" in os.path.basename(path).lower() or "oblique" in os.path.basename(path).lower()
    peso = str(f["OS/2"].usWeightClass)
    if "fvar" in f:
        for ax in f["fvar"].axes:
            if ax.axisTag == "wght":
                peso = "%d %d" % (ax.minValue, ax.maxValue)
    f.close()
    return peso, ("italic" if italic else "normal")


def nombre_meta(carpeta):
    with open(os.path.join(carpeta, "METADATA.pb"), encoding="utf-8") as f:
        return re.search(r'^name: "([^"]+)"', f.read(), re.M).group(1)


def main():
    gf, ext, salida = sys.argv[1], sys.argv[2], sys.argv[3]
    dest = os.path.join(salida, "extra")
    shutil.rmtree(dest, ignore_errors=True)
    os.makedirs(dest)

    trabajos = []   # (origen, destino)
    caras = []      # (familia, archivo relativo)
    licencias = {}  # familia -> texto de la licencia

    def agregar(familia, origen, lic=None):
        base = os.path.basename(origen)
        nombre = re.sub(r"[^A-Za-z0-9._-]", "", base.replace("[", "-").replace("]", ""))
        trabajos.append((origen, os.path.join(dest, nombre)))
        caras.append((familia, nombre))
        if lic and familia not in licencias and os.path.exists(lic):
            licencias[familia] = open(lic, encoding="utf-8", errors="replace").read()

    for rel in familias():
        carpeta = os.path.join(gf, rel)
        if not os.path.isdir(carpeta):
            print("falta", rel)
            continue
        fam = nombre_meta(carpeta)
        lic = next((os.path.join(carpeta, n) for n in ("OFL.txt", "LICENSE.txt", "UFL.txt")
                    if os.path.exists(os.path.join(carpeta, n))), None)
        for n in sorted(os.listdir(carpeta)):
            if not n.endswith(".ttf"):
                continue
            ruta = os.path.join(carpeta, n)
            peso, est = estilo(ruta)
            if " " not in peso:
                # Familias en archivos sueltos: rectas de 300 a 900, cursivas solo normal y negrita
                w = int(peso)
                if (est == "normal" and w < 300) or (est == "italic" and w not in (400, 700)):
                    continue
            agregar(fam, ruta, lic)

    for fam, pref in (("TeX Gyre Adventor", "texgyreadventor"), ("TeX Gyre Pagella", "texgyrepagella"),
                      ("TeX Gyre Bonum", "texgyrebonum"), ("TeX Gyre Schola", "texgyreschola")):
        for suf in ("regular", "bold", "italic", "bolditalic"):
            agregar(fam, os.path.join(TEXGYRE, "%s-%s.otf" % (pref, suf)), os.path.join(AQUI, "GUST-FONT-LICENSE.txt"))
    for fam, pref in (("DejaVu Sans", "DejaVuSans"), ("DejaVu Sans Condensed", "DejaVuSansCondensed")):
        for suf in ("", "-Bold", "-Oblique", "-BoldOblique"):
            agregar(fam, os.path.join(DEJAVU, pref + suf + ".ttf"), os.path.join(AQUI, "DEJAVU-LICENSE.txt"))

    hay = {}
    # Akasia (gemela libre de Aptos) se usa si algún día vuelve a estar en fuentes-externas/akasia
    for fam, sub in (("Akasia", "akasia"), ("Selawik", "selawik")):
        carpeta = os.path.join(ext, sub)
        archivos = sorted(n for n in os.listdir(carpeta) if n.lower().endswith((".ttf", ".otf"))) \
            if os.path.isdir(carpeta) else []
        lic = next((os.path.join(carpeta, n) for n in sorted(os.listdir(carpeta))
                    if n.upper().startswith(("OFL", "LICEN"))), None) if archivos else None
        # Si hay versión variable y estáticas, basta con las estáticas
        estaticas = [n for n in archivos if "[" not in n and "variable" not in n.lower()]
        for n in (estaticas or archivos):
            agregar(fam, os.path.join(carpeta, n), lic)
        hay[fam] = bool(archivos)
        print(fam, len(estaticas or archivos), "archivos")

    with ProcessPoolExecutor() as ex:
        for d in ex.map(recortar, trabajos, chunksize=4):
            pass

    reglas = []
    por_familia = {}
    for fam, arch in caras:
        peso, est = estilo(os.path.join(dest, arch))
        por_familia.setdefault(fam, []).append((arch, peso, est))

    def regla(nombre, arch, peso, est, ajuste=None):
        r = "@font-face{font-family:'%s';src:url('extra/%s');font-weight:%s;font-style:%s" % (nombre, arch, peso, est)
        if ajuste:
            r += ";size-adjust:" + ajuste
        return r + "}"

    for fam, lst in por_familia.items():
        for arch, peso, est in lst:
            reglas.append(regla(fam, arch, peso, est))
    for orig, libre in EQUIVALENCIAS.items():
        if libre == "Akasia" and not hay.get("Akasia"):
            libre, aj = APTOS_RESPALDO
        else:
            aj = AJUSTE.get(orig)
        if libre in ("Liberation Sans", "Liberation Mono"):
            pref = "LiberationSans" if libre == "Liberation Sans" else "LiberationMono"
            for suf, peso, est in (("Regular", "normal", "normal"), ("Bold", "bold", "normal"),
                                   ("Italic", "normal", "italic"), ("BoldItalic", "bold", "italic")):
                reglas.append("@font-face{font-family:'%s';src:url('%s-%s.ttf');font-weight:%s;font-style:%s}"
                              % (orig, pref, suf, peso, est))
            continue
        if libre not in por_familia:
            print("sin archivos para", orig, "->", libre)
            continue
        for arch, peso, est in por_familia[libre]:
            reglas.append(regla(orig, arch, peso, est, aj))

    with open(os.path.join(salida, "extra.css"), "w", encoding="utf-8") as f:
        f.write("/* Generado por herramientas/fuentes/armar_fuentes.py. No editar a mano. */\n")
        f.write("\n".join(reglas) + "\n")
    with open(os.path.join(dest, "LICENCIAS.txt"), "w", encoding="utf-8") as f:
        f.write("Fuentes libres incluidas en la app, recortadas a los alfabetos latino y griego.\n"
                "Origen: Google Fonts (github.com/google/fonts), TeX Gyre (GUST), DejaVu,\n"
                "y Selawik (github.com/microsoft/Selawik).\n\n")
        for fam in sorted(licencias):
            f.write("=" * 70 + "\n" + fam + "\n" + "=" * 70 + "\n" + licencias[fam].strip() + "\n\n")
    total = sum(os.path.getsize(os.path.join(dest, n)) for n in os.listdir(dest))
    print(len(por_familia), "familias,", len(trabajos), "archivos,", len(reglas), "reglas,",
          "%.1f MB" % (total / 1e6))


if __name__ == "__main__":
    main()
