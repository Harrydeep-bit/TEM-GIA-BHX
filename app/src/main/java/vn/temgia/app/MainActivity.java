package vn.temgia.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private static final int REQ_FILE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            Window w = getWindow();
            w.setStatusBarColor(Color.parseColor("#0A8048"));
        }
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        web.setWebViewClient(new WebViewClient());
        // Cho phép bấm nút chọn file (nạp file khuyến mãi .xlsx)
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Chọn file"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidPrinter");
        String html = readAsset("index.html");
        // Gốc https cố định để trang nhớ được cài đặt và danh sách tem (localStorage)
        web.loadDataWithBaseURL("https://temgia.app/", html, "text/html", "utf-8", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCb = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<h3>Không mở được trang in tem: " + e + "</h3>";
        }
    }

    private void reply(final int id, final boolean ok, final String error, final int bytes) {
        final String json;
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            if (error != null) o.put("error", error);
            if (bytes >= 0) o.put("bytes", bytes);
            json = JSONObject.quote(o.toString());
        } catch (Exception e) {
            return;
        }
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.__printCb && window.__printCb(" + id + "," + json + ")", null);
            }
        });
    }

    private static String errText(Exception e) {
        if (e instanceof SocketTimeoutException) return "hết thời gian chờ (máy in không phản hồi)";
        if (e instanceof NoRouteToHostException) return "không tìm thấy máy in trong mạng (điện thoại chưa vào wifi siêu thị?)";
        if (e instanceof UnknownHostException) return "địa chỉ IP không đúng";
        if (e instanceof ConnectException) return "không kết nối được máy in (máy tắt, khác mạng wifi, hoặc cổng 9100 đang bận)";
        return String.valueOf(e.getMessage());
    }

    private class Bridge {
        @JavascriptInterface
        public void ping(final int id, final String ip, final int port) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket so = new Socket()) {
                        so.connect(new InetSocketAddress(ip, port), 4000);
                        reply(id, true, null, -1);
                    } catch (Exception e) {
                        reply(id, false, errText(e), -1);
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void send(final int id, final String ip, final int port, final String b64) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket so = new Socket()) {
                        byte[] data = Base64.decode(b64, Base64.DEFAULT);
                        so.connect(new InetSocketAddress(ip, port), 5000);
                        so.setSoTimeout(15000);
                        OutputStream out = so.getOutputStream();
                        out.write(data);
                        out.flush();
                        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                        reply(id, true, null, data.length);
                    } catch (Exception e) {
                        reply(id, false, errText(e), -1);
                    }
                }
            }).start();
        }
    }
}
