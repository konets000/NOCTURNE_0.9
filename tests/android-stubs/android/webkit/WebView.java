package android.webkit;
import android.content.Context;
public class WebView extends android.view.View {
  public WebView(Context c) {}
  public static void setWebContentsDebuggingEnabled(boolean e) {}
  public void setBackgroundColor(int c) {}
  public WebSettings getSettings() { return null; }
  public void setWebChromeClient(WebChromeClient c) {}
  public void setWebViewClient(WebViewClient c) {}
  public void evaluateJavascript(String s, ValueCallback<String> cb) {}
  public void loadUrl(String u) {}
  public void onPause() {}
  public void onResume() {}
  public void stopLoading() {}
  public void destroy() {}
}