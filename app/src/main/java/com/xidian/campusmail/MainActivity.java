package com.xidian.campusmail;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {

    private static final String MAIL_URL = "https://mail.stu.xidian.edu.cn/coremail/";
    private static final String PREFS_NAME = "campus_mail";
    private static final String KEY_USER_ENC = "user_enc";
    private static final String KEY_PASS_ENC = "pass_enc";
    private static final String KEYSTORE_ALIAS = "campus_mail_key";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_DOWNLOAD_PERMISSION = 1002;
    private static final int GCM_IV_LEN = 12;

    // 自动登录探测：登录页由 JS 动态渲染，需要轮询等待表单出现
    private static final long AUTO_LOGIN_PROBE_INTERVAL_MS = 700L;
    private static final int AUTO_LOGIN_PROBE_MAX_TRIES = 25;      // 约 17 秒
    private static final long AUTO_LOGIN_COOLDOWN_MS = 6000L;      // 两次提交之间的最小间隔
    private static final int AUTO_LOGIN_MAX_ATTEMPTS = 3;          // 防止错误密码反复提交导致账号被锁
    // 探测脚本的返回状态
    private static final String JS_STATUS_LOGIN_PAGE = "w";        // 登录页已出现，表单还没渲染好
    private static final String JS_STATUS_NOT_LOGIN = "p";         // 不是登录页（已进入邮箱）
    private static final String JS_STATUS_BLOCKED = "b";           // 需要验证码/二次验证，或页面显示错误
    private static final String JS_STATUS_SUBMITTED = "s";

    private WebView webView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> filePathCallback;
    // 主页面加载失败标记（DNS/断网等），网络恢复后自动重试
    private boolean mainFrameLoadFailed;
    // 本次导航期间主页面是否发生过错误，用于在 onPageFinished 中区分成功与失败
    private boolean loadHadError;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String lastLoadedUrl = MAIL_URL;
    private boolean resumed;
    // 渲染进程在后台被回收时先记录，等回到前台再重建，避免在后台反复拉起渲染进程
    private boolean webViewNeedsRecreate;
    // API 33+ 的系统返回回调；低版本继续走 onBackPressed
    private Object systemBackCallback;

    private int autoLoginAttempts;
    private long lastAutoLoginSubmitAt;
    private boolean autoLoginBlockedNotified;

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createWebView();
        registerNetworkCallback();
        registerSystemBackCallback();
    }

    // ---------- WebView ----------

    /**
     * 创建（或重建）WebView。渲染进程被系统回收时也走这里，
     * 否则 WebView 会变成白屏，未处理时整个应用还会被判定为崩溃。
     */
    private void createWebView() {
        final WebView view = new WebView(this);
        webView = view;
        // 新页面即将从头加载，先清掉上一次会话的失败/错误标记
        mainFrameLoadFailed = false;
        loadHadError = false;

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false); // target=_blank loads in the same view
        settings.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(view, true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleExternalScheme(url);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                // 每次主页面导航开始时，重置本次加载的错误标记
                loadHadError = false;
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    lastLoadedUrl = url;
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(v, request, error);
                // 只处理主页面失败（忽略子资源），常见为 DNS 解析失败 net::ERR_NAME_NOT_RESOLVED
                if (!request.isForMainFrame()) {
                    return;
                }
                // 页面自己跳转、或加载中途被新的导航取代时，系统会以 ERR_ABORTED
                // 回调 onReceivedError。这不是网络故障：若当成故障处理，会误弹
                // "网络不可用"，并在下次网络变化时把用户正在看的页面整个重载。
                String desc = error.getDescription() != null ? error.getDescription().toString() : "";
                if (error.getErrorCode() == ERROR_UNKNOWN
                        && (desc.contains("ERR_ABORTED") || desc.contains("ERR_BLOCKED"))) {
                    return;
                }
                loadHadError = true;
                mainFrameLoadFailed = true;
                Toast.makeText(MainActivity.this, R.string.toast_network_retry,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                // 绝不在证书校验失败时继续加载（否则等于关掉 HTTPS 校验），
                // 但也不要不声不响地留一个白屏，给用户一个明确的提示
                handler.cancel();
                Toast.makeText(MainActivity.this, R.string.toast_ssl_error,
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (v != webView) {
                    return;
                }
                // 仅在本次加载成功（无主页面错误）时清除失败标记。
                // 注意：加载失败时 WebView 也会回调 onPageFinished，且此时 url 与 view.getUrl()
                // 相同（错误页显示原地址），因此必须结合 loadHadError 判断，否则失败后标记
                // 会被误清，导致网络恢复后不再自动重试
                if (!loadHadError && url != null && url.equals(v.getUrl())) {
                    mainFrameLoadFailed = false;
                }
                tryAutoLogin();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                // 系统在内存紧张或 WebView 组件更新后可能杀掉渲染进程。
                // 返回 true 表示已处理，避免整个应用崩溃，同时重建 WebView 恢复页面。
                if (v != webView) {
                    return true;
                }
                String url = v.getUrl();
                if (url == null || url.startsWith("about:")) {
                    url = lastLoadedUrl;
                }
                lastLoadedUrl = (url != null && !url.isEmpty()) ? url : MAIL_URL;
                destroyWebView();
                webViewNeedsRecreate = true;
                // 应用在后台时（如内存紧张被回收）不立刻重建并加载页面，
                // 否则可能在后台反复拉起渲染进程；等 onResume 再恢复
                if (resumed && !isFinishing() && !isDestroyed()) {
                    createWebView();
                }
                return true;
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    // 设备上没有文件选择器时，必须把回调置空，否则网页的文件上传
                    // 控件会一直"卡住"等待一个永远不会返回的选择结果
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, R.string.toast_file_picker_failed,
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                return true;
            }
        });

        view.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                pendingDownloadUrl = url;
                pendingDownloadUserAgent = userAgent;
                pendingDownloadDisposition = contentDisposition;
                pendingDownloadMime = mimetype;
                if (Build.VERSION.SDK_INT >= 29 || hasStoragePermission()) {
                    startDownload();
                } else {
                    requestPermissions(
                            new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_DOWNLOAD_PERMISSION);
                }
            }
        });

        setContentView(view);
        webViewNeedsRecreate = false;
        view.loadUrl(lastLoadedUrl);
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.setDownloadListener(null);
        webView.destroy();
        webView = null;
    }

    // 网络恢复后自动重载失败的页面，免去手动点「刷新」
    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    if (mainFrameLoadFailed && webView != null) {
                        mainFrameLoadFailed = false;
                        webView.reload();
                    }
                });
            }
        };
        cm.registerDefaultNetworkCallback(networkCallback);
        // 说明：registerDefaultNetworkCallback 需 API 24+，项目 minSdk 为 26，
        // 全部受支持设备均可调用，无需版本分支或 ConnectivityManager 以外的备选方案
    }

    private boolean handleExternalScheme(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        // WebView 自己就能处理的协议，不要拦截
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("blob:") || lower.startsWith("file:")
                || lower.startsWith("javascript:") || lower.startsWith("content:")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ---------- Auto login ----------

    private void tryAutoLogin() {
        if (autoLoginAttempts >= AUTO_LOGIN_MAX_ATTEMPTS) {
            return;
        }
        String[] creds = loadCredentials();
        if (creds == null) {
            return;
        }
        probeAutoLogin(creds[0], creds[1], 0);
    }

    private void probeAutoLogin(final String user, final String pass, final int tries) {
        final WebView view = webView;
        if (view == null || tries >= AUTO_LOGIN_PROBE_MAX_TRIES) {
            return;
        }
        if (autoLoginAttempts >= AUTO_LOGIN_MAX_ATTEMPTS) {
            return;
        }
        long sinceSubmit = System.currentTimeMillis() - lastAutoLoginSubmitAt;
        if (autoLoginAttempts > 0 && sinceSubmit < AUTO_LOGIN_COOLDOWN_MS) {
            uiHandler.postDelayed(() -> probeAutoLogin(user, pass, tries + 1),
                    AUTO_LOGIN_COOLDOWN_MS - sinceSubmit);
            return;
        }
        try {
            view.evaluateJavascript(buildAutoLoginJs(user, pass), value -> {
                if (view != webView || webView == null) {
                    return;
                }
                String status = value == null ? "" : value.replace("\"", "");
                if (JS_STATUS_SUBMITTED.equals(status)) {
                    autoLoginAttempts++;
                    lastAutoLoginSubmitAt = System.currentTimeMillis();
                    return;
                }
                if (JS_STATUS_NOT_LOGIN.equals(status)) {
                    // 已经进入邮箱页面（或根本没有登录表单），重置计数，
                    // 这样几天后会话过期时还能再次自动登录
                    autoLoginAttempts = 0;
                    autoLoginBlockedNotified = false;
                    return;
                }
                if (JS_STATUS_BLOCKED.equals(status)) {
                    if (!autoLoginBlockedNotified) {
                        autoLoginBlockedNotified = true;
                        Toast.makeText(MainActivity.this, R.string.toast_autologin_manual,
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                // 登录页还没渲染完，继续等
                uiHandler.postDelayed(() -> probeAutoLogin(user, pass, tries + 1),
                        AUTO_LOGIN_PROBE_INTERVAL_MS);
            });
        } catch (Exception ignored) {
            // WebView 刚被销毁等极端情况，忽略即可
        }
    }

    /**
     * 探测并填写登录表单。
     * 西电 Coremail 登录页的真实结构：用户名字段 #uid，可见密码框 #fakePassword
     * （提交时由页面自己的 JS 加密后写入隐藏字段 #password），登录按钮 .j-submit。
     * 只填可见密码框，绝不写隐藏字段，也不在没有把握时盲目提交。
     */
    private static String buildAutoLoginJs(String username, String password) {
        return "(function(){"
                + "var USER=" + jsQuote(username) + ",PASS=" + jsQuote(password) + ";"
                + "try{"
                + "var f=document.querySelector('form.j-login-form');"
                + "if(!f){return document.querySelector('.seo-link, .loginArea, .aside')?'" + JS_STATUS_LOGIN_PAGE + "':'" + JS_STATUS_NOT_LOGIN + "';}"
                + "var v=function(el){return !!(el&&el.offsetParent!==null&&el.getClientRects().length);};"
                // 验证码、二次验证已出现时必须停下来让人工处理
                + "if(v(document.querySelector('.j-second-auth-wrap')))return '" + JS_STATUS_BLOCKED + "';"
                + "if(v(document.querySelector('input[name=verifyCode]')))return '" + JS_STATUS_BLOCKED + "';"
                + "if(v(document.querySelector('input[name=verifyCellCode]')))return '" + JS_STATUS_BLOCKED + "';"
                // 页面上已经有错误提示（如密码错误）时不再重复提交，避免账号被锁
                + "var w=document.querySelector('#warnOrErrDiv');"
                + "if(w&&v(w)&&(w.textContent||'').replace(/\\s+/g,''))return '" + JS_STATUS_BLOCKED + "';"
                + "var u=f.querySelector('#uid, input[name=uid], input[name=userName], input[name=username]');"
                + "var p=f.querySelector('#fakePassword')||f.querySelector('input[type=password]');"
                + "if(!v(u)||!v(p))return '" + JS_STATUS_LOGIN_PAGE + "';"
                + "var s=function(el,val){"
                + "var proto=Object.getPrototypeOf(el);"
                + "var d=Object.getOwnPropertyDescriptor(proto,'value');"
                + "if(!d||!d.set){el.value=val;}else{d.set.call(el,val);}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "};"
                + "s(u,USER);s(p,PASS);"
                // 只操作真正的登录按钮；当前页面按钮为 .j-submit（type=button）
                + "var b=f.querySelector('.j-submit, button[type=submit], input[type=submit], .btn-login, .btn-submit, #dologin');"
                + "if(!v(b))return '" + JS_STATUS_BLOCKED + "';"
                + "setTimeout(function(){try{b.click();}catch(e){}},300);"
                + "return '" + JS_STATUS_SUBMITTED + "';"
                + "}catch(e){return '" + JS_STATUS_BLOCKED + "';}"
                + "})()";
    }

    private static String jsQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }

    // ---------- Encrypted credential storage (Android Keystore AES/GCM) ----------

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return keyGenerator.generateKey();
    }

    private String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private String decrypt(String encoded) {
        try {
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] iv = Arrays.copyOfRange(all, 0, GCM_IV_LEN);
            byte[] cipherText = Arrays.copyOfRange(all, GCM_IV_LEN, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return 是否成功保存。加密失败时不再假装保存成功。
     */
    private boolean saveCredentials(String username, String password) {
        String userEnc = encrypt(username);
        String passEnc = encrypt(password);
        if (userEnc == null || passEnc == null) {
            return false;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_ENC, userEnc)
                .putString(KEY_PASS_ENC, passEnc)
                .apply();
        // 换了新密码后重新允许自动登录
        autoLoginAttempts = 0;
        autoLoginBlockedNotified = false;
        return true;
    }

    private String[] loadCredentials() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String userEnc = prefs.getString(KEY_USER_ENC, null);
        String passEnc = prefs.getString(KEY_PASS_ENC, null);
        if (userEnc == null || passEnc == null) {
            return null;
        }
        String user = decrypt(userEnc);
        String pass = decrypt(passEnc);
        if (user == null || pass == null) {
            return null;
        }
        return new String[]{user, pass};
    }

    private void clearCredentials() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_USER_ENC)
                .remove(KEY_PASS_ENC)
                .apply();
        autoLoginAttempts = 0;
        autoLoginBlockedNotified = false;
    }

    // ---------- Save login dialog ----------

    private void showSaveLoginDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        final EditText userInput = new EditText(this);
        userInput.setHint(getString(R.string.dialog_username_hint));
        userInput.setSingleLine(true);

        final EditText passInput = new EditText(this);
        passInput.setHint(getString(R.string.dialog_password_hint));
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        String[] existing = loadCredentials();
        if (existing != null) {
            userInput.setText(existing[0]);
            passInput.setText(existing[1]);
        }

        layout.addView(userInput);
        layout.addView(passInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f));

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_save_login_title)
                .setView(layout)
                .setPositiveButton(R.string.dialog_save, (dialog, which) -> {
                    String user = userInput.getText().toString().trim();
                    String pass = passInput.getText().toString();
                    if (user.isEmpty() || pass.isEmpty()) {
                        Toast.makeText(this, R.string.toast_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!saveCredentials(user, pass)) {
                        Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
                    if (webView != null) {
                        webView.reload();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    // ---------- Downloads ----------

    private boolean hasStoragePermission() {
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_DOWNLOAD_PERMISSION) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDownload();
        } else {
            clearPendingDownload();
            Toast.makeText(this, R.string.toast_storage_denied, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 用 DownloadManager 下载附件。
     * DownloadManager 不会自动带上 WebView 的会话 Cookie，必须手动附加，
     * 否则校园邮箱的附件会下载到登录页 / 401 页面。
     */
    private void startDownload() {
        final String url = pendingDownloadUrl;
        if (url == null) {
            return;
        }
        final String ua = pendingDownloadUserAgent;
        final String mime = (pendingDownloadMime == null || pendingDownloadMime.isEmpty())
                ? "application/octet-stream" : pendingDownloadMime;
        final String baseName = guessFileName(pendingDownloadDisposition, url);
        try {
            try {
                enqueueDownload(url, ua, mime, uniqueDownloadName(baseName));
            } catch (IllegalArgumentException exists) {
                // Android 10+ 分区存储下，File.exists() 可能看不到其它应用已下载的同名文件，
                // 而 DownloadManager 遇到已存在的目标会直接抛 IllegalArgumentException。
                // 此时换一个带时间戳的名字再试一次，而不是让下载直接失败。
                enqueueDownload(url, ua, mime, timestampedName(baseName));
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_download_failed, Toast.LENGTH_LONG).show();
        } finally {
            clearPendingDownload();
        }
    }

    private void enqueueDownload(String url, String userAgent, String mime, String fileName)
            throws Exception {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            throw new IllegalStateException("DownloadManager unavailable");
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(mime);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setTitle(fileName);
        if (userAgent != null && !userAgent.isEmpty()) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null && !cookie.isEmpty()) {
            request.addRequestHeader("Cookie", cookie);
        }
        request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, fileName);
        dm.enqueue(request);
    }

    /** 在扩展名前插入时间戳，用于规避分区存储下看不到的同名文件。 */
    private static String timestampedName(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (safe.isEmpty()) {
            safe = "download";
        }
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        return base + "-" + System.currentTimeMillis() + ext;
    }

    private void clearPendingDownload() {
        pendingDownloadUrl = null;
        pendingDownloadUserAgent = null;
        pendingDownloadDisposition = null;
        pendingDownloadMime = null;
    }

    /**
     * Downloads 目录里已有同名文件时 DownloadManager 会直接失败，
     * 这里生成一个不冲突的文件名。
     */
    private static String uniqueDownloadName(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (safe.isEmpty()) {
            safe = "download";
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String base = safe;
        String ext = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot);
        }
        String candidate = safe;
        for (int i = 1; i <= 50 && new File(dir, candidate).exists(); i++) {
            candidate = base + " (" + i + ")" + ext;
        }
        if (new File(dir, candidate).exists()) {
            candidate = base + "-" + System.currentTimeMillis() + ext;
        }
        return candidate;
    }

    private static String guessFileName(String contentDisposition, String url) {
        if (contentDisposition != null) {
            int idx = contentDisposition.indexOf("filename=");
            if (idx >= 0) {
                String name = contentDisposition.substring(idx + "filename=".length())
                        .replace("\"", "").trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        String path = Uri.parse(url).getLastPathSegment();
        return (path != null && !path.isEmpty()) ? path : "download";
    }

    // ---------- Menu ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            if (webView != null) {
                webView.reload();
            }
            return true;
        } else if (id == R.id.action_save_login) {
            showSaveLoginDialog();
            return true;
        } else if (id == R.id.action_clear_login) {
            clearCredentials();
            Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_open_browser) {
            String url = webView != null ? webView.getUrl() : null;
            if (url != null) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // 仅为 API 26–32 保留；API 33+ 已注册 OnBackInvokedCallback（见 registerSystemBackCallback），
    // 系统会走那条路径。lint 的 GestureBackNavigation 检查看不到运行时分派逻辑，
    // 且本工程不使用 AndroidX，故这里显式说明并抑制该条告警。
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    /**
     * targetSdk 36 时 android:enableOnBackInvokedCallback="false" 已失效，
     * onBackPressed() 在 Android 16 上不会再被调用。必须注册系统返回回调，
     * 否则在邮箱里按返回键会直接退出应用，而不是返回上一页。
     */
    @SuppressLint("NewApi")
    private void registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        OnBackInvokedCallback callback = this::handleBack;
        systemBackCallback = callback;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
    }

    @SuppressLint("NewApi")
    private void unregisterSystemBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || systemBackCallback == null) {
            return;
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (OnBackInvokedCallback) systemBackCallback);
        systemBackCallback = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                // parseResult 能正确处理多选（ClipData）以及各类文件管理器的返回，
                // 只取 data.getData() 在多选时会拿到 null，导致上传失败
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (webView != null) {
            webView.onPause();
        }
        // 把 WebView 的 Cookie 落盘。进程被系统回收时，未落盘的会话 Cookie 会丢失，
        // 导致「明明登录过，下次打开又要重新登录」
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (webView == null && webViewNeedsRecreate && !isFinishing() && !isDestroyed()) {
            createWebView();
        } else if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        unregisterSystemBackCallback();
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        destroyWebView();
        super.onDestroy();
    }
}
