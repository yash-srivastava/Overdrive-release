package com.overdrive.app.parking.signage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw OCR text lines from garage signage into the three things a
 * returning driver actually needs — <b>level</b>, <b>zone</b>, <b>bay</b> —
 * and nothing else. Deliberately a grammar, not a language model: every
 * accepted token must match one of a small set of patterns seen on real
 * car-park signs (Malaysia / Singapore / HK / EU / US), so an OCR misread of
 * a shop name or a licence plate can never become a "level".
 *
 * <p>Multiple observations (several frames, several cameras) vote: the same
 * value seen twice with 0.6 confidence beats a lone 0.7. Confidence combines
 * as a noisy-OR of the per-observation scores.
 */
public final class SignageGrammar {

    /** Minimum combined confidence for a field to be reported. */
    static final float ACCEPT_MIN = 0.35f;
    /** Weight of a standalone "C142"-style bay (no keyword). */
    static final float BARE_BAY_WEIGHT = 0.55f;
    /** Weight of keyword-anchored matches. */
    static final float KEYWORD_WEIGHT = 0.95f;
    /** Weight of prefix-code levels ("B2", "P3"). */
    static final float CODE_WEIGHT = 0.85f;

    /** One OCR line handed to the grammar. */
    public static final class Observation {
        public final String text;
        public final float confidence;
        /** Frame index (for evidence / de-dup across cameras). */
        public final int frame;
        /** Line height normalised to frame height — big text is signage, small text is not. */
        public final float heightNorm;
        /** Box centre and width normalised to the frame; NaN when unknown. */
        public final float cx, cy, widthNorm;

        public Observation(String text, float confidence, int frame, float heightNorm) {
            this(text, confidence, frame, Float.NaN, Float.NaN, Float.NaN, heightNorm);
        }

        public Observation(String text, float confidence, int frame,
                           float cx, float cy, float widthNorm, float heightNorm) {
            this.text = text == null ? "" : text;
            this.confidence = confidence;
            this.frame = frame;
            this.heightNorm = heightNorm;
            this.cx = cx;
            this.cy = cy;
            this.widthNorm = widthNorm;
        }
    }

    /** Parsed signage. */
    public static final class Result {
        public String level, zone, bay;
        public float levelConf, zoneConf, bayConf;
        public final List<JSONObject> evidence = new ArrayList<>();

        public boolean isEmpty() { return level == null && zone == null && bay == null; }

        /** Overall confidence: mean of the reported fields. */
        public float confidence() {
            float sum = 0f; int n = 0;
            if (level != null) { sum += levelConf; n++; }
            if (zone != null) { sum += zoneConf; n++; }
            if (bay != null) { sum += bayConf; n++; }
            return n == 0 ? 0f : sum / n;
        }

        /** "B2 · Zone C · Bay 142" — the notification / card label. */
        public String label() {
            StringBuilder b = new StringBuilder();
            if (level != null) b.append(level);
            if (zone != null) { if (b.length() > 0) b.append(" · "); b.append("Zone ").append(zone); }
            if (bay != null) { if (b.length() > 0) b.append(" · "); b.append("Bay ").append(bay); }
            return b.toString();
        }

        public JSONObject toJson() {
            JSONObject j = new JSONObject();
            try {
                j.put("found", !isEmpty());
                if (level != null) { j.put("level", level); j.put("levelConf", round(levelConf)); }
                if (zone != null) { j.put("zone", zone); j.put("zoneConf", round(zoneConf)); }
                if (bay != null) { j.put("bay", bay); j.put("bayConf", round(bayConf)); }
                j.put("confidence", round(confidence()));
                j.put("label", label());
                JSONArray ev = new JSONArray();
                for (JSONObject e : evidence) ev.put(e);
                j.put("evidence", ev);
            } catch (Exception ignored) {}
            return j;
        }

        private static double round(float v) { return Math.round(v * 1000.0) / 1000.0; }
    }

    // ---------------------------------------------------------------- patterns

    /** Digits with the usual OCR look-alikes, normalised by {@link #digits}. */
    private static final String D = "[0-9OQDIZS]";
    /** A value read as a percentage ("B 23%": the recorder's own pedal readout) is never a sign value. */
    private static final String NOT_PERCENT = "(?!\\s?%)";

    // ------------------------------------------------------------ vocabulary
    // Every word is matched AFTER normalise(): upper-cased, NFKC-folded (full-
    // width "Ｂ２" → "B2"), diacritics stripped (ÉTAGE → ETAGE, SÓTANO → SOTANO,
    // PODLAŽÍ → PODLAZI, NIVÅ → NIVA, BÖLGE → BOLGE, TẦNG → TANG), and every
    // Unicode decimal digit (٢ ๒ २ ２) folded to ASCII. So the Latin lists below
    // are spelled without accents on purpose. The bundled PP-OCRv4 recogniser
    // emits Latin letters, digits and Chinese; other scripts are only seen when
    // a language-specific recogniser is side-loaded. Latin codes are what the
    // level CODES on non-English signs are made of ("B2", "P3", "-1", "2F",
    // "LG"); the words around them are a bonus, not a requirement.

    /** Level / floor words followed by the value: "LEVEL 2", "ARAS 3", "PISO -1", "ЭТАЖ 3", "KAT 2". */
    private static final String LEVEL_WORDS = String.join("|",
            // English / Malay / Indonesian
            "LEVEL", "LVL", "LEV", "FLOOR", "FLR", "STOREY", "STORY", "TINGKAT", "TKT", "ARAS", "LANTAI",
            // Spanish / Portuguese / Catalan / Italian / French
            "PISO", "ANDAR", "PLANTA", "NIVEL", "NIVELL", "NIVEAU", "PIANO", "LIVELLO", "ETAGE", "ETAJ",
            // German / Dutch / Norwegian / Czech / Turkish / Vietnamese ("PLAN" and
            // "DECK" are deliberately absent: too common as ordinary English words)
            "EBENE", "PARKDECK", "PARKEBENE", "VERDIEPING", "LAAG", "ETASJE", "NIVA", "PATRO", "PODLAZI", "KAT", "TANG",
            // Russian / Hebrew / Arabic / Hindi
            "ЭТАЖ", "УРОВЕНЬ", "קומה", "מפלס", "طابق", "الطابق", "الدور", "मंजिल", "तल");

    /** Basement words: the value that follows is BELOW ground; alone they mean B1. */
    private static final String BASEMENT_WORDS = String.join("|",
            "BASEMENT", "SOTANO", "SUBSOLO", "SOUS-SOL", "SOUSSOL", "INTERRATO", "UNTERGESCHOSS",
            "KELDER", "KJELLER", "SUTEREN", "BODRUM", "ПОДВАЛ", "القبو", "السرداب");
    /**
     * Basement words that are ordinary words elsewhere ("HAM"), or are a bare
     * "minus" ("מינוס"): they only count WITH a number behind them.
     */
    private static final String BASEMENT_WORDS_NEED_VALUE = String.join("|", "HAM", "מינוס", "MINUS");

    /** Zone / area / sector words. */
    private static final String ZONE_WORDS = String.join("|",
            // Longer forms first: alternation is ordered, and "ZON" must not eat the "A" of "ZONA B".
            "ZONE", "ZONA", "ZON(?![EA])", "SONE", "AREA", "SECTION", "SECTOR", "SECTEUR", "SEKSYEN", "SEC(?!T)", "SETOR", "SETTORE",
            "BLOCK", "BLOK", "BLK", "BLOQUE", "BLOCO", "WING", "BEREICH", "KHU", "BOLGE", "ЗОНА", "СЕКТОР", "אזור", "منطقة", "구역", "โซน");

    /** Bay / space / lot words (followed by a number). */
    private static final String BAY_WORDS = String.join("|",
            "BAY", "LOT", "SPACE", "SPOT", "PETAK", "PARKING NO", "NO", "NUM", "NR", "N°", "Nº",
            "PLAZA", "PLACA", "VAGA", "PLACE", "POSTO", "PLATZ", "PARKPLATZ", "STELLPLATZ", "PLEK", "PLAATS", "PLASS",
            "MISTO", "MESTO", "PARK YERI", "МЕСТО", "מקום", "موقف", "자리", "ที่จอด");

    // ------------------------------------------------------------ patterns

    /** "B2", "LG1", "P3", "L2", "M1", "UG2" — code + one or two REAL digits. */
    private static final Pattern LEVEL_CODE = Pattern.compile(
            "(?<![A-Z0-9])(B|LG|UG|UB|LB|P|L|M)\\s?[-.]?\\s?(\\d{1,2})(?![0-9A-Z])" + NOT_PERCENT);
    /**
     * "LEVEL 2", "ARAS 3", "TINGKAT B1", "FLOOR -1", "PLANTA -1", "LVL G",
     * "KAT 2", "ЭТАЖ 3". A dash in front of the digit is always the SIGN of a
     * below-ground level ("PLANTA -1", "קומה -1"), never a separator: European
     * signs print negative levels far more often than "LEVEL-1".
     */
    private static final Pattern LEVEL_WORD = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(" + LEVEL_WORDS + ")"
            + "\\s?[.:]?\\s?(B\\s?" + D + "|LG|UG|GF|G|-\\s?" + D + "{1,2}|" + D + "{1,2})(?![0-9A-Z])" + NOT_PERCENT);
    /** RTL scripts often come out value-first from the recogniser: "2 קומה", "3 طابق". */
    private static final Pattern LEVEL_WORD_RTL = Pattern.compile(
            "(?<![0-9A-Z])(-?\\s?" + D + "{1,2})\\s?(קומה|מפלס|طابق|الطابق|الدور)(?![\\p{L}])");
    /** "BASEMENT 2", "SOTANO 2", "SOUS-SOL 1", "UNTERGESCHOSS 2", "القبو 2"; alone → B1. */
    private static final Pattern LEVEL_BASEMENT_WORD = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(" + BASEMENT_WORDS + ")(?:\\s?(?:FLOOR|LEVEL|FLR))?"
            + "(?:\\s?[-.:]?\\s?(?:B\\s?)?(" + D + "))?(?![0-9A-Z])");
    /** "HAM B2" / "HẦM 2" (Vietnamese), "מינוס 1" (Hebrew), "MINUS 2": value required. */
    private static final Pattern LEVEL_BASEMENT_VALUE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(" + BASEMENT_WORDS_NEED_VALUE + ")\\s?[-.:]?\\s?(?:B\\s?)?(" + D + ")(?![0-9A-Z])");
    /** "LOWER GROUND", "UPPER GROUND", "GROUND FLOOR", "MEZZANINE", "ROOF". */
    private static final Pattern LEVEL_NAMED = Pattern.compile(
            "\\b(LOWER GROUND|UPPER GROUND|GROUND|MEZZANINE|ROOFTOP|ROOF|ERDGESCHOSS|REZ-DE-CHAUSSEE|BEGANE GROND)"
            + "(?:\\s?(?:FLOOR|LEVEL|FLR))?(?:\\s?[-.:]?\\s?(" + D + "))?(?![0-9A-Z])");
    /**
     * "2F", "3/F", "-1F", "B2F" (HK / JP style). Real digits only: with the
     * OCR look-alike class this would turn "IF" / "MOTIF" into "Level 1".
     */
    private static final Pattern LEVEL_SUFFIX = Pattern.compile(
            "(?<![A-Z0-9])(B?-?\\d{1,2})\\s?(?:/F|F|FL)(?![A-Z0-9])");
    /** German "2. OG" / "1. UG" (Ober-/Untergeschoss) and "EG". */
    private static final Pattern LEVEL_DE = Pattern.compile(
            "(?<![A-Z0-9])(?:(\\d)\\.?\\s?(OG|UG)|(EG))(?![A-Z0-9])");
    /**
     * CJK: value BEFORE the unit — "地下2层" (B2), "3楼" / "３階" (Level 3),
     * "B2层", "二层" (Chinese numerals), Korean "지하 3층" / "2층". The Latin
     * "F" unit lives in {@link #LEVEL_SUFFIX} so it only accepts real digits.
     */
    private static final Pattern LEVEL_CJK = Pattern.compile(
            "(地下|지하)?\\s?(B\\s?" + D + "{1,2}|-?" + D + "{1,2}|[一二三四五六七八九十]{1,3})\\s?(层|楼|樓|階|층)(?![0-9A-Z])");
    /** Thai: unit BEFORE the value — "ชั้น 2", "ชั้นใต้ดิน 1" (basement). */
    private static final Pattern LEVEL_TH = Pattern.compile(
            "ชั้น\\s?(ใต้ดิน)?\\s?(B\\s?" + D + "|-?" + D + "{1,2})(?![0-9A-Z])");

    /**
     * "ZONE C", "ZON B2", "AREA 3", "BLOCK A", "SECTOR 2", "WING B", "ZONA A",
     * "SETOR C", "BEREICH B", "ЗОНА 3". The CTC recogniser drops spaces
     * ("ZONEC"), so the value may be glued to the keyword; the short forms
     * therefore refuse to match INSIDE the long ones ({@code ZON(?!E)},
     * {@code SEC(?!T)}) instead of demanding a boundary.
     */
    private static final Pattern ZONE_WORD = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(" + ZONE_WORDS + ")"
            + "\\s?[-.:]?\\s?([A-Z]{1,2}\\d{0,2}|\\d{1,2})(?![0-9A-Z])" + NOT_PERCENT);
    /** CJK zone: "A区", "B區", "C区域", Korean "A 구역" is covered by ZONE_WORD (value after). */
    private static final Pattern ZONE_CJK = Pattern.compile(
            "(?<![A-Z0-9])([A-Z]\\d{0,2}|\\d{1,2})\\s?(区域|区|區)(?![0-9A-Z])");
    /** "RED ZONE", "ZONE BLUE". */
    private static final Pattern ZONE_COLOUR = Pattern.compile(
            "\\b(?:(RED|BLUE|GREEN|YELLOW|ORANGE|PURPLE|PINK|WHITE|BLACK)\\s(?:ZONE|AREA)"
            + "|(?:ZONE|AREA)\\s(RED|BLUE|GREEN|YELLOW|ORANGE|PURPLE|PINK|WHITE|BLACK))\\b");

    /** "BAY 142", "LOT C12", "SPACE 07", "PETAK 23", "NO. 118", "#118", "VAGA 12", "PLATZ 23", "МЕСТО 45". */
    private static final Pattern BAY_WORD = Pattern.compile(
            "(?:(?<![\\p{L}\\p{N}])(" + BAY_WORDS + ")(?![A-Z])\\s?[.:#-]?\\s?|#\\s?)"
            + "([A-Z]{0,2}\\s?-?\\d{1,4}[A-Z]?)(?![0-9A-Z])" + NOT_PERCENT);
    /** CJK bay: value BEFORE the unit — "12号车位", "３５番". */
    private static final Pattern BAY_CJK = Pattern.compile(
            "(?<![A-Z0-9])([A-Z]{0,2}\\d{1,4})\\s?(?:号|號)?\\s?(车位|車位|番|칸)(?![0-9A-Z])");
    /**
     * Standalone "C142" / "B-142" / "AB 12": letters + 2–3 digits. Four-digit
     * groups are deliberately excluded here — that is the shape of a licence
     * plate, which must never be mistaken for a bay.
     */
    private static final Pattern BAY_BARE = Pattern.compile(
            "(?<![A-Z0-9])([A-Z]{1,2})\\s?-?\\s?(\\d{2,3})(?![0-9A-Z])" + NOT_PERCENT);
    /** A standalone 4-digit group marks a licence plate line ("KA 50 MF 3155"): no bare bay in it. */
    private static final Pattern PLATE_DIGITS = Pattern.compile("(?<![0-9])\\d{4}(?![0-9])");
    /** A line that is only a level code ("B2"): the top of a level/location placard. */
    private static final Pattern LEVEL_CODE_LINE = Pattern.compile("^(B|LG|UG|UB|LB|P|L|M)\\s?[-.]?\\s?(\\d{1,2})$");
    /** The location code printed with it ("R03"); digit look-alikes fold, a leading "$" is an S. */
    private static final Pattern PLACARD_CODE = Pattern.compile("^([A-Z$]{1,2})([0-9OQDIZS]{2,3})$");

    private SignageGrammar() {}

    // ---------------------------------------------------------------- parsing

    public static Result parse(List<Observation> observations) {
        Result r = new Result();
        if (observations == null || observations.isEmpty()) return r;
        Votes levels = new Votes(), zones = new Votes(), bays = new Votes();
        Map<Observation, String> placard = placardCodes(observations);

        for (Observation o : observations) {
            if (o == null || o.text.trim().isEmpty()) continue;
            String t = normalise(o.text);
            float base = clamp01(o.confidence) * sizeBoost(o.heightNorm);
            if (base <= 0f) continue;

            Matcher m = LEVEL_CODE.matcher(t);
            while (m.find()) {
                levels.add(m.group(1) + m.group(2), base * CODE_WEIGHT, o, m.group());
            }
            m = LEVEL_WORD.matcher(t);
            while (m.find()) {
                String v = canonicalLevel(m.group(2));
                if (v != null) levels.add(v, base * KEYWORD_WEIGHT, o, m.group());
            }
            m = LEVEL_WORD_RTL.matcher(t);
            while (m.find()) {
                String v = canonicalLevel(m.group(1));
                if (v != null) levels.add(v, base * KEYWORD_WEIGHT, o, m.group());
            }
            m = LEVEL_BASEMENT_WORD.matcher(t);
            while (m.find()) {
                String d = m.group(2) == null ? "1" : digits(m.group(2));
                if (!d.isEmpty()) levels.add("B" + d, base * KEYWORD_WEIGHT, o, m.group());
            }
            m = LEVEL_BASEMENT_VALUE.matcher(t);
            while (m.find()) {
                String d = digits(m.group(2));
                if (!d.isEmpty() && !"0".equals(d)) levels.add("B" + d, base * KEYWORD_WEIGHT, o, m.group());
            }
            m = LEVEL_NAMED.matcher(t);
            while (m.find()) {
                String v = namedLevel(m.group(1), m.group(2));
                if (v != null) levels.add(v, base * KEYWORD_WEIGHT, o, m.group());
            }
            m = LEVEL_SUFFIX.matcher(t);
            while (m.find()) {
                String v = canonicalLevel(m.group(1));
                if (v != null) levels.add(v, base * CODE_WEIGHT, o, m.group());
            }
            m = LEVEL_DE.matcher(t);
            while (m.find()) {
                String v = m.group(3) != null ? "G"
                        : ("UG".equals(m.group(2)) ? "B" + m.group(1) : "Level " + m.group(1));
                levels.add(v, base * KEYWORD_WEIGHT, o, m.group());
            }
            m = LEVEL_CJK.matcher(t);
            while (m.find()) {
                String v = cjkLevel(m.group(1), m.group(2));
                if (v != null) levels.add(v, base * KEYWORD_WEIGHT, o, m.group());
            }
            m = LEVEL_TH.matcher(t);
            while (m.find()) {
                String v = canonicalLevel(m.group(2));
                if (v != null && m.group(1) != null && v.startsWith("Level ")) v = "B" + v.substring(6);
                if (v != null) levels.add(v, base * KEYWORD_WEIGHT, o, m.group());
            }

            m = ZONE_WORD.matcher(t);
            while (m.find()) {
                zones.add(m.group(2), base * KEYWORD_WEIGHT, o, m.group());
            }
            m = ZONE_CJK.matcher(t);
            while (m.find()) {
                zones.add(m.group(1), base * KEYWORD_WEIGHT, o, m.group());
            }
            m = ZONE_COLOUR.matcher(t);
            while (m.find()) {
                String c = m.group(1) != null ? m.group(1) : m.group(2);
                if (c != null) zones.add(cap(c), base * KEYWORD_WEIGHT, o, m.group());
            }

            m = BAY_WORD.matcher(t);
            while (m.find()) {
                bays.add(m.group(2).replaceAll("[\\s-]", ""), base * KEYWORD_WEIGHT, o, m.group());
            }
            m = BAY_CJK.matcher(t);
            while (m.find()) {
                bays.add(m.group(1), base * KEYWORD_WEIGHT, o, m.group());
            }
            String code = placard.get(o);
            if (code != null) bays.add(code, clamp01(o.confidence) * KEYWORD_WEIGHT, o, code);
            m = BAY_BARE.matcher(t);
            boolean plateLine = PLATE_DIGITS.matcher(t).find();
            while (code == null && !plateLine && m.find()) {
                String letters = m.group(1);
                String digits = m.group(2);
                // A code+digits that already parsed as a level (B2) can't reach
                // here (needs ≥2 digits) but "P10" can: treat 2-digit values
                // behind a level code as level, not bay.
                if (digits.length() <= 2 && isLevelCode(letters)) continue;
                bays.add(letters + digits, base * BARE_BAY_WEIGHT, o, m.group());
            }
        }

        Votes.Winner lw = levels.best();
        if (lw != null && lw.confidence >= ACCEPT_MIN) {
            r.level = lw.value; r.levelConf = lw.confidence; r.evidence.addAll(lw.evidence("level"));
        }
        Votes.Winner zw = zones.best();
        if (zw != null && zw.confidence >= ACCEPT_MIN) {
            r.zone = zw.value; r.zoneConf = zw.confidence; r.evidence.addAll(zw.evidence("zone"));
        }
        Votes.Winner bw = bays.best();
        if (bw != null && bw.confidence >= ACCEPT_MIN) {
            r.bay = bw.value; r.bayConf = bw.confidence; r.evidence.addAll(bw.evidence("bay"));
        }
        // A bare bay whose letters equal the zone ("C142" with zone C) is
        // redundant with the zone: strip the letters so the label reads
        // "Zone C · Bay 142" rather than "Zone C · Bay C142".
        if (r.bay != null && r.zone != null && r.bay.startsWith(r.zone)
                && r.bay.length() > r.zone.length()
                && Character.isDigit(r.bay.charAt(r.zone.length()))) {
            r.bay = r.bay.substring(r.zone.length());
        }
        return r;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Location codes printed on one placard with a level code: a line directly above or below an
     * exact level-code line of the same frame. The placard is the signage evidence, so these
     * codes are not discounted for their small size.
     */
    static Map<Observation, String> placardCodes(List<Observation> observations) {
        Map<Observation, String> out = new java.util.IdentityHashMap<>();
        for (Observation a : observations) {
            if (a == null || !hasBox(a) || !LEVEL_CODE_LINE.matcher(normalise(a.text).trim()).matches()) continue;
            for (Observation c : observations) {
                if (c == null || c == a || c.frame != a.frame || !hasBox(c) || out.containsKey(c)) continue;
                float h = Math.max(a.heightNorm, c.heightNorm);
                float dy = Math.abs(c.cy - a.cy);
                if (dy <= 0.3f * h || dy > 2f * h) continue;
                if (Math.abs(c.cx - a.cx) > 0.6f * Math.max(a.widthNorm, c.widthNorm)) continue;
                String code = placardCode(c.text);
                if (code != null) out.put(c, code);
            }
        }
        return out;
    }

    private static boolean hasBox(Observation o) {
        return o.heightNorm > 0f && o.widthNorm > 0f && !Float.isNaN(o.cx) && !Float.isNaN(o.cy);
    }

    /** "R03" / "RO3" → "R03", "$01" → "S01"; null unless 1–2 letters lead 2–3 digit slots holding a real digit. */
    static String placardCode(String raw) {
        String t = normalise(raw);
        if (t.indexOf('%') >= 0) return null;
        Matcher m = PLACARD_CODE.matcher(t.replaceAll("[^A-Z0-9$]", ""));
        if (!m.matches()) return null;
        String d = m.group(2);
        if (!d.matches(".*[0-9].*")) return null;
        return m.group(1).replace('$', 'S') + digits(d);
    }

    /**
     * Fold the OCR line into the grammar's alphabet: NFKC (full-width "Ｂ２" →
     * "B2", "㎡"-style compatibility forms), strip combining diacritics
     * (É→E, Ã→A, Å→A, Ö→O, Ầ→A), fold every Unicode decimal digit to ASCII
     * (Arabic-Indic ٢, Thai ๒, Devanagari २), upper-case, unify dashes, collapse
     * whitespace. Non-Latin letters (Cyrillic, Hebrew, Arabic, CJK, Thai, Hangul)
     * pass through untouched so the script-specific patterns can see them.
     */
    static String normalise(String s) {
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(t.length());
        boolean lastBaseLatin = false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                // Combining accent split off by NFD: drop it ONLY behind a Latin
                // base letter. Thai vowel marks and Hangul jamo must survive so
                // the script-specific patterns still see "ชั้น" / "지하" after the
                // final NFC recompose below.
                if (lastBaseLatin) continue;
                b.appendCodePoint(cp);
                continue;
            }
            lastBaseLatin = Character.isLetter(cp)
                    && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
            if (Character.isDigit(cp)) {
                int v = Character.getNumericValue(cp);
                if (v >= 0 && v <= 9) { b.append((char) ('0' + v)); continue; }
            }
            switch (cp) {
                case '–': case '—': case '‐': case '−': b.append('-'); break;
                case '|': b.append('I'); break;
                case 'ı': b.append('I'); break;   // dotless i (Turkish) → I, so KAT/BODRUM upper-case cleanly
                case 'ß': b.append("SS"); break;
                case 'đ': case 'Đ': b.append('D'); break;   // Vietnamese
                default: b.appendCodePoint(cp);
            }
        }
        String out = java.text.Normalizer.normalize(b.toString(), java.text.Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return " " + out + " ";
    }

    /** CJK / Korean level: prefix 地下|지하 = basement; value may be Chinese numerals. */
    static String cjkLevel(String prefix, String value) {
        if (value == null) return null;
        String v = value.replace(" ", "");
        boolean basement = prefix != null;
        if (v.startsWith("B")) { basement = true; v = v.substring(1); }
        if (v.startsWith("-")) { basement = true; v = v.substring(1); }
        int n;
        if (v.matches("[一二三四五六七八九十]{1,3}")) {
            n = chineseNumeral(v);
        } else {
            String d = digits(v);
            if (d.isEmpty() || !d.chars().allMatch(Character::isDigit)) return null;
            n = Integer.parseInt(d);
        }
        if (n <= 0 || n > 60) return null;
        return basement ? "B" + n : "Level " + n;
    }

    /** 一..九 → 1..9, 十 → 10, 十一 → 11, 二十 → 20, 二十三 → 23. */
    static int chineseNumeral(String s) {
        final String digitsStr = "一二三四五六七八九";
        int result = 0, current = 0;
        for (char c : s.toCharArray()) {
            if (c == '十') {
                result += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else {
                int idx = digitsStr.indexOf(c);
                if (idx < 0) return -1;
                current = idx + 1;
            }
        }
        return result + current;
    }

    /** Map OCR look-alikes inside a token that must be numeric. */
    static String digits(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'O': case 'Q': case 'D': b.append('0'); break;
                case 'I': b.append('1'); break;
                case 'Z': b.append('2'); break;
                case 'S': b.append('5'); break;
                case ' ': break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** "2" → "Level 2", "-1" → "B1", "B2" → "B2", "G"/"GF" → "G", "LG" → "LG". */
    static String canonicalLevel(String raw) {
        if (raw == null) return null;
        String v = raw.replace(" ", "");
        if (v.equals("G") || v.equals("GF")) return "G";
        if (v.equals("LG") || v.equals("UG")) return v;
        if (v.startsWith("B")) {
            String d = digits(v.substring(1));
            return d.isEmpty() ? null : "B" + d;
        }
        boolean neg = v.startsWith("-");
        String d = digits(neg ? v.substring(1) : v);
        if (d.isEmpty() || !d.chars().allMatch(Character::isDigit)) return null;
        int n = Integer.parseInt(d);
        if (n == 0) return "G";
        if (n > 60) return null;
        return neg ? "B" + n : "Level " + n;
    }

    static String namedLevel(String word, String digit) {
        String d = digit == null ? null : digits(digit);
        switch (word) {
            case "BASEMENT": return "B" + (d == null || d.isEmpty() ? "1" : d);
            case "LOWER GROUND": return "LG" + (d == null ? "" : d);
            case "UPPER GROUND": return "UG" + (d == null ? "" : d);
            case "GROUND": case "ERDGESCHOSS": case "REZ-DE-CHAUSSEE": case "BEGANE GROND": return "G";
            case "MEZZANINE": return "M" + (d == null ? "" : d);
            case "ROOF": case "ROOFTOP": return "Roof";
            default: return null;
        }
    }

    static boolean isLevelCode(String letters) {
        switch (letters) {
            case "B": case "P": case "L": case "M": case "LG": case "UG": case "UB": case "LB":
                return true;
            default:
                return false;
        }
    }

    /** Large text is signage; tiny text (shop names, plates) is discounted. */
    static float sizeBoost(float heightNorm) {
        if (heightNorm <= 0f) return 1f;          // unknown — neutral
        if (heightNorm < 0.02f) return 0.6f;
        if (heightNorm < 0.05f) return 0.85f;
        return 1f;
    }

    private static String cap(String s) {
        return s.substring(0, 1) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Per-field noisy-OR vote table. */
    static final class Votes {
        static final class Entry {
            double oneMinus = 1.0;
            final List<Object[]> ev = new ArrayList<>();
        }
        static final class Winner {
            final String value; final float confidence; final Entry entry;
            Winner(String v, float c, Entry e) { value = v; confidence = c; entry = e; }
            List<JSONObject> evidence(String field) {
                List<JSONObject> out = new ArrayList<>();
                int n = 0;
                for (Object[] e : entry.ev) {
                    if (n++ >= 5) break;
                    try {
                        JSONObject j = new JSONObject();
                        j.put("field", field);
                        j.put("text", (String) e[0]);
                        j.put("conf", Math.round(((Float) e[1]) * 1000.0) / 1000.0);
                        j.put("frame", (Integer) e[2]);
                        out.add(j);
                    } catch (Exception ignored) {}
                }
                return out;
            }
        }
        private final Map<String, Entry> map = new LinkedHashMap<>();

        void add(String value, float score, Observation o, String matched) {
            if (value == null || value.isEmpty() || score <= 0f) return;
            Entry e = map.get(value);
            if (e == null) { e = new Entry(); map.put(value, e); }
            e.oneMinus *= (1.0 - Math.min(0.99, score));
            e.ev.add(new Object[] {matched.trim(), o.confidence, o.frame});
        }

        Winner best() {
            Winner w = null;
            for (Map.Entry<String, Entry> kv : map.entrySet()) {
                float c = (float) Math.min(0.99, 1.0 - kv.getValue().oneMinus);
                if (w == null || c > w.confidence) w = new Winner(kv.getKey(), c, kv.getValue());
            }
            return w;
        }
    }
}
