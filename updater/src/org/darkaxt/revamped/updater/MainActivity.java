package org.darkaxt.revamped.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.List;

/**
 * One updater for several DroidDeck games. 20 Minutes Till Dawn keeps its Revamped update flow
 * (prepared installations, two owned files, one-time rune save reset). Warhammer Survivors installs
 * and updates MelonLoader plus Warhammer Survivors Tweaks directly from the phone.
 */
public final class MainActivity extends Activity {
    static final int PICK_FOLDER = 20;
    static final int FOLDER_BUTTON = 101, CHECK_BUTTON = 102, DEPLOY_BUTTON = 103, STATUS = 104, INSTALLED = 105, AVAILABLE = 106;
    static final int GAME_REVAMPED = 107, GAME_WARHAMMER = 108, COPY_BUTTON = 109;
    static final String REVAMPED = "revamped", WARHAMMER = "warhammer";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    Future<?> running;
    private String selected = REVAMPED;
    // 20 Minutes Till Dawn (Revamped)
    private UpdateEngine engine;
    private GitHubRelease release;
    private SafGame game;
    private String installedVersion;
    private int installedEpoch;
    // Warhammer Survivors
    private WarhammerEngine whEngine;
    private WarhammerRelease whRelease;
    private SafGame whGame;
    private String whInstalledVersion;
    /** Launch option state in DroidDeck's Steam; SET means it still needs setting. Null while unknown. */
    private SteamLaunchOptions.State whLaunch;

    private TextView status, installed, available, notes, location, headline, foot, gameLabel, launchText;
    private Button choose, check, deploy, tabRevamped, tabWarhammer, copy;
    private LinearLayout launchCard;
    private ProgressBar progress;
    private boolean busy, updatedHere;
    private boolean transitionNeeded() { return release != null && release.update.optInt("save_epoch",0)>installedEpoch; }
    private boolean wh() { return WARHAMMER.equals(selected); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = new UpdateEngine(this); whEngine = new WarhammerEngine(this);
        buildScreen();
        SharedPreferences prefs = getPreferences(0);
        String tree = prefs.getString("gameTree", null);
        if (tree != null) game = new SafGame(getContentResolver(), Uri.parse(tree), prefs.getString("gameDocumentId", DocumentsContract.getTreeDocumentId(Uri.parse(tree))));
        String whTree = prefs.getString("whTree", null);
        if (whTree != null) whGame = new SafGame(getContentResolver(), Uri.parse(whTree), prefs.getString("whDocumentId", DocumentsContract.getTreeDocumentId(Uri.parse(whTree))));
        selected = engine.pending() ? REVAMPED : whEngine.pending() ? WARHAMMER : prefs.getString("selectedGame", REVAMPED);
        showGame();
    }

    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density * value + .5f); }

    private TextView text(LinearLayout parent, String value, float size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }

    private Button button(LinearLayout parent, String value, int id) {
        Button view = new Button(this); view.setId(id); view.setText(value); view.setAllCaps(false); view.setTextSize(17);
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xfffc525f));
        view.setTextColor(0xff242032);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(56)); layout.topMargin = dp(12);
        parent.addView(view, layout); return view;
    }

    private Button tab(LinearLayout row, String label, int id, String key) {
        Button view = new Button(this); view.setId(id); view.setText(label); view.setAllCaps(false); view.setTextSize(14);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(44), 1); if (id == GAME_WARHAMMER) layout.leftMargin = dp(8);
        row.addView(view, layout); view.setOnClickListener(v -> selectGame(key)); return view;
    }

    private void buildScreen() {
        int pad = dp(24);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout root = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams)column.getLayoutParams();
                layout.width = Math.min(MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight(), dp(640));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        root.setBackgroundColor(0xff242032);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(pad, dp(18), pad, dp(4));
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView title = new TextView(this); title.setText("DroidDeck Mod Updater"); title.setTextSize(23); title.setTextColor(0xffffe3c3); title.setTypeface(Typeface.create("sans-serif-medium", 0));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        check = new Button(this); check.setId(CHECK_BUTTON); check.setText("↻"); check.setTextSize(27); check.setTextColor(0xffffe3c3); check.setContentDescription("Check GitHub for updates");
        check.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00242032));
        header.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48))); check.setOnClickListener(view -> checkGitHub());
        LinearLayout tabs = new LinearLayout(this); tabs.setPadding(pad, dp(4), pad, dp(4)); column.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        tabRevamped = tab(tabs, "20 Minutes Till Dawn", GAME_REVAMPED, REVAMPED); tabWarhammer = tab(tabs, "Warhammer Survivors", GAME_WARHAMMER, WARHAMMER);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(pad, dp(12), pad, dp(12)); scroll.addView(content);
        LinearLayout banner = new LinearLayout(this); banner.setOrientation(LinearLayout.VERTICAL); banner.setPadding(dp(18), dp(16), dp(18), dp(16)); banner.setBackground(surface(0xff34453f, 14));
        content.addView(banner, new LinearLayout.LayoutParams(-1, -2));
        gameLabel = text(banner, "20 Minutes Till Dawn", 14, 0xffc0cabb);
        headline = text(banner, "Connect your game", 25, 0xffffe3c3); headline.setTypeface(Typeface.create("sans-serif-medium", 0));
        status = text(banner, "Connect DroidDeck once. The game folder is found automatically.", 15, 0xffe0e2d5); status.setId(STATUS); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(View.GONE); banner.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        LinearLayout versions = new LinearLayout(this); LinearLayout.LayoutParams versionsLayout = new LinearLayout.LayoutParams(-1, -2); versionsLayout.topMargin = dp(20); content.addView(versions, versionsLayout);
        installed = versionCard(versions, "Installed", INSTALLED, false); available = versionCard(versions, "Latest", AVAILABLE, true);
        launchCard = new LinearLayout(this); launchCard.setOrientation(LinearLayout.VERTICAL); launchCard.setPadding(dp(16), dp(12), dp(16), dp(12)); launchCard.setBackground(surface(0xff302c40, 12));
        LinearLayout.LayoutParams launchLayout = new LinearLayout.LayoutParams(-1, -2); launchLayout.topMargin = dp(20); content.addView(launchCard, launchLayout);
        text(launchCard, "One-time Steam setting (MelonLoader needs it under Proton)", 14, 0xffa9b5aa);
        launchText = text(launchCard, "Set automatically after installing (close DroidDeck first), or in Steam › Warhammer Survivors › Properties › Launch options:\n" + WarhammerRelease.LAUNCH, 15, 0xffffe3c3); launchText.setTextIsSelectable(true);
        copy = new Button(this); copy.setId(COPY_BUTTON); copy.setAllCaps(false); copy.setText("Copy launch option"); copy.setTextColor(0xfffc7580); copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00242032));
        launchCard.addView(copy, new LinearLayout.LayoutParams(-2, dp(48))); copy.setOnClickListener(view -> { if (whLaunch == SteamLaunchOptions.State.SET && whGame != null) execute(() -> progress(setLaunchOption(true, "Launch option set in DroidDeck's Steam. Start Warhammer Survivors from DroidDeck."))); else copyLaunchOption(); });
        TextView changes = text(content, "What’s new", 19, 0xffffe3c3); changes.setPadding(0, dp(28), 0, dp(8)); changes.setTypeface(Typeface.create("sans-serif-medium", 0));
        notes = text(content, "Check GitHub to see the latest changes.", 16, 0xffe3d8cf); notes.setLineSpacing(dp(4), 1); notes.setTextIsSelectable(true);
        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL); footer.setPadding(pad, dp(8), pad, dp(18)); column.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this); divider.setBackgroundColor(0xff574d5b); footer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout folder = new LinearLayout(this); folder.setGravity(Gravity.CENTER_VERTICAL); folder.setPadding(0, dp(10), 0, dp(8)); footer.addView(folder);
        ImageView icon = new ImageView(this);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon("com.droiddeck.launcher")); } catch (android.content.pm.PackageManager.NameNotFoundException missing) { icon.setImageResource(R.drawable.ic_launcher); }
        folder.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout folderText = new LinearLayout(this); folderText.setOrientation(LinearLayout.VERTICAL); folderText.setPadding(dp(12), 0, 0, 0); folder.addView(folderText, new LinearLayout.LayoutParams(0, -2, 1));
        text(folderText, "DroidDeck", 14, 0xffa9b5aa); location = text(folderText, "Not connected", 16, 0xffffe3c3);
        choose = new Button(this); choose.setId(FOLDER_BUTTON); choose.setAllCaps(false); choose.setText("Connect"); choose.setTextSize(15); choose.setTextColor(0xfffc7580); choose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00242032));
        folder.addView(choose, new LinearLayout.LayoutParams(dp(90), dp(52))); choose.setOnClickListener(view -> chooseFolder());
        deploy = button(footer, "Connect DroidDeck", DEPLOY_BUTTON); deploy.setOnClickListener(view -> primaryAction());
        foot = text(footer, "Updates are manual. Your saves stay in place.", 14, 0xffa9b5aa); foot.setGravity(Gravity.CENTER);
        setContentView(root);
    }

    private GradientDrawable surface(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private TextView versionCard(LinearLayout row, String label, int id, boolean latest) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(14)); card.setBackground(surface(0xff302c40, 12));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); if (latest) layout.leftMargin = dp(12); row.addView(card, layout);
        text(card, label, 14, 0xffa9b5aa); TextView value = text(card, "—", 26, latest ? 0xfffc7580 : 0xffffe3c3); value.setId(id); value.setTypeface(Typeface.create("sans-serif-medium", 0)); return value;
    }

    private void selectGame(String key) {
        if (busy || key.equals(selected)) return;
        selected = key; getPreferences(0).edit().putString("selectedGame", key).apply(); updatedHere = false;
        showGame();
    }

    /** Restores the selected game's remembered state; a DroidDeck grant made for one game is reused for the other. */
    private void showGame() {
        gameLabel.setText(wh() ? "Warhammer Survivors" : "20 Minutes Till Dawn");
        launchCard.setVisibility(wh() ? View.VISIBLE : View.GONE);
        boolean hasRelease = wh() ? whRelease != null : release != null;
        available.setText(!hasRelease ? "—" : wh() ? whRelease.version : release.version);
        notes.setText(!hasRelease ? "Check GitHub to see the latest changes." : cleanNotes(wh() ? whRelease.notes : release.notes));
        installed.setText("—"); location.setText("Not connected"); status.setText("Connect DroidDeck once. The game folder is found automatically.");
        SafGame current = wh() ? whGame : game;
        if (current != null) {
            location.setText(wh() ? WarhammerEngine.FOLDER : "20MinuteTillDawn");
            execute(() -> {
                refreshInstalled();
                boolean pending = wh() ? whEngine.pending() : engine.pending();
                progress(pending ? "The previous update has not finished. Resume its recorded deployment." : "Game connected. Check GitHub for an update.");
            });
        } else {
            SafGame other = wh() ? game : whGame;
            if (other != null && "com.droiddeck.launcher.documents".equals(other.tree.getAuthority())) {
                Uri tree = other.tree;
                execute(() -> { try { connectDetected(tree); } catch (Exception notFound) { progress(notFound.getMessage()); } });
            }
        }
        renderButtons();
    }

    private static String cleanNotes(String value) { return value.replace("**", "").replace("`", "").replaceAll("(?m)^- ", "• "); }

    private void checkGitHub() {
        execute(() -> {
            if (wh()) {
                if (whGame != null) refreshInstalled();
                WarhammerRelease fetched = WarhammerRelease.fetch(this, this::progress);
                runOnUiThread(() -> { whRelease = fetched; if (wh()) { available.setText(fetched.version); notes.setText(cleanNotes(fetched.notes)); } });
                progress("Package verified. Close the game in DroidDeck before installing.");
            } else {
                if (game != null) refreshInstalled();
                GitHubRelease fetched = GitHubRelease.fetch(this, this::progress);
                runOnUiThread(() -> { release = fetched; if (!wh()) { available.setText(fetched.version); notes.setText(cleanNotes(fetched.notes)); } });
                progress("Package verified. Close the game in DroidDeck before deploying.");
            }
        });
    }
    private void openDroidDeck() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.droiddeck.launcher");
        if (intent != null) startActivity(intent); else progress("DroidDeck is not installed.");
    }
    /** Reads (write=false) or sets the Steam launch option; returns the message for the player. Never fails the install. */
    private String setLaunchOption(boolean write, String done) {
        try {
            whLaunch = SteamLaunchOptions.apply(this, SteamLaunchOptions.steam(this, whGame.tree), WarhammerEngine.APP_ID, whRelease != null ? whRelease.launchOptions : WarhammerRelease.LAUNCH, "version=n,b", write);
            if (write && whLaunch == SteamLaunchOptions.State.SET) { whLaunch = SteamLaunchOptions.State.ALREADY; return done; }
        } catch (Exception failure) {
            whLaunch = null;
            return "Installed and verified, but the launch option could not be set (" + failure.getMessage() + "). Copy it into Steam › Properties.";
        }
        switch (whLaunch) {
            case ALREADY: return "Installed and verified. The launch option is already set. Open DroidDeck to play.";
            case OTHER_OVERRIDE: return "Installed and verified. Your Steam launch options already set WINEDLLOVERRIDES: add version=n,b to it in Steam › Properties.";
            case NO_STEAM: return "Installed and verified. Copy the launch option into Steam › Warhammer Survivors › Properties.";
            default: return done;
        }
    }
    private void copyLaunchOption() {
        String value = whRelease != null ? whRelease.launchOptions : WarhammerRelease.LAUNCH;
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Steam launch option", value));
        progress("Launch option copied. Paste it in Steam › Warhammer Survivors › Properties.");
    }
    private void primaryAction() {
        if (wh()) {
            if (whEngine.pending()) execute(() -> { whEngine.finish(this::progress); updatedHere = true; refreshInstalled(); progress(setLaunchOption(true, "Installed and verified. Launch option set in DroidDeck's Steam. Open DroidDeck to play.")); });
            else if (whGame == null) chooseFolder();
            else if (whRelease == null) checkGitHub();
            else if (whInstalledVersion != null && GitHubRelease.compareVersions(whRelease.version, whInstalledVersion) <= 0) openDroidDeck();
            else execute(() -> { whEngine.deploy(whGame, whRelease, this::progress); updatedHere = true; refreshInstalled(); progress(setLaunchOption(true, "Installed and verified. Launch option set in DroidDeck's Steam. Open DroidDeck to play.")); });
            return;
        }
        if (engine.pending()) execute(() -> { engine.finish(this::progress); updatedHere = true; refreshInstalled(); progress("Update verified. Open DroidDeck to play."); });
        else if (game == null) chooseFolder();
        else if (release == null) checkGitHub();
        else if (installedVersion != null && GitHubRelease.compareVersions(release.version, installedVersion) <= 0 && !transitionNeeded()) openDroidDeck();
        else execute(() -> { engine.deploy(game, release, this::progress); updatedHere = true; refreshInstalled(); progress("Update verified. Open DroidDeck to play."); });
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { getPackageManager().getApplicationInfo("com.droiddeck.launcher", 0); }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { progress("Install DroidDeck first."); return; }
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri("com.droiddeck.launcher.documents", "app"));
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData(); int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(tree, flags); }
        catch (SecurityException failure) { progress("Android did not grant lasting folder access. Connect DroidDeck again."); return; }
        execute(() -> connectDetected(tree));
    }

    private void connectDetected(Uri tree) throws Exception {
        boolean warhammer = wh();
        progress(warhammer ? "Finding Warhammer Survivors in DroidDeck…" : "Finding 20 Minutes Till Dawn in DroidDeck…");
        List<SafGame> matches = warhammer ? WarhammerEngine.detect(this, tree) : GameLocator.detect(this, tree);
        if (matches.size() == 1) connectGame(warhammer, matches.get(0));
        else runOnUiThread(() -> {
            String[] names = new String[matches.size()]; for (int i = 0; i < names.length; i++) names[i] = matches.get(i).rootId;
            new AlertDialog.Builder(this).setTitle("Choose the installed copy").setItems(names, (dialog, index) -> execute(() -> connectGame(warhammer, matches.get(index)))).show();
        });
    }

    private void connectGame(boolean warhammer, SafGame selectedGame) throws Exception {
        if (warhammer) {
            WarhammerEngine.installed(selectedGame);
            SafGame.check(getPreferences(0).edit().putString("whTree", selectedGame.tree.toString()).putString("whDocumentId", selectedGame.rootId).commit(), "Cannot remember the connected folder.");
            whGame = selectedGame;
        } else {
            engine.validateGame(selectedGame, this::progress);
            SafGame.check(getPreferences(0).edit().putString("gameTree", selectedGame.tree.toString()).putString("gameDocumentId", selectedGame.rootId).commit(), "Cannot remember the connected folder.");
            game = selectedGame;
        }
        if (warhammer != wh()) return;
        runOnUiThread(() -> location.setText(warhammer ? WarhammerEngine.FOLDER : "20MinuteTillDawn")); refreshInstalled(); progress("Game found and verified. Check GitHub for an update.");
    }

    private void refreshInstalled() throws Exception {
        // SafGame caches folder listings; installs write through their own instance, so re-read through a fresh one.
        if (wh()) {
            if (whGame != null) whGame = new SafGame(getContentResolver(), whGame.tree, whGame.rootId);
            JSONObject data = whGame == null ? null : WarhammerEngine.installed(whGame);
            whInstalledVersion = data == null ? null : data.getString("version");
            if (whGame != null) setLaunchOption(false, null);
            runOnUiThread(() -> { if (wh()) installed.setText(whInstalledVersion == null ? "None" : whInstalledVersion); });
            return;
        }
        game = new SafGame(getContentResolver(), game.tree, game.rootId);
        JSONObject data = UpdateEngine.installed(game);
        installedVersion = data.getString("version");
        installedEpoch = data.optInt("rune_save_epoch",0);
        runOnUiThread(() -> { if (!wh()) installed.setText(installedVersion); });
    }

    private void progress(String message) { runOnUiThread(() -> status.setText(message)); }
    private interface Work { void run() throws Exception; }
    private void execute(Work work) {
        if (busy) return;
        busy = true; renderButtons(); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        running = worker.submit(() -> {
            try { work.run(); }
            catch (Exception failure) { progress(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
            finally { runOnUiThread(() -> { busy = false; renderButtons(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }); }
        });
    }

    private void styleTab(Button view, boolean active) {
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active ? 0xff34453f : 0xff302c40));
        view.setTextColor(active ? 0xffffe3c3 : 0xffa9b5aa); view.setEnabled(!busy);
    }

    private void renderButtons() {
        styleTab(tabRevamped, !wh()); styleTab(tabWarhammer, wh());
        if (wh()) { renderWarhammer(); return; }
        boolean interrupted = engine.pending();
        choose.setEnabled(!busy && !interrupted); check.setEnabled(!busy && !interrupted);
        choose.setText(game == null ? "Connect" : "Change");
        boolean reset = transitionNeeded();
        boolean current = release != null && installedVersion != null && GitHubRelease.compareVersions(release.version, installedVersion) <= 0 && !reset;
        String action = interrupted ? "Finish interrupted update" : game == null ? "Connect DroidDeck" : release == null ? "Check GitHub" : current ? "Open DroidDeck" : (reset ? "Reset saves & install " : "Install ") + release.version;
        deploy.setText(action); deploy.setEnabled(!busy);
        headline.setText(interrupted ? "Finish your update" : game == null ? "Connect your game" : release == null ? "Game connected" : current ? "You’re up to date" : "Ready to update");
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        foot.setText(reset ? "One-time reset: this game's progress and Continue save will be deleted. Settings stay in place." : updatedHere ? "Update verified. Future updates preserve your new saves and settings." : "Close the game in DroidDeck before updating.");
    }

    private void renderWarhammer() {
        boolean interrupted = whEngine.pending();
        choose.setEnabled(!busy && !interrupted); check.setEnabled(!busy && !interrupted);
        choose.setText(whGame == null ? "Connect" : "Change");
        boolean current = whRelease != null && whInstalledVersion != null && GitHubRelease.compareVersions(whRelease.version, whInstalledVersion) <= 0;
        String action = interrupted ? "Finish interrupted installation" : whGame == null ? "Connect DroidDeck" : whRelease == null ? "Check GitHub" : current ? "Open DroidDeck" : (whInstalledVersion == null ? "Install " : "Update to ") + whRelease.version;
        deploy.setText(action); deploy.setEnabled(!busy);
        boolean launchSet = whLaunch == SteamLaunchOptions.State.ALREADY;
        copy.setText(launchSet ? "Launch option set ✓" : whLaunch == SteamLaunchOptions.State.SET ? "Set launch option" : "Copy launch option"); copy.setEnabled(!busy && !launchSet);
        headline.setText(interrupted ? "Finish your installation" : whGame == null ? "Connect your game" : whRelease == null ? "Game connected" : current ? "You’re up to date" : whInstalledVersion == null ? "Ready to install" : "Ready to update");
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        foot.setText(updatedHere ? "Installed. The first game start takes a few minutes while MelonLoader prepares its files." : "Close the game in DroidDeck before installing. Saves and mod settings stay in place.");
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
