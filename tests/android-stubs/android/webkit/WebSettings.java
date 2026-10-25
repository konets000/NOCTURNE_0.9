package android.webkit;
public abstract class WebSettings {
  public static final int MIXED_CONTENT_NEVER_ALLOW = 1;
  public abstract void setJavaScriptEnabled(boolean f);
  public abstract void setDomStorageEnabled(boolean f);
  public abstract void setAllowFileAccess(boolean f);
  public abstract void setAllowContentAccess(boolean f);
  public abstract void setMediaPlaybackRequiresUserGesture(boolean f);
  public abstract void setSupportMultipleWindows(boolean f);
  public abstract void setJavaScriptCanOpenWindowsAutomatically(boolean f);
  public abstract void setMixedContentMode(int m);
}