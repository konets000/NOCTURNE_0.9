package androidx.webkit;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
public final class WebViewAssetLoader {
  public interface PathHandler { WebResourceResponse handle(String path); }
  public static final class AssetsPathHandler implements PathHandler {
    public AssetsPathHandler(Context c) {}
    public WebResourceResponse handle(String p) { return null; }
  }
  public WebResourceResponse shouldInterceptRequest(Uri uri) { return null; }
  public static final class Builder {
    public Builder setDomain(String d) { return this; }
    public Builder addPathHandler(String prefix, PathHandler h) { return this; }
    public WebViewAssetLoader build() { return new WebViewAssetLoader(); }
  }
}