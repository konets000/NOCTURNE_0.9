package androidx.activity;
public abstract class OnBackPressedCallback {
  public OnBackPressedCallback(boolean enabled) {}
  public void setEnabled(boolean e) {}
  public abstract void handleOnBackPressed();
}