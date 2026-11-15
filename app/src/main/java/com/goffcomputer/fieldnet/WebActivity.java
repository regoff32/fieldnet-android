package com.goffcomputer.fieldnet;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Simple browser for a device's admin page. Runs in the same app, so its
 * traffic stays on the Ethernet adapter like the rest of FieldNet.
 */
public class WebActivity extends Activity {

    WebView web;
    EditText urlBox;

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setTextSize(14);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go();
                return true;
            }
            return false;
        });
        bar.addView(urlBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button goBtn = new Button(this);
        goBtn.setText("Go");
        goBtn.setAllCaps(false);
        goBtn.setOnClickListener(v -> go());
        bar.addView(goBtn);

        Button reload = new Button(this);
        reload.setText("Reload");
        reload.setAllCaps(false);
        reload.setOnClickListener(v -> web.reload());
        bar.addView(reload);

        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new Client());
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        String url = getIntent().getStringExtra("url");
        if (url == null) url = "http://192.168.1.1";
        urlBox.setText(url);
        web.loadUrl(url);
    }

    void go() {
        String u = urlBox.getText().toString().trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        web.loadUrl(u);
    }

    class Client extends WebViewClient {
        @Override
        public void onPageFinished(WebView v, String url) {
            urlBox.setText(url);
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) {
                Toast.makeText(WebActivity.this,
                        "Couldn't load page: " + err.getDescription(), Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler h, SslError error) {
            new AlertDialog.Builder(WebActivity.this)
                    .setTitle("Certificate not trusted")
                    .setMessage("This device's security certificate isn't trusted. That's "
                            + "normal for routers, radios, cameras and printers on a local "
                            + "network.\n\nOnly continue if this is the device you're working on.")
                    .setPositiveButton("Continue", (d, w) -> h.proceed())
                    .setNegativeButton("Cancel", (d, w) -> h.cancel())
                    .setOnCancelListener(d -> h.cancel())
                    .show();
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
            LinearLayout box = new LinearLayout(WebActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(8), dp(20), 0);
            EditText user = new EditText(WebActivity.this);
            user.setHint("Username");
            user.setSingleLine(true);
            EditText pass = new EditText(WebActivity.this);
            pass.setHint("Password");
            pass.setSingleLine(true);
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(user);
            box.addView(pass);
            new AlertDialog.Builder(WebActivity.this)
                    .setTitle("Log in to " + host)
                    .setView(box)
                    .setPositiveButton("Log in", (d, w) ->
                            h.proceed(user.getText().toString(), pass.getText().toString()))
                    .setNegativeButton("Cancel", (d, w) -> h.cancel())
                    .setOnCancelListener(d -> h.cancel())
                    .show();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
