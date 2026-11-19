# Pruebas de los conversores (en el escritorio, sin Android)

- `gen_xlsx.py` y `gen_docx.py`: crean libros y documentos de prueba (gráficos de Excel, estilos de tabla de Word).
  Uso: `python3 gen_xlsx.py graficos.xlsx`. Para tener una versión con los valores guardados en el gráfico,
  como los archivos de Excel reales: `soffice --headless --convert-to xlsx --outdir lo graficos.xlsx`.
- `build.sh`: compila los conversores Java y `TestMain` en `/tmp/lector-cls`.
- `shot.py`: convierte un documento, arma la página con las constantes de `Html.kt` (con un `window.Android` falso)
  y la fotografía con Playwright. Uso: `python3 shot.py doc.xlsx salida/ 420 "showSheet(1)" dark`.
- Referencia: `soffice --headless --convert-to pdf doc.docx` y `pdftoppm -png`.
