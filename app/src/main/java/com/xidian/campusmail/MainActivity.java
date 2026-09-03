package com.xidian.campusmail;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

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

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false); // target=_blank loads in the same view
        settings.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalScheme(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                tryAutoLogin();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
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

        webView.loadUrl(MAIL_URL);
    }

    private boolean handleExternalScheme(String url) {
        if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    // ---------- Auto login ----------

    private void tryAutoLogin() {
        String[] creds = loadCredentials();
        if (creds == null) {
            return;
        }
        String js = buildAutoLoginJs(creds[0], creds[1]);
        webView.evaluateJavascript(js, null);
    }

    private static String buildAutoLoginJs(String username, String password) {
        return "(function() {"
                + "var USER=" + jsQuote(username) + ";"
                + "var PASS=" + jsQuote(password) + ";"
                + "var tries=0;"
                + "var timer=setInterval(function(){"
                + "tries++;"
                + "var pwd=document.querySelector('#password, input[type=password]');"
                + "if(!pwd){ if(tries>20) clearInterval(timer); return; }"
                + "clearInterval(timer);"
                + "var user=document.querySelector('#userName, #username, input[name=userName], input[name=username]');"
                + "if(!user){"
                + "var inputs=document.querySelectorAll('input[type=text]');"
                + "for(var i=0;i<inputs.length;i++){ if(inputs[i].offsetParent!==null){ user=inputs[i]; break; } }"
                + "}"
                + "function setVal(el,val){"
                + "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;"
                + "setter.call(el,val);"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "}"
                + "if(user) setVal(user,USER);"
                + "setVal(pwd,PASS);"
                + "var captcha=document.querySelector('#captcha, #captchaInput, #checkCode, #verifyCode, #jcaptcha, input[name*=captcha i], img[src*=captcha i]');"
                + "if(captcha) return;"
                + "var btn=document.querySelector('#dologin, #login_button, #loginBtn, #submitBtn, button[type=submit], input[type=submit], .btn-submit, .btn-login, a[onclick*=dologin]');"
                + "if(btn){ setTimeout(function(){ btn.click(); }, 300); }"
                + "},500);"
                + "})();";
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

    private void saveCredentials(String username, String password) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_ENC, encrypt(username))
                .putString(KEY_PASS_ENC, encrypt(password))
                .apply();
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
                    saveCredentials(user, pass);
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
                    webView.reload();
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
        if (requestCode == REQ_DOWNLOAD_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDownload();
        }
    }

    private void startDownload() {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(pendingDownloadUrl));
            request.setMimeType(pendingDownloadMime);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle(guessFileName(pendingDownloadDisposition, pendingDownloadUrl));
            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        guessFileName(pendingDownloadDisposition, pendingDownloadUrl));
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                        guessFileName(pendingDownloadDisposition, pendingDownloadUrl));
            }
            dm.enqueue(request);
        } catch (Exception ignored) {
        }
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
            webView.reload();
            return true;
        } else if (id == R.id.action_save_login) {
            showSaveLoginDialog();
            return true;
        } else if (id == R.id.action_clear_login) {
            clearCredentials();
            Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_open_browser) {
            String url = webView.getUrl();
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    Uri uri = data.getData();
                    result = (uri != null) ? new Uri[]{uri} : null;
                }
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
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}