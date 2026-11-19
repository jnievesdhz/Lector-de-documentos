"""Documento de Word de prueba con tablas de distintos estilos."""
import sys

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import parse_xml
from docx.oxml.ns import nsdecls, qn
from docx.shared import Pt

out = sys.argv[1]
doc = Document()

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'


def b(side, val="single", sz=4, color="auto"):
    return f'<w:{side} w:val="{val}" w:sz="{sz}" w:space="0" w:color="{color}"/>'


def borders(tag, color, sides=("top", "left", "bottom", "right", "insideH", "insideV"), val="single", sz=4):
    return f"<w:{tag}>" + "".join(b(s, val, sz, color) for s in sides) + f"</w:{tag}>"


def shd(fill):
    return f'<w:shd w:val="clear" w:color="auto" w:fill="{fill}"/>'


def cond(kind, rpr="", tcpr="", ppr=""):
    return (f'<w:tblStylePr w:type="{kind}">' + (f"<w:pPr>{ppr}</w:pPr>" if ppr else "")
            + (f"<w:rPr>{rpr}</w:rPr>" if rpr else "") + "<w:tblPr/>"
            + (f"<w:tcPr>{tcpr}</w:tcPr>" if tcpr else "") + "</w:tblStylePr>")


BOLD = "<w:b/><w:bCs/>"
WHITE = '<w:color w:val="FFFFFF"/>'
NIL_IN = '<w:insideH w:val="nil"/><w:insideV w:val="nil"/>'

# Estilos modernos de Word (2013 en adelante), escritos como los guarda Word
MODERN = [
    ("GridTable4-Accent1", "Grid Table 4 Accent 1",
     "<w:tblPr><w:tblStyleRowBandSize w:val=\"1\"/><w:tblStyleColBandSize w:val=\"1\"/>"
     + borders("tblBorders", "8EAADB") + "</w:tblPr>"
     + cond("firstRow", BOLD + WHITE,
            "<w:tcBorders>" + "".join(b(s, color="4472C4") for s in ("top", "left", "bottom", "right")) + NIL_IN
            + "</w:tcBorders>" + shd("4472C4"))
     + cond("lastRow", BOLD, "<w:tcBorders>" + b("top", "double", 4, "4472C4") + "</w:tcBorders>")
     + cond("firstCol", BOLD) + cond("lastCol", BOLD)
     + cond("band1Vert", tcpr=shd("D9E2F3")) + cond("band1Horz", tcpr=shd("D9E2F3"))),
    ("GridTable5Dark-Accent2", "Grid Table 5 Dark Accent 2",
     "<w:tblPr><w:tblStyleRowBandSize w:val=\"1\"/><w:tblStyleColBandSize w:val=\"1\"/>"
     + borders("tblBorders", "FFFFFF") + "</w:tblPr><w:tcPr>" + shd("FBE4D5") + "</w:tcPr>"
     + cond("firstRow", BOLD + WHITE,
            "<w:tcBorders>" + b("top", color="FFFFFF") + b("left", color="FFFFFF") + b("bottom", "nil")
            + b("right", color="FFFFFF") + NIL_IN + "</w:tcBorders>" + shd("ED7D31"))
     + cond("lastRow", BOLD + WHITE,
            "<w:tcBorders>" + b("top", "single", 4, "FFFFFF") + NIL_IN + "</w:tcBorders>" + shd("ED7D31"))
     + cond("firstCol", BOLD + WHITE,
            "<w:tcBorders>" + b("right", "nil") + NIL_IN + "</w:tcBorders>" + shd("ED7D31"))
     + cond("lastCol", BOLD + WHITE,
            "<w:tcBorders>" + b("left", "nil") + NIL_IN + "</w:tcBorders>" + shd("ED7D31"))
     + cond("band1Vert", tcpr=shd("F7CAAC")) + cond("band1Horz", tcpr=shd("F7CAAC"))),
    ("ListTable4-Accent6", "List Table 4 Accent 6",
     "<w:tblPr><w:tblStyleRowBandSize w:val=\"1\"/><w:tblStyleColBandSize w:val=\"1\"/>"
     + borders("tblBorders", "A8D08D", ("top", "left", "bottom", "right", "insideH")) + "</w:tblPr>"
     + cond("firstRow", BOLD + WHITE,
            "<w:tcBorders>" + "".join(b(s, color="70AD47") for s in ("top", "left", "bottom", "right")) + NIL_IN
            + "</w:tcBorders>" + shd("70AD47"))
     + cond("lastRow", BOLD, "<w:tcBorders>" + b("top", "double", 4, "70AD47") + "</w:tcBorders>")
     + cond("firstCol", BOLD) + cond("lastCol", BOLD)
     + cond("band1Vert", tcpr=shd("E2EFD9")) + cond("band1Horz", tcpr=shd("E2EFD9"))),
    ("PlainTable2", "Plain Table 2",
     "<w:tblPr><w:tblStyleRowBandSize w:val=\"1\"/><w:tblStyleColBandSize w:val=\"1\"/><w:tblBorders>"
     + b("top", color="7F7F7F") + b("bottom", color="7F7F7F") + "</w:tblBorders></w:tblPr>"
     + cond("firstRow", BOLD, "<w:tcBorders>" + b("bottom", color="7F7F7F") + "</w:tcBorders>")
     + cond("lastRow", BOLD, "<w:tcBorders>" + b("top", color="7F7F7F") + "</w:tcBorders>")
     + cond("firstCol", BOLD) + cond("lastCol", BOLD)
     + cond("band1Vert", tcpr="<w:tcBorders>" + b("left", color="7F7F7F") + b("right", color="7F7F7F") + "</w:tcBorders>")
     + cond("band2Vert", tcpr="<w:tcBorders>" + b("left", color="7F7F7F") + b("right", color="7F7F7F") + "</w:tcBorders>")
     + cond("band1Horz", tcpr="<w:tcBorders>" + b("top", color="7F7F7F") + b("bottom", color="7F7F7F") + "</w:tcBorders>")),
]
styles_el = doc.styles.element
for sid, name, body in MODERN:
    styles_el.append(parse_xml(
        f'<w:style {W} w:type="table" w:styleId="{sid}"><w:name w:val="{name}"/><w:basedOn w:val="TableNormal"/>'
        f'<w:uiPriority w:val="49"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>{body}</w:style>'))

FILAS = [
    ("Especie", "Bosque", "Borde", "Potrero", "Total"),
    ("Myioborus miniatus", "14", "9", "2", "25"),
    ("Turdus fuscater", "6", "11", "8", "25"),
    ("Zonotrichia capensis", "1", "7", "15", "23"),
    ("Basileuterus tristriatus", "12", "4", "0", "16"),
    ("Total", "33", "31", "25", "89"),
]


def look(tbl, first_row=True, last_row=False, first_col=True, last_col=False, h_band=True, v_band=False):
    tblPr = tbl._tbl.tblPr
    for old in tblPr.findall(qn("w:tblLook")):
        tblPr.remove(old)
    v = (0x20 if first_row else 0) | (0x40 if last_row else 0) | (0x80 if first_col else 0) | \
        (0x100 if last_col else 0) | (0 if h_band else 0x200) | (0 if v_band else 0x400)
    tblPr.append(parse_xml(
        f'<w:tblLook {nsdecls("w")} w:val="{v:04X}" w:firstRow="{int(first_row)}" w:lastRow="{int(last_row)}" '
        f'w:firstColumn="{int(first_col)}" w:lastColumn="{int(last_col)}" w:noHBand="{int(not h_band)}" '
        f'w:noVBand="{int(not v_band)}"/>'))


def tabla(titulo, estilo, nota="", filas=FILAS, **kw):
    doc.add_heading(titulo, level=2)
    if nota:
        doc.add_paragraph(nota)
    t = doc.add_table(rows=len(filas), cols=len(filas[0]))
    if estilo:
        t.style = doc.styles[estilo] if estilo in [s.name for s in doc.styles] else estilo
    for i, fila in enumerate(filas):
        for j, txt in enumerate(fila):
            t.cell(i, j).text = txt
    look(t, **kw)
    return t


doc.add_heading("Prueba de estilos de tabla", level=1)
doc.add_paragraph("Cada tabla usa un estilo distinto. Debajo de cada título se indica qué opciones "
                  "de estilo (fila de encabezado, filas con bandas, primera columna...) están activas.")

tabla("1. Sin estilo (sin bordes)", None, "La tabla no tiene estilo: en Word no se ven bordes al imprimir.")
tabla("2. Cuadrícula de tabla", "Table Grid", "El estilo más común: bordes negros finos en todas las celdas.")
tabla("3. Tabla de cuadrícula 4, énfasis 1", "Grid Table 4 Accent 1",
      "Encabezado, primera columna y filas con bandas.", last_row=True)
tabla("4. Tabla de cuadrícula 5 oscura, énfasis 2", "Grid Table 5 Dark Accent 2",
      "Encabezado, primera columna, filas con bandas.")
tabla("5. Tabla de lista 4, énfasis 6", "List Table 4 Accent 6",
      "Encabezado y fila de totales, sin primera columna, sin bandas.", last_row=True, first_col=False, h_band=False)
tabla("6. Tabla normal 2", "Plain Table 2", "Encabezado y columnas con bandas.", first_col=False, h_band=False,
      v_band=True)
tabla("7. Sombreado claro, énfasis 1 (Word 2010)", "Light Shading Accent 1")
tabla("8. Lista clara, énfasis 2 (Word 2010)", "Light List Accent 2", last_row=True)
tabla("9. Cuadrícula clara, énfasis 3 (Word 2010)", "Light Grid Accent 3")
tabla("10. Sombreado medio 1, énfasis 5 (Word 2010)", "Medium Shading 1 Accent 5", last_row=True)
tabla("11. Cuadrícula media 3, énfasis 4 (Word 2010)", "Medium Grid 3 Accent 4")
tabla("12. Lista oscura (Word 2010)", "Dark List")
tabla("13. Lista colorida, énfasis 6 (Word 2010)", "Colorful List Accent 6")

# 14. Bordes y sombreado puestos a mano
doc.add_heading("14. Bordes y sombreado a mano", level=2)
doc.add_paragraph("Borde exterior grueso rojo, líneas interiores discontinuas, una celda con fondo amarillo "
                  "y borde verde doble, texto centrado verticalmente y tabla centrada.")
t = doc.add_table(rows=4, cols=3)
t.alignment = WD_TABLE_ALIGNMENT.CENTER
tblPr = t._tbl.tblPr
tblPr.append(parse_xml(f'<w:tblBorders {nsdecls("w")}>' + b("top", "single", 18, "C00000") + b("left", "single", 18, "C00000")
                       + b("bottom", "single", 18, "C00000") + b("right", "single", 18, "C00000")
                       + b("insideH", "dashed", 6, "808080") + b("insideV", "dotted", 6, "808080") + "</w:tblBorders>"))
txt = [("Parcela", "Cobertura", "Observación"), ("P1", "85 %", "Dosel cerrado"),
       ("P2", "40 %", "Claro reciente\ncon helechos"), ("P3", "62 %", "Cerca del río")]
for i, fila in enumerate(txt):
    for j, s in enumerate(fila):
        t.cell(i, j).text = s
for c in t.rows[0].cells:
    c.paragraphs[0].runs[0].bold = True
cell = t.cell(2, 1)
cell._tc.get_or_add_tcPr().append(parse_xml(
    f'<w:tcBorders {nsdecls("w")}>' + "".join(b(s, "double", 6, "00B050") for s in ("top", "left", "bottom", "right"))
    + "</w:tcBorders>"))
cell._tc.get_or_add_tcPr().append(parse_xml(f'<w:shd {nsdecls("w")} w:val="clear" w:color="auto" w:fill="FFFF00"/>'))
for r in range(4):
    t.cell(r, 1)._tc.get_or_add_tcPr().append(parse_xml(f'<w:vAlign {nsdecls("w")} w:val="center"/>'))
    t.cell(r, 0)._tc.get_or_add_tcPr().append(parse_xml(f'<w:vAlign {nsdecls("w")} w:val="bottom"/>'))

# 15. Celdas combinadas con estilo
doc.add_heading("15. Celdas combinadas con estilo", level=2)
doc.add_paragraph("Encabezado de dos niveles con celdas combinadas, estilo Grid Table 4 Accent 1.")
t = doc.add_table(rows=5, cols=4)
t.style = "Grid Table 4 Accent 1"
a = t.cell(0, 0).merge(t.cell(1, 0)); a.text = "Sitio"
m = t.cell(0, 1).merge(t.cell(0, 3)); m.text = "Abundancia por temporada"
for j, s in enumerate(["Seca", "Lluvias", "Transición"]):
    t.cell(1, j + 1).text = s
for i, fila in enumerate([("Yurumí", "12", "30", "18"), ("Cimitarra", "8", "21", "15"), ("El Bosque", "5", "9", "7")]):
    for j, s in enumerate(fila):
        t.cell(i + 2, j).text = s
look(t, first_row=True, first_col=True, h_band=True)

doc.add_paragraph("Fin de la prueba.")
doc.save(out)
print("ok", out)
