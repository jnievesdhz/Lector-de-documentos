package com.jnieves.lector.office;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

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
import java.util.zip.ZipFile;

import static com.jnieves.lector.office.Ooxml.attr;
import static com.jnieves.lector.office.Ooxml.esc;
import static com.jnieves.lector.office.Ooxml.isHex6;
import static com.jnieves.lector.office.Ooxml.kid;
import static com.jnieves.lector.office.Ooxml.kids;
import static com.jnieves.lector.office.Ooxml.name;
import static com.jnieves.lector.office.Ooxml.parseInt;
import static com.jnieves.lector.office.Ooxml.parseLong;
import static com.jnieves.lector.office.Ooxml.val;

/**
 * Convierte una presentación de PowerPoint (.pptx) a HTML. Cada diapositiva
 * se arma con sus elementos en la posición original (textos, formas, imágenes,
 * tablas, grupos, SmartArt), heredando estilos del diseño y del patrón, con
 * los colores del tema. Debajo de cada diapositiva van las notas del orador.
 */
public final class PptxToHtml {

    private static final double EMU_PX = 9525.0;
    private static final int MAX_SLIDES = 400;
    private static final int MAX_IMAGES = 600;
    private static final Set<String> WEB_IMAGES = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg"));

    private final ZipFile zip;
    private final File mediaDir;
    private final Map<String, String> imageCache = new HashMap<>();
    private int imageCount;
    private final Map<String, Part> cache = new HashMap<>();
    private final Map<String, Theme> themes = new HashMap<>();
    private Element defaultTextStyle;
    private long slideW = 9144000, slideH = 6858000;

    private PptxToHtml(ZipFile zip, File outDir) {
        this.zip = zip;
        this.mediaDir = new File(outDir, "media");
    }

    public static String convert(File pptx, File outDir) throws Exception {
        try (ZipFile zip = new ZipFile(pptx)) {
            return new PptxToHtml(zip, outDir).run();
        }
    }

    // --------------------------------------------------------------- partes

    private static final class Part {
        String path;
        Element root;
        Map<String, String[]> rels;
    }

    private static final class Theme {
        final Map<String, String> colors = new HashMap<>();
        String major, minor;
    }

    private static final class Ctx {
        Part slide, layout, master;
        Theme theme;
        final Map<String, String> clrMap = new HashMap<>();
    }

    private Part load(String path, boolean keep) throws Exception {
        if (path == null) return null;
        if (keep && cache.containsKey(path)) return cache.get(path);
        Document d = Ooxml.dom(zip, path);
        Part p = null;
        if (d != null) {
            p = new Part();
            p.path = path;
            p.root = d.getDocumentElement();
            p.rels = Ooxml.rels(zip, Ooxml.relsPathFor(path), Ooxml.dirOf(path));
        }
        if (keep) cache.put(path, p);
        return p;
    }

    private Theme theme(Part master) throws Exception {
        String path = master != null ? Ooxml.partByType(master.rels, "/theme", null) : null;
        if (path == null) return new Theme();
        Theme t = themes.get(path);
        if (t != null) return t;
        t = new Theme();
        Document d = Ooxml.dom(zip, path);
        if (d != null) {
            Element te = kid(d.getDocumentElement(), "themeElements");
            for (Element c : kids(kid(te, "clrScheme"))) {
                Element v = kids(c).isEmpty() ? null : kids(c).get(0);
                if (v == null) continue;
                String hex = "sysClr".equals(name(v)) ? attr(v, "lastClr") : val(v);
                if (isHex6(hex)) t.colors.put(name(c), hex);
            }
            Element fs = kid(te, "fontScheme");
            t.major = attr(kid(kid(fs, "majorFont"), "latin"), "typeface");
            t.minor = attr(kid(kid(fs, "minorFont"), "latin"), "typeface");
        }
        themes.put(path, t);
        return t;
    }

    // ------------------------------------------------------------ principal

    private String run() throws Exception {
        String presPath = Ooxml.mainPart(zip, "ppt/presentation.xml");
        Part pres = load(presPath, false);
        if (pres == null) throw new IOException("No es una presentación de PowerPoint válida");
        Element sz = kid(pres.root, "sldSz");
        if (sz != null) {
            slideW = parseLong(attr(sz, "cx"), slideW);
            slideH = parseLong(attr(sz, "cy"), slideH);
        }
        defaultTextStyle = kid(pres.root, "defaultTextStyle");

        List<String> slides = new ArrayList<>();
        for (Element s : kids(kid(pres.root, "sldIdLst"))) {
            String[] r = pres.rels.get(Ooxml.rid(s));
            if (r != null) slides.add(r[0]);
        }
        if (slides.isEmpty()) return "<div class=\"note\">La presentación no tiene diapositivas.</div>";

        int w = (int) Math.round(slideW / EMU_PX), h = (int) Math.round(slideH / EMU_PX);
        StringBuilder sb = new StringBuilder(128 * 1024);
        int total = Math.min(slides.size(), MAX_SLIDES);
        for (int i = 0; i < total; i++) {
            StringBuilder one = new StringBuilder();
            boolean hidden = false;
            String notes = "";
            String bg = "background:#fff;";
            try {
                Ctx ctx = new Ctx();
                ctx.slide = load(slides.get(i), false);
                if (ctx.slide == null) throw new IOException();
                ctx.layout = load(Ooxml.partByType(ctx.slide.rels, "/slideLayout", null), true);
                ctx.master = ctx.layout != null
                        ? load(Ooxml.partByType(ctx.layout.rels, "/slideMaster", null), true) : null;
                ctx.theme = theme(ctx.master);
                if (ctx.master != null) {
                    Element cm = kid(ctx.master.root, "clrMap");
                    if (cm != null) {
                        NamedNodeMap a = cm.getAttributes();
                        for (int k = 0; k < a.getLength(); k++) {
                            Node n = a.item(k);
                            ctx.clrMap.put(name(n), n.getNodeValue());
                        }
                    }
                }
                hidden = "0".equals(ctx.slide.root.getAttribute("show"));
                bg = background(ctx);
                boolean slideShowsMaster = !"0".equals(ctx.slide.root.getAttribute("showMasterSp"));
                boolean layoutShowsMaster = ctx.layout == null
                        || !"0".equals(ctx.layout.root.getAttribute("showMasterSp"));
                Xform id = new Xform();
                if (slideShowsMaster && layoutShowsMaster && ctx.master != null) {
                    tree(spTree(ctx.master), ctx, ctx.master, id, true, one);
                }
                if (slideShowsMaster && ctx.layout != null) {
                    tree(spTree(ctx.layout), ctx, ctx.layout, id, true, one);
                }
                tree(spTree(ctx.slide), ctx, ctx.slide, id, false, one);
                notes = notes(ctx.slide);
            } catch (Exception e) {
                one.setLength(0);
                one.append("<div class=\"shp\" style=\"left:0;top:0;width:100%;height:100%;display:flex;")
                        .append("align-items:center;justify-content:center;font-size:28px;color:#888\">")
                        .append("No se pudo mostrar esta diapositiva</div>");
            }
            sb.append("<div class=\"sl\" data-w=\"").append(w).append("\" style=\"aspect-ratio:")
                    .append(w).append('/').append(h).append("\"><div class=\"si\" style=\"width:")
                    .append(w).append("px;height:").append(h).append("px;").append(bg).append("\">")
                    .append(one).append("</div></div>");
            sb.append("<div class=\"sn\">").append(i + 1).append(" / ").append(slides.size());
            if (hidden) sb.append(" · oculta");
            sb.append("</div>");
            if (!notes.isEmpty()) sb.append("<div class=\"snotes\">").append(notes).append("</div>");
        }
        if (slides.size() > MAX_SLIDES) {
            sb.append("<div class=\"note\">Se muestran las primeras ").append(MAX_SLIDES).append(" diapositivas.</div>");
        }
        return sb.toString();
    }

    private static Element spTree(Part p) {
        return kid(kid(p.root, "cSld"), "spTree");
    }

    // ------------------------------------------------------- transformación

    /** Convierte coordenadas internas (EMU) a coordenadas de la diapositiva. */
    private static final class Xform {
        double sx = 1, sy = 1, dx = 0, dy = 0;

        Xform child(Element xfrm) {
            Element off = kid(xfrm, "off"), ext = kid(xfrm, "ext");
            Element chOff = kid(xfrm, "chOff"), chExt = kid(xfrm, "chExt");
            if (off == null || ext == null || chOff == null || chExt == null) return this;
            double ox = parseLong(attr(off, "x"), 0), oy = parseLong(attr(off, "y"), 0);
            double ex = parseLong(attr(ext, "cx"), 0), ey = parseLong(attr(ext, "cy"), 0);
            double cox = parseLong(attr(chOff, "x"), 0), coy = parseLong(attr(chOff, "y"), 0);
            double cex = parseLong(attr(chExt, "cx"), 0), cey = parseLong(attr(chExt, "cy"), 0);
            double kx = cex > 0 ? ex / cex : 1, ky = cey > 0 ? ey / cey : 1;
            Xform r = new Xform();
            r.sx = sx * kx;
            r.sy = sy * ky;
            r.dx = dx + sx * (ox - cox * kx);
            r.dy = dy + sy * (oy - coy * ky);
            return r;
        }

        Xform shift(double ox, double oy) {
            Xform r = new Xform();
            r.sx = sx;
            r.sy = sy;
            r.dx = dx + sx * ox;
            r.dy = dy + sy * oy;
            return r;
        }
    }

    private static final class Box {
        double x, y, w, h, rot;
        boolean flipH, flipV;
    }

    private static Box box(Element xfrm, Xform xf) {
        if (xfrm == null) return null;
        Element off = kid(xfrm, "off"), ext = kid(xfrm, "ext");
        if (off == null || ext == null) return null;
        Box b = new Box();
        b.x = (xf.dx + xf.sx * parseLong(attr(off, "x"), 0)) / EMU_PX;
        b.y = (xf.dy + xf.sy * parseLong(attr(off, "y"), 0)) / EMU_PX;
        b.w = xf.sx * parseLong(attr(ext, "cx"), 0) / EMU_PX;
        b.h = xf.sy * parseLong(attr(ext, "cy"), 0) / EMU_PX;
        b.rot = parseLong(attr(xfrm, "rot"), 0) / 60000.0;
        b.flipH = "1".equals(attr(xfrm, "flipH"));
        b.flipV = "1".equals(attr(xfrm, "flipV"));
        return b;
    }

    private static String pos(Box b, boolean flips) {
        StringBuilder s = new StringBuilder();
        s.append("left:").append(r1(b.x)).append("px;top:").append(r1(b.y))
                .append("px;width:").append(r1(b.w)).append("px;height:").append(r1(b.h)).append("px;");
        StringBuilder t = new StringBuilder();
        if (b.rot != 0) t.append("rotate(").append(r1(b.rot)).append("deg) ");
        if (flips && b.flipH) t.append("scaleX(-1) ");
        if (flips && b.flipV) t.append("scaleY(-1) ");
        if (t.length() > 0) s.append("transform:").append(t.toString().trim()).append(';');
        return s.toString();
    }

    private static String r1(double v) {
        long r = Math.round(v * 10);
        return (r % 10 == 0) ? String.valueOf(r / 10) : String.valueOf(r / 10.0);
    }

    // --------------------------------------------------------------- árbol

    private void tree(Element spTree, Ctx ctx, Part part, Xform xf, boolean template, StringBuilder sb) {
        if (spTree == null) return;
        for (Element c : kids(spTree)) {
            try {
                switch (name(c)) {
                    case "sp": shape(c, ctx, part, xf, template, sb); break;
                    case "pic": picture(c, ctx, part, xf, template, sb); break;
                    case "cxnSp": connector(c, ctx, xf, sb); break;
                    case "graphicFrame": frame(c, ctx, part, xf, sb); break;
                    case "grpSp": {
                        Element gx = kid(kid(c, "grpSpPr"), "xfrm");
                        tree(c, ctx, part, gx != null ? xf.child(gx) : xf, template, sb);
                        break;
                    }
                    case "AlternateContent": {
                        Element ch = kid(c, "Choice");
                        if (ch == null) ch = kid(c, "Fallback");
                        if (ch != null) tree(ch, ctx, part, xf, template, sb);
                        break;
                    }
                    default: break;
                }
            } catch (RuntimeException ignored) {
                // Un elemento raro no debe dañar toda la diapositiva
            }
        }
    }

    // ---------------------------------------------------------- marcadores

    private static Element ph(Element shape) {
        for (Element c : kids(shape)) {
            String n = name(c);
            if (n.startsWith("nv") && n.endsWith("Pr")) return kid(kid(c, "nvPr"), "ph");
        }
        return null;
    }

    private static String normType(String t) {
        if (t == null || t.equals("obj") || t.equals("subTitle") || t.equals("body")) return "body";
        if (t.equals("ctrTitle") || t.equals("title")) return "title";
        return t;
    }

    /** Busca el marcador equivalente en el diseño o el patrón. */
    private Element findPh(Part part, String type, String idx, boolean byIdx) {
        if (part == null) return null;
        List<Element> all = new ArrayList<>();
        collectShapes(spTree(part), all);
        if (byIdx && idx != null) {
            for (Element s : all) {
                Element p = ph(s);
                if (p != null && idx.equals(attr(p, "idx"))) return s;
            }
        }
        String nt = normType(type);
        for (Element s : all) {
            Element p = ph(s);
            if (p != null && nt.equals(normType(attr(p, "type")))) return s;
        }
        return null;
    }

    private static void collectShapes(Element tree, List<Element> out) {
        if (tree == null) return;
        for (Element c : kids(tree)) {
            String n = name(c);
            if ("sp".equals(n) || "pic".equals(n) || "graphicFrame".equals(n)) out.add(c);
            else if ("grpSp".equals(n)) collectShapes(c, out);
        }
    }

    private static Element xfrmOf(Element shape) {
        if (shape == null) return null;
        Element spPr = kid(shape, "spPr");
        Element x = kid(spPr, "xfrm");
        if (x == null) x = kid(shape, "xfrm");
        return x;
    }

    private static String styleName(String phType) {
        String t = normType(phType);
        if ("title".equals(t)) return "titleStyle";
        if ("body".equals(t)) return "bodyStyle";
        return "otherStyle";
    }

    // -------------------------------------------------------------- formas

    private void shape(Element sp, Ctx ctx, Part part, Xform xf, boolean template, StringBuilder sb) {
        Element ph = ph(sp);
        if (template && ph != null) return;
        Element spPr = kid(sp, "spPr");

        Element layoutPh = null, masterPh = null;
        String phType = ph != null ? attr(ph, "type") : null;
        if (ph != null) {
            layoutPh = findPh(ctx.layout, phType, attr(ph, "idx"), true);
            String t = layoutPh != null && ph(layoutPh) != null ? attr(ph(layoutPh), "type") : phType;
            masterPh = findPh(ctx.master, t != null ? t : phType, null, false);
            if (phType == null && t != null) phType = t;
        }

        Element xfrm = kid(spPr, "xfrm");
        if (xfrm == null) xfrm = xfrmOf(layoutPh);
        if (xfrm == null) xfrm = xfrmOf(masterPh);
        Box b = box(xfrm, xf);
        if (b == null) return;

        Element geom = kid(spPr, "prstGeom");
        String prst = geom != null ? attr(geom, "prst") : null;
        Element style = kid(sp, "style");
        Element txBody = kid(sp, "txBody");
        boolean hasText = txBody != null && hasText(txBody);

        if (prst != null && (prst.equals("line") || prst.startsWith("straightConnector"))) {
            line(b, spPr, style, ctx, sb);
            return;
        }

        StringBuilder css = new StringBuilder(pos(b, !hasText));
        String fill = fillCss(spPr, ctx, part);
        if (fill == null && layoutPh != null) fill = fillCss(kid(layoutPh, "spPr"), ctx, ctx.layout);
        if (fill == null && style != null) {
            Element fr = kid(style, "fillRef");
            if (fr != null && parseInt(attr(fr, "idx"), 0) > 0) {
                String c = colorOf(fr, ctx);
                if (c != null) fill = "background:" + c + ";";
            }
        }
        if (fill != null) css.append(fill);
        String border = lineCss(spPr, style, ctx);
        if (border != null) css.append(border);
        css.append(geometry(prst, b));

        sb.append("<div class=\"shp\" style=\"").append(css).append("\">");
        if (hasText) {
            List<Element> lst = new ArrayList<>();
            lst.add(kid(txBody, "lstStyle"));
            if (layoutPh != null) lst.add(kid(kid(layoutPh, "txBody"), "lstStyle"));
            if (masterPh != null) lst.add(kid(kid(masterPh, "txBody"), "lstStyle"));
            if (ph != null && ctx.master != null) {
                lst.add(kid(kid(ctx.master.root, "txStyles"), styleName(phType)));
            } else {
                lst.add(defaultTextStyle);
                if (ctx.master != null) lst.add(kid(kid(ctx.master.root, "txStyles"), "otherStyle"));
            }
            List<Element> bodies = new ArrayList<>();
            bodies.add(kid(txBody, "bodyPr"));
            if (layoutPh != null) bodies.add(kid(kid(layoutPh, "txBody"), "bodyPr"));
            if (masterPh != null) bodies.add(kid(kid(masterPh, "txBody"), "bodyPr"));

            String fontColor = null, fontFace = null;
            if (style != null) {
                Element fr = kid(style, "fontRef");
                if (fr != null) {
                    fontColor = colorOf(fr, ctx);
                    String idx = attr(fr, "idx");
                    if ("major".equals(idx)) fontFace = "+mj-lt";
                    else if ("minor".equals(idx)) fontFace = "+mn-lt";
                }
            }
            boolean isTitle = "title".equals(normType(phType)) && ph != null;
            textBox(txBody, lst, bodies, fontColor, fontFace != null ? fontFace : (isTitle ? "+mj-lt" : "+mn-lt"),
                    ctx, part, sb);
        }
        sb.append("</div>");
    }

    private static boolean hasText(Element txBody) {
        for (Element p : kids(txBody)) {
            if (!"p".equals(name(p))) continue;
            for (Element r : kids(p)) {
                String n = name(r);
                if ("r".equals(n) || "fld".equals(n)) {
                    Element t = kid(r, "t");
                    if (t != null && !t.getTextContent().isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private String geometry(String prst, Box b) {
        if (prst == null) return "";
        double m = Math.min(b.w, b.h);
        switch (prst) {
            case "roundRect": return "border-radius:" + r1(m * 0.1667) + "px;";
            case "ellipse": return "border-radius:50%;";
            case "flowChartTerminator": return "border-radius:" + r1(m / 2) + "px;";
            case "triangle": return "clip-path:polygon(50% 0,100% 100%,0 100%);";
            case "rtTriangle": return "clip-path:polygon(0 0,100% 100%,0 100%);";
            case "diamond":
            case "flowChartDecision": return "clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%);";
            case "parallelogram":
            case "flowChartInputOutput": return "clip-path:polygon(25% 0,100% 0,75% 100%,0 100%);";
            case "trapezoid": return "clip-path:polygon(25% 0,75% 0,100% 100%,0 100%);";
            case "homePlate": return "clip-path:polygon(0 0,80% 0,100% 50%,80% 100%,0 100%);";
            case "chevron": return "clip-path:polygon(0 0,80% 0,100% 50%,80% 100%,0 100%,20% 50%);";
            case "rightArrow": return "clip-path:polygon(0 25%,70% 25%,70% 0,100% 50%,70% 100%,70% 75%,0 75%);";
            case "leftArrow": return "clip-path:polygon(100% 25%,30% 25%,30% 0,0 50%,30% 100%,30% 75%,100% 75%);";
            case "upArrow": return "clip-path:polygon(25% 100%,25% 30%,0 30%,50% 0,100% 30%,75% 30%,75% 100%);";
            case "downArrow": return "clip-path:polygon(25% 0,75% 0,75% 70%,100% 70%,50% 100%,0 70%,25% 70%);";
            case "hexagon": return "clip-path:polygon(25% 0,75% 0,100% 50%,75% 100%,25% 100%,0 50%);";
            case "octagon": return "clip-path:polygon(30% 0,70% 0,100% 30%,100% 70%,70% 100%,30% 100%,0 70%,0 30%);";
            case "pentagon": return "clip-path:polygon(50% 0,100% 38%,82% 100%,18% 100%,0 38%);";
            default: return "";
        }
    }

    private void line(Box b, Element spPr, Element style, Ctx ctx, StringBuilder sb) {
        String color = "#000";
        double width = 1;
        Element ln = kid(spPr, "ln");
        boolean set = false;
        if (ln != null) {
            if (kid(ln, "noFill") != null) return;
            String c = colorOf(kid(ln, "solidFill"), ctx);
            if (c != null) {
                color = c;
                set = true;
            }
            long w = parseLong(attr(ln, "w"), -1);
            if (w > 0) width = Math.max(1, w / EMU_PX);
        }
        if (!set && style != null) {
            String c = colorOf(kid(style, "lnRef"), ctx);
            if (c != null) color = c;
        }
        double w = Math.max(b.w, 1), h = Math.max(b.h, 1);
        double x1 = b.flipH ? w : 0, x2 = b.flipH ? 0 : w, y1 = b.flipV ? h : 0, y2 = b.flipV ? 0 : h;
        if (b.w < 1) x1 = x2 = 0.5;
        if (b.h < 1) y1 = y2 = 0.5;
        sb.append("<svg class=\"shp\" style=\"left:").append(r1(b.x)).append("px;top:").append(r1(b.y))
                .append("px;width:").append(r1(w)).append("px;height:").append(r1(h)).append("px;overflow:visible");
        if (b.rot != 0) sb.append(";transform:rotate(").append(r1(b.rot)).append("deg)");
        sb.append("\"><line x1=\"").append(r1(x1)).append("\" y1=\"").append(r1(y1)).append("\" x2=\"")
                .append(r1(x2)).append("\" y2=\"").append(r1(y2)).append("\" stroke=\"").append(color)
                .append("\" stroke-width=\"").append(r1(width)).append("\"/></svg>");
    }

    private void connector(Element c, Ctx ctx, Xform xf, StringBuilder sb) {
        Element spPr = kid(c, "spPr");
        Box b = box(kid(spPr, "xfrm"), xf);
        if (b != null) line(b, spPr, kid(c, "style"), ctx, sb);
    }

    // ------------------------------------------------------- rellenos/colores

    private String fillCss(Element spPr, Ctx ctx, Part part) {
        if (spPr == null) return null;
        for (Element c : kids(spPr)) {
            switch (name(c)) {
                case "noFill": return "background:transparent;";
                case "solidFill": {
                    String col = colorOf(c, ctx);
                    return col != null ? "background:" + col + ";" : null;
                }
                case "gradFill": return gradient(c, ctx);
                case "pattFill": {
                    String col = colorOf(kid(c, "fgClr"), ctx);
                    return col != null ? "background:" + col + ";" : null;
                }
                case "blipFill": {
                    String src = imageSrc(part, attr(kid(c, "blip"), "embed"));
                    return src != null ? "background:url('" + src + "') center/100% 100% no-repeat;" : null;
                }
                default: break;
            }
        }
        return null;
    }

    private String gradient(Element g, Ctx ctx) {
        List<String> stops = new ArrayList<>();
        for (Element gs : kids(kid(g, "gsLst"))) {
            String col = colorOf(gs, ctx);
            if (col == null) continue;
            stops.add(col + " " + r1(parseInt(attr(gs, "pos"), 0) / 1000.0) + "%");
        }
        if (stops.isEmpty()) return null;
        if (stops.size() == 1) return "background:" + stops.get(0).split(" ")[0] + ";";
        Element lin = kid(g, "lin");
        double ang = lin != null ? parseLong(attr(lin, "ang"), 0) / 60000.0 : 90;
        StringBuilder s = new StringBuilder("background:linear-gradient(").append(r1(ang + 90)).append("deg");
        for (String st : stops) s.append(',').append(st);
        return s.append(");").toString();
    }

    private String lineCss(Element spPr, Element style, Ctx ctx) {
        Element ln = kid(spPr, "ln");
        if (ln != null) {
            if (kid(ln, "noFill") != null) return "";
            String c = colorOf(kid(ln, "solidFill"), ctx);
            long w = parseLong(attr(ln, "w"), 12700);
            if (c != null) return "border:" + r1(Math.max(1, w / EMU_PX)) + "px solid " + c + ";";
        }
        if (style != null) {
            Element lr = kid(style, "lnRef");
            if (lr != null && parseInt(attr(lr, "idx"), 0) > 0) {
                String c = colorOf(lr, ctx);
                if (c != null) return "border:1px solid " + c + ";";
            }
        }
        return null;
    }

    private String scheme(String name, Ctx ctx) {
        String n = ctx.clrMap.get(name);
        if (n == null) {
            switch (name) {
                case "bg1": n = "lt1"; break;
                case "tx1": n = "dk1"; break;
                case "bg2": n = "lt2"; break;
                case "tx2": n = "dk2"; break;
                default: n = name;
            }
        }
        String hex = ctx.theme != null ? ctx.theme.colors.get(n) : null;
        if (hex == null) {
            if (n.startsWith("dk")) return "000000";
            if (n.startsWith("lt")) return "FFFFFF";
        }
        return hex;
    }

    /** Lee el color dentro de un elemento (solidFill, fontRef, gs...). */
    private String colorOf(Element holder, Ctx ctx) {
        if (holder == null) return null;
        for (Element c : kids(holder)) {
            String hex;
            switch (name(c)) {
                case "srgbClr": hex = val(c); break;
                case "schemeClr": hex = scheme(val(c), ctx); break;
                case "sysClr":
                    hex = attr(c, "lastClr");
                    if (hex == null) hex = "windowText".equals(val(c)) ? "000000" : "FFFFFF";
                    break;
                case "prstClr": hex = preset(val(c)); break;
                case "scrgbClr": {
                    int r = (int) Math.round(parseInt(attr(c, "r"), 0) * 255 / 100000.0);
                    int g = (int) Math.round(parseInt(attr(c, "g"), 0) * 255 / 100000.0);
                    int b = (int) Math.round(parseInt(attr(c, "b"), 0) * 255 / 100000.0);
                    hex = String.format(Locale.ROOT, "%02X%02X%02X", clamp(r), clamp(g), clamp(b));
                    break;
                }
                default: continue;
            }
            if (!isHex6(hex)) return null;
            return mods(hex, c);
        }
        return null;
    }

    private static String preset(String v) {
        if (v == null) return null;
        switch (v) {
            case "black": return "000000";
            case "white": return "FFFFFF";
            case "red": return "FF0000";
            case "green": return "008000";
            case "blue": return "0000FF";
            case "yellow": return "FFFF00";
            case "gray":
            case "grey": return "808080";
            case "orange": return "FFA500";
            default: return null;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String mods(String hex, Element c) {
        double r = Integer.parseInt(hex.substring(0, 2), 16);
        double g = Integer.parseInt(hex.substring(2, 4), 16);
        double b = Integer.parseInt(hex.substring(4, 6), 16);
        double alpha = 1;
        for (Element m : kids(c)) {
            double v = parseInt(val(m), 0) / 100000.0;
            switch (name(m)) {
                case "lumMod":
                case "lumOff":
                case "satMod": {
                    double[] hsl = toHsl(r, g, b);
                    if (name(m).equals("lumMod")) hsl[2] *= v;
                    else if (name(m).equals("lumOff")) hsl[2] += v;
                    else hsl[1] *= v;
                    hsl[1] = Math.max(0, Math.min(1, hsl[1]));
                    hsl[2] = Math.max(0, Math.min(1, hsl[2]));
                    double[] rgb = fromHsl(hsl);
                    r = rgb[0];
                    g = rgb[1];
                    b = rgb[2];
                    break;
                }
                case "tint":
                    // Se calcula en luz lineal, como PowerPoint
                    r = toSrgb(toLinear(r) * v + (1 - v));
                    g = toSrgb(toLinear(g) * v + (1 - v));
                    b = toSrgb(toLinear(b) * v + (1 - v));
                    break;
                case "shade":
                    r = toSrgb(toLinear(r) * v);
                    g = toSrgb(toLinear(g) * v);
                    b = toSrgb(toLinear(b) * v);
                    break;
                case "alpha":
                    alpha = v;
                    break;
                default:
                    break;
            }
        }
        int ri = clamp((int) Math.round(r)), gi = clamp((int) Math.round(g)), bi = clamp((int) Math.round(b));
        if (alpha < 0.999) {
            return "rgba(" + ri + "," + gi + "," + bi + "," + String.format(Locale.ROOT, "%.2f", alpha) + ")";
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", ri, gi, bi);
    }

    private static double toLinear(double c) {
        c /= 255;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double toSrgb(double l) {
        l = Math.max(0, Math.min(1, l));
        double c = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
        return c * 255;
    }

    private static double[] toHsl(double r, double g, double b) {
        r /= 255;
        g /= 255;
        b /= 255;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double h = 0, s, l = (max + min) / 2;
        if (max == min) {
            s = 0;
        } else {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    private static double[] fromHsl(double[] hsl) {
        double h = hsl[0], s = hsl[1], l = hsl[2];
        double r, g, b;
        if (s == 0) {
            r = g = b = l;
        } else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hue(p, q, h + 1 / 3.0);
            g = hue(p, q, h);
            b = hue(p, q, h - 1 / 3.0);
        }
        return new double[]{r * 255, g * 255, b * 255};
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6.0) return p + (q - p) * 6 * t;
        if (t < 1 / 2.0) return q;
        if (t < 2 / 3.0) return p + (q - p) * (2 / 3.0 - t) * 6;
        return p;
    }

    private String background(Ctx ctx) {
        Part[] order = {ctx.slide, ctx.layout, ctx.master};
        for (Part p : order) {
            if (p == null) continue;
            Element bg = kid(kid(p.root, "cSld"), "bg");
            if (bg == null) continue;
            Element bgPr = kid(bg, "bgPr");
            if (bgPr != null) {
                String f = fillCss(bgPr, ctx, p);
                if (f != null && !f.contains("transparent")) return f;
            }
            Element ref = kid(bg, "bgRef");
            if (ref != null) {
                String c = colorOf(ref, ctx);
                if (c != null) return "background:" + c + ";";
            }
        }
        return "background:#fff;";
    }

    // --------------------------------------------------------------- texto

    /** Propiedades de párrafo y texto que se heredan por niveles. */
    private static final class TP {
        Integer sz, marL, indent, spcBef, spcBefPct, lnPct, buSzPct;
        Boolean b, i, u, strike;
        String color, font, algn, bullet, autoNum, buColor;
        int autoStart = 1;
        Integer baseline;

        TP over(TP o) {
            if (o == null) return this;
            TP r = new TP();
            r.sz = o.sz != null ? o.sz : sz;
            r.marL = o.marL != null ? o.marL : marL;
            r.indent = o.indent != null ? o.indent : indent;
            r.spcBef = o.spcBef != null ? o.spcBef : spcBef;
            r.spcBefPct = o.spcBefPct != null ? o.spcBefPct : spcBefPct;
            r.lnPct = o.lnPct != null ? o.lnPct : lnPct;
            r.buSzPct = o.buSzPct != null ? o.buSzPct : buSzPct;
            r.b = o.b != null ? o.b : b;
            r.i = o.i != null ? o.i : i;
            r.u = o.u != null ? o.u : u;
            r.strike = o.strike != null ? o.strike : strike;
            r.color = o.color != null ? o.color : color;
            r.font = o.font != null ? o.font : font;
            r.algn = o.algn != null ? o.algn : algn;
            r.buColor = o.buColor != null ? o.buColor : buColor;
            r.baseline = o.baseline != null ? o.baseline : baseline;
            if (o.bullet != null || o.autoNum != null) {
                r.bullet = o.bullet;
                r.autoNum = o.autoNum;
                r.autoStart = o.autoStart;
            } else {
                r.bullet = bullet;
                r.autoNum = autoNum;
                r.autoStart = autoStart;
            }
            return r;
        }
    }

    private TP parseP(Element pPr, Ctx ctx) {
        TP t = new TP();
        if (pPr == null) return t;
        t.algn = attr(pPr, "algn");
        String v = attr(pPr, "marL");
        if (v != null) t.marL = parseInt(v, 0);
        v = attr(pPr, "indent");
        if (v != null) t.indent = parseInt(v, 0);
        for (Element c : kids(pPr)) {
            switch (name(c)) {
                case "defRPr": {
                    TP r = parseR(c, ctx);
                    t = t.over(r);
                    break;
                }
                case "buNone": t.bullet = ""; break;
                case "buChar": t.bullet = attr(c, "char"); break;
                case "buAutoNum":
                    t.autoNum = attr(c, "type");
                    t.autoStart = parseInt(attr(c, "startAt"), 1);
                    break;
                case "buClr": t.buColor = colorOf(c, ctx); break;
                case "buSzPct": t.buSzPct = parseInt(val(c), 100000); break;
                case "spcBef": {
                    Element pts = kid(c, "spcPts"), pct = kid(c, "spcPct");
                    if (pts != null) t.spcBef = parseInt(val(pts), 0);
                    if (pct != null) t.spcBefPct = parseInt(val(pct), 0);
                    break;
                }
                case "lnSpc": {
                    Element pct = kid(c, "spcPct");
                    if (pct != null) t.lnPct = parseInt(val(pct), 100000);
                    break;
                }
                default: break;
            }
        }
        return t;
    }

    private TP parseR(Element rPr, Ctx ctx) {
        TP t = new TP();
        if (rPr == null) return t;
        String v = attr(rPr, "sz");
        if (v != null) t.sz = parseInt(v, 1800);
        v = attr(rPr, "b");
        if (v != null) t.b = v.equals("1") || v.equals("true");
        v = attr(rPr, "i");
        if (v != null) t.i = v.equals("1") || v.equals("true");
        v = attr(rPr, "u");
        if (v != null) t.u = !v.equals("none");
        v = attr(rPr, "strike");
        if (v != null) t.strike = !v.equals("noStrike");
        v = attr(rPr, "baseline");
        if (v != null) t.baseline = parseInt(v, 0);
        for (Element c : kids(rPr)) {
            switch (name(c)) {
                case "solidFill": t.color = colorOf(c, ctx); break;
                case "latin": t.font = attr(c, "typeface"); break;
                default: break;
            }
        }
        return t;
    }

    private TP levelProps(List<Element> lst, int lvl, Ctx ctx) {
        TP t = new TP();
        for (int k = lst.size() - 1; k >= 0; k--) {
            Element s = lst.get(k);
            if (s == null) continue;
            Element lp = kid(s, "lvl" + (lvl + 1) + "pPr");
            if (lp != null) t = t.over(parseP(lp, ctx));
        }
        return t;
    }

    private void textBox(Element txBody, List<Element> lst, List<Element> bodies, String fontColor, String defFont,
                         Ctx ctx, Part part, StringBuilder sb) {
        String anchor = null, wrap = null;
        Integer lIns = null, tIns = null, rIns = null, bIns = null;
        double fontScale = 1;
        for (Element bp : bodies) {
            if (bp == null) continue;
            if (anchor == null) anchor = attr(bp, "anchor");
            if (wrap == null) wrap = attr(bp, "wrap");
            if (lIns == null && attr(bp, "lIns") != null) lIns = parseInt(attr(bp, "lIns"), 91440);
            if (tIns == null && attr(bp, "tIns") != null) tIns = parseInt(attr(bp, "tIns"), 45720);
            if (rIns == null && attr(bp, "rIns") != null) rIns = parseInt(attr(bp, "rIns"), 91440);
            if (bIns == null && attr(bp, "bIns") != null) bIns = parseInt(attr(bp, "bIns"), 45720);
        }
        Element own = bodies.get(0);
        Element fit = kid(own, "normAutofit");
        if (fit != null && attr(fit, "fontScale") != null) fontScale = parseInt(attr(fit, "fontScale"), 100000) / 100000.0;

        String jc = "flex-start";
        if ("ctr".equals(anchor)) jc = "center";
        else if ("b".equals(anchor)) jc = "flex-end";
        sb.append("<div class=\"txt\" style=\"justify-content:").append(jc).append(";padding:")
                .append(r1((tIns != null ? tIns : 45720) / EMU_PX)).append("px ")
                .append(r1((rIns != null ? rIns : 91440) / EMU_PX)).append("px ")
                .append(r1((bIns != null ? bIns : 45720) / EMU_PX)).append("px ")
                .append(r1((lIns != null ? lIns : 91440) / EMU_PX)).append("px;");
        if ("none".equals(wrap)) sb.append("white-space:nowrap;");
        sb.append("\">");
        paragraphs(txBody, lst, fontColor, defFont, fontScale, ctx, part, sb);
        sb.append("</div>");
    }

    private void paragraphs(Element txBody, List<Element> lst, String fontColor, String defFont, double fontScale,
                            Ctx ctx, Part part, StringBuilder sb) {
        int[] counters = new int[10];
        int lastLvl = -1;
        for (Element p : kids(txBody)) {
            if (!"p".equals(name(p))) continue;
            Element pPr = kid(p, "pPr");
            int lvl = Math.max(0, Math.min(8, parseInt(attr(pPr, "lvl"), 0)));
            TP base = levelProps(lst, lvl, ctx);
            TP direct = parseP(pPr, ctx);
            TP tp = base.over(direct);
            // El color del estilo de la forma (fontRef) gana sobre el heredado del patrón
            if (fontColor != null && direct.color == null && levelProps(lst.subList(0, 1), lvl, ctx).color == null) {
                tp.color = fontColor;
            }

            StringBuilder content = new StringBuilder();
            boolean any = false;
            for (Element r : kids(p)) {
                String n = name(r);
                if ("r".equals(n) || "fld".equals(n)) {
                    Element t = kid(r, "t");
                    String text = t != null ? t.getTextContent() : "";
                    if (text.isEmpty()) continue;
                    any = true;
                    TP rp = tp.over(parseR(kid(r, "rPr"), ctx));
                    String link = null;
                    Element hl = kid(kid(r, "rPr"), "hlinkClick");
                    if (hl != null) {
                        String[] rel = part.rels.get(Ooxml.rid(hl));
                        if (rel != null && "External".equals(rel[1])) link = rel[0];
                    }
                    String css = runCss(rp, defFont, fontScale, ctx);
                    if (link != null && (link.startsWith("http") || link.startsWith("mailto"))) {
                        content.append("<a href=\"").append(esc(link)).append("\">");
                    }
                    content.append("<span style=\"").append(css).append("\">").append(esc(text)).append("</span>");
                    if (link != null && (link.startsWith("http") || link.startsWith("mailto"))) content.append("</a>");
                } else if ("br".equals(n)) {
                    content.append("<br>");
                }
            }

            StringBuilder css = new StringBuilder();
            if (tp.algn != null) {
                switch (tp.algn) {
                    case "ctr": css.append("text-align:center;"); break;
                    case "r": css.append("text-align:right;"); break;
                    case "just":
                    case "dist": css.append("text-align:justify;"); break;
                    default: break;
                }
            }
            int marL = tp.marL != null ? tp.marL : 0;
            int indent = tp.indent != null ? tp.indent : 0;
            if (marL != 0) css.append("margin-left:").append(r1(marL / EMU_PX)).append("px;");
            if (indent != 0) css.append("text-indent:").append(r1(indent / EMU_PX)).append("px;");
            double size = (tp.sz != null ? tp.sz : 1800) / 100.0 * fontScale;
            if (tp.spcBef != null && tp.spcBef > 0) css.append("padding-top:").append(r1(tp.spcBef / 100.0 * fontScale)).append("pt;");
            else if (tp.spcBefPct != null && tp.spcBefPct > 0) css.append("padding-top:").append(r1(size * tp.spcBefPct / 100000.0)).append("pt;");
            if (tp.lnPct != null) css.append("line-height:").append(r1(120.0 * tp.lnPct / 100000.0)).append("%;");
            css.append("font-size:").append(r1(size)).append("pt;");

            sb.append("<div class=\"pp\" style=\"").append(css).append("\">");
            if (!any) {
                Element end = kid(p, "endParaRPr");
                TP ep = tp.over(parseR(end, ctx));
                double es = (ep.sz != null ? ep.sz : 1800) / 100.0 * fontScale;
                sb.append("<span style=\"font-size:").append(r1(es)).append("pt\">​</span>");
                lastLvl = -1;
            } else {
                // Viñeta o numeración
                String marker = null;
                if (tp.autoNum != null) {
                    if (lvl != lastLvl) {
                        for (int k = lvl; k < counters.length; k++) counters[k] = 0;
                    }
                    if (counters[lvl] == 0) counters[lvl] = tp.autoStart;
                    else counters[lvl]++;
                    marker = autoNum(counters[lvl], tp.autoNum);
                } else if (tp.bullet != null && !tp.bullet.isEmpty()) {
                    marker = bulletChar(tp.bullet);
                }
                lastLvl = lvl;
                if (marker != null) {
                    sb.append("<span class=\"bu\" style=\"");
                    if (indent < 0) sb.append("width:").append(r1(-indent / EMU_PX)).append("px;");
                    String bc = tp.buColor != null ? tp.buColor : tp.color;
                    if (bc != null) sb.append("color:").append(bc).append(';');
                    if (tp.buSzPct != null) sb.append("font-size:").append(r1(tp.buSzPct / 1000.0)).append("%;");
                    sb.append("\">").append(esc(marker)).append("</span>");
                }
                sb.append(content);
            }
            sb.append("</div>");
        }
    }

    private String runCss(TP t, String defFont, double scale, Ctx ctx) {
        StringBuilder c = new StringBuilder();
        double size = (t.sz != null ? t.sz : 1800) / 100.0 * scale;
        c.append("font-size:").append(r1(size)).append("pt;");
        if (Boolean.TRUE.equals(t.b)) c.append("font-weight:bold;");
        if (Boolean.TRUE.equals(t.i)) c.append("font-style:italic;");
        boolean u = Boolean.TRUE.equals(t.u), s = Boolean.TRUE.equals(t.strike);
        if (u || s) c.append("text-decoration:").append(u ? "underline " : "").append(s ? "line-through" : "").append(';');
        String color = t.color != null ? t.color : "#" + scheme("tx1", ctx);
        c.append("color:").append(color).append(';');
        c.append("font-family:").append(fontFamily(t.font != null ? t.font : defFont, ctx)).append(';');
        if (t.baseline != null && t.baseline != 0) {
            c.append("vertical-align:").append(t.baseline > 0 ? "super" : "sub").append(";font-size:").append(r1(size * 0.7)).append("pt;");
        }
        return c.toString();
    }

    private static String fontFamily(String face, Ctx ctx) {
        String f = face;
        if (f != null && ctx.theme != null) {
            if (f.startsWith("+mj")) f = ctx.theme.major;
            else if (f.startsWith("+mn")) f = ctx.theme.minor;
        }
        if (f == null || f.startsWith("+")) return "sans-serif";
        String l = f.toLowerCase(Locale.ROOT);
        String generic = "sans-serif";
        if (l.contains("courier") || l.contains("consolas") || l.contains("mono")) generic = "monospace";
        else if (l.contains("times") || l.contains("cambria") || l.contains("georgia") || l.contains("garamond")
                || l.contains("palatino") || l.contains("book") || (l.contains("serif") && !l.contains("sans"))) {
            generic = "serif";
        }
        String safe = f.replace("'", "").replace("\"", "").replace(";", "");
        return "'" + safe + "'," + generic;
    }

    private static String bulletChar(String b) {
        char c = b.charAt(0);
        if (c >= 0xE000 && c <= 0xF8FF) {
            switch (c & 0xFF) {
                case 0xA7: return "▪";
                case 0xFC: return "✓";
                case 0xD8: return "➢";
                case 0x76: return "❖";
                case 0x6E: return "■";
                default: return "•";
            }
        }
        if (c == 'o') return "◦";
        if (c == 'q' || c == 'n' || c == 'l' || c == 'v' || c == 'Ø' || c == '§' || c == 'ü') return "•";
        return b;
    }

    private static String autoNum(int v, String type) {
        String num;
        if (type.startsWith("alphaLc")) num = String.valueOf((char) ('a' + (v - 1) % 26));
        else if (type.startsWith("alphaUc")) num = String.valueOf((char) ('A' + (v - 1) % 26));
        else if (type.startsWith("romanLc")) num = roman(v).toLowerCase(Locale.ROOT);
        else if (type.startsWith("romanUc")) num = roman(v);
        else num = String.valueOf(v);
        if (type.endsWith("ParenBoth")) return "(" + num + ")";
        if (type.endsWith("ParenR")) return num + ")";
        if (type.endsWith("Period")) return num + ".";
        if (type.endsWith("Minus")) return num + " -";
        return num;
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

    // ------------------------------------------------------------ imágenes

    private String imageSrc(Part part, String rid) {
        if (rid == null || part == null) return null;
        String[] r = part.rels.get(rid);
        if (r == null || "External".equals(r[1])) return null;
        String path = r[0];
        String local = imageCache.get(path);
        if (local != null) return "media/" + local;
        String ext = Ooxml.extOf(path);
        if (!WEB_IMAGES.contains(ext) || imageCount >= MAX_IMAGES) return null;
        byte[] data = Ooxml.read(zip, path);
        if (data == null) return null;
        mediaDir.mkdirs();
        local = "s" + (++imageCount) + "." + ext;
        try (FileOutputStream fo = new FileOutputStream(new File(mediaDir, local))) {
            fo.write(data);
        } catch (IOException e) {
            return null;
        }
        imageCache.put(path, local);
        return "media/" + local;
    }

    private void picture(Element pic, Ctx ctx, Part part, Xform xf, boolean template, StringBuilder sb) {
        Element ph = ph(pic);
        if (template && ph != null) return;
        Element spPr = kid(pic, "spPr");
        Element xfrm = kid(spPr, "xfrm");
        if (xfrm == null && ph != null) {
            Element lp = findPh(ctx.layout, attr(ph, "type"), attr(ph, "idx"), true);
            xfrm = xfrmOf(lp);
            if (xfrm == null) xfrm = xfrmOf(findPh(ctx.master, attr(ph, "type"), null, false));
        }
        Box b = box(xfrm, xf);
        if (b == null) return;
        Element blipFill = kid(pic, "blipFill");
        String src = imageSrc(part, attr(kid(blipFill, "blip"), "embed"));
        StringBuilder css = new StringBuilder(pos(b, true));
        String border = lineCss(spPr, null, ctx);
        if (border != null) css.append(border);
        Element geom = kid(spPr, "prstGeom");
        css.append(geometry(geom != null ? attr(geom, "prst") : null, b));
        css.append("overflow:hidden;");
        sb.append("<div class=\"shp\" style=\"").append(css).append("\">");
        if (src == null) {
            sb.append("<div class=\"ph\">[imagen]</div>");
        } else {
            double l = 0, t = 0, r = 0, bt = 0;
            Element crop = kid(blipFill, "srcRect");
            if (crop != null) {
                l = parseInt(attr(crop, "l"), 0) / 100000.0;
                t = parseInt(attr(crop, "t"), 0) / 100000.0;
                r = parseInt(attr(crop, "r"), 0) / 100000.0;
                bt = parseInt(attr(crop, "b"), 0) / 100000.0;
            }
            double wp = 100 / Math.max(0.01, 1 - l - r), hp = 100 / Math.max(0.01, 1 - t - bt);
            sb.append("<img src=\"").append(src).append("\" alt=\"\" style=\"left:").append(r1(-l * wp))
                    .append("%;top:").append(r1(-t * hp)).append("%;width:").append(r1(wp))
                    .append("%;height:").append(r1(hp)).append("%\">");
        }
        sb.append("</div>");
    }

    // -------------------------------------------------- tablas, gráficos...

    private void frame(Element gf, Ctx ctx, Part part, Xform xf, StringBuilder sb) throws RuntimeException {
        Element xfrm = kid(gf, "xfrm");
        Box b = box(xfrm, xf);
        if (b == null) return;
        Element gd = Ooxml.firstDescendant(gf, "graphicData");
        String uri = gd != null ? attr(gd, "uri") : "";
        if (uri == null) uri = "";
        if (uri.endsWith("/table")) {
            table(kid(gd, "tbl"), b, ctx, part, sb);
        } else if (uri.contains("/diagram")) {
            if (!smartArt(gd, b, ctx, part, sb)) placeholder(b, "[diagrama]", sb);
        } else if (uri.contains("/chart")) {
            Element ch = gd != null ? kid(gd, "chart") : null;
            String[] rel = ch != null ? part.rels.get(Ooxml.rid(ch)) : null;
            final Ctx c = ctx;
            String svg = rel == null ? null : ChartSvg.render(zip, rel[0], b.w, b.h, new ChartSvg.Colors() {
                @Override
                public String of(Element holder) {
                    return colorOf(holder, c);
                }

                @Override
                public String accent(int n) {
                    String s = scheme("accent" + n, c);
                    return s != null ? "#" + s : null;
                }
            });
            if (svg != null) {
                sb.append("<div class=\"shp\" style=\"").append(pos(b, false)).append("\">").append(svg).append("</div>");
            } else {
                placeholder(b, "[gráfico]", sb);
            }
        } else {
            Element pic = gd != null ? Ooxml.firstDescendant(gd, "pic") : null;
            if (pic != null) {
                String src = imageSrc(part, attr(Ooxml.firstDescendant(pic, "blip"), "embed"));
                if (src != null) {
                    sb.append("<div class=\"shp\" style=\"").append(pos(b, false)).append("\"><img src=\"")
                            .append(src).append("\" alt=\"\" style=\"left:0;top:0;width:100%;height:100%\"></div>");
                    return;
                }
            }
            placeholder(b, "[objeto]", sb);
        }
    }

    private static void placeholder(Box b, String label, StringBuilder sb) {
        sb.append("<div class=\"shp\" style=\"").append(pos(b, false))
                .append("\"><div class=\"ph\">").append(label).append("</div></div>");
    }

    private boolean smartArt(Element gd, Box b, Ctx ctx, Part part, StringBuilder sb) {
        try {
            Element relIds = kid(gd, "relIds");
            String[] dm = relIds != null ? part.rels.get(attr(relIds, "dm")) : null;
            if (dm == null) return false;
            Document data = Ooxml.dom(zip, dm[0]);
            if (data == null) return false;
            Element ext = Ooxml.firstDescendant(data.getDocumentElement(), "dataModelExt");
            String[] drawing = ext != null ? part.rels.get(attr(ext, "relId")) : null;
            if (drawing == null) return false;
            Part dp = load(drawing[0], false);
            if (dp == null) return false;
            Element tree = kid(dp.root, "spTree");
            if (tree == null) return false;
            Xform xf = new Xform();
            xf.dx = b.x * EMU_PX;
            xf.dy = b.y * EMU_PX;
            tree(tree, ctx, dp, xf, false, sb);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void table(Element tbl, Box b, Ctx ctx, Part part, StringBuilder sb) {
        if (tbl == null) return;
        Element tblPr = kid(tbl, "tblPr");
        boolean styled = tblPr != null && kid(tblPr, "tableStyleId") != null;
        boolean firstRow = "1".equals(attr(tblPr, "firstRow"));
        boolean bandRow = "1".equals(attr(tblPr, "bandRow"));
        String accent = "#" + (scheme("accent1", ctx) != null ? scheme("accent1", ctx) : "4472C4");

        sb.append("<div class=\"shp\" style=\"").append(pos(b, false)).append("\"><table class=\"pt\"><colgroup>");
        for (Element gc : kids(kid(tbl, "tblGrid"))) {
            sb.append("<col style=\"width:").append(r1(parseLong(attr(gc, "w"), 0) / EMU_PX)).append("px\">");
        }
        sb.append("</colgroup>");
        List<Element> lst = new ArrayList<>();
        lst.add(defaultTextStyle);
        int ri = 0;
        for (Element tr : kids(tbl)) {
            if (!"tr".equals(name(tr))) continue;
            sb.append("<tr style=\"height:").append(r1(parseLong(attr(tr, "h"), 0) / EMU_PX)).append("px\">");
            boolean header = styled && firstRow && ri == 0;
            for (Element tc : kids(tr)) {
                if (!"tc".equals(name(tc))) continue;
                if ("1".equals(attr(tc, "hMerge")) || "1".equals(attr(tc, "vMerge"))) continue;
                Element tcPr = kid(tc, "tcPr");
                StringBuilder css = new StringBuilder();
                String fill = fillCss(tcPr, ctx, part);
                String textColor = null;
                if (fill == null && styled) {
                    if (header) {
                        fill = "background:" + accent + ";";
                        textColor = "#ffffff";
                    } else if (bandRow) {
                        int dataRow = firstRow ? ri - 1 : ri;
                        fill = "background:" + mix(accent, dataRow % 2 == 0 ? 0.75 : 0.88) + ";";
                    }
                }
                if (fill != null) css.append(fill);
                if (styled) css.append("border:1px solid #fff;");
                else css.append("border:1px solid #888;");
                String anchor = attr(tcPr, "anchor");
                css.append("vertical-align:").append("ctr".equals(anchor) ? "middle" : "b".equals(anchor) ? "bottom" : "top").append(';');
                css.append("padding:").append(r1(parseLong(attr(tcPr, "marT"), 45720) / EMU_PX)).append("px ")
                        .append(r1(parseLong(attr(tcPr, "marR"), 91440) / EMU_PX)).append("px ")
                        .append(r1(parseLong(attr(tcPr, "marB"), 45720) / EMU_PX)).append("px ")
                        .append(r1(parseLong(attr(tcPr, "marL"), 91440) / EMU_PX)).append("px;");
                sb.append("<td");
                int gs = parseInt(attr(tc, "gridSpan"), 1), rs = parseInt(attr(tc, "rowSpan"), 1);
                if (gs > 1) sb.append(" colspan=\"").append(gs).append('"');
                if (rs > 1) sb.append(" rowspan=\"").append(rs).append('"');
                sb.append(" style=\"").append(css).append("\">");
                Element txBody = kid(tc, "txBody");
                if (txBody != null) {
                    List<Element> chain = new ArrayList<>();
                    chain.add(kid(txBody, "lstStyle"));
                    chain.addAll(lst);
                    StringBuilder cell = new StringBuilder();
                    paragraphs(txBody, chain, textColor, "+mn-lt", 1, ctx, part, cell);
                    String html = cell.toString();
                    if (header) html = html.replace("<span style=\"", "<span style=\"font-weight:bold;");
                    sb.append(html);
                }
                sb.append("</td>");
            }
            sb.append("</tr>");
            ri++;
        }
        sb.append("</table></div>");
    }

    private static String mix(String hex, double white) {
        int r = Integer.parseInt(hex.substring(1, 3), 16), g = Integer.parseInt(hex.substring(3, 5), 16),
                b = Integer.parseInt(hex.substring(5, 7), 16);
        r = (int) Math.round(r + (255 - r) * white);
        g = (int) Math.round(g + (255 - g) * white);
        b = (int) Math.round(b + (255 - b) * white);
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    // --------------------------------------------------------------- notas

    private String notes(Part slide) throws Exception {
        String path = Ooxml.partByType(slide.rels, "/notesSlide", null);
        Part np = load(path, false);
        if (np == null) return "";
        List<Element> shapes = new ArrayList<>();
        collectShapes(spTree(np), shapes);
        StringBuilder out = new StringBuilder();
        for (Element s : shapes) {
            Element ph = ph(s);
            if (ph == null || !"body".equals(attr(ph, "type"))) continue;
            for (Element p : kids(kid(s, "txBody"))) {
                if (!"p".equals(name(p))) continue;
                StringBuilder line = new StringBuilder();
                for (Element r : kids(p)) {
                    String n = name(r);
                    if ("r".equals(n) || "fld".equals(n)) {
                        Element t = kid(r, "t");
                        if (t != null) line.append(t.getTextContent());
                    } else if ("br".equals(n)) {
                        line.append('\n');
                    }
                }
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        }
        String text = out.toString().trim();
        return text.isEmpty() ? "" : esc(text);
    }
}
