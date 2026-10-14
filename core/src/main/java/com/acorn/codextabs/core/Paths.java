package com.acorn.codextabs.core;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Translate paths once at the host boundary. Model prompts always use the execution environment's paths. */
public final class Paths {
    private static final Pattern WSL = Pattern.compile("^//(?:wsl\\.localhost|wsl\\$)/([^/]+)(/.*)?$", Pattern.CASE_INSENSITIVE);
    private Paths() {}
    public static String distro(String path) {
        var match = WSL.matcher(path.replace('\\', '/'));
        return match.matches() ? match.group(1) : "";
    }
    public static String linux(String path) {
        String normalized = path.replace('\\', '/');
        var match = WSL.matcher(normalized);
        if (match.matches()) { return match.group(2) == null ? "/" : match.group(2); }
        if (normalized.matches("^/?[A-Za-z]:/.*")) {
            normalized = normalized.replaceFirst("^/", "");
            return "/mnt/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
        }
        return normalized;
    }
    public static String host(String path, String distro, boolean windows) {
        if (!windows) { return linux(path); }
        if (!distro.isBlank() && path.startsWith("/") && !path.startsWith("//")) {
            if (path.matches("^/mnt/[a-z]/.*")) { return Character.toUpperCase(path.charAt(5)) + ":" + path.substring(6); }
            return "//wsl.localhost/" + distro + path;
        }
        return path.replace('\\', '/');
    }
    public static String link(String value, String cwd) {
        String result = value;
        if (result.startsWith("file:")) {
            var uri = URI.create(result);
            result = (uri.getHost() == null ? "" : "//" + uri.getHost()) + uri.getPath();
            if (result.matches("^/[A-Za-z]:/.*")) { result = result.substring(1); }
            if (uri.getFragment() != null) { result += "#" + uri.getFragment(); }
        }
        if (result.startsWith("sandbox:")) { result = result.substring(8); }
        if (!result.startsWith("/") && !result.startsWith("\\") && !result.matches("^[A-Za-z]:.*")) {
            result = cwd.replaceAll("/$", "") + "/" + result;
        }
        return result;
    }
    public static List<String> command(String binary, String distro, boolean windows) {
        return command(binary, distro, windows, List.of());
    }
    /** Pass provider configuration as individual arguments, including inside WSL. */
    public static List<String> command(String binary, String distro, boolean windows, List<String> options) {
        if (binary.isBlank()) { binary = "codex"; }
        if (windows && !distro.isBlank()) {
            // Positional arguments keep spaces and shell metacharacters out of executable shell text.
            var args = new ArrayList<>(List.of("wsl.exe", "--distribution", distro, "--exec", "/bin/sh", "-lc", "exec \"$@\" app-server", "codex-tabs", binary));
            args.addAll(options); return args;
        }
        var args = new ArrayList<>(List.of(binary));
        args.addAll(options); args.add("app-server"); return args;
    }
}
