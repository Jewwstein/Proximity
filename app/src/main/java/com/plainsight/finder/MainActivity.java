package com.plainsight.finder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 11;
    private static final int SPEECH_REQUEST = 12;
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private WebView web;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF090C15);
        getWindow().setNavigationBarColor(0xFF090C15);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_FULLSCREEN);
        web = new WebView(this);
        web.setBackgroundColor(0xFF090C15);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.addJavascriptInterface(new AndroidBridge(), "PlainSightAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("appassets.androidplatform.net".equals(url.getHost())) {
                    String path = url.getPath();
                    if ("/index.html".equals(path) || "/personal.js".equals(path)) {
                        try {
                            String asset = path.substring(1);
                            InputStream in = getAssets().open(asset);
                            return new WebResourceResponse(
                                path.endsWith(".js") ? "text/javascript" : "text/html",
                                "UTF-8", in);
                        } catch (Exception ignored) { }
                    }
                }
                return null;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (request.getOrigin().toString().startsWith(APP_ORIGIN) &&
                            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        for (String resource : request.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                                return;
                            }
                        }
                    }
                    request.deny();
                });
            }
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                android.util.Log.d("PlainSightWeb", message.message());
                return true;
            }
        });
        setContentView(web, new FrameLayout.LayoutParams(-1,-1));
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else {
            open();
        }
    }

    private void open() {
        web.loadUrl(APP_ORIGIN + "/index.html");
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == CAMERA_PERMISSION) {
            open();
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                web.evaluateJavascript("window.cameraPermissionDenied && window.cameraPermissionDenied()", null);
            }
        }
    }

    private class AndroidBridge {
        @JavascriptInterface public void speak() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
                    intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "What should I find?");
                    startActivityForResult(intent, SPEECH_REQUEST);
                } catch (Exception e) {
                    web.evaluateJavascript("window.speechUnavailable && window.speechUnavailable()", null);
                }
            });
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SPEECH_REQUEST && result == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                web.evaluateJavascript("window.onSpeech(" + JSONObject.quote(results.get(0)) + ")", null);
            }
        }
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.resetSearch && window.resetSearch()", null);
    }

    @Override protected void onPause() {
        super.onPause();
        web.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }
    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
