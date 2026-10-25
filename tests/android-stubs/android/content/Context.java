package android.content;
import android.content.res.AssetManager;
public class Context {
  public Context getApplicationContext() { return this; }
  public AssetManager getAssets() { return new AssetManager(); }
}
