package com.jnieves.lector.office;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static com.jnieves.lector.office.Ooxml.attr;
import static com.jnieves.lector.office.Ooxml.esc;
import static com.jnieves.lector.office.Ooxml.isHex6;
import static com.jnieves.lector.office.Ooxml.kid;
import static com.jnieves.lector.office.Ooxml.kids;
import static com.jnieves.lector.office.Ooxml.name;
import static com.jnieves.lector.office.Ooxml.parseInt;
import static com.jnieves.lector.office.Ooxml.val;

/**
 * Convierte un documento de Word (.docx) a HTML.
 * Soporta: títulos, negrita/cursiva/subrayado, tamaños y colores, alineación,
 * sangrías, listas numeradas y con viñetas, tablas (con celdas combinadas),
 * imágenes, cuadros de texto, enlaces, notas al pie y notas finales.
 */
public final class DocxToHtml {

    private static final Set<String> WEB_IMAGES = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg"));
    private static final Pattern HEADING = Pattern.compile("^(heading|t[ií]tulo|encabezado)\\s*(\\d)$");
    private static final int MAX_IMAGES = 400;

    private final ZipFile zip;
    private final File mediaDir;
    private Map<String, String[]> rels = new HashMap<>();

    private final Map<String, Style> styles = new HashMap<>();
    private String defaultParaStyle;
    private Props defaults = new Props();

    private final Map<String, String> numToAbstract = new HashMap<>();
    private final Map<String, Lvl[]> abstracts = new HashMap<>();
    private final Map<String, Integer> startOverrides = new HashMap<>();
    private final Map<String, int[]> counters = new HashMap<>();

    private final Map<String, String> imageCache = new HashMap<>();
    private int imageCount = 0;

    private final List<String> footnotes = new ArrayList<>();
    private final List<String> endnotes = new ArrayList<>();
    private boolean inNote;

    private DocxToHtml(ZipFile zip, File outDir) {
        this.zip = zip;
        this.mediaDir = new File(outDir, "media");
    }

    /** Devuelve el HTML del cuerpo. Las imágenes se guardan en outDir/media/. */
    public static String convert(File docx, File outDir) throws Exception {
        try (ZipFile zip = new ZipFile(docx)) {
            return new DocxToHtml(zip, outDir).run();
        }
    }

    private String run() throws Exception {
        String docPath = Ooxml.mainPart(zip, "word/document.xml");
        String base = Ooxml.dirOf(docPath);
        rels = Ooxml.rels(zip, Ooxml.relsPathFor(docPath), base);

        loadStyles(Ooxml.partByType(rels, "/styles", base + "styles.xml"));
        loadNumbering(Ooxml.partByType(rels, "/numbering", base + "numbering.xml"));

        Document d = Ooxml.dom(zip, docPath);
        if (d == null) throw new IOException("No es un documento de Word válido");
        Element body = kid(d.getDocumentElement(), "body");

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<div class=\"doc\" style=\"");
        sb.append("font-size:").append(defaults.sz != null ? fmtPt(defaults.sz) : "11pt").append(';');
        sb.append("font-family:").append(family(defaults.font)).append(';');
        if (defaults.color != null) sb.append("color:").append(defaults.color).append(';');
        sb.append("\">");
        if (body != null) blocks(body, sb);
        Map<String, String[]> docRels = rels;
        notes(sb, base, docRels, footnotes, "/footnotes", "footnotes.xml", "footnote", "Notas al pie");
        notes(sb, base, docRels, endnotes, "/endnotes", "endnotes.xml", "endnote", "Notas finales");
        sb.append("</div>");
        return sb.toString();
    }

    // ------------------------------------------------------------ estilos

    private static final class Props {
        Boolean b, i, u, strike, caps, smallCaps;
        Integer sz;
        String color, bg, font, va;

        Props copy() {
            Props p = new Props();
            p.b = b; p.i = i; p.u = u; p.strike = strike; p.caps = caps; p.smallCaps = smallCaps;
            p.sz = sz; p.color = color; p.bg = bg; p.font = font; p.va = va;
            return p;
        }

        Props over(Props o) {
            if (o == null) return this;
            Props r = copy();
            if (o.b != null) r.b = o.b;
            if (o.i != null) r.i = o.i;
            if (o.u != null) r.u = o.u;
            if (o.strike != null) r.strike = o.strike;
            if (o.caps != null) r.caps = o.caps;
            if (o.smallCaps != null) r.smallCaps = o.smallCaps;
            if (o.sz != null) r.sz = o.sz;
            if (o.color != null) r.color = o.color;
            if (o.bg != null) r.bg = o.bg;
            if (o.font != null) r.font = o.font;
            if (o.va != null) r.va = o.va;
            return r;
        }
    }

    private static final class Style {
        String id, type, basedOn, name, jc, numId;
        Integer outline, ilvl;
        Props rp = new Props();
    }

    private void loadStyles(String path) throws Exception {
        Document d = Ooxml.dom(zip, path);
        if (d == null) return;
        for (Element e : kids(d.getDocumentElement())) {
            String n = name(e);
            if ("docDefaults".equals(n)) {
                Element rd = kid(e, "rPrDefault");
                if (rd != null) defaults = defaults.over(parseRPr(kid(rd, "rPr")));
            } else if ("style".equals(n)) {
                Style s = new Style();
                s.id = attr(e, "styleId");
                s.type = attr(e, "type");
                if (s.id == null) continue;
                Element nm = kid(e, "name");
                s.name = nm != null ? val(nm) : null;
                Element bo = kid(e, "basedOn");
                s.basedOn = bo != null ? val(bo) : null;
                s.rp = parseRPr(kid(e, "rPr"));
                Element pPr = kid(e, "pPr");
                if (pPr != null) {
                    Element jc = kid(pPr, "jc");
                    if (jc != null) s.jc = val(jc);
                    Element ol = kid(pPr, "outlineLvl");
                    if (ol != null) s.outline = parseInt(val(ol), 9);
                    Element np = kid(pPr, "numPr");
                    if (np != null) {
                        Element id = kid(np, "numId");
                        Element lv = kid(np, "ilvl");
                        if (id != null) s.numId = val(id);
                        if (lv != null) s.ilvl = parseInt(val(lv), 0);
                    }
                }
                String def = attr(e, "default");
                if ("paragraph".equals(s.type) && ("1".equals(def) || "true".equals(def))) {
                    defaultParaStyle = s.id;
                }
                styles.put(s.id, s);
            }
        }
    }

    private Props styleProps(String id) {
        List<Style> chain = chain(id);
        Props p = new Props();
        for (int k = chain.size() - 1; k >= 0; k--) p = p.over(chain.get(k).rp);
        return p;
    }

    /** Estilo y sus padres (basedOn), del más específico al más general. */
    private List<Style> chain(String id) {
        List<Style> out = new ArrayList<>();
        Style s = id != null ? styles.get(id) : null;
        while (s != null && out.size() < 20) {
            out.add(s);
            s = s.basedOn != null ? styles.get(s.basedOn) : null;
        }
        return out;
    }

    private int headingLevel(String id) {
        for (Style s : chain(id)) {
            if (s.name != null) {
                String n = s.name.toLowerCase(Locale.ROOT).trim();
                Matcher m = HEADING.matcher(n);
                if (m.find()) return Integer.parseInt(m.group(2));
                if (n.equals("title") || n.equals("título") || n.equals("titulo")) return 1;
            }
            if (s.outline != null && s.outline >= 0 && s.outline < 9) return s.outline + 1;
        }
        return 0;
    }

    private String styleJc(String id) {
        for (Style s : chain(id)) if (s.jc != null) return s.jc;
        return null;
    }

    private Props parseRPr(Element rPr) {
        Props p = new Props();
        if (rPr == null) return p;
        for (Element c : kids(rPr)) {
            String v;
            switch (name(c)) {
                case "b": p.b = on(c); break;
                case "i": p.i = on(c); break;
                case "u":
                    v = val(c);
                    p.u = v == null || !v.equals("none");
                    break;
                case "strike":
                case "dstrike":
                    p.strike = on(c);
                    break;
                case "caps": p.caps = on(c); break;
                case "smallCaps": p.smallCaps = on(c); break;
                case "sz":
                    int sz = parseInt(val(c), -1);
                    if (sz > 0) p.sz = sz;
                    break;
                case "color":
                    v = val(c);
                    if (isHex6(v)) p.color = "#" + v;
                    break;
                case "highlight":
                    p.bg = highlight(val(c));
                    break;
                case "shd":
                    v = attr(c, "fill");
                    if (isHex6(v) && p.bg == null) p.bg = "#" + v;
                    break;
                case "rFonts":
                    v = attr(c, "ascii");
                    if (v == null) v = attr(c, "hAnsi");
                    if (v != null) p.font = v;
                    break;
                case "vertAlign":
                    p.va = val(c);
                    break;
                default:
                    break;
            }
        }
        return p;
    }

    private static boolean on(Element c) {
        String v = val(c);
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("off"));
    }

    private static String highlight(String v) {
        if (v == null || v.equals("none")) return null;
        switch (v) {
            case "darkYellow": return "#808000";
            case "lightGray": return "#d3d3d3";
            case "darkGray": return "#a9a9a9";
            default: return v;
        }
    }

    private static String family(String font) {
        if (font == null) return "sans-serif";
        String l = font.toLowerCase(Locale.ROOT);
        if (l.contains("courier") || l.contains("consolas") || l.contains("mono")) return "monospace";
        if (l.contains("times") || l.contains("cambria") || l.contains("georgia") || l.contains("garamond")
                || l.contains("book") || l.contains("palatino") || (l.contains("serif") && !l.contains("sans"))) {
            return "serif";
        }
        return "sans-serif";
    }

    private static String fmtPt(int halfPoints) {
        if (halfPoints % 2 == 0) return (halfPoints / 2) + "pt";
        return (halfPoints / 2) + ".5pt";
    }

    private String css(Props p) {
        StringBuilder c = new StringBuilder();
        if (Boolean.TRUE.equals(p.b)) c.append("font-weight:bold;");
        if (Boolean.TRUE.equals(p.i)) c.append("font-style:italic;");
        boolean u = Boolean.TRUE.equals(p.u), s = Boolean.TRUE.equals(p.strike);
        if (u || s) {
            c.append("text-decoration:");
            if (u) c.append("underline ");
            if (s) c.append("line-through");
            c.append(';');
        }
        if (p.sz != null && !p.sz.equals(defaults.sz) && !"superscript".equals(p.va) && !"subscript".equals(p.va)) {
            c.append("font-size:").append(fmtPt(p.sz)).append(';');
        }
        if (p.color != null && !p.color.equals(defaults.color)) c.append("color:").append(p.color).append(';');
        if (p.bg != null) c.append("background-color:").append(p.bg).append(';');
        if (p.font != null && !family(p.font).equals(family(defaults.font))) {
            c.append("font-family:").append(family(p.font)).append(';');
        }
        if (Boolean.TRUE.equals(p.caps)) c.append("text-transform:uppercase;");
        if (Boolean.TRUE.equals(p.smallCaps)) c.append("font-variant:small-caps;");
        if ("superscript".equals(p.va)) c.append("vertical-align:super;font-size:smaller;");
        if ("subscript".equals(p.va)) c.append("vertical-align:sub;font-size:smaller;");
        return c.toString();
    }

    // ---------------------------------------------------------- numeración

    private static final class Lvl {
        String fmt = "decimal";
        String text = "%1.";
        int start = 1;
        int left = -1, hanging = -1;
    }

    private void loadNumbering(String path) throws Exception {
        Document d = Ooxml.dom(zip, path);
        if (d == null) return;
        for (Element e : kids(d.getDocumentElement())) {
            String n = name(e);
            if ("abstractNum".equals(n)) {
                Lvl[] lv = new Lvl[9];
                for (Element l : kids(e)) {
                    if (!"lvl".equals(name(l))) continue;
                    int il = parseInt(attr(l, "ilvl"), -1);
                    if (il < 0 || il > 8) continue;
                    Lvl L = new Lvl();
                    Element st = kid(l, "start");
                    if (st != null) L.start = parseInt(val(st), 1);
                    Element nf = kid(l, "numFmt");
                    if (nf != null && val(nf) != null) L.fmt = val(nf);
                    Element lt = kid(l, "lvlText");
                    if (lt != null) L.text = val(lt) != null ? val(lt) : "";
                    Element ind = kid(kid(l, "pPr"), "ind");
                    if (ind != null) {
                        String left = attr(ind, "left");
                        if (left == null) left = attr(ind, "start");
                        L.left = parseInt(left, -1);
                        L.hanging = parseInt(attr(ind, "hanging"), -1);
                    }
                    lv[il] = L;
                }
                abstracts.put(attr(e, "abstractNumId"), lv);
            } else if ("num".equals(n)) {
                String id = attr(e, "numId");
                Element a = kid(e, "abstractNumId");
                if (id == null || a == null) continue;
                numToAbstract.put(id, val(a));
                for (Element o : kids(e)) {
                    if (!"lvlOverride".equals(name(o))) continue;
                    Element so = kid(o, "startOverride");
                    if (so != null) startOverrides.put(id + ":" + attr(o, "ilvl"), parseInt(val(so), 1));
                }
            }
        }
    }

    private Lvl level(String numId, int ilvl) {
        Lvl[] lv = abstracts.get(numToAbstract.get(numId));
        if (lv == null || ilvl < 0 || ilvl > 8) return null;
        return lv[ilvl] != null ? lv[ilvl] : new Lvl();
    }

    private int startFor(String numId, int ilvl, Lvl L) {
        Integer o = startOverrides.get(numId + ":" + ilvl);
        return o != null ? o : L.start;
    }

    private String nextMarker(String numId, int ilvl) {
        Lvl L = level(numId, ilvl);
        if (L == null) return null;
        int[] cnt = counters.get(numId);
        if (cnt == null) {
            cnt = new int[9];
            Arrays.fill(cnt, Integer.MIN_VALUE);
            counters.put(numId, cnt);
        }
        cnt[ilvl] = cnt[ilvl] == Integer.MIN_VALUE ? startFor(numId, ilvl, L) : cnt[ilvl] + 1;
        for (int k = ilvl + 1; k < 9; k++) cnt[k] = Integer.MIN_VALUE;

        if ("bullet".equals(L.fmt)) return bullet(L.text);
        if ("none".equals(L.fmt)) return "";
        String t = L.text;
        for (int k = 0; k < 9; k++) {
            String key = "%" + (k + 1);
            if (!t.contains(key)) continue;
            Lvl Lk = level(numId, k);
            if (Lk == null) Lk = L;
            int v = cnt[k] == Integer.MIN_VALUE ? startFor(numId, k, Lk) : cnt[k];
            t = t.replace(key, fmtNum(v, Lk.fmt));
        }
        return t;
    }

    private static String bullet(String text) {
        if (text == null || text.isEmpty()) return "•";
        char c = text.charAt(0);
        if (c >= 0xE000 && c <= 0xF8FF) {
            // Caracteres de fuentes de símbolos (Symbol, Wingdings, OpenSymbol...)
            switch (c & 0xFF) {
                case 0xA7: return "▪";
                case 0xFC: return "✓";
                case 0xD8: return "➢";
                case 0x76: return "❖";
                case 0x71: return "❑";
                case 0x6E: return "■";
                case 0x6F: return "◦";
                default: return "•";
            }
        }
        if (c == 'o') return "◦";
        if (c < 0x20 || (c >= 0x7F && c < 0xA0)) return "•";
        return String.valueOf(c);
    }

    private static String fmtNum(int v, String fmt) {
        if (fmt == null) return String.valueOf(v);
        switch (fmt) {
            case "lowerLetter": return letters(v, false);
            case "upperLetter": return letters(v, true);
            case "lowerRoman": return roman(v).toLowerCase(Locale.ROOT);
            case "upperRoman": return roman(v);
            case "decimalZero": return v < 10 && v >= 0 ? "0" + v : String.valueOf(v);
            case "ordinal": return v + "º";
            default: return String.valueOf(v);
        }
    }

    private static String letters(int v, boolean upper) {
        if (v <= 0) return String.valueOf(v);
        int rep = (v - 1) / 26 + 1;
        char c = (char) ((upper ? 'A' : 'a') + (v - 1) % 26);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < rep; k++) sb.append(c);
        return sb.toString();
    }

    private static String roman(int v) {
        if (v <= 0 || v > 3999) return String.valueOf(v);
        int[] n = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n.length; k++) {
            while (v >= n[k]) {
                sb.append(s[k]);
                v -= n[k];
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ bloques

    private void blocks(Element parent, StringBuilder sb) {
        for (Element c : kids(parent)) {
            switch (name(c)) {
                case "p": paragraph(c, sb); break;
                case "tbl": table(c, sb); break;
                case "sdt": {
                    Element sc = kid(c, "sdtContent");
                    if (sc != null) blocks(sc, sb);
                    break;
                }
                case "customXml":
                case "ins":
                    blocks(c, sb);
                    break;
                case "AlternateContent": {
                    Element ch = kid(c, "Choice");
                    if (ch == null) ch = kid(c, "Fallback");
                    if (ch != null) blocks(ch, sb);
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static int px(int twips) {
        return Math.round(twips / 15f);
    }

    private void paragraph(Element p, StringBuilder sb) {
        Element pPr = kid(p, "pPr");
        String sid = pPr != null ? val(kid(pPr, "pStyle")) : null;
        if (sid == null) sid = defaultParaStyle;

        int level = headingLevel(sid);
        if (pPr != null) {
            Element ol = kid(pPr, "outlineLvl");
            if (ol != null) {
                int o = parseInt(val(ol), 9);
                level = (o >= 0 && o < 9) ? o + 1 : 0;
            }
        }

        StringBuilder css = new StringBuilder();
        String jc = pPr != null ? val(kid(pPr, "jc")) : null;
        if (jc == null) jc = styleJc(sid);
        if (jc != null) {
            switch (jc) {
                case "center": css.append("text-align:center;"); break;
                case "right":
                case "end": css.append("text-align:right;"); break;
                case "both":
                case "distribute": css.append("text-align:justify;"); break;
                default: break;
            }
        }

        // Numeración (del párrafo o heredada del estilo)
        String numId = null;
        int ilvl = 0;
        Element numPr = pPr != null ? kid(pPr, "numPr") : null;
        if (numPr != null) {
            Element id = kid(numPr, "numId");
            Element lv = kid(numPr, "ilvl");
            if (id != null) numId = val(id);
            if (lv != null) ilvl = parseInt(val(lv), 0);
        }
        if (numId == null) {
            for (Style s : chain(sid)) {
                if (s.numId != null) {
                    numId = s.numId;
                    if (s.ilvl != null) ilvl = s.ilvl;
                    break;
                }
            }
        }
        String marker = null;
        Lvl lvl = null;
        if (numId != null && !"0".equals(numId)) {
            ilvl = Math.max(0, Math.min(8, ilvl));
            marker = nextMarker(numId, ilvl);
            lvl = level(numId, ilvl);
        }

        // Sangrías
        Element ind = pPr != null ? kid(pPr, "ind") : null;
        int left = -1, hanging = -1, first = -1;
        if (ind != null) {
            String l = attr(ind, "left");
            if (l == null) l = attr(ind, "start");
            left = parseInt(l, -1);
            hanging = parseInt(attr(ind, "hanging"), -1);
            first = parseInt(attr(ind, "firstLine"), -1);
        }
        if (marker != null && lvl != null) {
            if (left < 0) left = lvl.left >= 0 ? lvl.left : 360 * (ilvl + 1);
            if (hanging < 0 && first < 0) hanging = lvl.hanging >= 0 ? lvl.hanging : 360;
        }
        if (inNote) {
            left = -1;
            hanging = -1;
            first = -1;
        }
        if (left > 0) css.append("padding-left:").append(Math.min(px(left), 220)).append("px;");
        if (hanging > 0) css.append("text-indent:-").append(Math.min(px(hanging), 120)).append("px;");
        else if (first > 0) css.append("text-indent:").append(Math.min(px(first), 120)).append("px;");

        if (pPr != null) {
            Element shd = kid(pPr, "shd");
            String fill = shd != null ? attr(shd, "fill") : null;
            if (isHex6(fill)) css.append("background-color:#").append(fill).append(';');
            Element pbb = kid(pPr, "pageBreakBefore");
            if (pbb != null && on(pbb)) sb.append("<span class=\"pb\"></span>");
        }

        Props base = defaults.over(styleProps(sid));

        sb.append("<div class=\"p");
        if (level > 0) sb.append(" h").append(Math.min(level, 6));
        sb.append('"');
        if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
        sb.append('>');
        if (marker != null && !marker.isEmpty()) {
            sb.append("<span class=\"li\">").append(esc(marker)).append("</span>");
        }
        inline(p, base, sb);
        sb.append("</div>");
    }

    private void inline(Element parent, Props base, StringBuilder sb) {
        for (Element c : kids(parent)) {
            switch (name(c)) {
                case "r":
                    run(c, base, sb);
                    break;
                case "hyperlink": {
                    String url = null;
                    String rid = attr(c, "id");
                    if (rid != null) {
                        String[] r = rels.get(rid);
                        if (r != null) url = r[0];
                    }
                    if (url != null && isWebLink(url)) {
                        sb.append("<a href=\"").append(esc(url)).append("\">");
                        inline(c, base, sb);
                        sb.append("</a>");
                    } else {
                        inline(c, base, sb);
                    }
                    break;
                }
                case "ins":
                case "smartTag":
                case "customXml":
                case "fldSimple":
                case "bdo":
                case "dir":
                case "moveTo":
                    inline(c, base, sb);
                    break;
                case "sdt": {
                    Element sc = kid(c, "sdtContent");
                    if (sc != null) inline(sc, base, sb);
                    break;
                }
                case "AlternateContent": {
                    Element ch = kid(c, "Choice");
                    if (ch == null) ch = kid(c, "Fallback");
                    if (ch != null) inline(ch, base, sb);
                    break;
                }
                case "oMath":
                case "oMathPara":
                    math(c, sb);
                    break;
                default:
                    break;
            }
        }
    }

    private static boolean isWebLink(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("mailto:");
    }

    private void math(Element m, StringBuilder sb) {
        List<Element> ts = new ArrayList<>();
        Ooxml.descendants(m, "t", null, ts);
        StringBuilder t = new StringBuilder();
        for (Element e : ts) t.append(e.getTextContent());
        if (t.length() > 0) sb.append("<i class=\"math\">").append(esc(t.toString())).append("</i>");
    }

    private void run(Element r, Props base, StringBuilder sb) {
        Element rPr = kid(r, "rPr");
        Props p = base;
        if (rPr != null) {
            String rs = val(kid(rPr, "rStyle"));
            if (rs != null) p = p.over(styleProps(rs));
            p = p.over(parseRPr(rPr));
        }
        if (kid(r, "footnoteReference") != null || kid(r, "endnoteReference") != null) {
            // La llamada de nota ya es <sup>; evita subirla dos veces
            p = p.copy();
            p.va = null;
        }
        StringBuilder text = new StringBuilder();
        StringBuilder extra = new StringBuilder();
        runContent(r, text, extra);
        if (text.length() > 0) {
            String css = css(p);
            if (css.isEmpty()) {
                sb.append(text);
            } else {
                sb.append("<span style=\"").append(css).append("\">").append(text).append("</span>");
            }
        }
        sb.append(extra);
    }

    private void runContent(Element r, StringBuilder text, StringBuilder extra) {
        for (Element c : kids(r)) {
            switch (name(c)) {
                case "t":
                    text.append(esc(c.getTextContent()));
                    break;
                case "tab":
                    text.append('\t');
                    break;
                case "br":
                case "cr":
                    if ("page".equals(attr(c, "type"))) text.append("<span class=\"pb\"></span>");
                    else text.append("<br>");
                    break;
                case "noBreakHyphen":
                    text.append('‑');
                    break;
                case "sym": {
                    String ch = attr(c, "char");
                    int code = -1;
                    try {
                        code = Integer.parseInt(ch, 16);
                    } catch (Exception ignored) {
                    }
                    if (code >= 0xF000) code &= 0xFF;
                    if (code > 0x20) text.append(esc(bullet(String.valueOf((char) code))));
                    break;
                }
                case "drawing":
                    drawing(c, extra);
                    break;
                case "pict":
                case "object":
                    pict(c, extra);
                    break;
                case "AlternateContent": {
                    Element ch = kid(c, "Choice");
                    if (ch == null) ch = kid(c, "Fallback");
                    if (ch != null) runContent(ch, text, extra);
                    break;
                }
                case "footnoteReference":
                    text.append(noteRef(attr(c, "id"), footnotes));
                    break;
                case "endnoteReference":
                    text.append(noteRef(attr(c, "id"), endnotes));
                    break;
                default:
                    break;
            }
        }
    }

    private static String noteRef(String id, List<String> list) {
        if (id == null) return "";
        list.add(id);
        return "<sup class=\"fn\">" + list.size() + "</sup>";
    }

    // ------------------------------------------------------------ imágenes

    private void drawing(Element d, StringBuilder out) {
        long cx = 0;
        Element ext = Ooxml.firstDescendant(d, "extent");
        if (ext != null) cx = Ooxml.parseLong(attr(ext, "cx"), 0);

        List<Element> blips = new ArrayList<>();
        Ooxml.descendants(d, "blip", "txbxContent", blips);
        for (Element b : blips) {
            String rid = attr(b, "embed");
            if (rid == null) rid = attr(b, "link");
            out.append(image(rid, blips.size() == 1 ? cx : 0));
        }
        if (blips.isEmpty()) {
            List<Element> charts = new ArrayList<>();
            Ooxml.descendants(d, "chart", "txbxContent", charts);
            if (!charts.isEmpty()) out.append("<span class=\"noimg\">[gráfico]</span>");
        }
        List<Element> boxes = new ArrayList<>();
        Ooxml.descendants(d, "txbxContent", "txbxContent", boxes);
        for (Element tb : boxes) {
            out.append("<span class=\"tb\">");
            blocks(tb, out);
            out.append("</span>");
        }
    }

    private void pict(Element p, StringBuilder out) {
        List<Element> imgs = new ArrayList<>();
        Ooxml.descendants(p, "imagedata", "txbxContent", imgs);
        for (Element im : imgs) out.append(image(attr(im, "id"), 0));
        List<Element> boxes = new ArrayList<>();
        Ooxml.descendants(p, "txbxContent", "txbxContent", boxes);
        for (Element tb : boxes) {
            out.append("<span class=\"tb\">");
            blocks(tb, out);
            out.append("</span>");
        }
    }

    private String image(String rid, long cx) {
        if (rid == null) return "";
        String[] r = rels.get(rid);
        if (r == null || "External".equals(r[1])) return "";
        String path = r[0];
        String local = imageCache.get(path);
        if (local == null) {
            String ext = Ooxml.extOf(path);
            if (!WEB_IMAGES.contains(ext)) return "<span class=\"noimg\">[imagen]</span>";
            if (imageCount >= MAX_IMAGES) return "";
            byte[] data = Ooxml.read(zip, path);
            if (data == null) return "";
            mediaDir.mkdirs();
            local = "img" + (++imageCount) + "." + ext;
            try (FileOutputStream fo = new FileOutputStream(new File(mediaDir, local))) {
                fo.write(data);
            } catch (IOException e) {
                return "";
            }
            imageCache.put(path, local);
        }
        StringBuilder sb = new StringBuilder("<img src=\"media/").append(local).append('"');
        if (cx > 0) sb.append(" style=\"width:").append(Math.max(1, cx / 9525)).append("px\"");
        sb.append(" alt=\"\">");
        return sb.toString();
    }

    // ------------------------------------------------------------- tablas

    private static final class Cell {
        Element tc;
        int start, span = 1, vmerge; // vmerge: 0 no, 1 inicia, 2 continúa
        String bg;
    }

    private void table(Element tbl, StringBuilder sb) {
        List<Element> rows = new ArrayList<>();
        collect(tbl, "tr", rows);
        List<List<Cell>> grid = new ArrayList<>();
        for (Element tr : rows) {
            List<Cell> cells = new ArrayList<>();
            int col = 0;
            Element trPr = kid(tr, "trPr");
            if (trPr != null) {
                Element gb = kid(trPr, "gridBefore");
                if (gb != null) col += Math.max(0, parseInt(val(gb), 0));
            }
            List<Element> tcs = new ArrayList<>();
            collect(tr, "tc", tcs);
            for (Element tc : tcs) {
                Cell c = new Cell();
                c.tc = tc;
                c.start = col;
                Element tcPr = kid(tc, "tcPr");
                if (tcPr != null) {
                    Element gs = kid(tcPr, "gridSpan");
                    if (gs != null) c.span = Math.max(1, parseInt(val(gs), 1));
                    Element vm = kid(tcPr, "vMerge");
                    if (vm != null) c.vmerge = "restart".equals(val(vm)) ? 1 : 2;
                    Element shd = kid(tcPr, "shd");
                    String fill = shd != null ? attr(shd, "fill") : null;
                    if (isHex6(fill)) c.bg = "#" + fill;
                }
                col += c.span;
                cells.add(c);
            }
            grid.add(cells);
        }

        sb.append("<div class=\"tw\"><table class=\"t\">");
        for (int i = 0; i < grid.size(); i++) {
            sb.append("<tr>");
            for (Cell c : grid.get(i)) {
                if (c.vmerge == 2) continue;
                int rowspan = 1;
                if (c.vmerge == 1) {
                    for (int j = i + 1; j < grid.size(); j++) {
                        Cell below = cellAt(grid.get(j), c.start);
                        if (below != null && below.vmerge == 2) rowspan++;
                        else break;
                    }
                }
                sb.append("<td");
                if (c.span > 1) sb.append(" colspan=\"").append(c.span).append('"');
                if (rowspan > 1) sb.append(" rowspan=\"").append(rowspan).append('"');
                if (c.bg != null) sb.append(" style=\"background-color:").append(c.bg).append('"');
                sb.append('>');
                blocks(c.tc, sb);
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table></div>");
    }

    private static Cell cellAt(List<Cell> row, int start) {
        for (Cell c : row) if (c.start == start) return c;
        return null;
    }

    /** Hijos con ese nombre, entrando en controles de contenido (sdt) y customXml. */
    private static void collect(Element parent, String local, List<Element> out) {
        for (Element c : kids(parent)) {
            String n = name(c);
            if (local.equals(n)) {
                out.add(c);
            } else if ("sdt".equals(n)) {
                Element sc = kid(c, "sdtContent");
                if (sc != null) collect(sc, local, out);
            } else if ("customXml".equals(n)) {
                collect(c, local, out);
            }
        }
    }

    // -------------------------------------------------------------- notas

    private void notes(StringBuilder sb, String base, Map<String, String[]> docRels, List<String> ids,
                       String type, String fallback, String element, String title) throws Exception {
        if (ids.isEmpty()) return;
        String path = Ooxml.partByType(docRels, type, base + fallback);
        Document d = Ooxml.dom(zip, path);
        if (d == null) return;
        Map<String, Element> byId = new HashMap<>();
        for (Element e : kids(d.getDocumentElement())) {
            if (element.equals(name(e))) byId.put(attr(e, "id"), e);
        }
        Map<String, String[]> saved = rels;
        rels = Ooxml.rels(zip, Ooxml.relsPathFor(path), Ooxml.dirOf(path));
        sb.append("<div class=\"notes\"><div class=\"p\"><b>").append(title).append("</b></div>");
        List<String> copy = new ArrayList<>(ids);
        for (int k = 0; k < copy.size(); k++) {
            Element fn = byId.get(copy.get(k));
            if (fn == null) continue;
            StringBuilder nb = new StringBuilder();
            inNote = true;
            blocks(fn, nb);
            inNote = false;
            // Quita el tabulador que Word pone después del número de la nota
            String html = nb.toString().replaceFirst(">\t", ">");
            sb.append("<div class=\"note\"><sup>").append(k + 1).append("</sup> ").append(html).append("</div>");
        }
        sb.append("</div>");
        rels = saved;
    }
}
