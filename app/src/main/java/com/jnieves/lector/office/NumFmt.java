package com.jnieves.lector.office;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Aplica formatos de número y fecha de Excel ("#,##0.00", "dd/mm/yyyy", "0%"...). */
final class NumFmt {
    private NumFmt() {
    }

    static final Map<Integer, String> BUILTIN = new HashMap<>();

    static {
        BUILTIN.put(0, "General");
        BUILTIN.put(1, "0");
        BUILTIN.put(2, "0.00");
        BUILTIN.put(3, "#,##0");
        BUILTIN.put(4, "#,##0.00");
        BUILTIN.put(5, "\"$\"#,##0_);(\"$\"#,##0)");
        BUILTIN.put(6, "\"$\"#,##0_);[Red](\"$\"#,##0)");
        BUILTIN.put(7, "\"$\"#,##0.00_);(\"$\"#,##0.00)");
        BUILTIN.put(8, "\"$\"#,##0.00_);[Red](\"$\"#,##0.00)");
        BUILTIN.put(9, "0%");
        BUILTIN.put(10, "0.00%");
        BUILTIN.put(11, "0.00E+00");
        BUILTIN.put(12, "# ?/?");
        BUILTIN.put(13, "# ??/??");
        BUILTIN.put(14, "dd/mm/yyyy");
        BUILTIN.put(15, "d-mmm-yy");
        BUILTIN.put(16, "d-mmm");
        BUILTIN.put(17, "mmm-yy");
        BUILTIN.put(18, "h:mm AM/PM");
        BUILTIN.put(19, "h:mm:ss AM/PM");
        BUILTIN.put(20, "h:mm");
        BUILTIN.put(21, "h:mm:ss");
        BUILTIN.put(22, "dd/mm/yyyy h:mm");
        for (int i = 27; i <= 36; i++) BUILTIN.put(i, "dd/mm/yyyy");
        BUILTIN.put(37, "#,##0 ;(#,##0)");
        BUILTIN.put(38, "#,##0 ;[Red](#,##0)");
        BUILTIN.put(39, "#,##0.00;(#,##0.00)");
        BUILTIN.put(40, "#,##0.00;[Red](#,##0.00)");
        BUILTIN.put(45, "mm:ss");
        BUILTIN.put(46, "[h]:mm:ss");
        BUILTIN.put(47, "mm:ss.0");
        BUILTIN.put(48, "##0.0E+0");
        BUILTIN.put(49, "@");
        for (int i = 50; i <= 58; i++) BUILTIN.put(i, "dd/mm/yyyy");
    }

    private static final String[] MONTHS = {"enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    private static final String[] MONTHS_SHORT = {"ene", "feb", "mar", "abr", "may", "jun",
            "jul", "ago", "sept", "oct", "nov", "dic"};
    private static final String[] DAYS = {"lunes", "martes", "miércoles", "jueves", "viernes",
            "sábado", "domingo"};
    private static final String[] DAYS_SHORT = {"lun", "mar", "mié", "jue", "vie", "sáb", "dom"};

    /** Formato "General" de Excel: hasta 10 cifras significativas. */
    static String general(double v, DecimalFormatSymbols sym) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        double a = Math.abs(v);
        if (a >= 1e11 || a < 1e-9) {
            DecimalFormat df = new DecimalFormat("0.#####E0", sym);
            return df.format(v).replace("E", "E+").replace("E+-", "E-");
        }
        BigDecimal bd = new BigDecimal(v).round(new MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros();
        String s = bd.toPlainString();
        return s.replace('.', sym.getDecimalSeparator());
    }

    static String format(double v, String code, boolean date1904, DecimalFormatSymbols sym) {
        if (code == null || code.trim().isEmpty() || code.equalsIgnoreCase("General")) return general(v, sym);
        List<String> sections = split(code);
        String sec = sections.get(0);
        if (v < 0 && sections.size() > 1 && !sections.get(1).trim().isEmpty()) {
            sec = sections.get(1);
            v = -v;
        } else if (v == 0 && sections.size() > 2 && !sections.get(2).trim().isEmpty()) {
            sec = sections.get(2);
        }
        if (sec.equalsIgnoreCase("General")) return general(v, sym);
        try {
            if (isDate(sec)) return formatDate(v, sec, date1904);
            return formatNumber(v, sec, sym);
        } catch (RuntimeException e) {
            return general(v, sym);
        }
    }

    /** Divide el formato por ';' fuera de comillas y corchetes. */
    private static List<String> split(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        int br = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\\' && !q && i + 1 < code.length()) {
                cur.append(c).append(code.charAt(++i));
                continue;
            }
            if (c == '"') q = !q;
            if (!q && c == '[') br++;
            if (!q && c == ']') br = Math.max(0, br - 1);
            if (c == ';' && !q && br == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Quita texto entre comillas, escapes y corchetes (excepto [h], [m], [s]). */
    private static String strip(String sec) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            if (c == '"') {
                int j = sec.indexOf('"', i + 1);
                i = j < 0 ? sec.length() : j;
            } else if (c == '\\' || c == '_' || c == '*') {
                i++;
            } else if (c == '[') {
                int j = sec.indexOf(']', i + 1);
                String in = j < 0 ? "" : sec.substring(i + 1, j);
                if (in.matches("(?i)[hms]+")) sb.append(in);
                i = j < 0 ? sec.length() : j;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static boolean isDate(String sec) {
        String s = strip(sec).toLowerCase(Locale.ROOT);
        if (s.indexOf('#') >= 0 || s.indexOf('?') >= 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'y' || c == 'm' || c == 'd' || c == 'h' || c == 's') return true;
        }
        return false;
    }

    // ------------------------------------------------------------- fechas

    private static final class Tok {
        char type; // L literal, y m d h s, H M S (transcurrido), a (am/pm), f (fracción)
        int n;
        String text;
        boolean minute;

        Tok(char type, int n, String text) {
            this.type = type;
            this.n = n;
            this.text = text;
        }
    }

    static String formatDate(double v, String sec, boolean date1904) {
        if (v < 0) return "#####";
        List<Tok> toks = new ArrayList<>();
        boolean ampm = false;
        for (int i = 0; i < sec.length(); ) {
            char c = sec.charAt(i);
            char lc = Character.toLowerCase(c);
            if (c == '"') {
                int j = sec.indexOf('"', i + 1);
                if (j < 0) j = sec.length();
                toks.add(new Tok('L', 0, sec.substring(i + 1, j)));
                i = j + 1;
            } else if (c == '\\') {
                if (i + 1 < sec.length()) toks.add(new Tok('L', 0, String.valueOf(sec.charAt(i + 1))));
                i += 2;
            } else if (c == '_') {
                toks.add(new Tok('L', 0, " "));
                i += 2;
            } else if (c == '*') {
                i += 2;
            } else if (c == '[') {
                int j = sec.indexOf(']', i + 1);
                if (j < 0) j = sec.length();
                String in = sec.substring(i + 1, j).toLowerCase(Locale.ROOT);
                if (in.matches("h+")) toks.add(new Tok('H', in.length(), null));
                else if (in.matches("m+")) toks.add(new Tok('M', in.length(), null));
                else if (in.matches("s+")) toks.add(new Tok('S', in.length(), null));
                i = j + 1;
            } else if (sec.regionMatches(true, i, "AM/PM", 0, 5)) {
                toks.add(new Tok('a', 5, null));
                ampm = true;
                i += 5;
            } else if (sec.regionMatches(true, i, "A/P", 0, 3)) {
                toks.add(new Tok('a', 3, null));
                ampm = true;
                i += 3;
            } else if (lc == 'y' || lc == 'm' || lc == 'd' || lc == 'h' || lc == 's' || lc == 'e') {
                int j = i;
                while (j < sec.length() && Character.toLowerCase(sec.charAt(j)) == lc) j++;
                toks.add(lc == 'e' ? new Tok('y', 4, null) : new Tok(lc, j - i, null));
                i = j;
            } else if (c == '.' && i + 1 < sec.length() && sec.charAt(i + 1) == '0' && !toks.isEmpty()
                    && (lastNonLit(toks) == 's' || lastNonLit(toks) == 'S')) {
                int j = i + 1;
                while (j < sec.length() && sec.charAt(j) == '0') j++;
                toks.add(new Tok('f', j - i - 1, null));
                i = j;
            } else {
                toks.add(new Tok('L', 0, String.valueOf(c)));
                i++;
            }
        }
        // "m" es minutos si va después de horas o antes de segundos
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.type != 'm' || t.n > 2) continue;
            char prev = 0, next = 0;
            for (int b = k - 1; b >= 0; b--) {
                if (toks.get(b).type != 'L') {
                    prev = toks.get(b).type;
                    break;
                }
            }
            for (int f = k + 1; f < toks.size(); f++) {
                if (toks.get(f).type != 'L') {
                    next = toks.get(f).type;
                    break;
                }
            }
            if (prev == 'h' || prev == 'H' || next == 's' || next == 'S') t.minute = true;
        }

        boolean hasFrac = false;
        for (Tok t : toks) if (t.type == 'f') hasFrac = true;

        long days = (long) Math.floor(v);
        double frac = v - days;
        LocalDate date;
        if (date1904) {
            date = LocalDate.of(1904, 1, 1).plusDays(days);
        } else if (days < 60) {
            date = LocalDate.of(1899, 12, 31).plusDays(days);
        } else {
            date = LocalDate.of(1899, 12, 30).plusDays(days);
        }
        double secsExact = frac * 86400.0;
        long secs = hasFrac ? (long) Math.floor(secsExact + 1e-7) : Math.round(secsExact);
        if (secs >= 86400) {
            date = date.plusDays(1);
            secs -= 86400;
        }
        int hh = (int) (secs / 3600), mi = (int) ((secs % 3600) / 60), ss = (int) (secs % 60);

        StringBuilder out = new StringBuilder();
        for (Tok t : toks) {
            switch (t.type) {
                case 'L': out.append(t.text); break;
                case 'y':
                    if (t.n <= 2) out.append(pad(date.getYear() % 100, 2));
                    else out.append(date.getYear());
                    break;
                case 'm':
                    if (t.minute) {
                        out.append(t.n >= 2 ? pad(mi, 2) : String.valueOf(mi));
                    } else {
                        int m = date.getMonthValue();
                        if (t.n == 1) out.append(m);
                        else if (t.n == 2) out.append(pad(m, 2));
                        else if (t.n == 3) out.append(MONTHS_SHORT[m - 1]);
                        else if (t.n == 5) out.append(MONTHS[m - 1].charAt(0));
                        else out.append(MONTHS[m - 1]);
                    }
                    break;
                case 'd':
                    int dw = date.getDayOfWeek().getValue() - 1;
                    if (t.n == 1) out.append(date.getDayOfMonth());
                    else if (t.n == 2) out.append(pad(date.getDayOfMonth(), 2));
                    else if (t.n == 3) out.append(DAYS_SHORT[dw]);
                    else out.append(DAYS[dw]);
                    break;
                case 'h': {
                    int h = hh;
                    if (ampm) {
                        h = hh % 12;
                        if (h == 0) h = 12;
                    }
                    out.append(t.n >= 2 ? pad(h, 2) : String.valueOf(h));
                    break;
                }
                case 's': out.append(t.n >= 2 ? pad(ss, 2) : String.valueOf(ss)); break;
                case 'H': out.append((long) Math.floor(v * 24 + 1e-9)); break;
                case 'M': out.append((long) Math.floor(v * 1440 + 1e-9)); break;
                case 'S': out.append((long) Math.floor(v * 86400 + 1e-9)); break;
                case 'a':
                    if (t.n == 5) out.append(hh < 12 ? "a. m." : "p. m.");
                    else out.append(hh < 12 ? "a" : "p");
                    break;
                case 'f': {
                    double f = secsExact - Math.floor(secsExact + 1e-7);
                    long digits = Math.round(Math.max(0, f) * Math.pow(10, t.n));
                    if (digits >= Math.pow(10, t.n)) digits = (long) Math.pow(10, t.n) - 1;
                    out.append('.').append(pad((int) digits, t.n));
                    break;
                }
                default: break;
            }
        }
        return out.toString();
    }

    private static char lastNonLit(List<Tok> toks) {
        for (int k = toks.size() - 1; k >= 0; k--) if (toks.get(k).type != 'L') return toks.get(k).type;
        return 0;
    }

    private static String pad(int v, int n) {
        StringBuilder s = new StringBuilder(String.valueOf(v));
        while (s.length() < n) s.insert(0, '0');
        return s.toString();
    }

    // ------------------------------------------------------------ números

    static String formatNumber(double v, String sec, DecimalFormatSymbols sym) {
        StringBuilder pat = new StringBuilder();
        boolean digits = false, expPlus = false;
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            switch (c) {
                case '"': {
                    int j = sec.indexOf('"', i + 1);
                    if (j < 0) j = sec.length();
                    lit(pat, sec.substring(i + 1, j));
                    i = j;
                    break;
                }
                case '\\':
                    if (i + 1 < sec.length()) lit(pat, String.valueOf(sec.charAt(++i)));
                    break;
                case '_':
                    lit(pat, " ");
                    i++;
                    break;
                case '*':
                    i++;
                    break;
                case '[': {
                    int j = sec.indexOf(']', i + 1);
                    if (j < 0) j = sec.length();
                    String in = sec.substring(i + 1, j);
                    if (in.startsWith("$")) {
                        int dash = in.indexOf('-');
                        String cur = dash >= 0 ? in.substring(1, dash) : in.substring(1);
                        if (!cur.isEmpty()) lit(pat, cur);
                    }
                    i = j;
                    break;
                }
                case '0':
                case '#':
                    digits = true;
                    pat.append(c);
                    break;
                case '?':
                    digits = true;
                    pat.append('#');
                    break;
                case '.':
                case ',':
                case '%':
                    pat.append(c);
                    break;
                case 'E':
                case 'e':
                    if (i + 1 < sec.length() && (sec.charAt(i + 1) == '+' || sec.charAt(i + 1) == '-')) {
                        expPlus = sec.charAt(i + 1) == '+';
                        pat.append('E');
                        i++;
                    } else {
                        lit(pat, String.valueOf(c));
                    }
                    break;
                case '@':
                    return general(v, sym);
                case '/':
                    if (digits) return general(v, sym);
                    lit(pat, "/");
                    break;
                default:
                    lit(pat, String.valueOf(c));
                    break;
            }
        }
        if (!digits) {
            // Formato sin dígitos: solo texto literal
            return pat.toString().replace("''", "\u0000").replace("'", "").replace("\u0000", "'");
        }
        DecimalFormat df = new DecimalFormat(pat.toString(), sym);
        df.setRoundingMode(RoundingMode.HALF_UP);
        String s = df.format(v);
        if (expPlus) {
            int e = s.indexOf('E');
            if (e >= 0 && e + 1 < s.length() && s.charAt(e + 1) != '-') s = s.substring(0, e + 1) + "+" + s.substring(e + 1);
        }
        return s;
    }

    private static void lit(StringBuilder pat, String s) {
        pat.append('\'').append(s.replace("'", "''")).append('\'');
    }
}
