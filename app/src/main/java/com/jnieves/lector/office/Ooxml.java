package com.jnieves.lector.office;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Utilidades para leer archivos de Office modernos (.docx, .xlsx, .pptx),
 * que son archivos ZIP con XML adentro.
 */
final class Ooxml {
    private Ooxml() {
    }

    /** Lee un archivo interno del ZIP. Devuelve null si no existe o falla. */
    static byte[] read(ZipFile zip, String name) {
        if (name == null) return null;
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            try {
                e = zip.getEntry(URLDecoder.decode(name, "UTF-8"));
            } catch (Exception ignored) {
                e = null;
            }
        }
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    static boolean exists(ZipFile zip, String name) {
        return name != null && zip.getEntry(name) != null;
    }

    /** Carga un XML interno como árbol DOM (null si no existe). */
    static Document dom(ZipFile zip, String name) throws Exception {
        if (name == null) return null;
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            f.setExpandEntityReferences(false);
        } catch (Throwable ignored) {
        }
        trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySet(f, "http://xml.org/sax/features/external-general-entities", false);
        trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        try (InputStream in = zip.getInputStream(e)) {
            return b.parse(in);
        }
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Lee un archivo de relaciones (.rels). Devuelve id -> {destino, modo, tipo}.
     * Los destinos internos se devuelven como ruta completa dentro del ZIP.
     */
    static Map<String, String[]> rels(ZipFile zip, String relsPath, String baseDir) {
        Map<String, String[]> m = new HashMap<>();
        try {
            Document d = dom(zip, relsPath);
            if (d == null) return m;
            for (Element r : kids(d.getDocumentElement())) {
                if (!"Relationship".equals(name(r))) continue;
                String id = r.getAttribute("Id");
                String target = r.getAttribute("Target");
                String mode = r.getAttribute("TargetMode");
                String type = r.getAttribute("Type");
                String full = "External".equals(mode) ? target : resolve(baseDir, target);
                m.put(id, new String[]{full, mode, type});
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    /** Ruta del documento principal según _rels/.rels. */
    static String mainPart(ZipFile zip, String fallback) {
        Map<String, String[]> r = rels(zip, "_rels/.rels", "");
        for (String[] v : r.values()) {
            if (v[2] != null && v[2].endsWith("/officeDocument") && exists(zip, v[0])) return v[0];
        }
        return fallback;
    }

    /** Busca en las relaciones el primer destino cuyo tipo termina en el sufijo dado. */
    static String partByType(Map<String, String[]> rels, String typeSuffix, String fallback) {
        for (String[] v : rels.values()) {
            if (v[2] != null && v[2].endsWith(typeSuffix)) return v[0];
        }
        return fallback;
    }

    static String relsPathFor(String part) {
        int i = part.lastIndexOf('/');
        String dir = i >= 0 ? part.substring(0, i + 1) : "";
        String file = i >= 0 ? part.substring(i + 1) : part;
        return dir + "_rels/" + file + ".rels";
    }

    static String dirOf(String part) {
        int i = part.lastIndexOf('/');
        return i >= 0 ? part.substring(0, i + 1) : "";
    }

    static String resolve(String baseDir, String target) {
        if (target == null) return null;
        if (target.startsWith("/")) return target.substring(1);
        String p = baseDir + target;
        Deque<String> parts = new ArrayDeque<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ DOM

    static String name(Node n) {
        String ln = n.getLocalName();
        return ln != null ? ln : n.getNodeName();
    }

    static List<Element> kids(Element e) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }

    static Element kid(Element e, String local) {
        if (e == null) return null;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(name(n))) return (Element) n;
        }
        return null;
    }

    /** Atributo por nombre local, sin importar el prefijo (w:val, r:id...). */
    static String attr(Element e, String local) {
        if (e == null) return null;
        NamedNodeMap a = e.getAttributes();
        for (int i = 0; i < a.getLength(); i++) {
            Node n = a.item(i);
            if ("xmlns".equals(n.getPrefix()) || "xmlns".equals(n.getNodeName())) continue;
            if (local.equals(name(n))) return n.getNodeValue();
        }
        return null;
    }

    /** Atributo r:id (de relaciones), distinto de un "id" normal en el mismo elemento. */
    static String rid(Element e) {
        if (e == null) return null;
        NamedNodeMap a = e.getAttributes();
        String fallback = null;
        for (int i = 0; i < a.getLength(); i++) {
            Node n = a.item(i);
            if (!"id".equals(name(n))) continue;
            String ns = n.getNamespaceURI();
            if (ns != null && ns.contains("relationships")) return n.getNodeValue();
            if ("r".equals(n.getPrefix())) return n.getNodeValue();
            if (ns == null && n.getPrefix() == null) continue;
            fallback = n.getNodeValue();
        }
        return fallback;
    }

    static String val(Element e) {
        return attr(e, "val");
    }

    /** Descendientes con ese nombre local, sin entrar en los elementos llamados stopAt. */
    static void descendants(Element e, String local, String stopAt, List<Element> out) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            String nm = name(n);
            if (local.equals(nm)) out.add((Element) n);
            if (stopAt != null && stopAt.equals(nm)) continue;
            descendants((Element) n, local, stopAt, out);
        }
    }

    static Element firstDescendant(Element e, String local) {
        List<Element> l = new ArrayList<>();
        descendants(e, local, null, l);
        return l.isEmpty() ? null : l.get(0);
    }

    static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException e2) {
                return def;
            }
        }
    }

    static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean isHex6(String s) {
        if (s == null || s.length() != 6) return false;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Muchas imágenes EMF (formato de Windows) solo envuelven una foto normal.
     * Busca el mapa de bits más grande dentro del EMF y lo devuelve como
     * {extensión, bytes} listo para mostrar, o null si el EMF es puro dibujo vectorial.
     */
    static Object[] emfBitmap(byte[] d) {
        if (d == null || d.length < 88 || u32(d, 0) != 1 || u32(d, 40) != 0x464D4520) return null;
        int off = 0, bestSize = 0, bmiOff = 0, bmiLen = 0, bitsOff = 0, bitsLen = 0;
        while (off + 8 <= d.length) {
            long type = u32(d, off);
            long size = u32(d, off + 4);
            if (size < 8 || off + size > d.length) break;
            int ob = -1;
            if (type == 80 || type == 81) ob = 48;        // SETDIBITSTODEVICE / STRETCHDIBITS
            else if (type == 76 || type == 77) ob = 84;   // BITBLT / STRETCHBLT
            if (ob > 0 && size >= ob + 16) {
                long bo = u32(d, off + ob), bl = u32(d, off + ob + 4), xo = u32(d, off + ob + 8), xl = u32(d, off + ob + 12);
                if (bl >= 40 && xl > 0 && bo + bl <= size && xo + xl <= size && xl > bestSize) {
                    bestSize = (int) xl;
                    bmiOff = off + (int) bo;
                    bmiLen = (int) bl;
                    bitsOff = off + (int) xo;
                    bitsLen = (int) xl;
                }
            }
            if (type == 14) break;
            off += (int) size;
        }
        if (bestSize == 0) return null;
        long compression = u32(d, bmiOff + 16);
        if (compression == 4 || compression == 5) {
            // La imagen interna ya es JPG (4) o PNG (5)
            byte[] img = new byte[bitsLen];
            System.arraycopy(d, bitsOff, img, 0, bitsLen);
            return new Object[]{compression == 4 ? "jpg" : "png", img};
        }
        // Arma un archivo BMP: cabecera de 14 bytes + información + píxeles
        int total = 14 + bmiLen + bitsLen;
        byte[] bmp = new byte[total];
        bmp[0] = 'B';
        bmp[1] = 'M';
        put32(bmp, 2, total);
        put32(bmp, 10, 14 + bmiLen);
        System.arraycopy(d, bmiOff, bmp, 14, bmiLen);
        System.arraycopy(d, bitsOff, bmp, 14 + bmiLen, bitsLen);
        return new Object[]{"bmp", bmp};
    }

    private static long u32(byte[] d, int i) {
        if (i < 0 || i + 4 > d.length) return 0;
        return (d[i] & 0xFFL) | ((d[i + 1] & 0xFFL) << 8) | ((d[i + 2] & 0xFFL) << 16) | ((d[i + 3] & 0xFFL) << 24);
    }

    private static void put32(byte[] d, int i, int v) {
        d[i] = (byte) v;
        d[i + 1] = (byte) (v >> 8);
        d[i + 2] = (byte) (v >> 16);
        d[i + 3] = (byte) (v >> 24);
    }

    static String extOf(String path) {
        int i = path.lastIndexOf('.');
        return i >= 0 ? path.substring(i + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }
}
