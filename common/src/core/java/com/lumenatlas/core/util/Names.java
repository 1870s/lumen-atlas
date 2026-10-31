package com.lumenatlas.core.util;

import java.util.Locale;

/** Turns world, server and dimension names into safe, stable folder names. */
public final class Names {
    private Names() {}

    /**
     * Keeps letters, digits, '.', '-' and '_'; everything else becomes '_'. Ports in server addresses keep their
     * meaning ("play.example.com_25565"). Never returns an empty or "." / ".." name.
     */
    public static String folderName(String raw) {
        if (raw == null || raw.isBlank()) return "unnamed";
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            boolean safe = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '~';
            out.append(safe ? c : '_');
        }
        String s = out.toString();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) return "unnamed";
        // Windows reserved device names.
        if (s.matches("(con|prn|aux|nul|com[0-9]|lpt[0-9])([.].*)?")) s = "_" + s;
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** "minecraft:the_nether" -> "minecraft~the_nether". */
    public static String dimensionFolder(String dimensionId) {
        return folderName(dimensionId.replace(':', '~'));
    }
}
