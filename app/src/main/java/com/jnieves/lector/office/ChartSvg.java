package com.jnieves.lector.office;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import static com.jnieves.lector.office.Ooxml.attr;
import static com.jnieves.lector.office.Ooxml.esc;
import static com.jnieves.lector.office.Ooxml.kid;
import static com.jnieves.lector.office.Ooxml.kids;
import static com.jnieves.lector.office.Ooxml.name;
import static com.jnieves.lector.office.Ooxml.parseInt;
import static com.jnieves.lector.office.Ooxml.val;

/**
 * Dibuja gráficos de Office (columnas, barras, líneas, áreas, circulares,
 * anillos, dispersión y combinados) como SVG, con los valores guardados en el
 * archivo o, en Excel, leídos de las celdas.
 */
final class ChartSvg {
    private ChartSvg() {
    }

    private static final DecimalFormatSymbols SYM = DecimalFormatSymbols.getInstance();

    /** Traduce un elemento de color (solidFill...) a CSS. Puede devolver null. */
    interface Colors {
        String of(Element holder);

        /** Color de énfasis n (1 a 6) del tema. */
        String accent(int n);
    }

    /** Lee un rango de celdas ("Hoja1!$B$2:$B$7"). Solo existe en Excel. */
    interface Data {
        /** Una entrada por celda: texto como se ve en la hoja, o el número crudo. */
        List<String> cells(String formula, boolean asText);
    }

    private static final class Series {
        String name, color, markerColor;
        final List<String> cats = new ArrayList<>();
        final List<Double> vals = new ArrayList<>();
        final List<Double> xs = new ArrayList<>();
        final List<String> pointColors = new ArrayList<>();
        String valFmt;
        boolean marker = true, line = true, smooth, showVal;
        String symbol = "circle";
    }

    private static final class Group {
        String kind; // bar, line, area, scatter, pie
        final List<Series> series = new ArrayList<>();
        boolean stacked, percent, horizontal, secondary, midCat;
        int gap = 150;
        final List<String> axIds = new ArrayList<>();
        Element valAx, catAx;
    }

    static String render(ZipFile zip, String path, double w, double h, Colors colors) {
        return render(zip, path, w, h, colors, null);
    }

    static String render(ZipFile zip, String path, double w, double h, Colors colors, Data data) {
        try {
            Document d = Ooxml.dom(zip, path);
            if (d == null) return null;
            Element chart = kid(d.getDocumentElement(), "chart");
            Element plot = kid(chart, "plotArea");
            if (plot == null) return null;

            Map<String, Element> axes = new HashMap<>();
            for (Element c : kids(plot)) {
                String n = name(c);
                if (n.endsWith("Ax")) axes.put(val(kid(c, "axId")), c);
            }
            List<Group> groups = new ArrayList<>();
            int idx = 0;
            for (Element c : kids(plot)) {
                String n = name(c);
                if (!n.endsWith("Chart")) continue;
                Group g = new Group();
                g.kind = n.startsWith("bar") ? "bar" : n.startsWith("area") ? "area"
                        : n.startsWith("line") || n.startsWith("radar") || n.startsWith("stock") ? "line"
                        : n.startsWith("scatter") || n.startsWith("bubble") ? "scatter"
                        : n.startsWith("pie") || n.startsWith("doughnut") || n.startsWith("ofPie") ? "pie" : null;
                if (g.kind == null) continue;
                String grouping = val(kid(c, "grouping"));
                g.stacked = "stacked".equals(grouping) || "percentStacked".equals(grouping);
                g.percent = "percentStacked".equals(grouping);
                g.horizontal = "bar".equals(g.kind) && "bar".equals(val(kid(c, "barDir")));
                g.gap = parseInt(val(kid(c, "gapWidth")), 150);
                Element chartDl = kid(c, "dLbls");
                boolean scatterLines = !"marker".equals(val(kid(c, "scatterStyle")));
                Element chartMarker = kid(c, "marker");
                for (Element s : kids(c)) {
                    if (!"ser".equals(name(s))) continue;
                    Series sr = series(s, colors, data, idx++, "line".equals(g.kind) || "scatter".equals(g.kind));
                    sr.showVal = shows(kid(s, "dLbls"), "showVal") || (kid(s, "dLbls") == null && shows(chartDl, "showVal"));
                    if ("scatter".equals(g.kind) && !scatterLines && kid(s, "spPr") == null) sr.line = false;
                    if ("line".equals(g.kind) && chartMarker != null && "0".equals(val(chartMarker))
                            && kid(s, "marker") == null) sr.marker = false;
                    g.series.add(sr);
                }
                for (Element a : kids(c)) if ("axId".equals(name(a))) g.axIds.add(val(a));
                for (String id : g.axIds) {
                    Element ax = axes.get(id);
                    if (ax == null) continue;
                    if ("valAx".equals(name(ax)) && g.valAx == null) g.valAx = ax;
                    else if (!"valAx".equals(name(ax))) g.catAx = ax;
                }
                if ("scatter".equals(g.kind)) {
                    // Los dos ejes son de valores: el de abajo es X
                    for (String id : g.axIds) {
                        Element ax = axes.get(id);
                        if (ax == null) continue;
                        String pos = val(kid(ax, "axPos"));
                        if ("b".equals(pos) || "t".equals(pos)) g.catAx = ax;
                        else g.valAx = ax;
                    }
                }
                String cross = g.valAx != null ? val(kid(g.valAx, "crossBetween")) : null;
                g.midCat = cross != null ? "midCat".equals(cross) : "area".equals(g.kind);
                if (!g.series.isEmpty()) groups.add(g);
            }
            if (groups.isEmpty()) return null;
            Group first = groups.get(0);
            for (int k = 1; k < groups.size(); k++) {
                Group g = groups.get(k);
                for (String id : g.axIds) if (!first.axIds.contains(id)) g.secondary = true;
            }

            String title = text(kid(chart, "title"), data);
            boolean autoDeleted = "1".equals(val(kid(chart, "autoTitleDeleted")));
            if (title == null && !autoDeleted && groups.size() == 1 && first.series.size() == 1 && first.series.get(0).name != null
                    && (kid(chart, "title") != null || kid(chart, "autoTitleDeleted") != null)) {
                title = first.series.get(0).name;
            }
            boolean legend = kid(chart, "legend") != null;

            double fs = Math.max(9, Math.min(w, h) / 22);
            StringBuilder sb = new StringBuilder();
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n(w)).append(' ').append(n(h))
                    .append("\" width=\"100%\" height=\"100%\" font-family=\"Calibri,Carlito,sans-serif\" font-size=\"")
                    .append(n(fs)).append("\">");
            double top = 6, bottom = h - 6;
            if (title != null) {
                sb.append("<text x=\"").append(n(w / 2)).append("\" y=\"").append(n(fs * 1.4))
                        .append("\" text-anchor=\"middle\" font-weight=\"bold\" fill=\"#404040\" font-size=\"")
                        .append(n(fs * 1.25)).append("\">").append(esc(title)).append("</text>");
                top = fs * 2.2;
            }

            boolean pie = "pie".equals(first.kind);
            List<String[]> legendItems = new ArrayList<>();
            if (pie) {
                Series s = first.series.get(0);
                for (int i = 0; i < s.vals.size(); i++) {
                    legendItems.add(new String[]{pointColor(s, colors, i), i < s.cats.size() ? s.cats.get(i) : String.valueOf(i + 1)});
                }
            } else {
                for (Group g : groups) {
                    for (Series s : g.series) legendItems.add(new String[]{s.color, s.name != null ? s.name : ""});
                }
            }
            if (legend && !legendItems.isEmpty()) bottom = legend(sb, legendItems, w, h, fs);

            if (pie) {
                Element type = Ooxml.firstDescendant(plot, "doughnutChart");
                Element dl = Ooxml.firstDescendant(plot, "dLbls");
                int hole = type != null ? parseInt(val(kid(type, "holeSize")), 50) : 0;
                Element anyPie = null;
                for (Element c : kids(plot)) if (name(c).endsWith("Chart")) { anyPie = c; break; }
                int firstAng = anyPie != null ? parseInt(val(kid(anyPie, "firstSliceAng")), 0) : 0;
                pie(sb, first.series.get(0), colors, w, top, bottom, fs, hole, firstAng,
                        shows(dl, "showPercent"), shows(dl, "showVal"));
            } else if ("scatter".equals(first.kind)) {
                xy(sb, first, w, top, bottom, fs, data);
            } else {
                category(sb, groups, w, top, bottom, fs);
            }
            sb.append("</svg>");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean shows(Element dl, String what) {
        if (dl == null) return false;
        if ("1".equals(val(kid(dl, "delete")))) return false;
        String v = val(kid(dl, what));
        return "1".equals(v) || "true".equals(v);
    }

    /** Texto de un título (texto enriquecido o referencia a una celda). */
    private static String text(Element t, Data data) {
        if (t == null) return null;
        StringBuilder tb = new StringBuilder();
        List<Element> ts = new ArrayList<>();
        Ooxml.descendants(t, "t", null, ts);
        for (Element e : ts) {
            if (tb.length() > 0 && "p".equals(name((Element) e.getParentNode().getParentNode()))
                    && e.getParentNode().getPreviousSibling() == null) tb.append(' ');
            tb.append(e.getTextContent());
        }
        if (tb.length() == 0) {
            Element ref = Ooxml.firstDescendant(t, "strRef");
            if (ref != null) {
                List<String> p = points(ref, data, true);
                for (String s : p) tb.append(s);
            }
        }
        return tb.length() > 0 ? tb.toString() : null;
    }

    private static Series series(Element s, Colors colors, Data data, int index, boolean lineLike) {
        Series r = new Series();
        Element tx = kid(s, "tx");
        if (tx != null) {
            Element v = kid(tx, "v");
            if (v != null) r.name = v.getTextContent();
            else {
                List<String> p = points(tx, data, true);
                if (!p.isEmpty()) r.name = p.get(0);
            }
        }
        Element spPr = kid(s, "spPr");
        Element ln = kid(spPr, "ln");
        // En líneas y dispersión el color es el del trazo; en barras y áreas, el relleno
        if (lineLike) r.color = colors.of(kid(ln, "solidFill"));
        if (r.color == null) r.color = colors.of(kid(spPr, "solidFill"));
        if (r.color == null && !lineLike) r.color = colors.of(kid(ln, "solidFill"));
        if (r.color == null) r.color = palette(colors, index);
        if (ln != null && kid(ln, "noFill") != null) r.line = false;
        Element mk = kid(s, "marker");
        if (mk != null) {
            String sym = val(kid(mk, "symbol"));
            if ("none".equals(sym)) r.marker = false;
            else if (sym != null) r.symbol = sym;
            r.markerColor = colors.of(kid(kid(mk, "spPr"), "solidFill"));
        }
        Element sm = kid(s, "smooth");
        r.smooth = sm != null && !"0".equals(val(sm));
        Element cat = kid(s, "cat");
        if (cat == null) cat = kid(s, "xVal");
        r.cats.addAll(points(cat, data, true));
        Element valEl = kid(s, "val");
        if (valEl == null) valEl = kid(s, "yVal");
        for (String v : points(valEl, data, false)) r.vals.add(num(v));
        Element fc = Ooxml.firstDescendant(valEl, "formatCode");
        if (fc != null) r.valFmt = fc.getTextContent();
        for (String v : points(kid(s, "xVal"), data, false)) r.xs.add(num(v));
        TreeMap<Integer, String> dp = new TreeMap<>();
        for (Element p : kids(s)) {
            if (!"dPt".equals(name(p))) continue;
            String c = colors.of(kid(kid(p, "spPr"), "solidFill"));
            if (c != null) dp.put(parseInt(val(kid(p, "idx")), -1), c);
        }
        for (int i = 0; i < r.vals.size(); i++) r.pointColors.add(dp.get(i));
        return r;
    }

    /**
     * Lee los puntos: primero los guardados (strCache/numCache) y, si el archivo
     * no los trae, las celdas a las que apunta la fórmula.
     */
    private static List<String> points(Element holder, Data data, boolean asText) {
        List<String> out = new ArrayList<>();
        if (holder == null) return out;
        Element cache = Ooxml.firstDescendant(holder, "strCache");
        if (cache == null) cache = Ooxml.firstDescendant(holder, "numCache");
        if (cache == null) cache = Ooxml.firstDescendant(holder, "strLit");
        if (cache == null) cache = Ooxml.firstDescendant(holder, "numLit");
        if (cache == null) {
            Element f = Ooxml.firstDescendant(holder, "f");
            if (f != null && data != null) {
                List<String> cells = data.cells(f.getTextContent(), asText);
                if (cells != null) return cells;
            }
            // Categorías de varios niveles: se usa el primer nivel
            Element lvl = Ooxml.firstDescendant(holder, "lvl");
            if (lvl == null) return out;
            cache = lvl;
        }
        String code = null;
        Element fc = kid(cache, "formatCode");
        if (fc != null) code = fc.getTextContent();
        boolean fmt = asText && "numCache".equals(name(cache)) && code != null && !code.equalsIgnoreCase("General");
        int count = parseInt(val(kid(cache, "ptCount")), -1);
        TreeMap<Integer, String> m = new TreeMap<>();
        for (Element p : kids(cache)) {
            if (!"pt".equals(name(p))) continue;
            Element v = kid(p, "v");
            String s = v != null ? v.getTextContent() : "";
            String pc = attr(p, "formatCode");
            if (asText && (fmt || pc != null)) {
                double d = num(s);
                if (!Double.isNaN(d)) s = NumFmt.format(d, pc != null ? pc : code, false, SYM);
            }
            m.put(parseInt(attr(p, "idx"), m.size()), s);
        }
        int n = count >= 0 ? count : (m.isEmpty() ? 0 : m.lastKey() + 1);
        n = Math.min(n, 5000);
        for (int i = 0; i < n; i++) out.add(m.containsKey(i) ? m.get(i) : "");
        return out;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String palette(Colors colors, int i) {
        String base = colors.accent(i % 6 + 1);
        if (base == null) {
            String[] def = {"#4472c4", "#ed7d31", "#a5a5a5", "#ffc000", "#5b9bd5", "#70ad47"};
            base = def[i % 6];
        }
        return base;
    }

    private static String pointColor(Series s, Colors colors, int i) {
        return i < s.pointColors.size() && s.pointColors.get(i) != null ? s.pointColors.get(i) : palette(colors, i);
    }

    // ------------------------------------------------------------ partes

    private static double legend(StringBuilder sb, List<String[]> items, double w, double h, double fs) {
        double sw = fs * 0.8, gap = fs * 0.6;
        List<Double> widths = new ArrayList<>();
        for (String[] it : items) widths.add(sw + gap / 2 + it[1].length() * fs * 0.52 + gap * 1.5);
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        double used = 0;
        for (int i = 0; i < items.size(); i++) {
            if (used + widths.get(i) > w - 10 && !cur.isEmpty()) {
                rows.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            cur.add(i);
            used += widths.get(i);
        }
        if (!cur.isEmpty()) rows.add(cur);
        if (rows.size() > 4) rows = rows.subList(0, 4);
        double lineH = fs * 1.5;
        double y0 = h - rows.size() * lineH - 2;
        for (int r = 0; r < rows.size(); r++) {
            double total = 0;
            for (int i : rows.get(r)) total += widths.get(i);
            double x = (w - total) / 2;
            double y = y0 + r * lineH + lineH / 2;
            for (int i : rows.get(r)) {
                sb.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y - sw / 2)).append("\" width=\"").append(n(sw))
                        .append("\" height=\"").append(n(sw)).append("\" fill=\"").append(items.get(i)[0]).append("\"/>");
                sb.append("<text x=\"").append(n(x + sw + gap / 2)).append("\" y=\"").append(n(y + fs * 0.35))
                        .append("\" fill=\"#595959\">").append(esc(items.get(i)[1])).append("</text>");
                x += widths.get(i);
            }
        }
        return y0 - 4;
    }

    /** Mínimo y máximo de los valores de unos grupos (sumando si van apilados). */
    private static double[] range(List<Group> groups) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Group g : groups) {
            if (g.percent) {
                min = Math.min(min, 0);
                max = Math.max(max, 100);
                continue;
            }
            int nn = 0;
            for (Series s : g.series) nn = Math.max(nn, s.vals.size());
            if (g.stacked) {
                for (int i = 0; i < nn; i++) {
                    double pos = 0, neg = 0;
                    for (Series s : g.series) {
                        double v = i < s.vals.size() ? s.vals.get(i) : Double.NaN;
                        if (Double.isNaN(v)) continue;
                        if (v >= 0) pos += v;
                        else neg += v;
                    }
                    max = Math.max(max, pos);
                    min = Math.min(min, neg);
                }
            } else {
                for (Series s : g.series) {
                    for (double v : s.vals) {
                        if (Double.isNaN(v)) continue;
                        max = Math.max(max, v);
                        min = Math.min(min, v);
                    }
                }
            }
        }
        if (min > max) return new double[]{0, 1};
        return new double[]{min, max};
    }

    /** Escala automática como la de Excel, respetando mínimo y máximo fijos del eje. */
    private static double[] scale(double min, double max, Element ax) {
        Element sc = kid(ax, "scaling");
        String fmin = val(kid(sc, "min")), fmax = val(kid(sc, "max"));
        // Excel empieza en cero salvo que los datos estén muy juntos lejos del cero
        if (min >= 0 && !(max > 0 && (max - min) / max < 1.0 / 6)) min = 0;
        if (max <= 0 && !(min < 0 && (min - max) / min < 1.0 / 6)) max = 0;
        if (fmin != null) min = num(fmin);
        if (fmax != null) max = num(fmax);
        if (Double.isNaN(min)) min = 0;
        if (Double.isNaN(max) || max <= min) max = min + 1;
        double[] t = ticks(min, max);
        if (fmin != null) t[0] = min;
        if (fmax != null) t[1] = max;
        Element mu = kid(ax, "majorUnit");
        double unit = mu != null ? num(val(mu)) : Double.NaN;
        if (!Double.isNaN(unit) && unit > 0 && (t[1] - t[0]) / unit <= 40) t[2] = unit;
        return t;
    }

    /** Escala "bonita" con unas 5 divisiones. */
    private static double[] ticks(double min, double max) {
        double span = max - min;
        double raw = span / 5;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
        double lo = Math.floor(min / step + 1e-9) * step, hi = Math.ceil(max / step - 1e-9) * step;
        if (hi <= lo) hi = lo + step;
        return new double[]{lo, hi, step};
    }

    private static String label(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.replace('.', SYM.getDecimalSeparator());
    }

    /** Formato de los números de un eje (el del eje o el de los datos). */
    private static String axisFormat(Element ax, Series first) {
        Element nf = kid(ax, "numFmt");
        String code = attr(nf, "formatCode");
        boolean linked = nf == null || !"0".equals(attr(nf, "sourceLinked"));
        if (linked && first != null && first.valFmt != null) code = first.valFmt;
        if (code == null || code.equalsIgnoreCase("General")) return null;
        return code;
    }

    private static String fmt(double v, String code) {
        if (code == null) return label(v);
        try {
            return NumFmt.format(v, code, false, SYM);
        } catch (RuntimeException e) {
            return label(v);
        }
    }

    private static String axisTitle(Element ax) {
        if (ax == null || "1".equals(val(kid(ax, "delete")))) return null;
        return text(kid(ax, "title"), null);
    }

    private static boolean hidden(Element ax) {
        return ax != null && "1".equals(val(kid(ax, "delete")));
    }

    private static List<String> categories(List<Group> groups) {
        List<String> cats = new ArrayList<>();
        int nn = 0;
        for (Group g : groups) {
            for (Series s : g.series) {
                if (s.cats.size() > cats.size()) {
                    cats.clear();
                    cats.addAll(s.cats);
                }
                nn = Math.max(nn, s.vals.size());
            }
        }
        while (cats.size() < nn) cats.add(String.valueOf(cats.size() + 1));
        return cats;
    }

    /** Columnas, barras, líneas y áreas, también combinadas y con eje secundario. */
    private static void category(StringBuilder sb, List<Group> groups, double w, double top, double bottom, double fs) {
        List<String> cats = categories(groups);
        int nc = cats.size();
        if (nc == 0) return;
        List<Group> prim = new ArrayList<>(), sec = new ArrayList<>();
        for (Group g : groups) (g.secondary ? sec : prim).add(g);
        Group g0 = prim.get(0);
        boolean horizontal = g0.horizontal;
        for (Group g : groups) if (!g.horizontal) horizontal = false;

        double[] r1 = range(prim);
        double[] t1 = scale(r1[0], r1[1], g0.valAx);
        String f1 = g0.percent ? null : axisFormat(g0.valAx, g0.series.get(0));
        double[] t2 = null;
        String f2 = null;
        Group gs = sec.isEmpty() ? null : sec.get(0);
        if (gs != null) {
            double[] r2 = range(sec);
            t2 = scale(r2[0], r2[1], gs.valAx);
            f2 = axisFormat(gs.valAx, gs.series.get(0));
        }

        String yTitle = axisTitle(g0.valAx), xTitle = axisTitle(g0.catAx), y2Title = gs != null ? axisTitle(gs.valAx) : null;
        boolean showVals = !hidden(g0.valAx), showCats = !hidden(g0.catAx);
        double cw = fs * 0.52;

        int valLen = 1;
        for (double v = t1[0]; v <= t1[1] + t1[2] / 2; v += t1[2]) valLen = Math.max(valLen, (fmt(v, f1) + (g0.percent ? "%" : "")).length());
        int val2Len = 0;
        if (t2 != null) for (double v = t2[0]; v <= t2[1] + t2[2] / 2; v += t2[2]) val2Len = Math.max(val2Len, fmt(v, f2).length());

        double left = fs * 0.6, right = w - fs * 0.8;
        double plotTop = top + fs * 0.5, plotBottom = bottom;
        if (yTitle != null) {
            if (horizontal) plotBottom -= fs * 1.4;
            else left += fs * 1.4;
        }
        if (xTitle != null) {
            if (horizontal) left += fs * 1.4;
            else plotBottom -= fs * 1.4;
        }
        if (horizontal) {
            if (showCats) left += Math.min(w * 0.35, cw * maxLen(cats) + fs * 0.6);
            if (showVals) plotBottom -= fs * 1.5;
        } else {
            if (showVals) left += cw * valLen + fs * 0.5;
            if (showCats) plotBottom -= fs * 1.5;
            if (t2 != null) right -= cw * val2Len + fs * 0.5 + (y2Title != null ? fs * 1.4 : 0);
        }
        double pw = right - left, ph = plotBottom - plotTop;
        if (pw <= 10 || ph <= 10) return;

        // Títulos de los ejes
        if (yTitle != null) {
            if (horizontal) {
                sb.append(textAt(left + pw / 2, bottom - fs * 0.3, yTitle, "middle"));
            } else {
                sb.append(vtext(fs * 1.0, plotTop + ph / 2, yTitle));
            }
        }
        if (xTitle != null) {
            if (horizontal) sb.append(vtext(fs * 1.0, plotTop + ph / 2, xTitle));
            else sb.append(textAt(left + pw / 2, bottom - fs * 0.3, xTitle, "middle"));
        }
        if (y2Title != null) sb.append(vtext(w - fs * 0.8, plotTop + ph / 2, y2Title));

        // Cuadrícula y números del eje
        boolean grid = g0.valAx == null || kid(g0.valAx, "majorGridlines") != null;
        for (double v = t1[0]; v <= t1[1] + t1[2] / 2; v += t1[2]) {
            String lb = fmt(v, f1) + (g0.percent ? "%" : "");
            if (horizontal) {
                double x = left + (v - t1[0]) / (t1[1] - t1[0]) * pw;
                if (grid) sb.append(gridV(x, plotTop, plotBottom));
                if (showVals) sb.append(textAt(x, plotBottom + fs * 1.15, lb, "middle"));
            } else {
                double y = plotBottom - (v - t1[0]) / (t1[1] - t1[0]) * ph;
                if (grid) sb.append(gridH(left, right, y));
                if (showVals) sb.append(textAt(left - fs * 0.4, y + fs * 0.35, lb, "end"));
            }
        }
        if (t2 != null && !horizontal && !hidden(gs.valAx)) {
            for (double v = t2[0]; v <= t2[1] + t2[2] / 2; v += t2[2]) {
                double y = plotBottom - (v - t2[0]) / (t2[1] - t2[0]) * ph;
                sb.append(textAt(right + fs * 0.4, y + fs * 0.35, fmt(v, f2), "start"));
            }
        }

        double band = (horizontal ? ph : pw) / nc;
        // Ranuras de barras: cada grupo apilado ocupa una, los demás una por serie
        int slots = 0;
        int gap = 150;
        for (Group g : groups) {
            if (!"bar".equals(g.kind)) continue;
            slots += g.stacked ? 1 : g.series.size();
            gap = g.gap;
        }
        double barW = slots > 0 ? band / (slots + gap / 100.0) : 0;
        double barStart = (band - barW * slots) / 2;

        StringBuilder over = new StringBuilder(); // líneas y etiquetas van encima
        int slot = 0;
        List<Group> order = new ArrayList<>();
        for (Group g : groups) if ("area".equals(g.kind)) order.add(g);
        for (Group g : groups) if ("bar".equals(g.kind)) order.add(g);
        for (Group g : groups) if ("line".equals(g.kind)) order.add(g);
        for (Group g : order) {
            double[] t = g.secondary && t2 != null ? t2 : t1;
            double lo = t[0], span = t[1] - t[0];
            int ns = g.series.size();
            double[] accPos = new double[nc], accNeg = new double[nc], totals = new double[nc];
            if (g.percent) {
                for (Series s : g.series) {
                    for (int c = 0; c < nc && c < s.vals.size(); c++) {
                        if (!Double.isNaN(s.vals.get(c))) totals[c] += Math.abs(s.vals.get(c));
                    }
                }
            }
            for (int k = 0; k < ns; k++) {
                Series s = g.series.get(k);
                double[] from = new double[nc], to = new double[nc];
                boolean[] has = new boolean[nc];
                for (int c = 0; c < nc; c++) {
                    double v = c < s.vals.size() ? s.vals.get(c) : Double.NaN;
                    if (Double.isNaN(v)) continue;
                    double shown = v;
                    if (g.percent && totals[c] > 0) v = v / totals[c] * 100;
                    has[c] = true;
                    if (g.stacked) {
                        if (v >= 0 || !"bar".equals(g.kind)) {
                            from[c] = accPos[c];
                            to[c] = accPos[c] += v;
                        } else {
                            from[c] = accNeg[c];
                            to[c] = accNeg[c] += v;
                        }
                    } else {
                        from[c] = 0;
                        to[c] = v;
                    }
                    if (s.showVal && !"bar".equals(g.kind)) {
                        double x = catX(g, c, nc, left, pw, band);
                        double y = plotBottom - (to[c] - lo) / span * ph;
                        over.append(textAt(x, y - fs * 0.6, label(shown), "middle"));
                    }
                }
                if ("bar".equals(g.kind)) {
                    int sl = g.stacked ? slot : slot + k;
                    for (int c = 0; c < nc; c++) {
                        if (!has[c]) continue;
                        double a = Math.max(lo, Math.min(from[c], to[c])), b = Math.min(t[1], Math.max(from[c], to[c]));
                        String color = ns == 1 && c < s.pointColors.size() && s.pointColors.get(c) != null ? s.pointColors.get(c) : s.color;
                        double off = c * band + barStart + sl * barW;
                        if (horizontal) {
                            double y = plotTop + ph - off - barW; // la primera categoría va abajo
                            double x1 = left + (a - lo) / span * pw, x2 = left + (b - lo) / span * pw;
                            sb.append(rect(x1, y, x2 - x1, barW, color));
                            if (s.showVal) over.append(textAt(x2 + fs * 0.3, y + barW / 2 + fs * 0.35, label(s.vals.get(c)), "start"));
                        } else {
                            double x = left + off;
                            double y1 = plotBottom - (b - lo) / span * ph, y2 = plotBottom - (a - lo) / span * ph;
                            sb.append(rect(x, y1, barW, y2 - y1, color));
                            if (s.showVal) over.append(textAt(x + barW / 2, y1 - fs * 0.3, label(s.vals.get(c)), "middle"));
                        }
                    }
                } else {
                    List<double[]> pts = new ArrayList<>();
                    for (int c = 0; c < nc; c++) {
                        if (!has[c]) continue;
                        pts.add(new double[]{catX(g, c, nc, left, pw, band), plotBottom - (to[c] - lo) / span * ph});
                    }
                    if (pts.isEmpty()) continue;
                    if ("area".equals(g.kind)) {
                        StringBuilder poly = new StringBuilder();
                        for (double[] p : pts) poly.append(n(p[0])).append(',').append(n(p[1])).append(' ');
                        for (int c = nc - 1; c >= 0; c--) {
                            if (!has[c]) continue;
                            double base = g.stacked ? from[c] : Math.max(lo, 0);
                            poly.append(n(catX(g, c, nc, left, pw, band))).append(',')
                                    .append(n(plotBottom - (base - lo) / span * ph)).append(' ');
                        }
                        sb.append("<polygon points=\"").append(poly).append("\" fill=\"").append(s.color).append("\"/>");
                    } else {
                        if (s.line) over.append(path(pts, s.smooth, s.color, Math.max(1.5, fs / 5)));
                        if (s.marker) for (double[] p : pts) over.append(marker(p[0], p[1], fs, s));
                    }
                }
            }
            if ("bar".equals(g.kind)) slot += g.stacked ? 1 : ns;
        }

        // Eje de categorías en el cero y sus nombres
        double zero = Math.max(t1[0], Math.min(t1[1], 0));
        if (horizontal) {
            double x0 = left + (zero - t1[0]) / (t1[1] - t1[0]) * pw;
            sb.append(line(x0, plotTop, x0, plotBottom));
        } else {
            double y0 = plotBottom - (zero - t1[0]) / (t1[1] - t1[0]) * ph;
            sb.append(line(left, y0, right, y0));
        }
        sb.append(over);
        if (showCats) {
            double lw = cw * maxLen(cats) + fs * 0.4;
            int step = horizontal ? (int) Math.ceil(fs * 1.2 / band) : (int) Math.ceil(lw / band);
            step = Math.max(1, step);
            for (int c = 0; c < nc; c += step) {
                if (horizontal) {
                    double y = plotTop + ph - c * band - band / 2;
                    sb.append(textAt(left - fs * 0.4, y + fs * 0.35, cut(cats.get(c), left / (cw * 1.0) - 1), "end"));
                } else {
                    double x = catX(g0, c, nc, left, pw, band);
                    String lb = cut(cats.get(c), Math.max(band * step / cw, g0.midCat ? lw / cw : 0));
                    double half = lb.length() * cw / 2 + 2;
                    x = Math.max(half, Math.min(w - half, x));
                    sb.append(textAt(x, plotBottom + fs * 1.2, lb, "middle"));
                }
            }
        }
    }

    private static double catX(Group g, int c, int nc, double left, double pw, double band) {
        if (g.midCat && nc > 1) return left + c * pw / (nc - 1);
        return left + c * band + band / 2;
    }

    private static String path(List<double[]> pts, boolean smooth, String color, double width) {
        StringBuilder d = new StringBuilder("M").append(n(pts.get(0)[0])).append(',').append(n(pts.get(0)[1]));
        for (int i = 1; i < pts.size(); i++) {
            double[] p1 = pts.get(i - 1), p2 = pts.get(i);
            if (smooth) {
                double[] p0 = i > 1 ? pts.get(i - 2) : p1, p3 = i + 1 < pts.size() ? pts.get(i + 1) : p2;
                double c1x = p1[0] + (p2[0] - p0[0]) / 6, c1y = p1[1] + (p2[1] - p0[1]) / 6;
                double c2x = p2[0] - (p3[0] - p1[0]) / 6, c2y = p2[1] - (p3[1] - p1[1]) / 6;
                d.append(" C").append(n(c1x)).append(',').append(n(c1y)).append(' ').append(n(c2x)).append(',')
                        .append(n(c2y)).append(' ').append(n(p2[0])).append(',').append(n(p2[1]));
            } else {
                d.append(" L").append(n(p2[0])).append(',').append(n(p2[1]));
            }
        }
        return "<path d=\"" + d + "\" fill=\"none\" stroke=\"" + color + "\" stroke-width=\"" + n(width)
                + "\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>";
    }

    private static String marker(double x, double y, double fs, Series s) {
        double r = Math.max(2.2, fs / 3.6);
        String c = s.markerColor != null ? s.markerColor : s.color;
        switch (s.symbol) {
            case "square":
                return rect(x - r, y - r, 2 * r, 2 * r, c);
            case "diamond":
                return "<path d=\"M" + n(x) + "," + n(y - r * 1.3) + " L" + n(x + r * 1.3) + "," + n(y) + " L" + n(x) + ","
                        + n(y + r * 1.3) + " L" + n(x - r * 1.3) + "," + n(y) + " Z\" fill=\"" + c + "\"/>";
            case "triangle":
                return "<path d=\"M" + n(x) + "," + n(y - r * 1.3) + " L" + n(x + r * 1.2) + "," + n(y + r) + " L"
                        + n(x - r * 1.2) + "," + n(y + r) + " Z\" fill=\"" + c + "\"/>";
            default:
                return "<circle cx=\"" + n(x) + "\" cy=\"" + n(y) + "\" r=\"" + n(r) + "\" fill=\"" + c + "\"/>";
        }
    }

    private static void xy(StringBuilder sb, Group g, double w, double top, double bottom, double fs, Data data) {
        double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
        for (Series s : g.series) {
            for (int i = 0; i < s.vals.size(); i++) {
                double x = i < s.xs.size() && !Double.isNaN(s.xs.get(i)) ? s.xs.get(i) : i + 1;
                double y = s.vals.get(i);
                if (Double.isNaN(y)) continue;
                xmin = Math.min(xmin, x);
                xmax = Math.max(xmax, x);
                ymin = Math.min(ymin, y);
                ymax = Math.max(ymax, y);
            }
        }
        if (xmin > xmax) return;
        double[] tx = scale(xmin, xmax, g.catAx), ty = scale(ymin, ymax, g.valAx);
        String fx = axisFormat(g.catAx, null), fy = axisFormat(g.valAx, g.series.get(0));
        String xTitle = axisTitle(g.catAx), yTitle = axisTitle(g.valAx);
        double cw = fs * 0.52;
        int yl = 1;
        for (double v = ty[0]; v <= ty[1] + ty[2] / 2; v += ty[2]) yl = Math.max(yl, fmt(v, fy).length());
        double left = fs * 0.6 + cw * yl + fs * 0.5 + (yTitle != null ? fs * 1.4 : 0), right = w - fs;
        double plotTop = top + fs * 0.5, plotBottom = bottom - fs * 1.5 - (xTitle != null ? fs * 1.4 : 0);
        double pw = right - left, ph = plotBottom - plotTop;
        if (pw <= 10 || ph <= 10) return;
        if (yTitle != null) sb.append(vtext(fs * 1.0, plotTop + ph / 2, yTitle));
        if (xTitle != null) sb.append(textAt(left + pw / 2, bottom - fs * 0.3, xTitle, "middle"));
        boolean gridY = g.valAx == null || kid(g.valAx, "majorGridlines") != null;
        boolean gridX = g.catAx != null && kid(g.catAx, "majorGridlines") != null;
        for (double v = ty[0]; v <= ty[1] + ty[2] / 2; v += ty[2]) {
            double y = plotBottom - (v - ty[0]) / (ty[1] - ty[0]) * ph;
            if (gridY) sb.append(gridH(left, right, y));
            sb.append(textAt(left - fs * 0.4, y + fs * 0.35, fmt(v, fy), "end"));
        }
        int xl = 1;
        for (double v = tx[0]; v <= tx[1] + tx[2] / 2; v += tx[2]) xl = Math.max(xl, fmt(v, fx).length());
        int nTicks = (int) Math.round((tx[1] - tx[0]) / tx[2]);
        int step = Math.max(1, (int) Math.ceil((cw * xl + fs) / (pw / Math.max(1, nTicks))));
        int k = 0;
        for (double v = tx[0]; v <= tx[1] + tx[2] / 2; v += tx[2], k++) {
            double x = left + (v - tx[0]) / (tx[1] - tx[0]) * pw;
            if (gridX) sb.append(gridV(x, plotTop, plotBottom));
            if (k % step == 0) sb.append(textAt(x, plotBottom + fs * 1.2, fmt(v, fx), "middle"));
        }
        sb.append(line(left, plotBottom, right, plotBottom)).append(line(left, plotTop, left, plotBottom));
        for (Series s : g.series) {
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < s.vals.size(); i++) {
                double x = i < s.xs.size() && !Double.isNaN(s.xs.get(i)) ? s.xs.get(i) : i + 1;
                double y = s.vals.get(i);
                if (Double.isNaN(y)) continue;
                pts.add(new double[]{left + (x - tx[0]) / (tx[1] - tx[0]) * pw, plotBottom - (y - ty[0]) / (ty[1] - ty[0]) * ph});
            }
            if (pts.isEmpty()) continue;
            if (s.line && pts.size() > 1) sb.append(path(pts, s.smooth, s.color, Math.max(1.5, fs / 5)));
            if (s.marker || !s.line) for (double[] p : pts) sb.append(marker(p[0], p[1], fs, s));
        }
    }

    private static void pie(StringBuilder sb, Series s, Colors colors, double w, double top, double bottom, double fs,
                            int hole, int firstAngle, boolean pct, boolean vals) {
        double total = 0;
        for (double v : s.vals) if (!Double.isNaN(v) && v > 0) total += v;
        if (total <= 0) return;
        double cx = w / 2, cy = (top + bottom) / 2;
        double rad = Math.max(10, Math.min(w, bottom - top) / 2 - fs * 0.6);
        double a = -Math.PI / 2 + Math.toRadians(firstAngle);
        double inner = hole > 0 ? rad * Math.max(10, Math.min(90, hole)) / 100.0 : 0;
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < s.vals.size(); i++) {
            double v = s.vals.get(i);
            if (Double.isNaN(v) || v <= 0) continue;
            double sweep = v / total * Math.PI * 2;
            String color = pointColor(s, colors, i);
            if (sweep >= Math.PI * 2 - 1e-6) {
                sb.append("<circle cx=\"").append(n(cx)).append("\" cy=\"").append(n(cy)).append("\" r=\"").append(n(rad))
                        .append("\" fill=\"").append(color).append("\"/>");
            } else {
                double x1 = cx + rad * Math.cos(a), y1 = cy + rad * Math.sin(a);
                double x2 = cx + rad * Math.cos(a + sweep), y2 = cy + rad * Math.sin(a + sweep);
                sb.append("<path d=\"M").append(n(cx)).append(',').append(n(cy)).append(" L").append(n(x1)).append(',').append(n(y1))
                        .append(" A").append(n(rad)).append(',').append(n(rad)).append(" 0 ").append(sweep > Math.PI ? 1 : 0)
                        .append(",1 ").append(n(x2)).append(',').append(n(y2)).append(" Z\" fill=\"").append(color)
                        .append("\" stroke=\"#fff\" stroke-width=\"1\"/>");
            }
            double mid = a + sweep / 2;
            if ((pct || vals) && sweep > 0.2) {
                String t = vals ? label(v) : "";
                if (pct) t += (t.isEmpty() ? "" : "; ") + Math.round(v / total * 100) + "%";
                double lr = inner > 0 ? (rad + inner) / 2 : rad * 0.62;
                labels.append("<text x=\"").append(n(cx + lr * Math.cos(mid))).append("\" y=\"").append(n(cy + lr * Math.sin(mid) + fs * 0.35))
                        .append("\" text-anchor=\"middle\" fill=\"#fff\" font-weight=\"bold\">").append(esc(t)).append("</text>");
            }
            a += sweep;
        }
        if (inner > 0) {
            sb.append("<circle cx=\"").append(n(cx)).append("\" cy=\"").append(n(cy)).append("\" r=\"").append(n(inner))
                    .append("\" fill=\"#fff\"/>");
        }
        sb.append(labels);
    }

    // ----------------------------------------------------------- ayudas

    private static int maxLen(List<String> l) {
        int m = 0;
        for (String s : l) m = Math.max(m, s.length());
        return m;
    }

    private static String cut(String s, double maxChars) {
        int max = (int) Math.max(3, maxChars);
        return s.length() > max ? s.substring(0, Math.max(1, max - 1)) + "…" : s;
    }

    private static String rect(double x, double y, double w, double h, String fill) {
        return "<rect x=\"" + n(x) + "\" y=\"" + n(y) + "\" width=\"" + n(Math.max(0, w)) + "\" height=\"" + n(Math.max(0, h))
                + "\" fill=\"" + fill + "\"/>";
    }

    private static String gridH(double x1, double x2, double y) {
        return "<line x1=\"" + n(x1) + "\" y1=\"" + n(y) + "\" x2=\"" + n(x2) + "\" y2=\"" + n(y) + "\" stroke=\"#d9d9d9\" stroke-width=\"1\"/>";
    }

    private static String gridV(double x, double y1, double y2) {
        return "<line x1=\"" + n(x) + "\" y1=\"" + n(y1) + "\" x2=\"" + n(x) + "\" y2=\"" + n(y2) + "\" stroke=\"#d9d9d9\" stroke-width=\"1\"/>";
    }

    private static String line(double x1, double y1, double x2, double y2) {
        return "<line x1=\"" + n(x1) + "\" y1=\"" + n(y1) + "\" x2=\"" + n(x2) + "\" y2=\"" + n(y2) + "\" stroke=\"#a6a6a6\" stroke-width=\"1\"/>";
    }

    private static String textAt(double x, double y, String s, String anchor) {
        return "<text x=\"" + n(x) + "\" y=\"" + n(y) + "\" text-anchor=\"" + anchor + "\" fill=\"#595959\">" + esc(s) + "</text>";
    }

    /** Texto girado 90° (títulos de ejes verticales). */
    private static String vtext(double x, double y, String s) {
        return "<text transform=\"translate(" + n(x) + "," + n(y) + ") rotate(-90)\" text-anchor=\"middle\" fill=\"#595959\">"
                + esc(s) + "</text>";
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
