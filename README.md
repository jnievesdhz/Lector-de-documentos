# Lector de documentos

App Android personal para ver documentos sin anuncios. No pide permiso de internet.

## Qué hace

- Pantalla de inicio con **Recientes** (agrupados por fecha), **Favoritos** y **Carpetas**, en lista o en cuadrícula con miniaturas
- Filtros por tipo, búsqueda por nombre y menú de cada archivo (abrir, favoritos, compartir, abrir con, detalles, quitar)
- Los archivos que llegan desde otras apps (WhatsApp, Compartir) se guardan como copia para poder reabrirlos desde Recientes
- Abre PDF (zoom con dos dedos, páginas que cargan a medida que bajas, número de página)
- Abre Word (.docx): títulos, formato de texto, listas, tablas, imágenes, enlaces y notas al pie
- Abre Excel (.xlsx): todas las hojas con pestañas, fechas, monedas, porcentajes, colores y celdas combinadas
- Abre PowerPoint (.pptx): cada diapositiva a escala con textos, formas, imágenes, tablas, SmartArt y notas del orador
- Dibuja los gráficos (columnas, barras, líneas, áreas, circulares, anillos y dispersión) en PowerPoint y Word
- Abre CSV como tabla (detecta coma, punto y coma o tabulación)
- Abre imágenes (JPG, PNG, WEBP, GIF, HEIC…) y archivos de texto (TXT, JSON, MD…)
- Aparece en "Abrir con" y en "Compartir" de otras apps
- Botón para compartir el archivo abierto a WhatsApp u otras apps
- Tema claro u oscuro según el teléfono

## Cómo obtener el APK

Cada vez que se sube un cambio a `main`, GitHub Actions compila la app sola.
El APK queda en la sección **Releases** del repositorio (y también en **Actions** → la ejecución → *Artifacts*).

## Instalar en el teléfono

1. Descarga el `.apk` desde Releases en el navegador del celular.
2. Ábrelo. Android pedirá permitir "instalar apps desconocidas" para el navegador o el gestor de archivos.
3. Si Play Protect muestra una advertencia, elige "Instalar de todos modos".

Las versiones nuevas se instalan encima de la anterior (están firmadas con la misma llave, `app/keystore/lector.jks`).

## Límites conocidos

- Los formatos antiguos (.doc, .xls, .ppt) y los archivos con contraseña no se pueden abrir.
- En Excel se muestran hasta 3000 filas y 120 columnas por hoja. Los gráficos de Excel no se muestran.
- En PowerPoint no hay animaciones ni transiciones. Las formas poco comunes (flechas curvas, nubes, corazones) no se dibujan, pero su texto sí se muestra.
- Si el celular no tiene la fuente de la presentación, usa una parecida y encoge el texto que no quepa en su cuadro.
- En Word no se muestran encabezados ni pies de página, y las imágenes EMF/WMF salen como "[imagen]".

## Próximas etapas

4. Interfaz nueva (en curso): visor con barras que se esconden y herramientas abajo, modo oscuro para documentos, búsqueda de texto, pestañas y panel lateral
