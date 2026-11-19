"""Libros de Excel de prueba con varios tipos de gráfico."""
import datetime as dt
import sys

from openpyxl import Workbook
from openpyxl.chart import AreaChart, BarChart, DoughnutChart, LineChart, PieChart, Reference, ScatterChart, Series
from openpyxl.chart.label import DataLabelList
from openpyxl.drawing.colors import ColorChoice, SchemeColor
from openpyxl.styles import Font, PatternFill

out = sys.argv[1]
wb = Workbook()

# ---------------------------------------------------------------- Ventas
ws = wb.active
ws.title = "Muestreo"
ws.append(["Mes", "Coleópteros", "Arácnidos", "Lepidópteros"])
datos = [
    ("Enero", 34, 21, 12), ("Febrero", 41, 25, 18), ("Marzo", 52, 30, 27),
    ("Abril", 47, 28, 35), ("Mayo", 60, 33, 41), ("Junio", 55, 38, 30),
]
for d in datos:
    ws.append(d)
for c in ws[1]:
    c.font = Font(bold=True, color="FFFFFF")
    c.fill = PatternFill("solid", fgColor="4472C4")
ws.column_dimensions["A"].width = 12
for col in "BCD":
    ws.column_dimensions[col].width = 13

cats = Reference(ws, min_col=1, min_row=2, max_row=7)
vals = Reference(ws, min_col=2, min_row=1, max_col=4, max_row=7)

col = BarChart()
col.type = "col"
col.title = "Individuos por mes"
col.y_axis.title = "Individuos"
col.x_axis.title = "Mes"
col.add_data(vals, titles_from_data=True)
col.set_categories(cats)
col.width, col.height = 15, 7.5
ws.add_chart(col, "F2")

line = LineChart()
line.title = "Tendencia"
line.add_data(vals, titles_from_data=True)
line.set_categories(cats)
for s in line.series:
    s.marker.symbol = "circle"
line.width, line.height = 15, 7.5
ws.add_chart(line, "F18")

stacked = BarChart()
stacked.type = "bar"
stacked.grouping = "stacked"
stacked.overlap = 100
stacked.title = "Total apilado (barras horizontales)"
stacked.add_data(vals, titles_from_data=True)
stacked.set_categories(cats)
stacked.width, stacked.height = 15, 7.5
ws.add_chart(stacked, "A34")

pie = PieChart()
pie.title = "Proporción en enero"
pie.add_data(Reference(ws, min_col=2, min_row=2, max_col=4, max_row=2), from_rows=True)
pie.set_categories(Reference(ws, min_col=2, min_row=1, max_col=4, max_row=1))
pie.dataLabels = DataLabelList()
pie.dataLabels.showPercent = True
pie.width, pie.height = 10, 7.5
ws.add_chart(pie, "L34")

ws.append([])
ws["A9"] = "Total"
ws["A9"].font = Font(bold=True)
for i, colname in enumerate("BCD"):
    ws[f"{colname}9"] = f"=SUM({colname}2:{colname}7)"

# ---------------------------------------------------------------- Clima
w2 = wb.create_sheet("Clima")
w2.append(["Fecha", "Temperatura (°C)", "Humedad (%)", "Lluvia (mm)"])
base = dt.date(2026, 9, 1)
temps = [24.1, 25.3, 23.8, 26.9, 27.4, 22.5, 21.9, 24.8, 26.1, 28.0]
hums = [78, 74, 81, 69, 65, 88, 90, 76, 71, 62]
rain = [3.2, 0.0, 12.5, 0.4, 0.0, 25.1, 31.0, 4.4, 1.2, 0.0]
for i in range(10):
    w2.append([base + dt.timedelta(days=i * 3), temps[i], hums[i], rain[i]])
    w2.cell(row=i + 2, column=1).number_format = "dd/mm/yyyy"
w2.column_dimensions["A"].width = 12
w2.column_dimensions["B"].width = 17
w2.column_dimensions["C"].width = 13
w2.column_dimensions["D"].width = 12

sc = ScatterChart()
sc.title = "Temperatura frente a humedad"
sc.style = 13
sc.x_axis.title = "Temperatura (°C)"
sc.y_axis.title = "Humedad (%)"
ser = Series(Reference(w2, min_col=3, min_row=2, max_row=11), Reference(w2, min_col=2, min_row=2, max_row=11),
             title="Días de muestreo")
ser.marker.symbol = "circle"
ser.graphicalProperties.line.noFill = True
sc.series.append(ser)
sc.width, sc.height = 13, 7.5
w2.add_chart(sc, "F2")

area = AreaChart()
area.title = "Lluvia acumulada"
area.add_data(Reference(w2, min_col=4, min_row=1, max_row=11), titles_from_data=True)
area.set_categories(Reference(w2, min_col=1, min_row=2, max_row=11))
area.width, area.height = 13, 7
# Color del tema (como hace Excel): énfasis 5
area.series[0].graphicalProperties.solidFill = ColorChoice(schemeClr=SchemeColor(val="accent5"))
w2.add_chart(area, "F18")

dn = DoughnutChart()
dn.title = "Tipo de hábitat"
w2["A14"] = "Hábitat"
w2["B14"] = "Parcelas"
for i, (h, n) in enumerate([("Bosque", 14), ("Borde", 9), ("Potrero", 5), ("Río", 4)]):
    w2.cell(row=15 + i, column=1, value=h)
    w2.cell(row=15 + i, column=2, value=n)
dn.add_data(Reference(w2, min_col=2, min_row=14, max_row=18), titles_from_data=True)
dn.set_categories(Reference(w2, min_col=1, min_row=15, max_row=18))
dn.dataLabels = DataLabelList()
dn.dataLabels.showVal = True
dn.width, dn.height = 10, 7
w2.add_chart(dn, "A22")

# ------------------------------------------------- combinado en hoja aparte
cs = wb.create_chartsheet("Gráfico combinado")
bar = BarChart()
bar.title = "Coleópteros y lluvia"
bar.add_data(Reference(ws, min_col=2, min_row=1, max_row=7), titles_from_data=True)
bar.set_categories(cats)
bar.y_axis.title = "Individuos"
ln = LineChart()
ln.add_data(Reference(ws, min_col=4, min_row=1, max_row=7), titles_from_data=True)
ln.y_axis.axId = 200
ln.y_axis.title = "Lepidópteros"
ln.y_axis.crosses = "max"
bar += ln
cs.add_chart(bar)

wb.save(out)
print("ok", out)
