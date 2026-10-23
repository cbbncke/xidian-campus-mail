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
import android.graphics.Color;
import android.graphics.Insets;
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
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
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

    // 手机版（hxphone）是独立入口，直接打开可避免被服务端识别成桌面浏览器后
    // 跳回 PC 版网页（PC 版在手机上会被挤成桌面布局）
    private static final String MOBILE_MAIL_URL =
            "https://mail.stu.xidian.edu.cn/coremail/hxphone/";
    private static final String DESKTOP_MAIL_URL =
            "https://mail.stu.xidian.edu.cn/coremail/";
    private static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Safari/537.36";
    private static final String KEY_DESKTOP_MODE = "desktop_mode";
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
    private LinearLayout rootLayout;
    private LinearLayout headerView;
    private FrameLayout webContainer;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> filePathCallback;
    // 主页面加载失败标记（DNS/断网等），网络恢复后自动重试
    private boolean mainFrameLoadFailed;
    // 本次导航期间主页面是否发生过错误，用于在 onPageFinished 中区分成功与失败
    private boolean loadHadError;
    private ConnectivityManager.NetworkCallback networkCallback;
    // 默认手机版；有些学校可能关闭手机版登录权限，菜单里可切回电脑版页面
    private boolean desktopMode;
    private String lastLoadedUrl = MOBILE_MAIL_URL;
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
        desktopMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_DESKTOP_MODE, false);
        lastLoadedUrl = currentMailUrl();
        buildLayout();
        createWebView();
        registerNetworkCallback();
        registerSystemBackCallback();
    }

    // ---------- Layout ----------

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /**
     * 自己画标题栏，而不是用系统 ActionBar。
     * Android 15 起系统强制 edge-to-edge，系统 ActionBar 与内容区的 insets
     * 处理不一致，网页顶部会被标题栏盖住（用户能在手机上看到正文第一行被截断）。
     * 这里用「标题栏 + 网页容器」的纵向布局，并显式把系统栏 insets 变成内边距。
     */
    private void buildLayout() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.WHITE);

        headerView = new LinearLayout(this);
        headerView.setOrientation(LinearLayout.HORIZONTAL);
        headerView.setGravity(Gravity.CENTER_VERTICAL);
        headerView.setBackgroundColor(0xFF1565C0);
        headerView.setPadding(dp(16), 0, dp(8), 0);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        headerView.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));

        TextView refresh = makeHeaderAction(getString(R.string.menu_refresh));
        refresh.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });
        headerView.addView(refresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(56)));

        TextView more = makeHeaderAction("\u22ee");
        more.setContentDescription(getString(R.string.menu_more));
        more.setOnClickListener(this::showOverflowMenu);
        headerView.addView(more, new LinearLayout.LayoutParams(dp(44), dp(56)));

        rootLayout.addView(headerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        webContainer = new FrameLayout(this);
        rootLayout.addView(webContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootLayout);
        applyEdgeToEdge();
    }

    private TextView makeHeaderAction(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    /**
     * Android 15+ 会强制边到边；这里主动把系统栏区域交给自己的布局处理：
     * 标题栏顶部加上状态栏高度（蓝色延伸到状态栏后面），
     * 网页底部加上导航栏/输入法高度，内容不再被系统栏或标题栏遮挡。
     */
    @SuppressLint("NewApi")
    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 30) {
            // API 26–29 仍由系统把内容排在系统栏之内，无需额外处理
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            // 状态栏在蓝色标题栏之上，用白色图标；导航栏在白色页面上，用深色图标
            controller.setSystemBarsAppearance(
                    0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            headerView.setPadding(dp(16) + bars.left, bars.top, dp(8) + bars.right, 0);
            webContainer.setPadding(bars.left, 0, bars.right,
                    Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
        rootLayout.requestApplyInsets();
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
        // 显式声明 UA：手机版下避免服务端把 WebView 当成桌面浏览器而返回 PC 版页面；
        // 电脑版则需要桌面 UA，否则会被重定向回手机版
        settings.setUserAgentString(desktopMode ? DESKTOP_USER_AGENT : MOBILE_USER_AGENT);

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
                lastLoadedUrl = (url != null && !url.isEmpty()) ? url : currentMailUrl();
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

        if (webContainer != null) {
            webContainer.removeAllViews();
            webContainer.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            setContentView(view);
        }
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
     * 探测并填写登录表单，同时支持两套页面：
     *
     * 1. 手机版 hxphone（AngularJS）：form.login-form，账号 input.account-input，
     *    密码 input.password，登录按钮 #anchor。
     * 2. 电脑版登录页：用户名 #uid，可见密码框 #fakePassword（提交时页面自己的 JS
     *    加密后写入隐藏字段 #password），按钮 .j-submit。
     *
     * 只填可见字段，绝不直接写隐藏的 #password；验证码 / 二次验证 / 已有错误提示时
     * 直接停下交给人工处理。
     */
    private static String buildAutoLoginJs(String username, String password) {
        return "(function(){"
                + "var USER=" + jsQuote(username) + ",PASS=" + jsQuote(password) + ";"
                + "try{"
                + "var v=function(el){return !!(el&&el.offsetParent!==null&&el.getClientRects().length);};"
                + "var set=function(el,val){"
                + "var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');"
                + "if(!d||!d.set){el.value=val;}else{d.set.call(el,val);}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "};"
                // 填完等 Angular / 页面脚本消化完再点；按钮仍禁用（例如还在做环境检查）
                // 就重试几次，而不是把一次没点出去的提交计成已尝试
                + "var fill=function(u,p,b){"
                + "if(!v(u)||!v(p))return '" + JS_STATUS_LOGIN_PAGE + "';"
                + "set(u,USER);set(p,PASS);"
                + "if(!v(b))return '" + JS_STATUS_BLOCKED + "';"
                + "var go=function(n){"
                + "if(!b.disabled){try{b.click();}catch(e){}return;}"
                + "if(n<6){set(u,USER);set(p,PASS);setTimeout(function(){go(n+1);},400);}"
                + "};"
                + "setTimeout(function(){go(0);},300);"
                + "return '" + JS_STATUS_SUBMITTED + "';"
                + "};"
                // ---- 手机版 ----
                + "var mf=document.querySelector('form.login-form');"
                + "if(mf){"
                + "if(v(mf.querySelector('input[ng-model=\"loginParams.verifyCode\"]'))"
                + "||v(mf.querySelector('input[ng-model=\"loginParams.dynamicPwd\"]'))"
                + "||v(document.querySelector('#vercodePic')))return '" + JS_STATUS_BLOCKED + "';"
                + "return fill("
                + "mf.querySelector('input.account-input')||mf.querySelector('input[ng-model=\"loginParams.uid\"]'),"
                + "mf.querySelector('input.password')||mf.querySelector('input[ng-model=\"loginParams.password\"]'),"
                + "document.querySelector('#anchor')||mf.querySelector('button'));"
                + "}"
                // ---- 电脑版 ----
                + "var df=document.querySelector('form.j-login-form');"
                + "if(df){"
                + "if(v(document.querySelector('.j-second-auth-wrap'))"
                + "||v(document.querySelector('input[name=verifyCode]'))"
                + "||v(document.querySelector('input[name=verifyCellCode]')))return '" + JS_STATUS_BLOCKED + "';"
                + "var w=document.querySelector('#warnOrErrDiv');"
                + "if(w&&v(w)&&(w.textContent||'').replace(/\\s+/g,''))return '" + JS_STATUS_BLOCKED + "';"
                + "return fill("
                + "df.querySelector('#uid,input[name=uid],input[name=userName],input[name=username]'),"
                + "df.querySelector('#fakePassword')||df.querySelector('input[type=password]'),"
                + "df.querySelector('.j-submit,button[type=submit],input[type=submit],.btn-login,.btn-submit'));"
                + "}"
                // ---- 还在加载，或已经不是登录页 ----
                + "if(location.hash&&location.hash.indexOf('login')>=0)return '" + JS_STATUS_LOGIN_PAGE + "';"
                + "if(!location.hash&&document.querySelector('.index-loading-container'))return '" + JS_STATUS_LOGIN_PAGE + "';"
                + "if(document.querySelector('.seo-link,.loginArea,.aside'))return '" + JS_STATUS_LOGIN_PAGE + "';"
                + "return '" + JS_STATUS_NOT_LOGIN + "';"
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

    private String currentMailUrl() {
        return desktopMode ? DESKTOP_MAIL_URL : MOBILE_MAIL_URL;
    }

    /**
     * 手机版 / 电脑版切换。
     * 极少数情况下学校会关闭手机版（hxphone）的登录权限，这时可以用电脑版页面；
     * 切换后记住选择，下次启动直接进入对应版本。
     */
    private void toggleWebmailMode() {
        desktopMode = !desktopMode;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_DESKTOP_MODE, desktopMode).apply();
        autoLoginAttempts = 0;
        autoLoginBlockedNotified = false;
        lastLoadedUrl = currentMailUrl();
        if (webView != null) {
            webView.getSettings().setUserAgentString(
                    desktopMode ? DESKTOP_USER_AGENT : MOBILE_USER_AGENT);
            webView.loadUrl(lastLoadedUrl);
        }
        Toast.makeText(this, desktopMode
                ? R.string.toast_switched_desktop : R.string.toast_switched_mobile,
                Toast.LENGTH_SHORT).show();
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

    /** 自绘标题栏没有系统 ActionBar，溢出菜单用 PopupMenu 弹出同一份菜单资源。 */
    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
        // 刷新已经有独立按钮，菜单里不再重复
        MenuItem refresh = popup.getMenu().findItem(R.id.action_refresh);
        if (refresh != null) {
            refresh.setVisible(false);
        }
        MenuItem switchMode = popup.getMenu().findItem(R.id.action_switch_mode);
        if (switchMode != null) {
            switchMode.setTitle(desktopMode
                    ? R.string.menu_switch_mobile : R.string.menu_switch_desktop);
        }
        popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
        popup.show();
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
        } else if (id == R.id.action_switch_mode) {
            toggleWebmailMode();
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
