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
import java.util.Base64;
import java.util.Locale;
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
 * alineación, celdas combinadas, gráficos e imágenes.
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
    private final Map<String, String> theme = new HashMap<>();
    /** Nombre de hoja -> ruta, para leer las celdas que usan los gráficos. */
    private final Map<String, String> sheetPaths = new HashMap<>();
    private final Map<String, SheetHandler> parsed = new HashMap<>();
    private String currentSheet;
    private long mediaBytes;

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
        loadTheme(Ooxml.partByType(rels, "/theme", base + "theme/theme1.xml"));

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
            if (attr(s, "name") != null) sheetPaths.put(attr(s, "name"), r[0]);
            sheets.add(new String[]{attr(s, "name"), r[0], r[2]});
        }
        if (sheets.isEmpty()) return "<div class=\"note\">El libro no tiene hojas visibles.</div>";

        StringBuilder sb = new StringBuilder(128 * 1024);
        for (int i = 0; i < sheets.size(); i++) {
            String[] s = sheets.get(i);
            sb.append("<div class=\"sh").append(i == 0 ? " on" : "").append("\">");
            currentSheet = s[0];
            if (s[2] != null && s[2].endsWith("/chartsheet")) {
                chartSheet(s[1], sb);
            } else if (cellBudget <= 0) {
                sb.append("<div class=\"note\">Esta hoja no se cargó porque el libro es muy grande.</div>");
            } else {
                renderSheet(s[0], s[1], sb);
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
        final Map<Integer, Double> heights = new HashMap<>();
        double defaultWidth = 8.43, defaultHeight = 15;
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
                    String ht = a.getValue("ht");
                    if (ht != null) {
                        try {
                            heights.put(row, Double.parseDouble(ht));
                        } catch (NumberFormatException ignored) {
                        }
                    }
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
                    String dh = a.getValue("defaultRowHeight");
                    if (dh != null) {
                        try {
                            defaultHeight = Double.parseDouble(dh);
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

    private SheetHandler parse(String path) throws Exception {
        ZipEntry e = path != null ? zip.getEntry(path) : null;
        if (e == null) return null;
        SheetHandler h = new SheetHandler();
        try (InputStream in = zip.getInputStream(e)) {
            sax().parse(in, h);
        }
        return h;
    }

    private void renderSheet(String name, String path, StringBuilder sb) throws Exception {
        SheetHandler h = parse(path);
        if (h == null) {
            sb.append("<div class=\"note\">No se encontró esta hoja.</div>");
            return;
        }
        if (name != null) parsed.put(name, h);
        cellBudget -= h.stored;
        List<Anchor> anchors = drawings(path, h);
        if (h.maxRow == 0 && anchors.isEmpty()) {
            sb.append("<div class=\"note\">Hoja vacía</div>");
            return;
        }
        int lastRow = Math.max(1, h.maxRow), lastCol = Math.max(1, h.maxCol);
        for (int[] m : h.merges) {
            if (m[0] <= lastRow && m[1] <= lastCol) {
                lastRow = Math.max(lastRow, Math.min(m[2], MAX_ROWS));
                lastCol = Math.max(lastCol, Math.min(m[3], MAX_COLS));
            }
        }
        // La cuadrícula llega hasta donde termina el último gráfico
        for (Anchor an : anchors) {
            lastRow = Math.max(lastRow, Math.min(an.r2 + 1, Math.min(MAX_ROWS, h.maxRow + 300)));
            lastCol = Math.max(lastCol, Math.min(an.c2 + 1, Math.min(MAX_COLS, h.maxCol + 60)));
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
        sb.append("<div class=\"sw\"><table class=\"s\"");
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
        if (!anchors.isEmpty()) placeDrawings(anchors, h, lastRow, lastCol, sb);
        sb.append("</div>");
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
        String[] tk = textOf(c, xf);
        String text = tk[0];
        boolean number = "n".equals(tk[1]), bool = "b".equals(tk[1]);

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

    /** Texto de una celda como se ve en Excel, y si es número ("n") o lógico ("b"). */
    private String[] textOf(C c, Xf xf) {
        String t = c.t;
        if ("s".equals(t)) {
            int idx = parseInt(c.v, -1);
            return new String[]{idx >= 0 && idx < shared.size() ? shared.get(idx) : "", null};
        }
        if ("inlineStr".equals(t)) return new String[]{c.is != null ? c.is : "", null};
        if ("str".equals(t) || "e".equals(t) || "d".equals(t)) return new String[]{c.v != null ? c.v : "", null};
        if ("b".equals(t)) return new String[]{"1".equals(c.v) ? "VERDADERO" : "FALSO", "b"};
        if (c.v == null || c.v.isEmpty()) return new String[]{"", null};
        try {
            double v = Double.parseDouble(c.v);
            int fid = xf != null ? xf.numFmt : 0;
            String code = numFmts.get(fid);
            if (code == null) code = NumFmt.BUILTIN.get(fid);
            return new String[]{NumFmt.format(v, code, date1904, sym), "n"};
        } catch (NumberFormatException e) {
            return new String[]{c.v, null};
        }
    }

    // ------------------------------------------------------------ tema

    private void loadTheme(String path) {
        try {
            Document d = Ooxml.dom(zip, path);
            if (d == null) return;
            Element te = kid(d.getDocumentElement(), "themeElements");
            for (Element c : kids(kid(te, "clrScheme"))) {
                List<Element> k = kids(c);
                if (k.isEmpty()) continue;
                Element v = k.get(0);
                String hex = "sysClr".equals(name(v)) ? attr(v, "lastClr") : attr(v, "val");
                if (Ooxml.isHex6(hex)) theme.put(name(c), hex);
            }
        } catch (Exception ignored) {
        }
    }

    private String scheme(String v) {
        if (v == null) return null;
        switch (v) {
            case "tx1": v = "dk1"; break;
            case "bg1": v = "lt1"; break;
            case "tx2": v = "dk2"; break;
            case "bg2": v = "lt2"; break;
            default: break;
        }
        String h = theme.get(v);
        if (h == null) {
            String[] def = {"accent1", "4472C4", "accent2", "ED7D31", "accent3", "A5A5A5", "accent4", "FFC000",
                    "accent5", "5B9BD5", "accent6", "70AD47", "dk1", "000000", "lt1", "FFFFFF", "dk2", "44546A", "lt2", "E7E6E6"};
            for (int i = 0; i < def.length; i += 2) if (def[i].equals(v)) h = def[i + 1];
        }
        return h;
    }

    private final ChartSvg.Colors colors = new ChartSvg.Colors() {
        @Override
        public String of(Element holder) {
            if (holder == null) return null;
            for (Element c : kids(holder)) {
                String hex;
                switch (name(c)) {
                    case "srgbClr": hex = attr(c, "val"); break;
                    case "schemeClr": hex = scheme(attr(c, "val")); break;
                    case "sysClr": hex = attr(c, "lastClr"); break;
                    default: continue;
                }
                if (!Ooxml.isHex6(hex)) return null;
                return PptxToHtml.mods(hex, c);
            }
            return null;
        }

        @Override
        public String accent(int n) {
            String h = scheme("accent" + n);
            return h != null ? "#" + h : null;
        }
    };

    // ---------------------------------------------------- celdas para gráficos

    private final ChartSvg.Data data = (formula, asText) -> {
        try {
            String f = formula.trim();
            if (f.startsWith("(") && f.endsWith(")")) f = f.substring(1, f.length() - 1);
            List<String> out = new ArrayList<>();
            for (String part : splitUnion(f)) {
                String sheet = currentSheet, ref = part.trim();
                int bang = ref.lastIndexOf('!');
                if (bang >= 0) {
                    sheet = ref.substring(0, bang);
                    ref = ref.substring(bang + 1);
                    if (sheet.startsWith("'") && sheet.endsWith("'") && sheet.length() >= 2) {
                        sheet = sheet.substring(1, sheet.length() - 1).replace("''", "'");
                    }
                }
                SheetHandler h = parsed.get(sheet);
                if (h == null) {
                    h = parse(sheetPaths.get(sheet));
                    if (h == null) return null;
                    parsed.put(sheet, h);
                }
                String a = ref, b = ref;
                int colon = ref.indexOf(':');
                if (colon >= 0) {
                    a = ref.substring(0, colon);
                    b = ref.substring(colon + 1);
                }
                int[] p = parseRef(a), q = parseRef(b);
                if (p == null || q == null) return null;
                for (int r = Math.min(p[0], q[0]); r <= Math.max(p[0], q[0]) && out.size() < 5000; r++) {
                    TreeMap<Integer, C> row = h.rows.get(r);
                    for (int c = Math.min(p[1], q[1]); c <= Math.max(p[1], q[1]); c++) {
                        C cell = row != null ? row.get(c) : null;
                        if (cell == null) {
                            out.add("");
                        } else if (asText) {
                            out.add(textOf(cell, cell.s >= 0 && cell.s < xfs.size() ? xfs.get(cell.s) : null)[0]);
                        } else {
                            String t = cell.t;
                            out.add(t == null || "n".equals(t) ? (cell.v != null ? cell.v : "") : "");
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    };

    private static List<String> splitUnion(String f) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char ch : f.toCharArray()) {
            if (ch == '\'') q = !q;
            if (ch == ',' && !q) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------- gráficos e imágenes

    /** Un gráfico o imagen anclado a la hoja. Filas y columnas desde 0, desfases en EMU. */
    private static final class Anchor {
        int r1, c1, r2, c2;
        long dr1, dc1, dr2, dc2;
        boolean two;
        long cx, cy;
        String chart, image;
    }

    private static final double EMU_PX = 9525;

    private double colPx(SheetHandler h, int col) {
        if (h.hiddenCols.contains(col)) return 0;
        Double w = h.widths.get(col);
        return Math.round((w != null ? w : h.defaultWidth) * 7 + 5);
    }

    private double rowPx(SheetHandler h, int row) {
        if (h.hiddenRows.contains(row)) return 0;
        Double ht = h.heights.get(row);
        return (ht != null ? ht : h.defaultHeight) * 4 / 3;
    }

    private List<Anchor> drawings(String sheetPath, SheetHandler h) {
        List<Anchor> out = new ArrayList<>();
        try {
            Map<String, String[]> srels = Ooxml.rels(zip, Ooxml.relsPathFor(sheetPath), Ooxml.dirOf(sheetPath));
            for (String[] r : srels.values()) {
                if (r[2] == null || !r[2].endsWith("/drawing")) continue;
                Document d = Ooxml.dom(zip, r[0]);
                if (d == null) continue;
                Map<String, String[]> drels = Ooxml.rels(zip, Ooxml.relsPathFor(r[0]), Ooxml.dirOf(r[0]));
                for (Element an : kids(d.getDocumentElement())) {
                    String kind = name(an);
                    if (!kind.endsWith("Anchor")) continue;
                    Anchor a = new Anchor();
                    Element from = kid(an, "from");
                    if (from != null) {
                        a.c1 = parseInt(kid(from, "col").getTextContent().trim(), 0);
                        a.r1 = parseInt(kid(from, "row").getTextContent().trim(), 0);
                        a.dc1 = Ooxml.parseLong(text(kid(from, "colOff")), 0);
                        a.dr1 = Ooxml.parseLong(text(kid(from, "rowOff")), 0);
                    }
                    Element to = kid(an, "to");
                    Element ext = kid(an, "ext");
                    if ("twoCellAnchor".equals(kind) && to != null) {
                        a.two = true;
                        a.c2 = parseInt(text(kid(to, "col")), a.c1);
                        a.r2 = parseInt(text(kid(to, "row")), a.r1);
                        a.dc2 = Ooxml.parseLong(text(kid(to, "colOff")), 0);
                        a.dr2 = Ooxml.parseLong(text(kid(to, "rowOff")), 0);
                    } else {
                        if (ext == null) ext = Ooxml.firstDescendant(an, "ext");
                        a.cx = ext != null ? Ooxml.parseLong(attr(ext, "cx"), 0) : 0;
                        a.cy = ext != null ? Ooxml.parseLong(attr(ext, "cy"), 0) : 0;
                        if (a.cx <= 0 || a.cy <= 0) {
                            a.cx = 480 * (long) EMU_PX;
                            a.cy = 290 * (long) EMU_PX;
                        }
                        if ("absoluteAnchor".equals(kind)) {
                            // Posición en EMU desde la esquina: se pasa a celda
                            Element pos = kid(an, "pos");
                            double x = Ooxml.parseLong(attr(pos, "x"), 0) / EMU_PX, y = Ooxml.parseLong(attr(pos, "y"), 0) / EMU_PX;
                            int c = 1;
                            while (c < MAX_COLS && x >= colPx(h, c)) x -= colPx(h, c++);
                            int rr = 1;
                            while (rr < MAX_ROWS && y >= rowPx(h, rr)) y -= rowPx(h, rr++);
                            a.c1 = c - 1;
                            a.r1 = rr - 1;
                            a.dc1 = Math.round(x * EMU_PX);
                            a.dr1 = Math.round(y * EMU_PX);
                        }
                        // Celda donde termina, para que la cuadrícula llegue hasta ahí
                        double x = a.dc1 / EMU_PX + a.cx / EMU_PX, y = a.dr1 / EMU_PX + a.cy / EMU_PX;
                        int c = a.c1 + 1;
                        while (c < MAX_COLS && x > colPx(h, c)) x -= colPx(h, c++);
                        int rr = a.r1 + 1;
                        while (rr < MAX_ROWS && y > rowPx(h, rr)) y -= rowPx(h, rr++);
                        a.c2 = c - 1;
                        a.r2 = rr - 1;
                    }
                    Element ch = Ooxml.firstDescendant(an, "chart");
                    if (ch != null) {
                        String[] cr = drels.get(Ooxml.rid(ch));
                        if (cr != null) a.chart = cr[0];
                    } else {
                        Element blip = Ooxml.firstDescendant(an, "blip");
                        if (blip != null) {
                            String[] ir = drels.get(attr(blip, "embed"));
                            if (ir != null && !"External".equals(ir[1])) a.image = ir[0];
                        }
                    }
                    if (a.chart != null || a.image != null) out.add(a);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String text(Element e) {
        return e != null ? e.getTextContent().trim() : null;
    }

    /**
     * Escribe los gráficos e imágenes encima de la tabla. La posición final la
     * calcula el JavaScript con el tamaño real de las celdas: cada punto es
     * "fila de la tabla, columna de la tabla, fracción en x, fracción en y".
     */
    private void placeDrawings(List<Anchor> anchors, SheetHandler h, int lastRow, int lastCol, StringBuilder sb) {
        // Índices de filas y columnas visibles
        int[] rowIdx = new int[lastRow + 2], colIdx = new int[lastCol + 2];
        int k = 0;
        for (int r = 1; r <= lastRow; r++) {
            rowIdx[r] = k;
            if (!h.hiddenRows.contains(r)) k++;
        }
        rowIdx[lastRow + 1] = k;
        k = 1;
        for (int c = 1; c <= lastCol; c++) {
            colIdx[c] = k;
            if (!h.hiddenCols.contains(c)) k++;
        }
        colIdx[lastCol + 1] = k;
        int maxRowIdx = rowIdx[lastRow + 1], maxColIdx = colIdx[lastCol + 1];
        for (Anchor a : anchors) {
            String p1 = point(h, a.r1 + 1, a.c1 + 1, a.dr1, a.dc1, rowIdx, colIdx, lastRow, lastCol, maxRowIdx, maxColIdx);
            String p2 = a.two ? point(h, a.r2 + 1, a.c2 + 1, a.dr2, a.dc2, rowIdx, colIdx, lastRow, lastCol, maxRowIdx, maxColIdx) : null;
            double w, ht;
            if (a.two) {
                w = span(a.c1 + 1, a.dc1, a.c2 + 1, a.dc2, true, h);
                ht = span(a.r1 + 1, a.dr1, a.r2 + 1, a.dr2, false, h);
            } else {
                w = a.cx / EMU_PX;
                ht = a.cy / EMU_PX;
            }
            if (w < 8 || ht < 8) continue;
            String inner;
            if (a.chart != null) {
                inner = ChartSvg.render(zip, a.chart, w, ht, colors, data);
                if (inner == null) inner = "<div class=\"note\">[gráfico]</div>";
            } else {
                inner = imageTag(a.image);
                if (inner == null) continue;
            }
            sb.append("<div class=\"xc").append(a.image != null ? " xi" : "").append("\" data-f=\"").append(p1).append('"');
            if (p2 != null) sb.append(" data-t=\"").append(p2).append('"');
            else sb.append(" data-wh=\"").append(Math.round(w)).append(',').append(Math.round(ht)).append('"');
            sb.append('>').append(inner).append("</div>");
        }
    }

    private String point(SheetHandler h, int row, int col, long dr, long dc, int[] rowIdx, int[] colIdx,
                         int lastRow, int lastCol, int maxRowIdx, int maxColIdx) {
        int ri, ci;
        double fy, fx;
        if (row > lastRow) {
            ri = Math.max(0, maxRowIdx - 1);
            fy = 1;
        } else {
            ri = Math.min(rowIdx[row], Math.max(0, maxRowIdx - 1));
            double rp = rowPx(h, row);
            fy = rp > 0 ? Math.min(1, dr / EMU_PX / rp) : 0;
        }
        if (col > lastCol) {
            ci = Math.max(1, maxColIdx - 1);
            fx = 1;
        } else {
            ci = Math.min(colIdx[col], Math.max(1, maxColIdx - 1));
            double cp = colPx(h, col);
            fx = cp > 0 ? Math.min(1, dc / EMU_PX / cp) : 0;
        }
        return ri + "," + ci + "," + String.format(Locale.ROOT, "%.3f,%.3f", fx, fy);
    }

    /** Tamaño en píxeles de Excel entre dos puntos de anclaje. */
    private double span(int a, long da, int b, long db, boolean cols, SheetHandler h) {
        double px = 0;
        for (int i = a; i < b && i - a < 2000; i++) px += cols ? colPx(h, i) : rowPx(h, i);
        return px - da / EMU_PX + db / EMU_PX;
    }

    private String imageTag(String path) {
        String ext = Ooxml.extOf(path);
        String mime;
        switch (ext) {
            case "png": mime = "image/png"; break;
            case "jpg": case "jpeg": mime = "image/jpeg"; break;
            case "gif": mime = "image/gif"; break;
            case "bmp": mime = "image/bmp"; break;
            case "webp": mime = "image/webp"; break;
            default: return null;
        }
        byte[] b = Ooxml.read(zip, path);
        if (b == null || b.length > 4_000_000 || mediaBytes + b.length > 20_000_000) return null;
        mediaBytes += b.length;
        return "<img alt=\"\" src=\"data:" + mime + ";base64," + Base64.getEncoder().encodeToString(b) + "\">";
    }

    /** Hoja que es solo un gráfico. */
    private void chartSheet(String path, StringBuilder sb) {
        try {
            Map<String, String[]> srels = Ooxml.rels(zip, Ooxml.relsPathFor(path), Ooxml.dirOf(path));
            for (String[] r : srels.values()) {
                if (r[2] == null || !r[2].endsWith("/drawing")) continue;
                Document d = Ooxml.dom(zip, r[0]);
                if (d == null) continue;
                Map<String, String[]> drels = Ooxml.rels(zip, Ooxml.relsPathFor(r[0]), Ooxml.dirOf(r[0]));
                List<Element> charts = new ArrayList<>();
                Ooxml.descendants(d.getDocumentElement(), "chart", null, charts);
                for (Element ch : charts) {
                    String[] cr = drels.get(Ooxml.rid(ch));
                    if (cr == null) continue;
                    String svg = ChartSvg.render(zip, cr[0], 720, 450, colors, data);
                    if (svg != null) {
                        sb.append("<div class=\"xcs\">").append(svg).append("</div>");
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sb.append("<div class=\"note\">Esta hoja es un gráfico y no se pudo dibujar.</div>");
    }
}
