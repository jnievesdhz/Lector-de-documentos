package com.jnieves.lector.office;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.jnieves.lector.office.Ooxml.esc;

/** Muestra un archivo CSV/TSV como tabla, igual que una hoja de Excel. */
public final class CsvToHtml {
    private CsvToHtml() {
    }

    private static final int MAX_ROWS = 5000;
    private static final int MAX_COLS = 200;
    private static final Pattern NUMBER = Pattern.compile("^[-+]?[$€]?\\s?[\\d.,]+%?$");

    public static String convert(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        char delim = detect(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false, truncated = false;
        int maxCols = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        q = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"' && cur.length() == 0) {
                q = true;
            } else if (c == delim) {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cur.toString());
                cur.setLength(0);
                maxCols = Math.max(maxCols, row.size());
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() >= MAX_ROWS) {
                    truncated = i + 1 < text.length();
                    break;
                }
            } else {
                cur.append(c);
            }
        }
        if (rows.size() < MAX_ROWS && (cur.length() > 0 || !row.isEmpty())) {
            row.add(cur.toString());
            maxCols = Math.max(maxCols, row.size());
            rows.add(row);
        }
        if (rows.isEmpty()) return "<div class=\"note\">El archivo está vacío.</div>";
        int cols = Math.min(maxCols, MAX_COLS);

        StringBuilder sb = new StringBuilder(text.length() * 2 + 1024);
        sb.append("<div class=\"sh on\"><table class=\"s\"><thead><tr><th class=\"corner\"></th>");
        for (int c = 1; c <= cols; c++) {
            sb.append("<th style=\"min-width:48px\">").append(XlsxToHtml.colName(c)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            sb.append("<tr><th class=\"rh\">").append(r + 1).append("</th>");
            for (int c = 0; c < cols; c++) {
                String v = c < cells.size() ? cells.get(c) : "";
                boolean num = !v.isEmpty() && NUMBER.matcher(v.trim()).matches();
                sb.append(num ? "<td class=\"n\">" : "<td>").append(esc(v)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (truncated || maxCols > MAX_COLS) {
            sb.append("<div class=\"note\">El archivo es muy grande: se muestran hasta ")
                    .append(MAX_ROWS).append(" filas y ").append(MAX_COLS).append(" columnas.</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Elige el separador más frecuente en la primera línea: coma, punto y coma o tabulación. */
    private static char detect(String text) {
        int comma = 0, semi = 0, tab = 0;
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') q = !q;
            if (q) continue;
            if (c == '\n') break;
            if (c == ',') comma++;
            else if (c == ';') semi++;
            else if (c == '\t') tab++;
        }
        if (tab > comma && tab > semi) return '\t';
        if (semi > comma) return ';';
        return ',';
    }
}
