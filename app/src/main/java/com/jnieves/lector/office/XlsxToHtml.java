package com.jnieves.lector.office;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import static com.jnieves.lector.office.Ooxml.attr;
import static com.jnieves.lector.office.Ooxml.esc;
import static com.jnieves.lector.office.Ooxml.kid;
import static com.jnieves.lector.office.Ooxml.kids;
import static com.jnieves.lector.office.Ooxml.name;
import static com.jnieves.lector.office.Ooxml.parseInt;

/**
 * Convierte un libro de Excel (.xlsx) a HTML: una tabla por hoja con
 * pestañas abajo, formatos de número y fecha, negrita, colores de relleno,
 * alineación y celdas combinadas.
 */
public final class XlsxToHtml {

    static final int MAX_ROWS = 3000;
    static final int MAX_COLS = 120;
    static final int MAX_CELLS = 250_000;

    private final ZipFile zip;
    private final DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance();
    private List<String> shared = new ArrayList<>();
    private boolean date1904;
    private final Map<Integer, String> numFmts = new HashMap<>();
    private final List<Xf> xfs = new ArrayList<>();
    private final List<Font> fonts = new ArrayList<>();
    private final List<String> fills = new ArrayList<>();
    private int cellBudget = MAX_CELLS;

    private XlsxToHtml(ZipFile zip) {
        this.zip = zip;
    }

    public static String convert(File xlsx) throws Exception {
        try (ZipFile zip = new ZipFile(xlsx)) {
            return new XlsxToHtml(zip).run();
        }
    }

    private String run() throws Exception {
        String wb = Ooxml.mainPart(zip, "xl/workbook.xml");
        String base = Ooxml.dirOf(wb);
        Map<String, String[]> rels = Ooxml.rels(zip, Ooxml.relsPathFor(wb), base);
        shared = loadShared(Ooxml.partByType(rels, "/sharedStrings", base + "sharedStrings.xml"));
        loadStyles(Ooxml.partByType(rels, "/styles", base + "styles.xml"));

        Document w = Ooxml.dom(zip, wb);
        if (w == null) throw new IOException("No es un libro de Excel válido");
        Element root = w.getDocumentElement();
        Element pr = kid(root, "workbookPr");
        String d1904 = attr(pr, "date1904");
        date1904 = "1".equals(d1904) || "true".equals(d1904);

        List<String[]> sheets = new ArrayList<>(); // nombre, ruta, tipo
        for (Element s : kids(kid(root, "sheets"))) {
            if (!"sheet".equals(name(s))) continue;
            String state = attr(s, "state");
            if ("hidden".equals(state) || "veryHidden".equals(state)) continue;
            String[] r = rels.get(Ooxml.rid(s));
            if (r == null) continue;
            sheets.add(new String[]{attr(s, "name"), r[0], r[2]});
        }
        if (sheets.isEmpty()) return "<div class=\"note\">El libro no tiene hojas visibles.</div>";

        StringBuilder sb = new StringBuilder(128 * 1024);
        for (int i = 0; i < sheets.size(); i++) {
            String[] s = sheets.get(i);
            sb.append("<div class=\"sh").append(i == 0 ? " on" : "").append("\">");
            if (s[2] != null && s[2].endsWith("/chartsheet")) {
                sb.append("<div class=\"note\">Esta hoja es un gráfico y no se puede mostrar.</div>");
            } else if (cellBudget <= 0) {
                sb.append("<div class=\"note\">Esta hoja no se cargó porque el libro es muy grande.</div>");
            } else {
                renderSheet(s[1], sb);
            }
            sb.append("</div>");
        }
        if (sheets.size() > 1) {
            sb.append("<div class=\"tabs\">");
            for (int i = 0; i < sheets.size(); i++) {
                sb.append("<button").append(i == 0 ? " class=\"on\"" : "")
                        .append(" onclick=\"showSheet(").append(i).append(")\">")
                        .append(esc(sheets.get(i)[0])).append("</button>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ estilos

    private static final class Xf {
        int numFmt, font, fill;
        String align;
        boolean wrap;
    }

    private static final class Font {
        boolean b, i, u, strike;
        String color, name;
    }

    private void loadStyles(String path) throws Exception {
        Document d = Ooxml.dom(zip, path);
        if (d == null) return;
        Element root = d.getDocumentElement();
        for (Element nf : kids(kid(root, "numFmts"))) {
            numFmts.put(parseInt(attr(nf, "numFmtId"), -1), attr(nf, "formatCode"));
        }
        for (Element f : kids(kid(root, "fonts"))) {
            Font ft = new Font();
            for (Element c : kids(f)) {
                switch (name(c)) {
                    case "b": ft.b = on(c); break;
                    case "i": ft.i = on(c); break;
                    case "u": ft.u = !"none".equals(attr(c, "val")); break;
                    case "strike": ft.strike = on(c); break;
                    case "color": ft.color = rgb(attr(c, "rgb")); break;
                    case "name": ft.name = attr(c, "val"); break;
                    default: break;
                }
            }
            fonts.add(ft);
        }
        for (Element f : kids(kid(root, "fills"))) {
            String color = null;
            Element pf = kid(f, "patternFill");
            if (pf != null && "solid".equals(attr(pf, "patternType"))) {
                color = rgb(attr(kid(pf, "fgColor"), "rgb"));
            }
            fills.add(color);
        }
        for (Element x : kids(kid(root, "cellXfs"))) {
            Xf xf = new Xf();
            xf.numFmt = parseInt(attr(x, "numFmtId"), 0);
            xf.font = parseInt(attr(x, "fontId"), 0);
            xf.fill = parseInt(attr(x, "fillId"), 0);
            Element al = kid(x, "alignment");
            if (al != null) {
                xf.align = attr(al, "horizontal");
                String w = attr(al, "wrapText");
                xf.wrap = "1".equals(w) || "true".equals(w);
            }
            xfs.add(xf);
        }
    }

    private static String cssFont(String font) {
        String safe = font.replace("'", "").replace("\"", "").replace(";", "");
        return "'" + safe + "',sans-serif";
    }

    private static boolean on(Element c) {
        String v = attr(c, "val");
        return v == null || !(v.equals("0") || v.equals("false"));
    }

    private static String rgb(String argb) {
        if (argb == null) return null;
        if (argb.length() == 8) argb = argb.substring(2);
        return Ooxml.isHex6(argb) ? "#" + argb : null;
    }

    // ------------------------------------------------------- texto compartido

    private SAXParser sax() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Throwable ignored) {
        }
        return f.newSAXParser();
    }

    private static String local(String localName, String qName) {
        if (localName != null && !localName.isEmpty()) return localName;
        int i = qName.indexOf(':');
        return i >= 0 ? qName.substring(i + 1) : qName;
    }

    private List<String> loadShared(String path) throws Exception {
        final List<String> out = new ArrayList<>();
        ZipEntry e = path != null ? zip.getEntry(path) : null;
        if (e == null) return out;
        DefaultHandler h = new DefaultHandler() {
            final StringBuilder sb = new StringBuilder();
            boolean inSi, inT, inPh;

            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                String n = local(ln, qn);
                if ("si".equals(n)) {
                    inSi = true;
                    sb.setLength(0);
                } else if ("rPh".equals(n)) {
                    inPh = true;
                } else if ("t".equals(n) && inSi && !inPh) {
                    inT = true;
                }
            }

            @Override
            public void endElement(String uri, String ln, String qn) {
                String n = local(ln, qn);
                if ("si".equals(n)) {
                    out.add(sb.toString());
                    inSi = false;
                } else if ("rPh".equals(n)) {
                    inPh = false;
                } else if ("t".equals(n)) {
                    inT = false;
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inT) sb.append(ch, start, length);
            }
        };
        try (InputStream in = zip.getInputStream(e)) {
            sax().parse(in, h);
        }
        return out;
    }

    // --------------------------------------------------------------- hojas

    private static final class C {
        String t, v, is;
        int s;
    }

    private final class SheetHandler extends DefaultHandler {
        final TreeMap<Integer, TreeMap<Integer, C>> rows = new TreeMap<>();
        final Map<Integer, Double> widths = new HashMap<>();
        final Set<Integer> hiddenCols = new HashSet<>();
        final Set<Integer> hiddenRows = new HashSet<>();
        final List<int[]> merges = new ArrayList<>();
        double defaultWidth = 8.43;
        int maxRow, maxCol, stored;
        boolean truncated;

        private int row, col;
        private C cur;
        private final StringBuilder text = new StringBuilder();
        private StringBuilder inline;
        private boolean inV, inT, inPh, inIs;

        @Override
        public void startElement(String uri, String ln, String qn, Attributes a) {
            String n = local(ln, qn);
            switch (n) {
                case "row": {
                    String r = a.getValue("r");
                    row = r != null ? parseInt(r, row + 1) : row + 1;
                    col = 0;
                    String h = a.getValue("hidden");
                    if ("1".equals(h) || "true".equals(h)) hiddenRows.add(row);
                    break;
                }
                case "c": {
                    String ref = a.getValue("r");
                    if (ref != null) {
                        int[] rc = parseRef(ref);
                        if (rc != null) {
                            row = rc[0];
                            col = rc[1];
                        } else {
                            col++;
                        }
                    } else {
                        col++;
                    }
                    cur = new C();
                    cur.t = a.getValue("t");
                    cur.s = parseInt(a.getValue("s"), 0);
                    break;
                }
                case "v":
                    inV = true;
                    text.setLength(0);
                    break;
                case "is":
                    inIs = true;
                    inline = new StringBuilder();
                    break;
                case "rPh":
                    inPh = true;
                    break;
                case "t":
                    if (inIs && !inPh) {
                        inT = true;
                        text.setLength(0);
                    }
                    break;
                case "col": {
                    int min = parseInt(a.getValue("min"), 0), max = parseInt(a.getValue("max"), 0);
                    String w = a.getValue("width");
                    String h = a.getValue("hidden");
                    boolean hid = "1".equals(h) || "true".equals(h);
                    for (int c = Math.max(1, min); c <= Math.min(max, MAX_COLS); c++) {
                        if (w != null) {
                            try {
                                widths.put(c, Double.parseDouble(w));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (hid) hiddenCols.add(c);
                    }
                    break;
                }
                case "sheetFormatPr": {
                    String w = a.getValue("defaultColWidth");
                    if (w != null) {
                        try {
                            defaultWidth = Double.parseDouble(w);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                }
                case "mergeCell": {
                    String ref = a.getValue("ref");
                    if (ref != null && ref.contains(":")) {
                        int[] p = parseRef(ref.substring(0, ref.indexOf(':')));
                        int[] q = parseRef(ref.substring(ref.indexOf(':') + 1));
                        if (p != null && q != null) merges.add(new int[]{p[0], p[1], q[0], q[1]});
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public void endElement(String uri, String ln, String qn) {
            String n = local(ln, qn);
            switch (n) {
                case "v":
                    if (cur != null) cur.v = text.toString();
                    inV = false;
                    break;
                case "t":
                    if (inT && inline != null) inline.append(text);
                    inT = false;
                    break;
                case "rPh":
                    inPh = false;
                    break;
                case "is":
                    if (cur != null && inline != null) cur.is = inline.toString();
                    inIs = false;
                    inline = null;
                    break;
                case "c":
                    if (cur != null) store();
                    cur = null;
                    break;
                default:
                    break;
            }
        }

        private void store() {
            boolean hasValue = (cur.v != null && !cur.v.isEmpty()) || (cur.is != null && !cur.is.isEmpty());
            if (!hasValue && cur.s == 0) return;
            if (row > MAX_ROWS || col > MAX_COLS || stored >= cellBudget) {
                if (hasValue) truncated = true;
                return;
            }
            TreeMap<Integer, C> r = rows.get(row);
            if (r == null) {
                r = new TreeMap<>();
                rows.put(row, r);
            }
            r.put(col, cur);
            stored++;
            if (hasValue) {
                maxRow = Math.max(maxRow, row);
                maxCol = Math.max(maxCol, col);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inV || inT) text.append(ch, start, length);
        }
    }

    /** "B12" -> {12, 2} */
    static int[] parseRef(String ref) {
        int i = 0, col = 0;
        ref = ref.replace("$", "");
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        if (i == 0 || i == ref.length()) return null;
        try {
            return new int[]{Integer.parseInt(ref.substring(i)), col};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        while (c > 0) {
            int m = (c - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return sb.toString();
    }

    private void renderSheet(String path, StringBuilder sb) throws Exception {
        ZipEntry e = zip.getEntry(path);
        if (e == null) {
            sb.append("<div class=\"note\">No se encontró esta hoja.</div>");
            return;
        }
        SheetHandler h = new SheetHandler();
        try (InputStream in = zip.getInputStream(e)) {
            sax().parse(in, h);
        }
        cellBudget -= h.stored;
        if (h.maxRow == 0) {
            sb.append("<div class=\"note\">Hoja vacía</div>");
            return;
        }
        int lastRow = h.maxRow, lastCol = h.maxCol;
        for (int[] m : h.merges) {
            if (m[0] <= lastRow && m[1] <= lastCol) {
                lastRow = Math.max(lastRow, Math.min(m[2], MAX_ROWS));
                lastCol = Math.max(lastCol, Math.min(m[3], MAX_COLS));
            }
        }

        // Celdas combinadas: la esquina guarda el tamaño, las demás se omiten
        Map<Long, int[]> spans = new HashMap<>();
        Set<Long> covered = new HashSet<>();
        for (int[] m : h.merges) {
            int r1 = m[0], c1 = m[1], r2 = Math.min(m[2], lastRow), c2 = Math.min(m[3], lastCol);
            if (r1 > lastRow || c1 > lastCol) continue;
            int rs = 0, cs = 0;
            for (int r = r1; r <= r2; r++) if (!h.hiddenRows.contains(r)) rs++;
            for (int c = c1; c <= c2; c++) if (!h.hiddenCols.contains(c)) cs++;
            spans.put(key(r1, c1), new int[]{Math.max(1, rs), Math.max(1, cs)});
            for (int r = r1; r <= r2; r++) {
                for (int c = c1; c <= c2; c++) {
                    if (r != r1 || c != c1) covered.add(key(r, c));
                }
            }
        }

        String defFont = fonts.isEmpty() ? null : fonts.get(0).name;
        sb.append("<table class=\"s\"");
        if (defFont != null && !defFont.equalsIgnoreCase("Calibri")) sb.append(" style=\"font-family:").append(cssFont(defFont)).append('"');
        sb.append("><thead><tr><th class=\"corner\"></th>");
        for (int c = 1; c <= lastCol; c++) {
            if (h.hiddenCols.contains(c)) continue;
            Double w = h.widths.get(c);
            double chars = w != null ? w : h.defaultWidth;
            int px = (int) Math.round(chars * 7 + 5);
            sb.append("<th style=\"min-width:").append(Math.max(24, px)).append("px\">")
                    .append(colName(c)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");

        for (int r = 1; r <= lastRow; r++) {
            if (h.hiddenRows.contains(r)) continue;
            TreeMap<Integer, C> row = h.rows.get(r);
            sb.append("<tr><th class=\"rh\">").append(r).append("</th>");
            for (int c = 1; c <= lastCol; c++) {
                if (h.hiddenCols.contains(c)) continue;
                long k = key(r, c);
                if (covered.contains(k)) continue;
                cell(row != null ? row.get(c) : null, spans.get(k), sb);
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (h.truncated) {
            sb.append("<div class=\"note\">La hoja es muy grande: se muestran hasta ")
                    .append(MAX_ROWS).append(" filas y ").append(MAX_COLS).append(" columnas.</div>");
        }
    }

    private static long key(int r, int c) {
        return ((long) r << 20) | c;
    }

    private void cell(C c, int[] span, StringBuilder sb) {
        sb.append("<td");
        if (span != null) {
            if (span[0] > 1) sb.append(" rowspan=\"").append(span[0]).append('"');
            if (span[1] > 1) sb.append(" colspan=\"").append(span[1]).append('"');
        }
        if (c == null) {
            sb.append("></td>");
            return;
        }
        Xf xf = c.s >= 0 && c.s < xfs.size() ? xfs.get(c.s) : null;
        String text;
        boolean number = false, bool = false;
        String t = c.t;
        if ("s".equals(t)) {
            int idx = parseInt(c.v, -1);
            text = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
        } else if ("inlineStr".equals(t)) {
            text = c.is != null ? c.is : "";
        } else if ("str".equals(t) || "e".equals(t) || "d".equals(t)) {
            text = c.v != null ? c.v : "";
        } else if ("b".equals(t)) {
            text = "1".equals(c.v) ? "VERDADERO" : "FALSO";
            bool = true;
        } else if (c.v == null || c.v.isEmpty()) {
            text = "";
        } else {
            try {
                double v = Double.parseDouble(c.v);
                int fid = xf != null ? xf.numFmt : 0;
                String code = numFmts.get(fid);
                if (code == null) code = NumFmt.BUILTIN.get(fid);
                text = NumFmt.format(v, code, date1904, sym);
                number = true;
            } catch (NumberFormatException e) {
                text = c.v;
            }
        }

        StringBuilder css = new StringBuilder();
        String cls = null;
        if (xf != null) {
            if (xf.font >= 0 && xf.font < fonts.size()) {
                Font f = fonts.get(xf.font);
                if (f.b) css.append("font-weight:bold;");
                if (f.i) css.append("font-style:italic;");
                if (f.u || f.strike) {
                    css.append("text-decoration:").append(f.u ? "underline " : "").append(f.strike ? "line-through" : "").append(';');
                }
                if (f.color != null && !f.color.equalsIgnoreCase("#000000")) css.append("color:").append(f.color).append(';');
                String def = fonts.get(0).name;
                if (f.name != null && def != null && !f.name.equals(def)) css.append("font-family:").append(cssFont(f.name)).append(';');
            }
            if (xf.fill >= 0 && xf.fill < fills.size() && fills.get(xf.fill) != null) {
                css.append("background-color:").append(fills.get(xf.fill)).append(';');
            }
            if (xf.align != null) {
                switch (xf.align) {
                    case "center":
                    case "centerContinuous": css.append("text-align:center;"); break;
                    case "right": css.append("text-align:right;"); break;
                    case "left": css.append("text-align:left;"); break;
                    case "justify": css.append("text-align:justify;"); break;
                    default: break;
                }
            }
            if (xf.wrap) cls = "w";
        }
        if (cls == null && (number || bool) && (xf == null || xf.align == null)) cls = bool ? "b" : "n";
        if (cls != null) sb.append(" class=\"").append(cls).append('"');
        if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
        sb.append('>').append(esc(text)).append("</td>");
    }
}
