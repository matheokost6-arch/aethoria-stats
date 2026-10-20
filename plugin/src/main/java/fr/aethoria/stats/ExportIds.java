package fr.aethoria.stats;

import java.util.Map;

/** Identifiants de l'export du mod : nombres ou textes, parfois absents. */
final class ExportIds {
    private ExportIds() {}

    static String str(Object o) {
        return AethoriaExport.idString(o);
    }

    static String id(Map<String, Object> m) {
        String s = str(m.get("id"));
        return s == null ? "" : s;
    }
}
