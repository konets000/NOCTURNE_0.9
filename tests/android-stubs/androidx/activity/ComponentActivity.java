package androidx.activity;
import android.app.Activity;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.LifecycleOwner;
public class ComponentActivity extends Activity implements LifecycleOwner {
  public final OnBackPressedDispatcher getOnBackPressedDispatcher() { return new OnBackPressedDispatcher(); }
  public final <I, O> ActivityResultLauncher<I> registerForActivityResult(
      ActivityResultContracts.StartActivityForResult contract, ActivityResultCallback<androidx.activity.result.ActivityResult> cb) { return null; }
}