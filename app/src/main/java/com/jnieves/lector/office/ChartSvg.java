package com.jnieves.lector.office;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * anillos y dispersión) como SVG, usando los valores guardados en el archivo.
 */
final class ChartSvg {
    private ChartSvg() {
    }

    /** Traduce un elemento de color (solidFill...) a CSS. Puede devolver null. */
    interface Colors {
        String of(Element holder);

        /** Color de énfasis n (1 a 6) del tema. */
        String accent(int n);
    }

    private static final class Series {
        String name;
        String color;
        final List<String> cats = new ArrayList<>();
        final List<Double> vals = new ArrayList<>();
        final List<Double> xs = new ArrayList<>();
        final List<String> pointColors = new ArrayList<>();
    }

    static String render(ZipFile zip, String path, double w, double h, Colors colors) {
        try {
            Document d = Ooxml.dom(zip, path);
            if (d == null) return null;
            Element chart = kid(d.getDocumentElement(), "chart");
            Element plot = kid(chart, "plotArea");
            if (plot == null) return null;

            Element type = null;
            for (Element c : kids(plot)) {
                String n = name(c);
                if (n.endsWith("Chart")) {
                    type = c;
                    break;
                }
            }
            if (type == null) return null;
            String kind = name(type);
            List<Series> series = new ArrayList<>();
            int idx = 0;
            for (Element s : kids(type)) {
                if (!"ser".equals(name(s))) continue;
                series.add(series(s, colors, idx++));
            }
            if (series.isEmpty()) return null;

            String title = null;
            Element t = kid(chart, "title");
            boolean autoDeleted = "1".equals(val(kid(chart, "autoTitleDeleted")));
            if (t != null) {
                StringBuilder tb = new StringBuilder();
                List<Element> ts = new ArrayList<>();
                Ooxml.descendants(t, "t", null, ts);
                for (Element e : ts) tb.append(e.getTextContent());
                if (tb.length() > 0) title = tb.toString();
            }
            if (title == null && !autoDeleted && series.size() == 1 && series.get(0).name != null
                    && (t != null || kid(chart, "autoTitleDeleted") != null)) {
                title = series.get(0).name;
            }
            Element dl = kid(type, "dLbls");
            if (dl == null) dl = kid(Ooxml.firstDescendant(type, "ser"), "dLbls");
            boolean showPct = dl != null && ("1".equals(val(kid(dl, "showPercent"))) || "1".equals(val(kid(dl, "showVal"))));
            boolean legend = kid(chart, "legend") != null;

            double fs = Math.max(9, Math.min(w, h) / 22);
            StringBuilder sb = new StringBuilder();
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n(w)).append(' ').append(n(h))
                    .append("\" width=\"100%\" height=\"100%\" font-family=\"sans-serif\" font-size=\"").append(n(fs)).append("\">");
            double top = 6, bottom = h - 6;
            if (title != null) {
                sb.append("<text x=\"").append(n(w / 2)).append("\" y=\"").append(n(fs * 1.4)).append("\" text-anchor=\"middle\" font-weight=\"bold\" font-size=\"")
                        .append(n(fs * 1.25)).append("\">").append(esc(title)).append("</text>");
                top = fs * 2.2;
            }

            boolean pie = kind.startsWith("pie") || kind.startsWith("doughnut") || kind.startsWith("ofPie");
            List<String[]> legendItems = new ArrayList<>();
            if (pie) {
                Series s = series.get(0);
                for (int i = 0; i < s.vals.size(); i++) {
                    String c = i < s.pointColors.size() && s.pointColors.get(i) != null ? s.pointColors.get(i) : palette(colors, i);
                    legendItems.add(new String[]{c, i < s.cats.size() ? s.cats.get(i) : String.valueOf(i + 1)});
                }
            } else {
                for (Series s : series) legendItems.add(new String[]{s.color, s.name != null ? s.name : ""});
            }
            if (legend && !legendItems.isEmpty()) {
                bottom = legend(sb, legendItems, w, h, fs);
            }

            if (pie) {
                pie(sb, series.get(0), colors, w, top, bottom, fs, kind.startsWith("doughnut"), showPct);
            } else if (kind.startsWith("scatter") || kind.startsWith("bubble")) {
                xy(sb, series, w, top, bottom, fs, true);
            } else {
                String barDir = val(kid(type, "barDir"));
                String grouping = val(kid(type, "grouping"));
                boolean stacked = "stacked".equals(grouping) || "percentStacked".equals(grouping);
                boolean percent = "percentStacked".equals(grouping);
                if (kind.startsWith("bar")) {
                    bars(sb, series, w, top, bottom, fs, !"bar".equals(barDir), stacked, percent);
                } else if (kind.startsWith("area")) {
                    lines(sb, series, w, top, bottom, fs, true, stacked);
                } else if (kind.startsWith("line") || kind.startsWith("radar") || kind.startsWith("stock")) {
                    lines(sb, series, w, top, bottom, fs, false, false);
                } else {
                    return null;
                }
            }
            sb.append("</svg>");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Series series(Element s, Colors colors, int index) {
        Series r = new Series();
        Element tx = kid(s, "tx");
        if (tx != null) {
            List<Element> vs = new ArrayList<>();
            Ooxml.descendants(tx, "v", null, vs);
            if (!vs.isEmpty()) r.name = vs.get(0).getTextContent();
        }
        Element spPr = kid(s, "spPr");
        r.color = colors.of(kid(spPr, "solidFill"));
        if (r.color == null) r.color = colors.of(kid(kid(spPr, "ln"), "solidFill"));
        if (r.color == null) r.color = palette(colors, index);
        r.cats.addAll(points(kid(s, "cat")));
        if (r.cats.isEmpty()) r.cats.addAll(points(kid(s, "xVal")));
        for (String v : points(kid(s, "val"))) r.vals.add(num(v));
        if (r.vals.isEmpty()) for (String v : points(kid(s, "yVal"))) r.vals.add(num(v));
        for (String v : points(kid(s, "xVal"))) r.xs.add(num(v));
        TreeMap<Integer, String> dp = new TreeMap<>();
        for (Element p : kids(s)) {
            if (!"dPt".equals(name(p))) continue;
            String c = colors.of(kid(kid(p, "spPr"), "solidFill"));
            if (c != null) dp.put(parseInt(val(kid(p, "idx")), -1), c);
        }
        for (int i = 0; i < r.vals.size(); i++) r.pointColors.add(dp.get(i));
        return r;
    }

    /** Lee los puntos guardados (strCache/numCache), ordenados por índice. */
    private static List<String> points(Element holder) {
        List<String> out = new ArrayList<>();
        if (holder == null) return out;
        Element cache = Ooxml.firstDescendant(holder, "strCache");
        if (cache == null) cache = Ooxml.firstDescendant(holder, "numCache");
        if (cache == null) cache = Ooxml.firstDescendant(holder, "strLit");
        if (cache == null) cache = Ooxml.firstDescendant(holder, "numLit");
        if (cache == null) {
            // Categorías de varios niveles: se usa el primer nivel
            Element lvl = Ooxml.firstDescendant(holder, "lvl");
            if (lvl == null) return out;
            cache = lvl;
        }
        int count = parseInt(val(kid(cache, "ptCount")), -1);
        TreeMap<Integer, String> m = new TreeMap<>();
        for (Element p : kids(cache)) {
            if (!"pt".equals(name(p))) continue;
            Element v = kid(p, "v");
            m.put(parseInt(attr(p, "idx"), m.size()), v != null ? v.getTextContent() : "");
        }
        int n = count >= 0 ? count : (m.isEmpty() ? 0 : m.lastKey() + 1);
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

    // ------------------------------------------------------------ partes

    private static double legend(StringBuilder sb, List<String[]> items, double w, double h, double fs) {
        double sw = fs * 0.8, gap = fs * 0.6;
        // Ancho aproximado de cada elemento
        List<Double> widths = new ArrayList<>();
        for (String[] it : items) widths.add(sw + gap / 2 + it[1].length() * fs * 0.55 + gap * 1.5);
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
                sb.append("<text x=\"").append(n(x + sw + gap / 2)).append("\" y=\"").append(n(y + fs * 0.35)).append("\">")
                        .append(esc(items.get(i)[1])).append("</text>");
                x += widths.get(i);
            }
        }
        return y0 - 4;
    }

    private static double[] range(List<Series> series, boolean stacked, boolean percent) {
        double min = 0, max = 0;
        if (percent) return new double[]{0, 100};
        int n = 0;
        for (Series s : series) n = Math.max(n, s.vals.size());
        if (stacked) {
            for (int i = 0; i < n; i++) {
                double pos = 0, neg = 0;
                for (Series s : series) {
                    double v = i < s.vals.size() ? s.vals.get(i) : Double.NaN;
                    if (Double.isNaN(v)) continue;
                    if (v >= 0) pos += v;
                    else neg += v;
                }
                max = Math.max(max, pos);
                min = Math.min(min, neg);
            }
        } else {
            for (Series s : series) {
                for (double v : s.vals) {
                    if (Double.isNaN(v)) continue;
                    max = Math.max(max, v);
                    min = Math.min(min, v);
                }
            }
        }
        if (max == min) max = min + 1;
        return new double[]{min, max};
    }

    /** Escala "bonita" con unas 5 divisiones. */
    private static double[] ticks(double min, double max) {
        double span = max - min;
        double raw = span / 5;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
        double lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
        return new double[]{lo, hi, step};
    }

    private static String label(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static List<String> categories(List<Series> series) {
        List<String> cats = new ArrayList<>(series.get(0).cats);
        int n = 0;
        for (Series s : series) n = Math.max(n, s.vals.size());
        while (cats.size() < n) cats.add(String.valueOf(cats.size() + 1));
        return cats;
    }

    private static void bars(StringBuilder sb, List<Series> series, double w, double top, double bottom, double fs,
                             boolean vertical, boolean stacked, boolean percent) {
        List<String> cats = categories(series);
        int nc = cats.size();
        if (nc == 0) return;
        double[] r = range(series, stacked, percent);
        double[] t = ticks(r[0], r[1]);
        double left = fs * 3.2, right = w - fs, plotTop = top + fs * 0.5, plotBottom = bottom - fs * 1.8;
        if (!vertical) left = Math.min(w * 0.35, fs * 0.6 * maxLen(cats) + fs);
        double pw = right - left, ph = plotBottom - plotTop;
        if (pw <= 0 || ph <= 0) return;

        // Cuadrícula y valores del eje
        for (double v = t[0]; v <= t[1] + t[2] / 2; v += t[2]) {
            if (vertical) {
                double y = plotBottom - (v - t[0]) / (t[1] - t[0]) * ph;
                sb.append(gridH(left, right, y)).append(textAt(left - fs * 0.4, y + fs * 0.35, label(v) + (percent ? "%" : ""), "end"));
            } else {
                double x = left + (v - t[0]) / (t[1] - t[0]) * pw;
                sb.append(gridV(x, plotTop, plotBottom)).append(textAt(x, plotBottom + fs * 1.2, label(v) + (percent ? "%" : ""), "middle"));
            }
        }
        double band = (vertical ? pw : ph) / nc;
        int ns = series.size();
        double barW = stacked ? band * 0.6 : band * 0.75 / ns;
        for (int c = 0; c < nc; c++) {
            double total = 0;
            if (percent) for (Series s : series) total += c < s.vals.size() && !Double.isNaN(s.vals.get(c)) ? Math.abs(s.vals.get(c)) : 0;
            double accPos = 0, accNeg = 0;
            for (int k = 0; k < ns; k++) {
                Series s = series.get(k);
                double v = c < s.vals.size() ? s.vals.get(c) : Double.NaN;
                if (Double.isNaN(v)) continue;
                if (percent && total > 0) v = v / total * 100;
                double from, to;
                if (stacked) {
                    if (v >= 0) {
                        from = accPos;
                        to = accPos += v;
                    } else {
                        from = accNeg;
                        to = accNeg += v;
                    }
                } else {
                    from = 0;
                    to = v;
                }
                double off = stacked ? (band - barW) / 2 : band * 0.125 + k * barW;
                String color = c < s.pointColors.size() && s.pointColors.get(c) != null && ns == 1 ? s.pointColors.get(c) : s.color;
                if (vertical) {
                    double x = left + c * band + off;
                    double y1 = plotBottom - (Math.max(from, to) - t[0]) / (t[1] - t[0]) * ph;
                    double y2 = plotBottom - (Math.min(from, to) - t[0]) / (t[1] - t[0]) * ph;
                    sb.append(rect(x, y1, barW, y2 - y1, color));
                } else {
                    double y = plotTop + (nc - 1 - c) * band + off;
                    double x1 = left + (Math.min(from, to) - t[0]) / (t[1] - t[0]) * pw;
                    double x2 = left + (Math.max(from, to) - t[0]) / (t[1] - t[0]) * pw;
                    sb.append(rect(x1, y, x2 - x1, barW, color));
                }
            }
            if (vertical) {
                sb.append(textAt(left + c * band + band / 2, plotBottom + fs * 1.2, cut(cats.get(c), band, fs), "middle"));
            } else {
                sb.append(textAt(left - fs * 0.4, plotTop + (nc - 1 - c) * band + band / 2 + fs * 0.35, cats.get(c), "end"));
            }
        }
        sb.append(axis(left, plotTop, plotBottom, right, vertical));
    }

    private static void lines(StringBuilder sb, List<Series> series, double w, double top, double bottom, double fs,
                              boolean area, boolean stacked) {
        List<String> cats = categories(series);
        int nc = cats.size();
        if (nc == 0) return;
        double[] r = range(series, stacked, false);
        double[] t = ticks(r[0], r[1]);
        double left = fs * 3.2, right = w - fs, plotTop = top + fs * 0.5, plotBottom = bottom - fs * 1.8;
        double pw = right - left, ph = plotBottom - plotTop;
        if (pw <= 0 || ph <= 0) return;
        for (double v = t[0]; v <= t[1] + t[2] / 2; v += t[2]) {
            double y = plotBottom - (v - t[0]) / (t[1] - t[0]) * ph;
            sb.append(gridH(left, right, y)).append(textAt(left - fs * 0.4, y + fs * 0.35, label(v), "end"));
        }
        double band = pw / nc;
        double[] acc = new double[nc];
        for (Series s : series) {
            StringBuilder pts = new StringBuilder();
            double[] base = acc.clone();
            for (int c = 0; c < nc; c++) {
                double v = c < s.vals.size() ? s.vals.get(c) : Double.NaN;
                if (Double.isNaN(v)) continue;
                if (stacked) {
                    acc[c] += v;
                    v = acc[c];
                }
                double x = left + c * band + band / 2;
                double y = plotBottom - (v - t[0]) / (t[1] - t[0]) * ph;
                pts.append(n(x)).append(',').append(n(y)).append(' ');
            }
            if (pts.length() == 0) continue;
            if (area) {
                StringBuilder poly = new StringBuilder(pts);
                for (int c = nc - 1; c >= 0; c--) {
                    double v = stacked ? base[c] : 0;
                    double x = left + c * band + band / 2;
                    double y = plotBottom - (v - t[0]) / (t[1] - t[0]) * ph;
                    poly.append(n(x)).append(',').append(n(y)).append(' ');
                }
                sb.append("<polygon points=\"").append(poly).append("\" fill=\"").append(s.color).append("\" fill-opacity=\"0.85\"/>");
            } else {
                sb.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"").append(s.color)
                        .append("\" stroke-width=\"").append(n(Math.max(1.5, fs / 6))).append("\"/>");
            }
        }
        for (int c = 0; c < nc; c++) {
            sb.append(textAt(left + c * band + band / 2, plotBottom + fs * 1.2, cut(cats.get(c), band, fs), "middle"));
        }
        sb.append(axis(left, plotTop, plotBottom, right, true));
    }

    private static void xy(StringBuilder sb, List<Series> series, double w, double top, double bottom, double fs, boolean dots) {
        double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
        for (Series s : series) {
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
        if (xmax == xmin) xmax = xmin + 1;
        if (ymax == ymin) ymax = ymin + 1;
        double[] tx = ticks(Math.min(0, xmin), xmax), ty = ticks(Math.min(0, ymin), ymax);
        double left = fs * 3.2, right = w - fs, plotTop = top + fs * 0.5, plotBottom = bottom - fs * 1.8;
        double pw = right - left, ph = plotBottom - plotTop;
        if (pw <= 0 || ph <= 0) return;
        for (double v = ty[0]; v <= ty[1] + ty[2] / 2; v += ty[2]) {
            double y = plotBottom - (v - ty[0]) / (ty[1] - ty[0]) * ph;
            sb.append(gridH(left, right, y)).append(textAt(left - fs * 0.4, y + fs * 0.35, label(v), "end"));
        }
        for (double v = tx[0]; v <= tx[1] + tx[2] / 2; v += tx[2]) {
            double x = left + (v - tx[0]) / (tx[1] - tx[0]) * pw;
            sb.append(textAt(x, plotBottom + fs * 1.2, label(v), "middle"));
        }
        double rad = Math.max(2, fs / 4);
        for (Series s : series) {
            for (int i = 0; i < s.vals.size(); i++) {
                double x = i < s.xs.size() && !Double.isNaN(s.xs.get(i)) ? s.xs.get(i) : i + 1;
                double y = s.vals.get(i);
                if (Double.isNaN(y)) continue;
                double px = left + (x - tx[0]) / (tx[1] - tx[0]) * pw;
                double py = plotBottom - (y - ty[0]) / (ty[1] - ty[0]) * ph;
                sb.append("<circle cx=\"").append(n(px)).append("\" cy=\"").append(n(py)).append("\" r=\"").append(n(rad))
                        .append("\" fill=\"").append(s.color).append("\"/>");
            }
        }
        sb.append(axis(left, plotTop, plotBottom, right, true));
    }

    private static void pie(StringBuilder sb, Series s, Colors colors, double w, double top, double bottom, double fs,
                            boolean donut, boolean labels) {
        double total = 0;
        for (double v : s.vals) if (!Double.isNaN(v) && v > 0) total += v;
        if (total <= 0) return;
        double cx = w / 2, cy = (top + bottom) / 2;
        double rad = Math.max(10, Math.min(w, bottom - top) / 2 - fs);
        double a = -Math.PI / 2;
        for (int i = 0; i < s.vals.size(); i++) {
            double v = s.vals.get(i);
            if (Double.isNaN(v) || v <= 0) continue;
            double sweep = v / total * Math.PI * 2;
            String color = i < s.pointColors.size() && s.pointColors.get(i) != null ? s.pointColors.get(i) : palette(colors, i);
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
            if (labels && sweep > 0.25) {
                double lr = donut ? rad * 0.78 : rad * 0.62;
                sb.append("<text x=\"").append(n(cx + lr * Math.cos(mid))).append("\" y=\"").append(n(cy + lr * Math.sin(mid) + fs * 0.35))
                        .append("\" text-anchor=\"middle\" fill=\"#fff\" font-weight=\"bold\">")
                        .append(Math.round(v / total * 100)).append("%</text>");
            }
            a += sweep;
        }
        if (donut) {
            sb.append("<circle cx=\"").append(n(cx)).append("\" cy=\"").append(n(cy)).append("\" r=\"").append(n(rad * 0.5))
                    .append("\" fill=\"#fff\"/>");
        }
    }

    // ----------------------------------------------------------- ayudas

    private static int maxLen(List<String> l) {
        int m = 0;
        for (String s : l) m = Math.max(m, s.length());
        return m;
    }

    private static String cut(String s, double band, double fs) {
        int max = (int) Math.max(3, band / (fs * 0.55));
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

    private static String axis(double left, double top, double bottom, double right, boolean vertical) {
        if (vertical) {
            return "<line x1=\"" + n(left) + "\" y1=\"" + n(bottom) + "\" x2=\"" + n(right) + "\" y2=\"" + n(bottom) + "\" stroke=\"#8c8c8c\" stroke-width=\"1\"/>";
        }
        return "<line x1=\"" + n(left) + "\" y1=\"" + n(top) + "\" x2=\"" + n(left) + "\" y2=\"" + n(bottom) + "\" stroke=\"#8c8c8c\" stroke-width=\"1\"/>";
    }

    private static String textAt(double x, double y, String s, String anchor) {
        return "<text x=\"" + n(x) + "\" y=\"" + n(y) + "\" text-anchor=\"" + anchor + "\" fill=\"#595959\">" + esc(s) + "</text>";
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
