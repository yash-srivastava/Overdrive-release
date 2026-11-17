package com.overdrive.app.voice.local;

/** Prints CommandCatalog as TSV (language, tag, intent, slots, phrase) for gen_kws_keywords.py. */
public final class DumpCatalog {
    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        for (String language : CommandCatalog.languages()) {
            for (CommandCatalog.Entry e : CommandCatalog.entries(language)) {
                out.append(language).append('\t').append(e.tag).append('\t').append(e.intent)
                        .append('\t').append(slotsJson(e.slots)).append('\t').append(e.phrase)
                        .append('\n');
            }
        }
        System.out.print(out);
    }

    private static String slotsJson(Object[] slots) {
        StringBuilder s = new StringBuilder("{");
        for (int i = 0; i + 1 < slots.length; i += 2) {
            if (i > 0) s.append(',');
            s.append('"').append(slots[i]).append("\":");
            Object v = slots[i + 1];
            s.append(v instanceof String ? "\"" + v + "\"" : String.valueOf(v));
        }
        return s.append('}').toString();
    }
}
