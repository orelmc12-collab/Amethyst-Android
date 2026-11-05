package net.kdt.pojavlaunch.volt;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Volt Launcher for Android: runs right before Minecraft starts.
 *  1. Installs the Volt default mods (mobile-safe set) the first time you play a Fabric/Quilt version.
 *  2. Pins the featured servers from sponsored.json (same file as the PC launcher) in the Multiplayer list.
 * Everything is best-effort: if there's no internet, the game just starts normally.
 */
public final class VoltSetup {
    private VoltSetup() {}

    private static final String TAG = "VoltSetup";
    private static final String UA = "VoltLauncher-Android/1.0 (github.com/orelmc12-collab/VoltLauncher)";
    private static final String SPONSORED_URL = "https://raw.githubusercontent.com/orelmc12-collab/VoltLauncher/main/sponsored.json";

    /** Mobile-safe defaults: no Sodium/Iris/shaders (they don't work with Android renderers). */
    private static final String[] DEFAULT_MODS = {
        "fabric-api", "lithium", "ferrite-core", "modernfix", "entityculling", "dynamic-fps",
        "appleskin", "modmenu", "xaeros-minimap",
    };

    private static final Pattern FABRIC_ID = Pattern.compile("^(fabric|quilt)-loader-[^-]+-(.+)$");

    public static void beforeLaunch(File gameDir, String versionId) {
        try { installDefaultMods(gameDir, versionId); } catch (Throwable t) { Log.w(TAG, "default mods", t); }
        try { pinFeaturedServers(gameDir); } catch (Throwable t) { Log.w(TAG, "featured servers", t); }
    }

    // ------------------------------------------------------------ default mods

    static void installDefaultMods(File gameDir, String versionId) throws Exception {
        Matcher m = FABRIC_ID.matcher(versionId == null ? "" : versionId);
        if (!m.matches()) return; // vanilla / forge: nothing to add
        String loader = m.group(1), mc = m.group(2);
        File modsDir = new File(gameDir, "mods");
        File marker = new File(modsDir, ".volt-defaults-" + mc);
        if (marker.exists()) return; // only once per version, so mods you delete stay deleted
        if (!modsDir.exists() && !modsDir.mkdirs()) return;

        Set<String> have = new HashSet<>();
        File[] existing = modsDir.listFiles();
        if (existing != null) for (File f : existing) have.add(f.getName().toLowerCase());

        List<String> installed = new ArrayList<>();
        String loaders = URLEncoder.encode(loader.equals("quilt") ? "[\"quilt\",\"fabric\"]" : "[\"fabric\"]", "UTF-8");
        String versions = URLEncoder.encode("[\"" + mc + "\"]", "UTF-8");
        for (String slug : DEFAULT_MODS) {
            try {
                JSONArray vs = new JSONArray(get("https://api.modrinth.com/v2/project/" + slug + "/version?loaders=" + loaders + "&game_versions=" + versions));
                if (vs.length() == 0) continue;
                JSONObject pick = vs.getJSONObject(0);
                for (int i = 0; i < vs.length(); i++) if ("release".equals(vs.getJSONObject(i).optString("version_type"))) { pick = vs.getJSONObject(i); break; }
                JSONArray files = pick.getJSONArray("files");
                JSONObject file = files.getJSONObject(0);
                for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).optBoolean("primary")) { file = files.getJSONObject(i); break; }
                String name = file.getString("filename");
                if (have.contains(name.toLowerCase())) continue;
                download(file.getString("url"), new File(modsDir, name));
                installed.add(name);
            } catch (Exception e) { Log.w(TAG, "skip " + slug + ": " + e.getMessage()); }
        }
        StringBuilder sb = new StringBuilder();
        for (String n : installed) sb.append(n).append('\n');
        write(marker, sb.toString().getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "Installed default mods for " + mc + ": " + installed);
    }

    // ------------------------------------------------------------ featured servers

    static void pinFeaturedServers(File gameDir) throws Exception {
        File cache = new File(gameDir, ".volt-sponsored.json");
        String json;
        try { json = get(SPONSORED_URL + "?t=" + System.currentTimeMillis()); write(cache, json.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { if (!cache.exists()) return; json = new String(ServerList.readFile(cache), StandardCharsets.UTF_8); }

        JSONObject root = new JSONObject(json);
        JSONArray list = root.optJSONArray("servers");
        if (list == null) return;
        List<String[]> featured = new ArrayList<>();
        for (int i = 0; i < list.length() && i < 12; i++) {
            JSONObject s = list.getJSONObject(i);
            if (s.has("name") && s.has("address")) featured.add(new String[]{"§e★ §f" + s.getString("name"), s.getString("address").trim()});
        }
        ServerList.pin(gameDir, featured);
    }

    // ------------------------------------------------------------ http helpers

    static String get(String url) throws Exception {
        return new String(fetch(url), StandardCharsets.UTF_8);
    }

    static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    static void download(String url, File dest) throws Exception {
        File tmp = new File(dest.getPath() + ".part");
        write(tmp, fetch(url));
        if (!tmp.renameTo(dest)) throw new Exception("rename failed");
    }

    static void write(File f, byte[] data) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
}
