package org.darkaxt.revamped.updater;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;

/**
 * Installs and updates Warhammer Survivors Tweaks (MelonLoader + mod) in a DroidDeck Steam game folder.
 * Ownership is recorded per file: files this updater created may be replaced or removed later; identical
 * files that were already present (for example MelonLoader from another mod) are shared and never removed.
 * The whole plan is journaled in app storage before the first change, so an interruption can be finished.
 */
final class WarhammerEngine {
    static final String FOLDER = "Warhammer Survivors", EXE = "WarhammerSurvivors.exe";
    /** Steam app id (appmanifest_3669620.acf). */
    static final int APP_ID = 3669620;
    static final String RECORD = "UserData/WHSurvivorsTweaks.install.json", MOD = "Mods/WHSurvivorsTweaks.dll";
    /** The mod's own files: a manual (zip) installation of an older version may be replaced. */
    private static final Set<String> ADOPTABLE = new HashSet<>(Arrays.asList(MOD, "WHSurvivorsTweaks.txt"));
    private final Context context;
    private final File journal, store;

    WarhammerEngine(Context context) {
        this.context = context;
        journal = new File(context.getFilesDir(), "warhammer-transaction.json");
        store = new File(context.getFilesDir(), "warhammer-payload");
    }

    boolean pending() { return journal.exists(); }

    static List<SafGame> detect(Context context, Uri tree) throws Exception {
        return GameLocator.detect(context, tree, FOLDER, candidate -> candidate.exists(EXE) && candidate.exists("GameAssembly.dll"),
            "Warhammer Survivors was not found. Install it in DroidDeck's Steam first, then connect the DroidDeck folder.");
    }

    private static JSONObject json(byte[] bytes) throws Exception { return new JSONObject(new String(bytes, "UTF-8")); }
    private static byte[] read(File file) throws Exception { try (InputStream stream = new FileInputStream(file)) { return SafGame.bytes(stream); } }
    private static void write(File file, byte[] bytes) throws Exception {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream stream = atomic.startWrite();
        try { stream.write(bytes); atomic.finishWrite(stream); } catch (Exception failure) { atomic.failWrite(stream); throw failure; }
    }

    /** The installation record, or null when this updater has not installed the mod in this folder. */
    static JSONObject installed(SafGame game) throws Exception {
        if (!game.exists(RECORD)) return null;
        JSONObject data = json(game.read(RECORD));
        SafGame.check(data.getString("product").equals(WarhammerRelease.PRODUCT), "Invalid Warhammer Survivors installation record.");
        GitHubRelease.compareVersions(data.getString("version"), "0.0.0");
        JSONArray rows = data.getJSONArray("files"); Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); String name = row.getString("path");
            SafGame.check(WarhammerRelease.installable(name) && seen.add(name) && row.get("owned") instanceof Boolean && row.getString("sha256").matches("[a-f0-9]{64}"), "Invalid Warhammer Survivors installation record.");
        }
        return data;
    }

    private static JSONObject op(String path, String old, String now) throws Exception {
        return new JSONObject().put("path", path).put("old", old == null ? JSONObject.NULL : old).put("new", now == null ? JSONObject.NULL : now);
    }

    void deploy(SafGame game, WarhammerRelease release, GitHubRelease.Progress progress) throws Exception {
        SafGame.check(!pending(), "Finish the interrupted installation first.");
        game = new SafGame(context.getContentResolver(), game.tree, game.rootId);
        SafGame.check(game.exists(EXE), "The connected folder is no longer a Warhammer Survivors installation.");
        JSONObject before = installed(game);
        if (before != null) SafGame.check(GitHubRelease.compareVersions(release.version, before.getString("version")) >= 0, "The installed mod is newer than this release. It was preserved.");
        Map<String, JSONObject> prior = new HashMap<>();
        if (before != null) { JSONArray rows = before.getJSONArray("files"); for (int i = 0; i < rows.length(); i++) prior.put(rows.getJSONObject(i).getString("path"), rows.getJSONObject(i)); }
        List<String> names = new ArrayList<>(); for (Iterator<String> it = release.files.keys(); it.hasNext();) names.add(it.next());
        Collections.sort(names);
        JSONArray ops = new JSONArray(), rows = new JSONArray(); int count = 0;
        for (String path : names) {
            String want = release.files.getString(path); JSONObject row = prior.remove(path);
            String current = game.exists(path) ? game.hash(path) : null;
            boolean owned;
            if (want.equals(current)) owned = row != null ? row.getBoolean("owned") : ADOPTABLE.contains(path);
            else if (current == null) { owned = true; ops.put(op(path, null, want)); }
            else if (row != null && row.getBoolean("owned")) {
                SafGame.check(current.equals(row.getString("sha256")), "A mod file changed since it was installed; nothing was changed: " + path);
                owned = true; ops.put(op(path, current, want));
            } else if (row == null && ADOPTABLE.contains(path)) { owned = true; ops.put(op(path, current, want)); }
            else throw new IllegalStateException("A different version of " + path + " is already installed (another MelonLoader version or mod?). Nothing was changed.");
            rows.put(new JSONObject().put("path", path).put("sha256", want).put("owned", owned));
            if (++count % 20 == 0) progress.show("Checking game folder " + count + " / " + names.size());
        }
        for (JSONObject row : prior.values()) {
            String path = row.getString("path");
            if (row.getBoolean("owned") && game.exists(path) && game.hash(path).equals(row.getString("sha256"))) ops.put(op(path, row.getString("sha256"), null));
        }
        JSONObject record = new JSONObject().put("product", WarhammerRelease.PRODUCT).put("version", release.version).put("source_commit", release.source)
            .put("launch_options", release.launchOptions).put("files", rows);
        byte[] recordBytes = (record.toString(2) + "\n").getBytes("UTF-8");
        String oldRecord = game.exists(RECORD) ? game.hash(RECORD) : null, newRecord = SafGame.hash(recordBytes);
        if (!newRecord.equals(oldRecord)) ops.put(op(RECORD, oldRecord, newRecord));
        for (int i = 0; i < ops.length(); i++) {
            String path = ops.getJSONObject(i).getString("path");
            SafGame.check(!game.exists(path + ".revamped-next") && !game.exists(path + ".revamped-previous"), "An unrecorded installation staging file exists; it was preserved: " + path);
        }
        // Commit every payload byte to private storage before recording the plan.
        WarhammerRelease.delete(store); SafGame.check(store.mkdirs(), "Cannot prepare private installation storage.");
        for (int i = 0; i < ops.length(); i++) {
            JSONObject step = ops.getJSONObject(i); if (step.isNull("new") || step.getString("path").equals(RECORD)) continue;
            File target = new File(store, step.getString("new"));
            if (!target.exists()) write(target, read(release.member(step.getString("new"))));
        }
        write(new File(store, newRecord), recordBytes);
        JSONObject expected = new JSONObject(); for (int i = 0; i < rows.length(); i++) expected.put(rows.getJSONObject(i).getString("path"), rows.getJSONObject(i).getString("sha256"));
        expected.put(RECORD, newRecord);
        write(journal, new JSONObject().put("tree", game.tree.toString()).put("root_id", game.rootId).put("version", release.version).put("source_commit", release.source)
            .put("ops", ops).put("files", expected).toString().getBytes("UTF-8"));
        finish(progress);
    }

    private static void activate(SafGame game, String path, String oldHash, String newHash, byte[] bytes) throws Exception {
        SafGame.check(SafGame.hash(bytes).equals(newHash), "Private installation payload changed; staging was preserved.");
        String next = path + ".revamped-next", previous = path + ".revamped-previous";
        if (game.exists(path) && game.hash(path).equals(newHash)) {
            if (game.exists(next)) game.deleteExpected(next, newHash);
            return;
        }
        if (game.exists(path)) SafGame.check(oldHash != null && game.hash(path).equals(oldHash) && !game.exists(previous), "A game file changed during installation; originals were preserved: " + path);
        else SafGame.check(oldHash == null ? !game.exists(previous) : game.exists(previous) && game.hash(previous).equals(oldHash), "An interrupted original is missing; staging was preserved: " + path);
        if (game.exists(next) && !game.hash(next).equals(newHash)) {
            // A killed write can leave an exact prefix of the recorded payload; anything else is preserved.
            byte[] partial = game.read(next);
            SafGame.check(partial.length < bytes.length && Arrays.equals(partial, Arrays.copyOf(bytes, partial.length)), "An interrupted staging file changed; it was preserved: " + next);
            game.deleteExpected(next, SafGame.hash(partial));
        }
        if (!game.exists(next)) game.putNew(next, bytes);
        if (game.exists(path)) game.rename(path, previous);
        game.rename(next, path);
        SafGame.check(game.hash(path).equals(newHash), "Installed file verification failed; original retained for recovery: " + path);
    }

    void finish(GitHubRelease.Progress progress) throws Exception {
        SafGame.check(pending(), "There is no interrupted installation.");
        JSONObject record = json(read(journal)); JSONArray ops = record.getJSONArray("ops");
        Set<String> owned = new HashSet<>(); for (int i = 0; i < ops.length(); i++) owned.add(ops.getJSONObject(i).getString("path"));
        SafGame game = new SafGame(context.getContentResolver(), Uri.parse(record.getString("tree")), record.getString("root_id")).owning(owned);
        for (int i = 0; i < ops.length(); i++) {
            JSONObject step = ops.getJSONObject(i); String path = step.getString("path");
            String oldHash = step.isNull("old") ? null : step.getString("old");
            // A removed mod file that someone changed since installation is kept.
            if (step.isNull("new")) { if (game.exists(path) && game.hash(path).equals(oldHash)) game.deleteExpected(path, oldHash); }
            else {
                int slash = path.lastIndexOf('/'); if (slash > 0) game.mkdirs(path.substring(0, slash));
                activate(game, path, oldHash, step.getString("new"), read(new File(store, step.getString("new"))));
            }
            progress.show("Installing files " + (i + 1) + " / " + ops.length());
        }
        JSONObject files = record.getJSONObject("files"); int count = 0;
        for (Iterator<String> it = files.keys(); it.hasNext();) {
            String name = it.next(); SafGame.check(game.hash(name).equals(files.getString(name)), "Installed file verification failed: " + name);
            if (++count % 20 == 0) progress.show("Verifying installed files " + count + " / " + files.length());
        }
        for (int i = 0; i < ops.length(); i++) {
            JSONObject step = ops.getJSONObject(i); String path = step.getString("path");
            SafGame.check(!game.exists(path + ".revamped-next"), "Installation staging remains: " + path);
            if (!step.isNull("old") && !step.isNull("new")) game.deleteExpected(path + ".revamped-previous", step.getString("old"));
        }
        JSONObject installed = installed(game);
        SafGame.check(installed != null && installed.getString("version").equals(record.getString("version")), "Installed version read-back failed.");
        write(new File(context.getFilesDir(), "warhammer-last-install.json"), new JSONObject().put("version", record.getString("version")).put("source_commit", record.getString("source_commit"))
            .put("changed_files", ops.length()).put("verified_files", count).put("completed_at", System.currentTimeMillis()).toString(2).getBytes("UTF-8"));
        new AtomicFile(journal).delete(); WarhammerRelease.delete(store);
    }
}
