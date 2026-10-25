package android.webkit;
import android.net.Uri;
public class WebChromeClient {
  public static abstract class FileChooserParams {
    public static final int MODE_OPEN_MULTIPLE = 1;
    public abstract int getMode();
  }
  public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) { return false; }
}