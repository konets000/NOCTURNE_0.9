package android.webkit;
import java.io.InputStream; import java.util.Map;
public class WebResourceResponse {
  public WebResourceResponse(String mimeType, String encoding, InputStream data) {}
  public WebResourceResponse(String mimeType, String encoding, int status, String reason, Map<String,String> headers, InputStream data) {}
}