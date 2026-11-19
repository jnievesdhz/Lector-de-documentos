# Lector de documentos

App Android personal para ver documentos sin anuncios. No pide permiso de internet.

## Qué hace

- Abre PDF (zoom con dos dedos, páginas que cargan a medida que bajas, número de página)
- Abre Word (.docx): títulos, formato de texto, listas, tablas, imágenes, enlaces y notas al pie
- Abre Excel (.xlsx): todas las hojas con pestañas, fechas, monedas, porcentajes, colores y celdas combinadas
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
- En Excel se muestran hasta 3000 filas y 120 columnas por hoja. Los gráficos no se muestran.
- En Word no se muestran encabezados ni pies de página, y las imágenes EMF/WMF salen como "[imagen]".

## Próximas etapas

3. PowerPoint (.pptx)
4. Modo oscuro para documentos, búsqueda de texto, archivos recientes
