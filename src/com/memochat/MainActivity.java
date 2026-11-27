package com.memochat;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 单 Activity 壳：WebView 承载界面，原生负责网络、存储、记忆。 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0A0D15;

    private WebView web;
    private Bridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.setNavigationBarContrastEnforced(false);
        }
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        // 3D 模型（.vrm/.glb）要用 XHR 从 https://memochat.local 读本地文件，
        // file:// 页面跨到 https 需要放开这一项；页面内容全在 APK 里，没有外部脚本。
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 通话预览是 <video> 直接播 getUserMedia 的流，需要免手势自动播放
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsVideo = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsVideo = true;
                    }
                    if (wantsVideo && checkSelfPermission(android.Manifest.permission.CAMERA)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                // 站外链接一律交给系统浏览器，App 内只跑本地页面
                android.net.Uri uri = request.getUrl();
                if (uri != null && "file".equals(uri.getScheme())) return false;
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                // 用户上传的形象图走本地虚拟域名，不读外网、也不塞 base64
                android.net.Uri u = request.getUrl();
                if (u != null && AvatarStore.HOST.equals(u.getHost())) {
                    return AvatarStore.respond(MainActivity.this, u.getPath());
                }
                return null;
            }
        });

        bridge = new Bridge(this, web);
        web.clearCache(true);
        web.addJavascriptInterface(bridge, "Native");
        web.loadUrl("file:///android_asset/index.html?v=4.8");
        setContentView(web);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9002 && bridge != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            bridge.onVoicePermissionResult(granted);
            return;
        }
        if (requestCode == 9001 && bridge != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            bridge.onCameraPermissionResult(granted);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (bridge != null) bridge.onPickResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__back && window.__back()) ? '1' : '0'", value ->
                runOnUiThread(() -> {
                    if (value == null || !value.contains("1")) super.onBackPressed();
                }));
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            ProactiveJobService.ensureChannel(this);
            ProactiveScheduler.schedule(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            ProactiveScheduler.schedule(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("Native");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
