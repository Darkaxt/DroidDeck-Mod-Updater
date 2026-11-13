package org.darkaxt.revamped.updater;

import android.content.Context;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.*;

/**
 * A verified Warhammer Survivors Tweaks release: the same zip players install on Windows, plus a
 * file manifest. Every member is checked and stored by content hash before anything is installed.
 */
final class WarhammerRelease {
    static final String REPOSITORY = "Darkaxt/DroidDeck-Mod-Updater";
    static final String TAG_PREFIX = "warhammer-survivors-v";
    static final String PRODUCT = "WHSurvivorsTweaks";
    /** Proton must load MelonLoader's version.dll proxy instead of Wine's builtin one. */
    static final String LAUNCH = "WINEDLLOVERRIDES=\"version=n,b\" %command%";
    final String version, notes, source, launchOptions;
    final JSONObject files;
    final File payload;

    WarhammerRelease(String version, String notes, String source, String launchOptions, JSONObject files, File payload) {
        this.version = version; this.notes = notes; this.source = source; this.launchOptions = launchOptions; this.files = files; this.payload = payload;
    }

    File member(String sha256) { return new File(payload, sha256); }

    /** Members the updater may write. Game files, saves and settings are never part of a payload. */
    static boolean installable(String path) {
        SafGame.safe(path);
        if (path.endsWith(".revamped-next") || path.endsWith(".revamped-previous")) return false;
        return path.equals("version.dll") || path.equals("WHSurvivorsTweaks.txt") || path.startsWith("MelonLoader/") || path.startsWith("Mods/") || path.startsWith("UserLibs/") || path.startsWith("Plugins/");
    }

    private static HttpsURLConnection connect(String url) throws Exception {
        SafGame.check(url.startsWith("https://"), "GitHub returned a non-HTTPS download.");
        HttpsURLConnection connection = (HttpsURLConnection)new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", "DroidDeck-Mod-Updater/" + BuildInfo.VERSION);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        return connection;
    }

    private static void download(JSONObject asset, String expectedPrefix, File target, long limit) throws Exception {
        String digest = asset.getString("digest"); long size = asset.getLong("size");
        SafGame.check(digest.matches("sha256:[a-f0-9]{64}") && asset.getString("state").equals("uploaded") && size <= limit, "GitHub has not supplied a complete package checksum.");
        String url = asset.getString("browser_download_url");
        SafGame.check(url.startsWith(expectedPrefix), "The asset is outside the expected release.");
        HttpsURLConnection connection = connect(url);
        try {
            SafGame.check(connection.getResponseCode() == 200, "Package download returned HTTP " + connection.getResponseCode());
            try (InputStream stream = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                long count = 0; byte[] buffer = new byte[65536]; int read;
                while ((read = stream.read(buffer)) != -1) {
                    count += read; SafGame.check(count <= size, "Download exceeds GitHub's recorded size."); output.write(buffer, 0, read);
                }
                SafGame.check(count == size, "The package download is incomplete.");
            }
        } finally { connection.disconnect(); }
        try (InputStream stream = new FileInputStream(target)) {
            SafGame.check(SafGame.hash(stream).equals(digest.substring(7)), "GitHub package checksum mismatch. Nothing was installed.");
        }
    }

    static WarhammerRelease fetch(Context context, GitHubRelease.Progress progress) throws Exception {
        progress.show("Checking GitHub releases…");
        HttpsURLConnection api = connect("https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=30");
        JSONArray releases;
        try {
            SafGame.check(api.getResponseCode() == 200, "GitHub returned HTTP " + api.getResponseCode() + ". Check the connection and try again.");
            try (InputStream stream = api.getInputStream()) { releases = new JSONArray(new String(SafGame.bytes(stream), "UTF-8")); }
        } finally { api.disconnect(); }
        JSONObject selected = null, zipAsset = null, manifestAsset = null; String version = null;
        for (int i = 0; i < releases.length() && selected == null; i++) {
            JSONObject release = releases.getJSONObject(i); String tag = release.getString("tag_name");
            if (release.getBoolean("draft") || release.getBoolean("prerelease") || !tag.matches(TAG_PREFIX.replace("-", "\\-") + "[0-9]+\\.[0-9]+\\.[0-9]+")) continue;
            String candidate = tag.substring(TAG_PREFIX.length()); JSONObject zip = null, manifest = null;
            JSONArray assets = release.getJSONArray("assets");
            for (int n = 0; n < assets.length(); n++) {
                JSONObject asset = assets.getJSONObject(n); String name = asset.getString("name");
                if (name.equals(PRODUCT + "-" + candidate + ".zip")) zip = asset;
                if (name.equals(PRODUCT + "-" + candidate + ".json")) manifest = asset;
            }
            if (zip != null && manifest != null) { selected = release; zipAsset = zip; manifestAsset = manifest; version = candidate; }
        }
        SafGame.check(selected != null, "No published Warhammer Survivors Tweaks package was found.");
        String prefix = "https://github.com/" + REPOSITORY + "/releases/download/" + TAG_PREFIX + version + "/";
        File manifestFile = new File(context.getCacheDir(), "warhammer-release.json"), archive = new File(context.getCacheDir(), "warhammer-release.zip");
        download(manifestAsset, prefix, manifestFile, 1 << 20);
        JSONObject manifest;
        try (InputStream stream = new FileInputStream(manifestFile)) { manifest = new JSONObject(new String(SafGame.bytes(stream), "UTF-8")); }
        SafGame.check(manifest.getInt("schema") == 1 && manifest.getString("product").equals(PRODUCT) && manifest.getString("version").equals(version), "Unsupported Warhammer Survivors package contract.");
        SafGame.check(GitHubRelease.compareVersions(BuildInfo.VERSION, manifest.getString("minimum_updater")) >= 0, "This release needs a newer DroidDeck Mod Updater.");
        SafGame.check(zipAsset.getString("digest").equals("sha256:" + manifest.getString("archive_sha256")), "The package manifest does not describe this archive.");
        progress.show("Downloading Warhammer Survivors Tweaks " + version + "…");
        download(zipAsset, prefix, archive, 200L << 20);
        progress.show("Verifying package contents…");
        JSONObject files = manifest.getJSONObject("files");
        for (Iterator<String> it = files.keys(); it.hasNext();) {
            String name = it.next();
            SafGame.check(installable(name) && files.getString(name).matches("[a-f0-9]{64}"), "Unexpected package member: " + name);
        }
        SafGame.check(files.has("version.dll") && files.has("Mods/" + PRODUCT + ".dll"), "The package lacks the loader or the mod.");
        File payload = new File(context.getCacheDir(), "warhammer-payload");
        delete(payload); SafGame.check(payload.mkdirs(), "Cannot prepare the verified payload.");
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) { SafGame.safe(entry.getName().substring(0, entry.getName().length() - 1)); continue; }
                String name = SafGame.safe(entry.getName());
                SafGame.check(files.has(name) && seen.add(name), "Unexpected or duplicate package member: " + name);
                String expected = files.getString(name); File target = new File(payload, expected);
                if (!target.exists()) {
                    File partial = new File(payload, expected + ".part");
                    try (InputStream stream = zip.getInputStream(entry); OutputStream output = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[65536]; int read; while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
                    }
                    try (InputStream stream = new FileInputStream(partial)) { SafGame.check(SafGame.hash(stream).equals(expected), "Changed package member: " + name); }
                    SafGame.check(partial.renameTo(target), "Cannot store the verified payload.");
                }
            }
        }
        SafGame.check(seen.size() == files.length(), "The package file inventory is incomplete.");
        archive.delete(); manifestFile.delete();
        return new WarhammerRelease(version, selected.optString("body", ""), manifest.optString("source_commit", ""), manifest.getString("launch_options"), files, payload);
    }

    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
