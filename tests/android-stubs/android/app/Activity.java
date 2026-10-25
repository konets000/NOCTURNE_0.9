package android.app;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
public abstract class Activity extends android.content.Context {
  public static final int RESULT_OK = -1;
  protected void onCreate(Bundle b) {}
  protected void onPause() {}
  protected void onResume() {}
  protected void onDestroy() {}
  public void finish() {}
  public void startActivity(Intent i) throws android.content.ActivityNotFoundException {}
  public void setContentView(View v) {}
  public android.view.Window getWindow() { return new android.view.Window(); }
}