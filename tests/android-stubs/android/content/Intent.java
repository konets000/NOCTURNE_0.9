package android.content;
import android.net.Uri;
public class Intent {
  public static final String ACTION_OPEN_DOCUMENT = "a", ACTION_VIEW = "b", CATEGORY_OPENABLE = "c", EXTRA_ALLOW_MULTIPLE = "d";
  public static final int FLAG_GRANT_READ_URI_PERMISSION = 1;
  public Intent(String action) {}
  public Intent(String action, Uri uri) {}
  public void addCategory(String c) {}
  public Intent setType(String t) { return this; }
  public void addFlags(int f) {}
  public Intent putExtra(String k, boolean v) { return this; }
  public ClipData getClipData() { return null; }
  public Uri getData() { return null; }
  public static class ClipData {
    public int getItemCount() { return 0; }
    public Item getItemAt(int i) { return null; }
    public static class Item { public Uri getUri() { return null; } }
  }
}