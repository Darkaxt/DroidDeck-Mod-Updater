package org.darkaxt.revamped.updater;

import android.content.Context;
import android.net.Uri;
import java.util.*;
import java.util.regex.*;

/**
 * Sets one game's Steam launch options in DroidDeck's Steam, the way DroidDeck's own Launch tab does while
 * Steam is not running: apps/&lt;appid&gt;/LaunchOptions in userdata/&lt;account&gt;/config/localconfig.vdf.
 * Only that value changes; the previous file is kept as localconfig.vdf.revamped-previous.
 */
final class SteamLaunchOptions {
    static final String CONFIG = "localconfig.vdf";
    private static final long STEAM_ID_BASE = 76561197960265728L;
    private static final String[] PATH = {"UserLocalConfigStore", "Software", "Valve", "Steam", "apps"};

    /** Outcome shown to the player. */
    enum State { SET, ALREADY, OTHER_OVERRIDE, NO_STEAM }

    /** DroidDeck's Steam folder inside the granted tree, or null when the grant is not DroidDeck's. */
    static SafGame steam(Context context, Uri tree) throws Exception {
        if (!"com.droiddeck.launcher.documents".equals(tree.getAuthority())) return null;
        String app = context.getPackageManager().getApplicationInfo("com.droiddeck.launcher", 0).dataDir;
        return GameLocator.at(context, new SafGame(context.getContentResolver(), tree), app + "/files/linuxfs/root/.local/share/Steam");
    }

    /** userdata/&lt;account&gt;/config of the most recent Steam login, else the only account folder. */
    static String accountConfig(SafGame steam) throws Exception {
        if (steam.exists("config/loginusers.vdf")) {
            String users = new String(steam.read("config/loginusers.vdf"), "UTF-8");
            Matcher user = Pattern.compile("\"(7656119[0-9]{10})\"\\s*\\{([^{}]*)\\}").matcher(users);
            String best = null; long bestTime = -1; boolean bestRecent = false;
            while (user.find()) {
                boolean recent = Pattern.compile("\"MostRecent\"\\s*\"1\"", Pattern.CASE_INSENSITIVE).matcher(user.group(2)).find();
                Matcher stamp = Pattern.compile("\"Timestamp\"\\s*\"([0-9]+)\"", Pattern.CASE_INSENSITIVE).matcher(user.group(2));
                long time = stamp.find() ? Long.parseLong(stamp.group(1)) : 0;
                if (best == null || (recent && !bestRecent) || (recent == bestRecent && time > bestTime)) { best = user.group(1); bestTime = time; bestRecent = recent; }
            }
            if (best != null) {
                String folder = "userdata/" + (Long.parseLong(best) - STEAM_ID_BASE) + "/config";
                if (steam.exists(folder)) return folder;
            }
        }
        List<String> accounts = new ArrayList<>();
        for (String name : steam.childDirectories("userdata")) if (name.matches("[1-9][0-9]*")) accounts.add(name);
        return accounts.size() == 1 && steam.exists("userdata/" + accounts.get(0) + "/config") ? "userdata/" + accounts.get(0) + "/config" : null;
    }

    /** The options to store, given the current ones, or null when another DLL override is already chosen. */
    static String merged(String current, String wanted, String marker) {
        String value = current == null ? "" : current.trim();
        if (value.contains(marker)) return value;
        if (value.contains("WINEDLLOVERRIDES")) return null;
        if (value.isEmpty()) return wanted;
        String prefix = wanted.replace("%command%", "").trim();
        return value.contains("%command%") ? prefix + " " + value : wanted + " " + value;
    }

    /** Reads and, if needed, sets the launch options. Never changes anything but the one value. */
    static State apply(Context context, SafGame steam, int appId, String wanted, String marker, boolean write) throws Exception {
        String folder = steam == null ? null : accountConfig(steam);
        if (folder == null || !steam.exists(folder + "/" + CONFIG)) return State.NO_STEAM;
        String path = folder + "/" + CONFIG;
        byte[] before = steam.read(path); String text = new String(before, "UTF-8");
        Vdf vdf = new Vdf(text);
        String current = vdf.launchOptions(appId);
        String target = merged(current, wanted, marker);
        if (target == null) return State.OTHER_OVERRIDE;
        if (target.equals(current == null ? "" : current.trim()) && current != null) return State.ALREADY;
        if (!write) return State.SET;
        byte[] after = vdf.withLaunchOptions(appId, target).getBytes("UTF-8");
        Set<String> owned = new HashSet<>(Collections.singleton(path));
        SafGame writer = new SafGame(context.getContentResolver(), steam.tree, steam.rootId).owning(owned);
        String next = path + ".revamped-next", previous = path + ".revamped-previous";
        if (writer.exists(next)) writer.deleteExpected(next, writer.hash(next));
        writer.putNew(next, after);
        SafGame.check(writer.hash(path).equals(SafGame.hash(before)), "Steam changed its settings meanwhile. Close DroidDeck and try again.");
        if (writer.exists(previous)) writer.deleteExpected(previous, writer.hash(previous));
        writer.rename(path, previous);
        writer.rename(next, path);
        SafGame check = new SafGame(context.getContentResolver(), steam.tree, steam.rootId);
        SafGame.check(target.equals(new Vdf(new String(check.read(path), "UTF-8")).launchOptions(appId)), "The launch option could not be verified.");
        return State.SET;
    }

    /** Minimal Valve KeyValues text reader that edits values in place and keeps everything else byte for byte. */
    static final class Vdf {
        private final String text;
        private int pos;
        Vdf(String text) { this.text = text; }

        private static final class Node { String key; int valueStart = -1, valueEnd = -1, open = -1, close = -1; List<Node> children; }

        private String token(int[] span) {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c)) { pos++; continue; }
                if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') { while (pos < text.length() && text.charAt(pos) != '\n') pos++; continue; }
                if (c == '{' || c == '}') { span[0] = pos; span[1] = ++pos; return String.valueOf(c); }
                SafGame.check(c == '"', "Unrecognised Steam settings file.");
                int start = ++pos; StringBuilder value = new StringBuilder();
                while (true) {
                    SafGame.check(pos < text.length(), "Unrecognised Steam settings file.");
                    char d = text.charAt(pos);
                    if (d == '\\' && pos + 1 < text.length()) { value.append(text.charAt(pos + 1)); pos += 2; continue; }
                    if (d == '"') break;
                    value.append(d); pos++;
                }
                span[0] = start; span[1] = pos++;
                return "\"" + value;
            }
            return null;
        }

        private List<Node> section(boolean nested) {
            List<Node> nodes = new ArrayList<>(); int[] span = new int[2];
            while (true) {
                String key = token(span);
                if (key == null) { SafGame.check(!nested, "Unrecognised Steam settings file."); return nodes; }
                if (key.equals("}")) { SafGame.check(nested, "Unrecognised Steam settings file."); pos = span[0]; return nodes; }
                SafGame.check(key.startsWith("\""), "Unrecognised Steam settings file.");
                Node node = new Node(); node.key = key.substring(1);
                String value = token(span);
                SafGame.check(value != null && !value.equals("}"), "Unrecognised Steam settings file.");
                if (value.equals("{")) {
                    node.open = span[0]; node.children = section(true);
                    SafGame.check("}".equals(token(span)), "Unrecognised Steam settings file."); node.close = span[0];
                } else { node.valueStart = span[0]; node.valueEnd = span[1]; }
                nodes.add(node);
            }
        }

        private static Node child(List<Node> nodes, String key, boolean section) {
            if (nodes == null) return null;
            for (Node node : nodes) if (node.key.equalsIgnoreCase(key) && (node.children != null) == section) return node;
            return null;
        }

        private List<Node> root() { pos = 0; return section(false); }

        String launchOptions(int appId) {
            Node node = null; List<Node> level = root();
            for (String key : PATH) { node = child(level, key, true); if (node == null) return null; level = node.children; }
            Node app = child(level, String.valueOf(appId), true);
            Node value = app == null ? null : child(app.children, "LaunchOptions", false);
            return value == null ? null : unescape(text.substring(value.valueStart, value.valueEnd));
        }

        String withLaunchOptions(int appId, String options) {
            List<Node> level = root(); Node parent = null;
            SafGame.check(child(level, PATH[0], true) != null, "Unrecognised Steam settings file.");
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            List<String> keys = new ArrayList<>(Arrays.asList(PATH)); keys.add(String.valueOf(appId));
            for (int i = 0; i < keys.size(); i++) {
                Node next = child(level, keys.get(i), true);
                if (next == null) {
                    String indent = indent(parent.close) + "\t";
                    StringBuilder insert = new StringBuilder();
                    for (int n = i; n < keys.size(); n++) insert.append(indent).append(repeat(n - i)).append('"').append(keys.get(n)).append('"').append(newline).append(indent).append(repeat(n - i)).append('{').append(newline);
                    insert.append(indent).append(repeat(keys.size() - i)).append("\"LaunchOptions\"\t\t\"").append(escape(options)).append('"').append(newline);
                    for (int n = keys.size() - 1; n >= i; n--) insert.append(indent).append(repeat(n - i)).append('}').append(newline);
                    int at = lineStart(parent.close);
                    return text.substring(0, at) + insert + text.substring(at);
                }
                parent = next; level = next.children;
            }
            Node value = child(level, "LaunchOptions", false);
            if (value != null) return text.substring(0, value.valueStart) + escape(options) + text.substring(value.valueEnd);
            int at = lineStart(parent.close);
            return text.substring(0, at) + indent(parent.close) + "\t\"LaunchOptions\"\t\t\"" + escape(options) + "\"" + newline + text.substring(at);
        }

        private int lineStart(int index) { int start = text.lastIndexOf('\n', index - 1) + 1; return text.substring(start, index).trim().isEmpty() ? start : index; }
        private String indent(int index) { int start = text.lastIndexOf('\n', index - 1) + 1; String before = text.substring(start, index); return before.trim().isEmpty() ? before : ""; }
        private static String repeat(int count) { StringBuilder value = new StringBuilder(); for (int i = 0; i < count; i++) value.append('\t'); return value.toString(); }
        static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
        static String unescape(String value) { return value.replaceAll("\\\\(.)", "$1"); }
    }
}
