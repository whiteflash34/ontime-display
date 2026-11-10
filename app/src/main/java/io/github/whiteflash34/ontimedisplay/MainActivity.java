package io.github.whiteflash34.ontimedisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Full-screen Ontime display for Fire TV.
 *
 * Remote:
 *   Arrows    move between buttons and links on the page (assets/dpad-nav.js)
 *   Select    press the highlighted item
 *   Menu (☰)  switch site, add or remove a site, or reload
 *   Back      go back; press twice to exit
 *
 * Saved sites are "Name|address" pairs. An edition can build some in (BuildConfig.DEFAULT_SITES,
 * set in edition.properties); otherwise the app asks for one on first launch.
 *
 * From a computer with adb:
 *   adb shell "am start -n <app id>/io.github.whiteflash34.ontimedisplay.MainActivity --es url 'https://...'"
 *   adb shell "am start -n <app id>/io.github.whiteflash34.ontimedisplay.MainActivity --es projects 'Stage|https://...;Foyer|https://.../foyer/'"
 */
public class MainActivity extends Activity {

    private static final String TAG = "OntimeTV";
    private static final String PREFS = "ontime";
    private static final String KEY_URL = "url";
    private static final String KEY_SITES = "projects"; // name kept so existing installs keep their list
    private static final long RETRY_DELAY_MS = 10_000;
    private static final long EXIT_WINDOW_MS = 2_000;
    // Auto-return after Fire OS's "Are you still watching?" prompt (see onPause).
    private static final long COVERED_MIN_MS = 30_000;
    private static final long BOUNCE_MAX_MS = 2_000;
    private static final long RETURN_DELAY_MS = 3_000;

    // Ontime's own pages. Anything from one of these on is "a view", not part of the site address.
    private static final Pattern VIEW_SEGMENT = Pattern.compile(
            "^(timer|backstage|timeline|studio|countdown|info|cuesheet|op|editor|preset|login|minimal|clock|lower|public)$");
    private static final Pattern LOCAL_HOST = Pattern.compile(
            "^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+]|[^.]+|.+\\.(local|lan|home|internal|localdomain|home\\.arpa))$");

    // A host name (letters, digits, hyphens, dots), IPv4 address or bracketed IPv6 address.
    private static final Pattern VALID_HOST = Pattern.compile(
            "^([A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$|^\\[[0-9a-fA-F:.]+]$");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::loadSavedUrl;
    private final Runnable returnToFront = this::bringToFront;

    private WebView web;
    private long lastBackPress;
    private long resumedAt;
    private long pausedAt;
    private long coveredForMs;
    private boolean sentHomeByPrompt;

    /** A saved Ontime site: a name and the address its views live under. */
    private static final class Site {
        final String name;
        final String base; // absolute, ends with "/"

        Site(String name, String base) {
            this.name = name;
            this.base = base;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Stop the Fire TV screensaver from covering the timer.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);

        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new OntimeClient());
        // Page console output → logcat (tag "OntimeTV"), handy with `adb logcat -s OntimeTV`.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        // The remote's keys only reach the page if the WebView has focus.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        saveSitesFromIntent(getIntent());
        saveUrlFromIntent(getIntent());
        if (savedUrl() == null) {
            showSiteDialog(true);
        } else {
            loadSavedUrl();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        saveSitesFromIntent(intent);
        if (saveUrlFromIntent(intent)) {
            loadSavedUrl();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        long now = SystemClock.elapsedRealtime();
        coveredForMs = pausedAt == 0 ? 0 : now - pausedAt;
        resumedAt = now;
        handler.removeCallbacks(returnToFront);
        web.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        long now = SystemClock.elapsedRealtime();
        pausedAt = now;
        // Fire OS's "Are you still watching?" prompt covers the app; when nobody answers,
        // it closes, the app is resumed for a split second, and then Home is launched.
        // A person pressing Home looks different: the app has been showing for a while.
        sentHomeByPrompt = !isFinishing()
                && now - resumedAt < BOUNCE_MAX_MS
                && coveredForMs >= COVERED_MIN_MS;
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sentHomeByPrompt) {
            Log.i(TAG, "Sent to Home after being covered for " + coveredForMs / 1000
                    + "s; coming back in " + RETURN_DELAY_MS / 1000 + "s");
            handler.postDelayed(returnToFront, RETURN_DELAY_MS);
        }
    }

    private void bringToFront() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(returnToFront);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // If Ontime's menu (or another dialog) is open, close it with Escape instead.
            web.evaluateJavascript("!!(window.__ontimeTvHasDialog && window.__ontimeTvHasDialog())", result -> {
                if ("true".equals(result)) {
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE));
                } else {
                    goBackOrExit();
                }
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void goBackOrExit() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < EXIT_WINDOW_MS) {
            finish();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Address handling ----------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** The page to open: the last one saved, else the first saved site, else null (first launch). */
    private String savedUrl() {
        String url = prefs().getString(KEY_URL, null);
        if (url != null) return url;
        List<Site> sites = sites();
        return sites.isEmpty() ? null : sites.get(0).base;
    }

    /**
     * Adds http:// or https:// when it's missing. Ontime on a local network is plain http
     * (e.g. 192.168.1.20:4001); public names like ontime.example.com get https.
     * Returns null if it isn't a usable web address.
     */
    private static String normalizeUrl(String input) {
        String url = input == null ? "" : input.trim();
        if (url.isEmpty()) return null;
        if (!url.matches("(?i)^https?://.*")) {
            String hostPort = url.split("[/?#]", 2)[0];
            String host = hostPort.replaceFirst(":\\d+$", "");
            boolean local = LOCAL_HOST.matcher(host).matches() || !host.equals(hostPort);
            url = (local ? "http://" : "https://") + url;
        }
        if (url.matches(".*\\s.*")) return null; // spaces aren't valid in an address
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return host != null && VALID_HOST.matcher(host).matches() ? url : null;
    }

    private boolean saveUrl(String input) {
        String url = normalizeUrl(input);
        if (url == null) {
            Toast.makeText(this, "That doesn't look like a web address", Toast.LENGTH_LONG).show();
            return false;
        }
        prefs().edit().putString(KEY_URL, url).apply();
        return true;
    }

    private boolean saveUrlFromIntent(Intent intent) {
        return intent != null && intent.hasExtra(KEY_URL) && saveUrl(intent.getStringExtra(KEY_URL));
    }

    private void loadSavedUrl() {
        handler.removeCallbacks(retry);
        String url = savedUrl();
        if (url != null) web.loadUrl(url);
    }

    /** The page being shown now (falls back to the saved address on error pages), or null. */
    private Uri currentUri() {
        String url = web.getUrl();
        if (url == null || !url.startsWith("http")) url = savedUrl();
        return url == null ? null : Uri.parse(url);
    }

    // ---- Saved sites -------------------------------------------------------------

    private List<Site> sites() {
        String raw = prefs().getString(KEY_SITES, BuildConfig.DEFAULT_SITES);
        List<Site> list = new ArrayList<>();
        Uri now = web == null ? null : Uri.parse(String.valueOf(prefs().getString(KEY_URL, "")));
        for (String entry : raw.split(";")) {
            String[] p = entry.split("\\|", 2);
            if (p.length != 2 || p[0].trim().isEmpty()) continue;
            String address = p[1].trim();
            // A path like "/foyer/" means that path on the current server.
            if (address.startsWith("/")) {
                Uri ref = now != null && now.getHost() != null ? now : firstAbsolute(raw);
                if (ref == null) continue;
                address = ref.getScheme() + "://" + ref.getEncodedAuthority() + address;
            }
            String base = siteBase(address);
            if (base != null) list.add(new Site(p[0].trim(), base));
        }
        return list;
    }

    private static Uri firstAbsolute(String raw) {
        for (String entry : raw.split(";")) {
            String[] p = entry.split("\\|", 2);
            if (p.length == 2 && p[1].trim().matches("(?i)^https?://.*")) return Uri.parse(p[1].trim());
        }
        return null;
    }

    private void saveSites(List<Site> list) {
        StringBuilder sb = new StringBuilder();
        for (Site site : list) {
            if (sb.length() > 0) sb.append(';');
            sb.append(site.name.replaceAll("[|;]", " ")).append('|').append(site.base);
        }
        prefs().edit().putString(KEY_SITES, sb.toString()).apply();
    }

    private void saveSitesFromIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(KEY_SITES)) return;
        String raw = intent.getStringExtra(KEY_SITES);
        if (raw != null) prefs().edit().putString(KEY_SITES, raw).apply();
    }

    /**
     * The address a site's views live under: the given address up to the first Ontime view.
     * "http://10.0.0.5:4001/timer?token=x" → "http://10.0.0.5:4001/";
     * "https://ontime.example.com/foyer/cuesheet" → "https://ontime.example.com/foyer/".
     */
    private static String siteBase(String address) {
        String url = normalizeUrl(address);
        if (url == null) return null;
        Uri uri = Uri.parse(url);
        StringBuilder path = new StringBuilder("/");
        for (String segment : uri.getPathSegments()) {
            if (VIEW_SEGMENT.matcher(segment).matches()) break;
            path.append(Uri.encode(segment)).append('/');
        }
        return uri.getScheme() + "://" + uri.getEncodedAuthority() + path;
    }

    private static String defaultName(String base) {
        Uri uri = Uri.parse(base);
        String path = uri.getPath() == null ? "/" : uri.getPath();
        // Keep the port: two Ontime servers on one machine differ only by port.
        return uri.getEncodedAuthority() + ("/".equals(path) ? "" : path.replaceAll("/$", ""));
    }

    /** Index of the saved site whose address is the longest match for this page, or -1. */
    private static int siteIndexFor(Uri uri, List<Site> list) {
        if (uri == null) return -1;
        String path = uri.getEncodedPath() == null ? "/" : uri.getEncodedPath();
        if (!path.endsWith("/")) path = path + "/";
        String page = uri.getScheme() + "://" + uri.getEncodedAuthority() + path;
        int best = -1;
        for (int i = 0; i < list.size(); i++) {
            String base = list.get(i).base;
            if (page.startsWith(base) && (best < 0 || base.length() > list.get(best).base.length())) {
                best = i;
            }
        }
        return best;
    }

    /** Same screen (view, and login token if it's the same server), different site. */
    private void switchToSite(Site site) {
        List<Site> list = sites();
        Uri now = currentUri();
        int from = siteIndexFor(now, list);
        String rest = "";
        String query = null;
        if (now != null && from >= 0) {
            Uri fromBase = Uri.parse(list.get(from).base);
            String path = now.getEncodedPath() == null ? "/" : now.getEncodedPath();
            String basePath = fromBase.getEncodedPath() == null ? "/" : fromBase.getEncodedPath();
            rest = path.length() >= basePath.length() ? path.substring(basePath.length()) : "";
            // A login token only works on the server it came from.
            if (TextUtils.equals(now.getEncodedAuthority(), Uri.parse(site.base).getEncodedAuthority())) {
                query = now.getEncodedQuery();
            }
        }
        // Don't carry a login page over; the new site opens on its default view.
        if (rest.startsWith("login")) rest = "";
        if (query != null && query.contains("redirect=")) query = null;
        String url = site.base + rest + (TextUtils.isEmpty(query) ? "" : "?" + query);
        if (saveUrl(url)) {
            loadSavedUrl();
            Toast.makeText(this, site.name, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Menus -------------------------------------------------------------------

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void showMenu() {
        List<Site> list = sites();
        int current = siteIndexFor(currentUri(), list);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            items.add((i == current ? "✓  " : "     ") + list.get(i).name);
        }
        final int add = items.size();
        items.add("+   Add a site…");
        final int remove = list.isEmpty() ? -1 : items.size();
        if (remove >= 0) items.add("     Remove a site…");
        final int reload = items.size();
        items.add("     Reload");

        dialog().setTitle(R.string.app_name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < list.size()) {
                        switchToSite(list.get(which));
                    } else if (which == add) {
                        showSiteDialog(false);
                    } else if (which == remove) {
                        showRemoveDialog();
                    } else if (which == reload) {
                        loadSavedUrl();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Adding a site ---------------------------------------------------------------

    /**
     * Ask for an Ontime address (and an optional name), save it as a site and open it.
     * Typing uses the Fire TV keyboard; on Fire TVs whose keyboard offers it (e.g. 4K Max),
     * that keyboard shows Amazon's own QR code for typing on a phone.
     * On first launch there's nothing to fall back to, so it can only be filled in or exited.
     */
    private void showSiteDialog(boolean firstLaunch) {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView addressLabel = new TextView(this);
        addressLabel.setText("Ontime address");
        EditText address = new EditText(this);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        address.setHint("192.168.1.20:4001");
        address.setContentDescription("Ontime address");
        addressLabel.setLabelFor(View.generateViewId());
        address.setId(addressLabel.getLabelFor());

        TextView nameLabel = new TextView(this);
        nameLabel.setText("Name (optional)");
        EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setSingleLine(true);
        name.setHint("e.g. Main stage");
        name.setContentDescription("Name, optional");
        nameLabel.setLabelFor(View.generateViewId());
        name.setId(nameLabel.getLabelFor());

        box.addView(addressLabel);
        box.addView(address);
        box.addView(nameLabel);
        box.addView(name);

        String message = "Enter the address you use to open Ontime in a web browser, "
                + "like 192.168.1.20:4001 or ontime.example.com.";

        AlertDialog d = dialog()
                .setTitle(firstLaunch ? "Add your Ontime site" : "Add a site")
                .setMessage(message)
                .setView(box)
                .setPositiveButton(firstLaunch ? "Open" : "Save", null)
                .setNegativeButton(firstLaunch ? "Exit" : "Cancel", (di, w) -> {
                    if (firstLaunch) finish();
                })
                .setCancelable(!firstLaunch)
                .create();
        d.setOnShowListener(di -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (addSite(address.getText().toString(), name.getText().toString(), firstLaunch)) {
                d.dismiss();
            } else {
                address.setError("Enter a web address, like 192.168.1.20:4001");
                address.requestFocus();
            }
        }));
        d.show();
    }

    /** Saves the site for this address (replacing one with the same address) and opens it. */
    private boolean addSite(String typed, String label, boolean firstLaunch) {
        String base = siteBase(typed);
        if (base == null) return false;
        List<Site> list = sites();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).base.equals(base)) list.remove(i);
        }
        label = label == null ? "" : label.trim();
        list.add(new Site(label.isEmpty() ? defaultName(base) : label, base));
        saveSites(list);
        if (!saveUrl(typed)) return false;
        loadSavedUrl();
        if (firstLaunch) {
            Toast.makeText(this, "Press ☰ Menu on the remote any time to switch or add sites",
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private void showRemoveDialog() {
        List<Site> list = sites();
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
        dialog().setTitle("Remove a site")
                .setItems(names, (d, which) -> {
                    Site gone = list.get(which);
                    dialog().setTitle("Remove " + gone.name + "?")
                            .setMessage(gone.base)
                            .setPositiveButton("Remove", (d2, w) -> {
                                list.remove(which);
                                saveSites(list);
                                boolean wasShowing = siteIndexFor(currentUri(), list) < 0;
                                if (wasShowing) {
                                    prefs().edit().remove(KEY_URL).apply();
                                    if (list.isEmpty()) {
                                        web.loadUrl("about:blank");
                                        showSiteDialog(true);
                                    } else {
                                        loadSavedUrl();
                                    }
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Errors: show a message and keep retrying -----------------------------

    private void showError(String detail) {
        String url = savedUrl();
        String html = "<!doctype html><html style=\"background:#000\"><head>"
                + "<meta name=\"color-scheme\" content=\"dark\"></head>"
                + "<body style=\"background:#000;color:#ddd;font-family:sans-serif;font-size:1.5rem;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;"
                + "text-align:center\"><main><h1 style=\"font-weight:500\">Can't reach Ontime</h1>"
                + "<p>" + TextUtils.htmlEncode(url == null ? "" : url) + "</p>"
                + "<p style=\"color:#aaa\">" + TextUtils.htmlEncode(detail) + "</p>"
                + "<p>Retrying every 10 seconds. Press &#9776; Menu to switch or add a site.</p>"
                + "</main></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_DELAY_MS);
    }

    // ---- D-pad navigation for web pages (assets/dpad-nav.js) -------------------

    private String dpadNav;

    private String dpadNavScript() {
        if (dpadNav == null) {
            try (InputStream in = getAssets().open("dpad-nav.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
                dpadNav = out.toString("UTF-8");
            } catch (IOException e) {
                dpadNav = "";
            }
        }
        return dpadNav;
    }

    private void hideSystemUi() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private class OntimeClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false; // keep every page inside the app
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(dpadNavScript(), null);
        }

        // Called for main-frame network failures on every Android version.
        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            showError(description);
        }

        // e.g. 502 from a reverse proxy while Ontime is restarting (Android 6+).
        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                showError("Server error " + response.getStatusCode());
            }
        }
    }
}
