package com.lumenatlas.core.util;

import java.util.OptionalLong;

/** Parses a world seed the way the vanilla "Create World" screen does. */
public final class SeedText {
    private SeedText() {}

    /**
     * Numbers are used as they are; any other text is converted with {@link String#hashCode()}, exactly like the
     * seed field of the world creation screen. Blank input gives an empty result.
     */
    public static OptionalLong parse(String text) {
        if (text == null) return OptionalLong.empty();
        String s = text.trim();
        if (s.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return OptionalLong.of(s.hashCode());
        }
    }
}
