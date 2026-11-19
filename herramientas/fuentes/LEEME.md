# Paquete de fuentes libres

La app trae unas 170 familias libres en `app/src/main/assets/fonts/extra/`, con sus reglas
`@font-face` en `app/src/main/assets/fonts/extra.css` (que `FONT_CSS` de `Html.kt` importa).
Así, las presentaciones hechas en Canva, Google Slides o PowerPoint se ven con su fuente
real (o una con las mismas medidas) y el texto no se sale de los cuadros.

- `familias.txt`: carpetas de github.com/google/fonts que entran (fuentes de Google Slides,
  Canva y las más usadas, más Gelasio, Arimo, Tinos, Cousine y Comic Relief).
- Cada familia se recorta a los alfabetos latino y griego con signos y símbolos comunes, sin
  hinting. En las variables solo queda el eje de peso. En las de archivos sueltos quedan los
  pesos rectos de 300 a 900 y las cursivas normal y negrita.
- Equivalencias de fuentes de pago (`EQUIVALENCIAS` en `armar_fuentes.py`):
  - Mismas medidas: Georgia → Gelasio, Segoe UI → Selawik, Comic Sans MS → Comic Relief,
    Century Gothic → TeX Gyre Adventor, Book Antiqua/Palatino → TeX Gyre Pagella,
    Bookman → TeX Gyre Bonum, Century Schoolbook → TeX Gyre Schola.
  - Parecidas: Aptos → Source Sans 3 al 97 % (Akasia, la gemela libre de Aptos, desapareció de
    Codeberg en noviembre de 2026), Verdana/Tahoma → DejaVu Sans (normal/condensada),
    Garamond → EB Garamond, Impact → Anton, Arial Black → Archivo Black,
    Franklin Gothic → Libre Franklin, Trebuchet MS → Fira Sans, Consolas → Inconsolata al 110 %,
    Canva Sans → Open Sans.
- Los pesos escritos en el nombre ("Montserrat SemiBold", "Segoe UI Light") los resuelve
  `PptxToHtml.faceWeight`.

## Cómo rearmarlo

```
git clone --depth 1 --filter=blob:none --sparse https://github.com/google/fonts gfonts
cd gfonts && git sparse-checkout set --no-cone $(grep -v '^#' ../lector-de-documentos/herramientas/fuentes/familias.txt | sed 's|$|/*|') && cd ..
python3 lector-de-documentos/herramientas/fuentes/armar_fuentes.py gfonts lector-de-documentos/fuentes-externas lector-de-documentos/app/src/main/assets/fonts
```

Necesita `fontTools`, y TeX Gyre y DejaVu instalados en el sistema (`/usr/share/texmf/...`,
`/usr/share/fonts/truetype/dejavu`). Selawik viene de `fuentes-externas/selawik`, que llena
el flujo "Bajar fuentes externas" de GitHub Actions (se corre a mano).
