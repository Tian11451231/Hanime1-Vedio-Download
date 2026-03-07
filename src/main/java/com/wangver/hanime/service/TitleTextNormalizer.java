package com.wangver.hanime.service;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class TitleTextNormalizer {

    private static final Pattern HANIME_SUFFIX = Pattern.compile("\\s*-\\s*H.*?Hanime1\\.me\\s*$", Pattern.CASE_INSENSITIVE);

    private TitleTextNormalizer() {
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }

        String normalized = repairMojibakeSegments(text)
                .replace('\u00a0', ' ')
                .strip();

        normalized = HANIME_SUFFIX.matcher(normalized).replaceFirst("").strip();
        normalized = normalized.replaceAll("\\s*-\\s*免費高清AV.*$", "").strip();
        normalized = normalized.replaceAll("\\s*-\\s*Hanime1\\.me\\s*$", "").strip();
        return normalized;
    }

    static boolean looksBroken(String text) {
        return text != null && looksLikeMojibake(text);
    }

    private static String repairMojibakeSegments(String text) {
        StringBuilder result = new StringBuilder();
        StringBuilder segment = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x00FF) {
                segment.append(ch);
            } else {
                flushSegment(result, segment);
                result.append(ch);
            }
        }
        flushSegment(result, segment);
        return result.toString();
    }

    private static void flushSegment(StringBuilder result, StringBuilder segment) {
        if (segment.isEmpty()) {
            return;
        }

        String original = segment.toString();
        if (looksLikeMojibake(original)) {
            byte[] bytes = original.getBytes(StandardCharsets.ISO_8859_1);
            String repaired = new String(bytes, StandardCharsets.UTF_8);
            result.append(repaired);
        } else {
            result.append(original);
        }
        segment.setLength(0);
    }

    private static boolean looksLikeMojibake(String text) {
        return text.contains("ã")
                || text.contains("â")
                || text.contains("å")
                || text.contains("è")
                || text.contains("ç")
                || text.contains("ï")
                || text.chars().anyMatch(ch -> ch >= 0x80 && ch <= 0x9F);
    }
}
