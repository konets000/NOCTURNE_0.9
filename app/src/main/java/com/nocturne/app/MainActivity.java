package com.nocturne.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Offline WebView shell for NOCTURNE.
 *
 * <p>The page and its bundled dictionaries are served from a synthetic https origin
 * ({@code https://appassets.androidplatform.net}) by {@link WebViewAssetLoader}. Nothing is fetched from a
 * server, and the APK requests no INTERNET permission. Dictionary files are decompressed here, so the page
 * receives plain text through an ordinary same-origin {@code fetch()}.
 */
public final class MainActivity extends ComponentActivity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";

    /** The only external pages the reader may open, and only from a user-initiated source or licence link. */
    private static final Set<String> APPROVED_SOURCE_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("www.mdbg.net", "mdbg.net", "creativecommons.org")));

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> filePathCallback;
    private OnBackPressedCallback backCallback;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::onFileChooserResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Use one inset model across Android versions. The WebView receives system-bar insets below.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(APP_HOST)
                .addPathHandler("/assets/", new AppAssetHandler(this))
                .addPathHandler("/dictionary/", new BundledDictionaryHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(9, 12, 11));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // The page loads only through the asset loader, so it needs no file or content URL access.
        // The file picker returns a content:// URI that the page reads through a blob, so it does not need these.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // The page validates the file type itself, so the picker stays broad: some providers report
                // .srt files with an inconsistent MIME type.
                intent.setType("*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                boolean multiple = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);

                try {
                    fileChooserLauncher.launch(intent);
                } catch (ActivityNotFoundException ex) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this,
                            "No file picker is available on this device.", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Returns null for any URL outside the app origin, which then fails with no network available.
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && APP_HOST.equals(uri.getHost())) {
                    return false;
                }
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                // Only a tap or click may open these links; the page cannot open them by itself.
                if ("https".equals(uri.getScheme()) && APPROVED_SOURCE_HOSTS.contains(host)
                        && request.hasGesture()) {
                    openExternally(uri);
                }
                return true; // Every other navigation is blocked; the reader itself stays offline.
            }
        });

        backCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView == null) {
                    finish();
                    return;
                }
                // Let the page close an open sheet or modal first; leave the app only when nothing was open.
                webView.evaluateJavascript(
                        "window.Nocturne ? window.Nocturne.dismissOverlays() : false",
                        value -> {
                            if (!"true".equals(value)) {
                                finish();
                            }
                        });
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        setContentView(webView);

        // targetSdk 35 draws edge-to-edge, so the WebView extends under the status and navigation bars. The page
        // only reads env(safe-area-inset-bottom), which does not cover those bars, so pad the WebView natively.
        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);
        webView.loadUrl(APP_URL);
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "No browser is available to open this source link.", Toast.LENGTH_LONG).show();
        }
    }

    private void onFileChooserResult(ActivityResult result) {
        ValueCallback<Uri[]> callback = filePathCallback;
        filePathCallback = null;
        if (callback == null) {
            return;
        }

        Uri[] uris = null;
        Intent data = result.getData();
        if (result.getResultCode() == RESULT_OK && data != null) {
            List<Uri> selected = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) {
                        selected.add(uri);
                    }
                }
            } else if (data.getData() != null) {
                selected.add(data.getData());
            }
            if (!selected.isEmpty()) {
                uris = selected.toArray(new Uri[0]);
            }
        }
        callback.onReceiveValue(uris);
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            // No foreground media service/notification is implemented: do not keep audio playing behind other apps.
            webView.evaluateJavascript(
                    "(function(){var a=document.getElementById('audio');if(a)a.pause();})();", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** Only the HTML entry point and its application script are served from /assets/. */
    private static final class AppAssetHandler implements WebViewAssetLoader.PathHandler {
        private final WebViewAssetLoader.PathHandler delegate;

        AppAssetHandler(Context context) {
            delegate = new WebViewAssetLoader.AssetsPathHandler(context.getApplicationContext());
        }

        @Override
        public WebResourceResponse handle(String path) {
            if (!"index.html".equals(path) && !"app.js".equals(path)) return BundledDictionaryHandler.notFound();
            return delegate.handle(path);
        }
    }

    /**
     * Serves a fixed allowlist of bundled dictionary files at /dictionary/. Gzipped assets are decompressed on the
     * fly, so the page reads plain text. Any other name gets a 404, which means no path can reach arbitrary assets.
     */
    private static final class BundledDictionaryHandler implements WebViewAssetLoader.PathHandler {

        private final Context context;

        BundledDictionaryHandler(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public WebResourceResponse handle(String path) {
            String assetPath;
            boolean gzipped;
            String mimeType;
            switch (path) {
                case "cc-cedict.txt":
                    assetPath = "dictionary/cc-cedict.txt.gz";
                    gzipped = true;
                    mimeType = "text/plain";
                    break;
                case "unihan-characters.json":
                    assetPath = "dictionary/unihan-characters.json.gz";
                    gzipped = true;
                    mimeType = "application/json";
                    break;
                case "segmentation.txt":
                    assetPath = "dictionary/segmentation.txt.gz";
                    gzipped = true;
                    mimeType = "text/plain";
                    break;
                case "manifest.json":
                    assetPath = "dictionary/manifest.json";
                    gzipped = false;
                    mimeType = "application/json";
                    break;
                case "NOTICE.txt":
                    assetPath = "dictionary/NOTICE.txt";
                    gzipped = false;
                    mimeType = "text/plain";
                    break;
                default:
                    return notFound();
            }

            try {
                InputStream raw = context.getAssets().open(assetPath);
                InputStream body = gzipped ? new GZIPInputStream(raw, 1 << 16) : raw;
                return new WebResourceResponse(mimeType, "UTF-8", 200, "OK",
                        Collections.singletonMap("Cache-Control", "no-store"), body);
            } catch (IOException ex) {
                return notFound();
            }
        }

        static WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        }
    }
}
