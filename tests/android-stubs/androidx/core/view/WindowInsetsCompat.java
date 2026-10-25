package androidx.core.view;
import androidx.core.graphics.Insets;
public class WindowInsetsCompat {
  public static final class Type {
    public static int systemBars() { return 1; }
    public static int displayCutout() { return 2; }
    public static int ime() { return 4; }
  }
  public Insets getInsets(int typeMask) { return new Insets(0, 0, 0, 0); }
}
