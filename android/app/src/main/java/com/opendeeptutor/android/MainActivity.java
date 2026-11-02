package com.opendeeptutor.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hosts the DeepTutor web UI served by the on-device runtime. */
public class MainActivity extends Activity implements RuntimeState.Listener {
    static final String ACTION_EXIT = "com.opendeeptutor.android.EXIT";
    private static final int REQUEST_FILES = 10;
    private static final int REQUEST_AUDIO = 11;

    private final RuntimeState state = RuntimeState.INSTANCE;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WebView web;
    private View splash;
    private TextView status;
    private TextView hint;
    private TextView log;
    private ProgressBar progress;
    private View actions;
    private String loadedUrl;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private long lastBack;
    private String compatScript = "";
    private boolean documentStartCompat;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ACTION_EXIT.equals(getIntent().getAction())) {
            exitApp();
            return;
        }
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        // targetSdk 35 draws edge to edge; keep the page clear of the system bars and keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(types);
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return WindowInsets.CONSUMED;
        });

        web = findViewById(R.id.web);
        // Below 768dp the page's navigation is a drawer opened by swiping in
        // from the left edge; keep that edge for the page instead of the
        // system Back gesture (Back still works from the right edge).
        web.addOnLayoutChangeListener((v, left, top, right, bottom, ol, ot, or, ob) -> {
            if (Build.VERSION.SDK_INT < 29) return;
            float density = getResources().getDisplayMetrics().density;
            boolean drawerLayout = (right - left) / density < 768;
            int strip = Math.round(24 * density);
            int height = bottom - top;
            // The system honours at most 200dp of exclusion per edge.
            int band = Math.min(height, Math.round(200 * density));
            int bandTop = Math.max(0, (height - band) / 2);
            v.setSystemGestureExclusionRects(drawerLayout
                    ? java.util.Collections.singletonList(new android.graphics.Rect(0, bandTop, strip, bandTop + band))
                    : java.util.Collections.emptyList());
        });
        splash = findViewById(R.id.splash);
        status = findViewById(R.id.status);
        hint = findViewById(R.id.hint);
        log = findViewById(R.id.log);
        progress = findViewById(R.id.progress);
        actions = findViewById(R.id.actions);
        Button retry = findViewById(R.id.retry);
        retry.setOnClickListener(v -> RuntimeService.send(this, RuntimeService.ACTION_RESTART));
        findViewById(R.id.reinstall).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.reinstall)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> RuntimeService.send(this, RuntimeService.ACTION_REINSTALL))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        findViewById(R.id.copy_log).setOnClickListener(v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("DeepTutor log", state.logText()));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
        });
        setupWebView();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        RuntimeService.send(this, RuntimeService.ACTION_START);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ACTION_EXIT.equals(intent.getAction())) exitApp();
    }

    private void exitApp() {
        RuntimeService.send(this, RuntimeService.ACTION_STOP);
        finishAndRemoveTask();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web == null) return;
        state.addListener(this);
        main.post(ticker);
    }

    @Override
    protected void onStop() {
        state.removeListener(this);
        main.removeCallbacks(ticker);
        CookieManager.getInstance().flush();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    public void onStateChanged() {
        main.post(this::render);
    }

    private void render() {
        if (web == null) return;
        RuntimeState.Phase phase = state.phase;
        if (phase == RuntimeState.Phase.READY && state.url != null) {
            String url = state.url;
            if (!url.equals(loadedUrl)) {
                boolean sameOrigin = loadedUrl != null && origin(loadedUrl).equals(origin(url));
                loadedUrl = url;
                if (sameOrigin && web.getUrl() != null) web.reload(); else web.loadUrl(url);
            }
            return;
        }
        if (phase == RuntimeState.Phase.FAILED || phase == RuntimeState.Phase.STARTING
                || phase == RuntimeState.Phase.INSTALLING) {
            loadedUrl = null;
            showSplash();
        }
        actions.setVisibility(phase == RuntimeState.Phase.FAILED ? View.VISIBLE : View.GONE);
        log.setVisibility(phase == RuntimeState.Phase.FAILED ? View.VISIBLE : View.GONE);
        long elapsed = (System.currentTimeMillis() - state.phaseStartedAt) / 1000;
        switch (phase) {
            case INSTALLING:
                status.setText(getString(R.string.state_installing, state.progress));
                progress.setIndeterminate(state.progress == 0);
                progress.setProgress(state.progress);
                hint.setText(state.lastLine);
                break;
            case STARTING:
                status.setText(getString(R.string.state_starting, elapsed));
                progress.setIndeterminate(true);
                hint.setText(getString(R.string.state_starting_hint) + "\n" + state.lastLine);
                break;
            case FAILED:
                status.setText(getString(R.string.state_failed) + "\n" + state.message);
                progress.setIndeterminate(false);
                progress.setProgress(0);
                hint.setText("");
                String text = state.logText();
                int max = 6000;
                log.setText(text.length() > max ? text.substring(text.length() - max) : text);
                break;
            default:
                status.setText(R.string.state_checking);
                progress.setIndeterminate(true);
                hint.setText("");
        }
    }

    private void showSplash() {
        splash.setVisibility(View.VISIBLE);
        web.setVisibility(View.INVISIBLE);
    }

    private static String origin(String url) {
        Uri uri = Uri.parse(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private void setupWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // An app shell, not a document: pinching would scale the toolbars and
        // composer too. The reader and PDF views have their own zoom.
        settings.setSupportZoom(false);
        // Follow the system font size, like native apps (Settings > Display > Font size).
        settings.setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100));
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString()
                + " OpenDeepTutor/" + BuildConfig.VERSION_NAME + " (Android; " + BuildConfig.GUEST_ARCH + ")");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.WHITE);
        web.addJavascriptInterface(new DownloadBridge(), "DeepTutorAndroid");
        compatScript = loadCompatScript();
        // Older system WebViews lack APIs the UI uses; shim them before any page script runs.
        documentStartCompat = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (documentStartCompat) {
            WebViewCompat.addDocumentStartJavaScript(web, compatScript,
                    java.util.Collections.singleton("*"));
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = uri.getHost();
                    if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                }
                if ("blob".equals(scheme) || "data".equals(scheme) || "about".equals(scheme)) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!documentStartCompat) view.evaluateJavascript(compatScript, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (state.phase == RuntimeState.Phase.READY && url != null && url.startsWith("http://127.0.0.1")) {
                    splash.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] types = params.getAcceptTypes();
                List<String> mimes = new ArrayList<>();
                if (types != null) {
                    for (String type : types) {
                        if (type != null && type.contains("/")) mimes.add(type.trim());
                    }
                }
                if (!mimes.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(intent, null), REQUEST_FILES);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean wantsAudio = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsAudio = true;
                }
                if (!wantsAudio) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingPermission = request;
                    requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
                }
            }
        });

        // Long-pressing an external link offers open/copy, as in a browser;
        // text, images and in-app links keep the WebView's own behaviour.
        web.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = web.getHitTestResult();
            int type = hit.getType();
            String extra = hit.getExtra();
            if (extra == null || (type != WebView.HitTestResult.SRC_ANCHOR_TYPE
                    && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                return false;
            }
            Uri uri = Uri.parse(extra);
            String host = uri.getHost();
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
            web.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            new AlertDialog.Builder(this)
                    .setTitle(extra)
                    .setItems(new CharSequence[] {getString(R.string.link_open), getString(R.string.link_copy)},
                            (dialog, which) -> {
                                if (which == 0) {
                                    openExternal(uri);
                                } else {
                                    getSystemService(ClipboardManager.class)
                                            .setPrimaryClip(ClipData.newPlainText("link", extra));
                                    Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
                                }
                            })
                    .show();
            return true;
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:")) {
                // Blob URLs exist only inside the page; read them there and hand the bytes over.
                String js = "(async()=>{try{const r=await fetch(" + jsString(url) + ");const b=await r.blob();"
                        + "const f=new FileReader();f.onload=()=>DeepTutorAndroid.save(" + jsString(name)
                        + ",b.type||" + jsString(mimeType == null ? "" : mimeType) + ",f.result);"
                        + "f.readAsDataURL(b);}catch(e){DeepTutorAndroid.fail(String(e));}})()";
                web.evaluateJavascript(js, null);
            } else if (url.startsWith("data:")) {
                saveDataUrl(name, mimeType, url);
            } else {
                String cookies = CookieManager.getInstance().getCookie(url);
                io.execute(() -> downloadHttp(url, name, mimeType, cookies, userAgent));
            }
        });
    }

    private String loadCompatScript() {
        try (InputStream in = getAssets().open("compat.js")) {
            return new String(PayloadInstaller.readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String jsString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    private final class DownloadBridge {
        @JavascriptInterface
        public void save(String name, String mimeType, String dataUrl) {
            saveDataUrl(name, mimeType, dataUrl);
        }

        @JavascriptInterface
        public void fail(String message) {
            main.post(() -> Toast.makeText(MainActivity.this,
                    getString(R.string.download_failed, message), Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void backResult(boolean consumed) {
            main.post(() -> onPageBack(consumed));
        }
    }

    private void saveDataUrl(String name, String mimeType, String dataUrl) {
        io.execute(() -> {
            try {
                int comma = dataUrl.indexOf(',');
                String header = dataUrl.substring(0, comma);
                byte[] bytes = header.endsWith(";base64")
                        ? Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                        : Uri.decode(dataUrl.substring(comma + 1)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String mime = mimeType;
                if (mime == null || mime.isEmpty()) mime = header.substring(5).split(";")[0];
                try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                    saveToDownloads(name, mime, in);
                }
            } catch (Exception e) {
                toast(getString(R.string.download_failed, e.getMessage()));
            }
        });
    }

    private void downloadHttp(String url, String name, String mimeType, String cookies, String userAgent) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            if (cookies != null) connection.setRequestProperty("Cookie", cookies);
            connection.setRequestProperty("User-Agent", userAgent);
            String disposition = connection.getHeaderField("Content-Disposition");
            String type = connection.getContentType();
            String fileName = disposition != null ? URLUtil.guessFileName(url, disposition, type) : name;
            try (InputStream in = connection.getInputStream()) {
                saveToDownloads(fileName, type != null ? type.split(";")[0] : mimeType, in);
            }
        } catch (IOException e) {
            toast(getString(R.string.download_failed, e.getMessage()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void saveToDownloads(String name, String mimeType, InputStream in) throws IOException {
        String mime = mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DeepTutor");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                copy(in, out);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DeepTutor");
            dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                copy(in, out);
            }
        }
        toast(getString(R.string.download_saved, "DeepTutor/" + name));
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast(uri.toString());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILES) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[] {data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_AUDIO && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermission.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * Back closes the top layer first, like Escape on desktop: a menu, dialog,
     * the navigation drawer or a text selection (the page's AndroidBackBridge).
     * Only when nothing is open does it go back in history, then leave the app.
     */
    private void handleBack() {
        if (web == null || web.getVisibility() != View.VISIBLE) {
            leaveOrConfirm();
            return;
        }
        web.evaluateJavascript(
                "(async()=>{let r=false;try{r=window.__deeptutorBack?await window.__deeptutorBack():false}"
                        + "catch(e){}DeepTutorAndroid.backResult(r===true)})()",
                null);
    }

    /** Called by the page with whether it consumed Back (see handleBack). */
    private void onPageBack(boolean consumed) {
        if (consumed || web == null) return;
        if (web.canGoBack()) web.goBack();
        else leaveOrConfirm();
    }

    private void leaveOrConfirm() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            // Leave the backend running so returning to the app is instant;
            // the notification's Exit action stops it.
            moveTaskToBack(true);
            return;
        }
        lastBack = now;
        Toast.makeText(this, R.string.exit_confirm, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
