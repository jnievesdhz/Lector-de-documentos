"""Presentación de prueba de fuentes y texto desbordado.

uso: python3 gen_pptx_fuentes.py fuentes.pptx

- Diapositiva 1: una muestra de fuentes de Google (Canva, Google Slides) y de Microsoft
  con equivalente libre, con pesos escritos en el nombre ("Montserrat SemiBold").
- Diapositiva 2: red de seguridad. Un nombre largo en una fuente que la app no tiene,
  encima de otro cuadro (debe encogerse), un cuadro que se sale sin tapar nada (no se toca)
  y un cuadro con "reducir texto al desbordar" (normAutofit) que no cabe.
"""
import sys

from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.util import Pt, Emu

prs = Presentation()
prs.slide_width, prs.slide_height = Emu(12192000), Emu(6858000)
blank = prs.slide_layouts[6]


def caja(sl, x, y, w, h, texto, fuente, pt, bold=False, italic=False, auto=None):
    tb = sl.shapes.add_textbox(Pt(x), Pt(y), Pt(w), Pt(h))
    tf = tb.text_frame
    tf.word_wrap = True
    bp = tf._txBody.find("{http://schemas.openxmlformats.org/drawingml/2006/main}bodyPr")
    for c in list(bp):
        bp.remove(c)
    if auto:
        etree.SubElement(bp, "{http://schemas.openxmlformats.org/drawingml/2006/main}" + auto)
    p = tf.paragraphs[0]
    r = p.add_run()
    r.text = texto
    r.font.name = fuente
    r.font.size = Pt(pt)
    r.font.bold = bold
    r.font.italic = italic
    r.font.color.rgb = RGBColor(0x20, 0x20, 0x28)
    return tb


s1 = prs.slides.add_slide(blank)
muestras = [
    ("Montserrat", False), ("Montserrat SemiBold", False), ("Poppins Light", False), ("Poppins", True),
    ("Open Sans", False), ("Lato", True), ("Roboto", False), ("Playfair Display", True),
    ("Lora", False), ("Raleway", False), ("Bebas Neue", False), ("Pacifico", False),
    ("Aptos", False), ("Aptos Display", True), ("Georgia", False), ("Segoe UI", False),
    ("Segoe UI Semibold", False), ("Century Gothic", False), ("Verdana", False), ("Tahoma", False),
    ("Book Antiqua", False), ("Comic Sans MS", False), ("Garamond", False), ("Consolas", False),
]
for k, (f, b) in enumerate(muestras):
    col, fila = k % 3, k // 3
    caja(s1, 30 + col * 310, 25 + fila * 62, 300, 50, f + " · Ñandú ágil", f, 20, bold=b)

s2 = prs.slides.add_slide(blank)
# Fuente que la app no tiene: el nombre se parte en dos renglones y tapa al director
caja(s2, 40, 60, 250, 26, "Andrés Felipe Cáceres Obregón", "Fuente Inexistente", 20, bold=True, auto="spAutoFit")
caja(s2, 40, 88, 250, 20, "Director: Javier Alexander Penagos", "Fuente Inexistente", 14, auto="spAutoFit")
# Se sale hacia abajo pero no tapa nada: debe quedar igual
caja(s2, 400, 60, 200, 26, "Este texto largo se sale de su cuadro por abajo, pero debajo no hay nada.",
     "Fuente Inexistente", 18)
# Reducir texto al desbordar: debe caber completo en el cuadro
tb = caja(s2, 40, 200, 300, 80,
          "Párrafo largo con ajuste automático de PowerPoint: si no cabe, el visor lo encoge "
          "hasta que entre completo en su cuadro, como hace PowerPoint al presentar.",
          "Fuente Inexistente", 24, auto="normAutofit")
tb.line.color.rgb = RGBColor(0xC4, 0x7D, 0x4F)
prs.save(sys.argv[1])
print("listo", sys.argv[1])
