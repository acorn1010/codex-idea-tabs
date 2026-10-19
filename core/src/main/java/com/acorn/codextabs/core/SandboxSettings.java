package com.acorn.codextabs.core;

import com.google.gson.JsonObject;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Carry explicitly selected folders into Codex without changing read-only or network permissions. */
public final class SandboxSettings {
    private SandboxSettings() {}
    /** Convert one absolute folder per line to the agent's filesystem, preserving spaces in names. */
    public static List<String> folders(String configured, String distro, boolean windows) {
        var folders = new LinkedHashSet<String>();
        for (String line : configured.lines().toList()) {
            String path = line.strip();
            if (path.isEmpty()) { continue; }
            if (windows) { path = path.replace('\\', '/'); }
            String sourceDistro = Paths.distro(path);
            if (!distro.isBlank()) {
                if (!sourceDistro.isBlank() && !sourceDistro.equalsIgnoreCase(distro)) {
                    throw new IllegalArgumentException("Writable folder belongs to another WSL distribution: " + line.strip());
                }
                path = Paths.linux(path);
            }
            boolean absolute = windows && distro.isBlank()
                ? path.matches("^[A-Za-z]:/.*") || path.matches("^//[^/]+/[^/]+(?:/.*)?$")
                : path.startsWith("/") && !path.startsWith("//");
            if (!absolute || path.indexOf('\0') >= 0) { throw new IllegalArgumentException("Use an absolute path for each writable folder: " + line.strip()); }
            if (!path.equals("/") && !path.matches("^[A-Za-z]:/$")) { path = path.replaceAll("/+$", ""); }
            folders.add(path);
        }
        return List.copyOf(folders);
    }
    /** Thread settings cover starts, resumes, forks, goals, and automatic continuations. */
    public static void configureThread(JsonObject params, List<String> folders) {
        var config = obj(params, "config").deepCopy();
        config.add("sandbox_workspace_write.writable_roots", GSON.toJsonTree(folders));
        params.add("config", config);
    }
    /** Keep the selected checkout writable alongside the project's extra folders. */
    public static JsonObject policy(String permissions, String cwd, List<String> folders) {
        if (permissions.equals("read")) { return object("type", "readOnly"); }
        var roots = new LinkedHashSet<String>(); roots.add(cwd); roots.addAll(folders);
        return object("type", "workspaceWrite", "writableRoots", roots, "networkAccess", false, "excludeTmpdirEnvVar", false, "excludeSlashTmp", false);
    }
}
