package com.hubcargolift.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/**
 * Hub Cargolift - locked-down (kiosk) shell around the Cargo Lift Checklist web app.
 *
 * - No address bar / browser chrome at all: full screen, splash, native loading + error screens.
 * - Session (cargo_lift_token / cargo_lift_user) is mirrored into SharedPreferences so the
 *   dashboard opens automatically on every launch.
 * - Username + password are stored once (first login) and re-submitted automatically if the
 *   saved session is missing or expired.  A manual logout forgets them.
 */
public class MainActivity extends Activity {

    private static final String TARGET_URL = "https://1st-instance.tail5b9279.ts.net/Hub_CargoLift/";
    private static final String ALLOWED_HOST = "1st-instance.tail5b9279.ts.net";
    private static final String LOGIN_PAGE = "login.html";
    private static final long LOAD_TIMEOUT_MS = 25000L;
    private static final long AUTO_RETRY_GAP_MS = 1500L;

    private static final String PREFS = "hub_cargolift_session";
    private static final String KEY_TOKEN = "session_token";
    private static final String KEY_USER = "session_user";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_LAST_AUTO = "last_auto_login";

    private static final int MAX_AUTO_LOGIN = 3;
    private static final long AUTO_LOGIN_GAP_MS = 15000L;
    private static final int REQ_STORAGE = 41;
    private static final int REQ_SAVE_DOC = 44;

    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/Bachanali/hub-cargolift-apk/main/version.json";
    private static final String KEY_SEEN_UPDATE = "seen_update";

    private static final String PAGE_TOKEN_KEY = "cargo_lift_token";
    private static final String PAGE_USER_KEY = "cargo_lift_user";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Runs in the page: push the saved session into localStorage before the app boots. */
    private static final String RESTORE_JS = """
            (function () {
              try {
                if (typeof window.HubAppNative === 'undefined') return;
                if (localStorage.getItem('%s')) return;
                var t = window.HubAppNative.getSessionToken();
                if (!t) return;
                localStorage.setItem('%s', t);
                var u = window.HubAppNative.getSessionUser();
                if (u) localStorage.setItem('%s', u);
              } catch (e) {}
            })();
            """;

    /** Runs on every page: mirror the session back to the app and remember the logout button. */
    private static final String SYNC_JS = """
            (function () {
              try {
                if (typeof window.HubAppNative === 'undefined') return;
                var t = null, u = null;
                try { t = localStorage.getItem('%s'); u = localStorage.getItem('%s'); } catch (e) {}
                if (t) window.HubAppNative.setSession(t, u || '');
                var b = document.getElementById('btnLogout');
                if (b && !b.__hubHooked) {
                  b.__hubHooked = 1;
                  b.addEventListener('click', function () {
                    try { window.HubAppNative.userLogout(); } catch (e) {}
                  }, true);
                }
              } catch (e) {}
            })();
            """;

    /** Runs on the login page: save what the user typed and re-login automatically when needed. */
    private static final String LOGIN_JS = """
            (function () {
              try {
                if (typeof window.HubAppNative === 'undefined') return;
                if (localStorage.getItem('%s')) return;
                var f = document.getElementById('loginForm');
                var u = document.getElementById('username');
                var p = document.getElementById('password');
                if (!f || !u || !p) return;
                if (!f.__hubHooked) {
                  f.__hubHooked = 1;
                  f.addEventListener('submit', function () {
                    try { if (u.value && p.value) window.HubAppNative.saveCredentials(u.value, p.value); } catch (e) {}
                  }, true);
                }
                if (u.value || p.value) return;
                if (!window.HubAppNative.allowAutoLogin()) return;
                var su = window.HubAppNative.getSavedUsername();
                var sp = window.HubAppNative.getSavedPassword();
                if (!su || !sp) return;
                u.value = su;
                p.value = sp;
                window.HubAppNative.markAutoLogin();
                if (f.requestSubmit) f.requestSubmit(); else f.submit();
              } catch (e) {}
            })();
            """;

    /**
     * Keep the site in its normal mobile layout.  The scale bounds only make pinch zoom
     * explicit (zoom out to fit the width, zoom in up to 5x); no desktop width override.
     */
    private static final String VIEWPORT_JS = """
            (function () {
              try {
                var c = 'width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=5';
                function apply() {
                  var m = null;
                  try { m = document.querySelector('meta[name="viewport"]'); } catch (e) {}
                  if (!m && document.head) {
                    m = document.createElement('meta');
                    m.setAttribute('name', 'viewport');
                    document.head.appendChild(m);
                  }
                  if (m && m.getAttribute('content') !== c) m.setAttribute('content', c);
                }
                if (document.readyState === 'loading') {
                  document.addEventListener('DOMContentLoaded', apply, { once: true });
                } else {
                  apply();
                }
                setTimeout(apply, 250);
              } catch (e) {}
            })();
            """;

    /**
     * Catch exports generated as Blob + <a download> and hand them to the native side,
     * so no browser "Save as" UI is ever shown. The site's own click still runs, but the
     * WebView download is ignored by startDownload() while a native save is pending.
     */
    private static final String EXPORT_HOOK_JS = """
            (function () {
              try {
                if (typeof window.HubAppNative === 'undefined') return;
                if (window.__hubExportHook) return;
                window.__hubExportHook = 1;
                var origCreate = URL.createObjectURL;
                var origClick = HTMLAnchorElement.prototype.click;
                var pending = null;
                URL.createObjectURL = function (blob) {
                  try {
                    if (blob && typeof blob.size === 'number' && blob.size > 0
                        && blob.size < 60 * 1024 * 1024) pending = blob;
                  } catch (e) {}
                  return origCreate.call(URL, blob);
                };
                HTMLAnchorElement.prototype.click = function () {
                  try {
                    if (pending && this.href && String(this.href).indexOf('blob:') === 0) {
                      var blob = pending;
                      pending = null;
                      var name = this.download || 'cargo_lift_export';
                      var reader = new FileReader();
                      reader.onload = function () {
                        try {
                          var s = String(reader.result || '');
                          var i = s.indexOf(',');
                          var b64 = i >= 0 ? s.slice(i + 1) : s;
                          if (b64) window.HubAppNative.exportBlob(name, b64);
                        } catch (e) {}
                      };
                      reader.readAsDataURL(blob);
                    }
                  } catch (e) {}
                  return origClick.apply(this, arguments);
                };
              } catch (e) {}
            })();
            """;

    private WebView webView;
    private View splashOverlay;
    private View loadingView;
    private View errorView;
    private TextView errorTitle;
    private TextView errorDetail;
    private Button retryButton;
    private SharedPreferences prefs;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean errorShown;
    private boolean pageLoaded;
    /** Only inject the saved token on the very first page of a launch. Injecting it after
     *  the web app clears localStorage on 401/logout creates a login <-> dashboard loop. */
    private boolean restorePending = true;
    private long lastBackPress;
    private long lastAttemptAt = -AUTO_RETRY_GAP_MS;   // device uptime can be tiny right after boot
    private int autoLoginAttempts;

    private String[] pendingDownload;
    private long lastNativeExportAt;
    private String pendingExportName;
    private byte[] pendingExportBytes;
    private long updateDownloadId = -1L;
    private Uri updateDownloadedUri;
    private AlertDialog updateDialog;
    private ProgressBar updateBar;
    private TextView updateStatusText;
    private Button updateDownloadBtn;
    private Button updateInstallBtn;

    /** Polls DownloadManager and renders 0-100% inside the update popup. */
    private final Runnable updateProgressTask = new Runnable() {
        @Override
        public void run() {
            if (updateDialog == null || !updateDialog.isShowing() || updateDownloadId < 0L) return;
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(updateDownloadId);
            Cursor cursor = dm.query(query);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        long soFar = cursor.getLong(cursor.getColumnIndex(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cursor.getLong(cursor.getColumnIndex(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int pct = total > 0 ? (int) Math.min(100L, soFar * 100L / total) : 0;
                        updateBar.setProgress(pct);
                        updateStatusText.setText(getString(R.string.update_status_downloading, pct));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            updateDownloadedUri = dm.getUriForDownloadedFile(updateDownloadId);
                            updateDownloadId = -1L;
                            updateBar.setProgress(100);
                            updateStatusText.setText(R.string.update_status_done);
                            updateDownloadBtn.setVisibility(View.GONE);
                            updateInstallBtn.setVisibility(View.VISIBLE);
                            return;
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            updateDownloadId = -1L;
                            updateStatusText.setText(R.string.update_status_failed);
                            updateDownloadBtn.setEnabled(true);
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // query failed - try again on the next tick
                } finally {
                    cursor.close();
                }
            }
            handler.postDelayed(this, 400L);
        }
    };

    private final Runnable timeoutTask = () -> {
        if (!pageLoaded && !errorShown) {
            showError(getString(R.string.error_title), getString(R.string.error_timeout_detail));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        webView = findViewById(R.id.webview);
        splashOverlay = findViewById(R.id.splash_overlay);
        loadingView = findViewById(R.id.loading_view);
        errorView = findViewById(R.id.error_view);
        errorTitle = findViewById(R.id.error_title);
        errorDetail = findViewById(R.id.error_detail);
        retryButton = findViewById(R.id.retry_button);
        retryButton.setOnClickListener(v -> attemptLoad());

        applyFullscreen();
        configureWebView();
        attemptLoad();
        fadeSplash();
        handler.postDelayed(this::checkForUpdate, 1200L);   // silent background check
    }

    /** Fade the in-activity splash into the branded loading view / dashboard. */
    private void fadeSplash() {
        splashOverlay.postDelayed(() -> {
            if (splashOverlay == null || splashOverlay.getVisibility() == View.GONE) return;
            AlphaAnimation fade = new AlphaAnimation(1f, 0f);
            fade.setDuration(320);
            fade.setFillAfter(true);
            fade.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) { }

                @Override
                public void onAnimationEnd(Animation animation) {
                    if (splashOverlay == null) return;
                    splashOverlay.clearAnimation();
                    splashOverlay.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationRepeat(Animation animation) { }
            });
            splashOverlay.startAnimation(fade);
        }, 1600L);
        // Safety net: never let a transparent overlay keep swallowing touches.
        splashOverlay.postDelayed(() -> {
            if (splashOverlay != null) splashOverlay.setVisibility(View.GONE);
        }, 2050L);
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(USER_AGENT);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLongClickable(true);
        webView.setOnLongClickListener(v -> true);   // no browser context menu
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new HubBridge(), "HubAppNative");

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !allowNavigation(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !allowNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoaded = false;
                handler.removeCallbacks(timeoutTask);
                if (!errorShown) {
                    loadingView.setVisibility(View.VISIBLE);
                }
                inject(VIEWPORT_JS);
                if (restorePending) {
                    restorePending = false;
                    inject(String.format(RESTORE_JS, PAGE_TOKEN_KEY, PAGE_TOKEN_KEY, PAGE_USER_KEY));
                }
                handler.postDelayed(timeoutTask, LOAD_TIMEOUT_MS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (errorShown) return;
                pageLoaded = true;
                handler.removeCallbacks(timeoutTask);
                loadingView.setVisibility(View.GONE);
                errorView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);

                inject(VIEWPORT_JS);
                inject(String.format(SYNC_JS, PAGE_TOKEN_KEY, PAGE_USER_KEY));
                inject(EXPORT_HOOK_JS);
                if (url != null && url.contains(LOGIN_PAGE)) {
                    inject(String.format(LOGIN_JS, PAGE_TOKEN_KEY));
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                showError(getString(R.string.error_title), describeError(error.getErrorCode()));
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                int status = response.getStatusCode();
                if (status >= 400) {
                    showError(getString(R.string.error_title),
                            getString(R.string.error_http_detail, status));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showError(getString(R.string.error_title), getString(R.string.error_secure_detail));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.app_name)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.app_name)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.app_name)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        webView.setDownloadListener(this::startDownload);
    }

    /** Kiosk rule: only our own host may be opened, and only inside this WebView. */
    private boolean allowNavigation(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            Toast.makeText(this, R.string.blocked_link, Toast.LENGTH_SHORT).show();
            return false;
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOST.equalsIgnoreCase(host)) {
            Toast.makeText(this, R.string.blocked_link, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void inject(String js) {
        if (webView != null) webView.evaluateJavascript(js, null);
    }

    private String describeError(int code) {
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP:
            case WebViewClient.ERROR_CONNECT:
            case WebViewClient.ERROR_TIMEOUT:
            case WebViewClient.ERROR_TOO_MANY_REQUESTS:
            case WebViewClient.ERROR_IO:
            case WebViewClient.ERROR_PROXY_AUTHENTICATION:
            case WebViewClient.ERROR_REDIRECT_LOOP:
                return getString(R.string.error_server_detail);
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
            case WebViewClient.ERROR_AUTHENTICATION:
            case WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME:
                return getString(R.string.error_secure_detail);
            default:
                return getString(R.string.error_generic_detail);
        }
    }

    // ---------------------------------------------------------------- loading

    private void attemptLoad() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAttemptAt < AUTO_RETRY_GAP_MS) return;
        lastAttemptAt = now;

        if (errorShown) restorePending = true;   // retry is a fresh start, allow one restore
        errorShown = false;
        pageLoaded = false;
        handler.removeCallbacks(timeoutTask);
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        loadingView.setVisibility(View.VISIBLE);

        if (!isOnline()) {
            showError(getString(R.string.error_no_internet), getString(R.string.error_internet_detail));
            return;
        }

        webView.stopLoading();
        webView.loadUrl(TARGET_URL);
        handler.postDelayed(timeoutTask, LOAD_TIMEOUT_MS);
    }

    private void showError(String title, String detail) {
        errorShown = true;
        pageLoaded = false;
        handler.removeCallbacks(timeoutTask);
        loadingView.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        errorTitle.setText(title);
        errorDetail.setText(detail);
        errorView.setVisibility(View.VISIBLE);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return false;
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    // ---------------------------------------------------------------- downloads

    private void startDownload(String url, String userAgent, String contentDisposition,
                               String mimeType, long contentLength) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        // Blob exports are saved natively; ignore the WebView's copy of the same download.
        if (System.currentTimeMillis() - lastNativeExportAt < 6000L) return;
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, name, mimeType};
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        enqueueDownload(url, name, mimeType);
    }

    private void enqueueDownload(String url, String name, String mimeType) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
            req.addRequestHeader("User-Agent", USER_AGENT);
            String token = prefs.getString(KEY_TOKEN, "");
            if (token != null && !token.isEmpty()) req.addRequestHeader("Authorization", "Bearer " + token);
            String cookieHeader = CookieManager.getInstance().getCookie(url);
            if (cookieHeader != null) req.addRequestHeader("Cookie", cookieHeader);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) dm.enqueue(req);
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- self update

    /** Silent check against the public GitHub repo; the update option stays hidden unless
     *  a newer version is actually available (and the user has not dismissed this one). */
    private void checkForUpdate() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(UPDATE_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;
                String body;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    body = sb.toString();
                }
                JSONObject json = new JSONObject(body);
                int remote = json.optInt("versionCode", 0);
                if (remote <= Version.CODE) return;
                final String name = json.optString("versionName", "");
                final String notes = json.optString("notes", "");
                final String apk = json.optString("apkUrl", "");
                if (apk.isEmpty() || name.equals(prefs.getString(KEY_SEEN_UPDATE, ""))) return;
                runOnUiThread(() -> showUpdateDialog(name, notes, apk));
            } catch (Exception ignored) {
                // offline / GitHub unreachable: fail silently
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "hub-update-check").start();
    }

    private void showUpdateDialog(String versionName, String notes, String apkUrl) {
        if (isFinishing() || isDestroyed()) return;
        View view = getLayoutInflater().inflate(R.layout.dialog_update, null);
        ((TextView) view.findViewById(R.id.update_version)).setText(
                getString(R.string.update_current_new, Version.NAME, versionName));
        ((TextView) view.findViewById(R.id.update_notes)).setText(notes);

        updateBar = view.findViewById(R.id.update_progress);
        updateStatusText = view.findViewById(R.id.update_status);
        updateDownloadBtn = view.findViewById(R.id.update_download);
        updateInstallBtn = view.findViewById(R.id.update_install);
        updateDownloadId = -1L;
        updateDownloadedUri = null;
        updateBar.setProgress(0);
        updateStatusText.setText(R.string.update_status_ready);
        updateDownloadBtn.setEnabled(true);
        updateDownloadBtn.setVisibility(View.VISIBLE);
        updateInstallBtn.setVisibility(View.GONE);

        updateDownloadBtn.setOnClickListener(v -> {
            prefs.edit().putString(KEY_SEEN_UPDATE, versionName).apply();
            updateDownloadBtn.setEnabled(false);
            updateStatusText.setText(getString(R.string.update_status_downloading, 0));
            startUpdateDownload(apkUrl, versionName);
        });
        updateInstallBtn.setOnClickListener(v -> {
            if (updateDownloadedUri != null) promptInstall(updateDownloadedUri);
        });
        view.findViewById(R.id.update_later).setOnClickListener(v -> {
            prefs.edit().putString(KEY_SEEN_UPDATE, versionName).apply();
            if (updateDialog != null) updateDialog.dismiss();
        });

        updateDialog = new AlertDialog.Builder(this).setView(view).create();
        updateDialog.setOnDismissListener(d -> {
            handler.removeCallbacks(updateProgressTask);
            updateDialog = null;
        });
        updateDialog.show();
    }

    private void startUpdateDownload(String apkUrl, String versionName) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "HubCargolift_v" + versionName + ".apk");
            updateDownloadId = dm.enqueue(req);
            handler.removeCallbacks(updateProgressTask);
            handler.post(updateProgressTask);
        } catch (Exception e) {
            updateStatusText.setText(R.string.update_status_failed);
            updateDownloadBtn.setEnabled(true);
        }
    }

    private void promptInstall(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- native export

    /** Android's built-in Save As (system document picker). The user picks the name and
     *  folder; the bytes are written straight to the returned content:// URI. */
    private void launchSaveAs(String name, byte[] bytes) {
        if (isFinishing() || isDestroyed()) return;
        pendingExportName = name;
        pendingExportBytes = bytes;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeFor(name));
        intent.putExtra(Intent.EXTRA_TITLE, name);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_SAVE_DOC);
        } catch (Exception e) {
            pendingExportName = null;
            pendingExportBytes = null;
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE_DOC) return;
        byte[] bytes = pendingExportBytes;
        String name = pendingExportName;
        pendingExportBytes = null;
        pendingExportName = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
        saveToUri(data.getData(), name, bytes);
    }

    private void saveToUri(Uri uri, String name, byte[] bytes) {
        new Thread(() -> {
            boolean ok = false;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(bytes);
                    os.flush();
                    ok = true;
                }
            } catch (Exception e) {
                ok = false;
            }
            final boolean saved = ok;
            final String shown = name == null ? "export" : name;
            runOnUiThread(() -> Toast.makeText(this,
                    saved ? getString(R.string.export_saved_system, shown)
                            : getString(R.string.export_failed),
                    Toast.LENGTH_LONG).show());
        }, "hub-cargolift-export").start();
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (n.endsWith(".db") || n.endsWith(".sqlite")) return "application/x-sqlite3";
        return "application/octet-stream";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        String[] job = pendingDownload;
        pendingDownload = null;
        if (job == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enqueueDownload(job[0], job[1], job[2]);
        } else {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- native bridge

    /** Exposed to the page as window.HubAppNative (JS thread - no UI work here). */
    private class HubBridge {

        @android.webkit.JavascriptInterface
        public String getSessionToken() {
            return prefs.getString(KEY_TOKEN, "");
        }

        @android.webkit.JavascriptInterface
        public String getSessionUser() {
            return prefs.getString(KEY_USER, "");
        }

        @android.webkit.JavascriptInterface
        public void setSession(String token, String user) {
            if (token == null || token.isEmpty()) return;
            prefs.edit().putString(KEY_TOKEN, token).putString(KEY_USER, user == null ? "" : user).apply();
        }

        @android.webkit.JavascriptInterface
        public String getSavedUsername() {
            return prefs.getString(KEY_USERNAME, "");
        }

        @android.webkit.JavascriptInterface
        public String getSavedPassword() {
            return prefs.getString(KEY_PASSWORD, "");
        }

        /** Called from the login form the very first time the user signs in. */
        @android.webkit.JavascriptInterface
        public void saveCredentials(String username, String password) {
            if (username == null || username.isEmpty() || password == null || password.isEmpty()) return;
            prefs.edit().putString(KEY_USERNAME, username).putString(KEY_PASSWORD, password).apply();
        }

        /** Export produced by the web app as a Blob: show our native Save As dialog. */
        @android.webkit.JavascriptInterface
        public void exportBlob(String name, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (bytes.length == 0) return;
                lastNativeExportAt = System.currentTimeMillis();
                String safe = (name == null || name.trim().isEmpty())
                        ? "cargo_lift_export" : name.trim();
                runOnUiThread(() -> launchSaveAs(safe, bytes));
            } catch (Exception ignored) {
                // invalid blob - nothing to save
            }
        }

        /** The user pressed Logout on purpose: forget the saved sign-in. */
        @android.webkit.JavascriptInterface
        public void userLogout() {
            prefs.edit()
                    .remove(KEY_TOKEN)
                    .remove(KEY_USER)
                    .remove(KEY_USERNAME)
                    .remove(KEY_PASSWORD)
                    .remove(KEY_LAST_AUTO)
                    .apply();
            autoLoginAttempts = 0;
            restorePending = false;
        }

        @android.webkit.JavascriptInterface
        public boolean allowAutoLogin() {
            if (autoLoginAttempts >= MAX_AUTO_LOGIN) return false;
            long last = prefs.getLong(KEY_LAST_AUTO, 0L);
            return System.currentTimeMillis() - last >= AUTO_LOGIN_GAP_MS;
        }

        @android.webkit.JavascriptInterface
        public void markAutoLogin() {
            autoLoginAttempts++;
            prefs.edit().putLong(KEY_LAST_AUTO, System.currentTimeMillis()).apply();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || networkCallback != null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    if (errorShown) {
                        lastAttemptAt = -AUTO_RETRY_GAP_MS;   // connectivity just came back: retry right away
                        attemptLoad();
                    }
                });
            }
        };
        try {
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                    networkCallback);
        } catch (RuntimeException ignored) {
            networkCallback = null;
        }
    }

    @Override
    protected void onStop() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && networkCallback != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
        networkCallback = null;
        super.onStop();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (updateDialog != null && updateDialog.isShowing()) {
            try {
                updateDialog.dismiss();
            } catch (Exception ignored) {
                // activity going away
            }
        }
        updateDialog = null;
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!errorShown && webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (SystemClock.elapsedRealtime() - lastBackPress < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPress = SystemClock.elapsedRealtime();
        Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyFullscreen();
    }

    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
