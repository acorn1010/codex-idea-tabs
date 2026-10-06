package com.acorn.codextabs.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Keep URL suffixes out of file lookup while preserving browser anchors and editor positions. */
public record FileLink(String path, int line, int column, String query, String fragment) {
    private static final Pattern POSITION = Pattern.compile("^(.*?):(\\d+)(?::(\\d+))?$");
    private static final Pattern LINE_ANCHOR = Pattern.compile("^L(\\d+)(?:C(\\d+))?(?:-L?\\d+(?:C\\d+)?)?$");

    public static FileLink parse(String value, String cwd) {
        int hash = value.indexOf('#');
        String fragment = hash < 0 ? null : value.substring(hash + 1);
        String path = hash < 0 ? value : value.substring(0, hash);
        int question = path.indexOf('?');
        String query = question < 0 ? null : path.substring(question + 1);
        path = question < 0 ? path : path.substring(0, question);
        int line = -1, column = 0;
        var position = POSITION.matcher(path);
        if (position.matches()) {
            path = position.group(1); line = index(position.group(2)); column = index(position.group(3));
        }
        // Decode only after splitting. Escaped # and ? characters can be part of a filename.
        path = Paths.link(path.startsWith("file:") ? path : decode(path), cwd);
        var anchor = LINE_ANCHOR.matcher(fragment == null ? "" : fragment);
        if (line < 0 && !html(path) && anchor.matches()) { line = index(anchor.group(1)); column = index(anchor.group(2)); }
        return new FileLink(path, line, column, query, fragment);
    }
    public boolean preview() { return line < 0 && html(path); }
    /** The host supplies an encoded file URL after resolving and checking the actual file. */
    public String browserUrl(String fileUrl) { return fileUrl + (query == null ? "" : "?" + query) + (fragment == null ? "" : "#" + fragment); }
    private static boolean html(String path) { return path.toLowerCase(Locale.ROOT).endsWith(".html") || path.toLowerCase(Locale.ROOT).endsWith(".htm"); }
    private static int index(String value) { return value == null ? 0 : Math.max(0, Integer.parseInt(value) - 1); }
    private static String decode(String path) {
        try { return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ignored) { return path; } // A plain filename can contain a literal percent sign.
    }
}
