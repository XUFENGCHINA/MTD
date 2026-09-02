package com.mtdownloader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 多线程下载器 —— 主界面。
 * UI 全部代码构建，无 XML 布局，零第三方依赖。
 * 下载到应用专属下载目录（无需存储权限），支持多线程分段、进度与速度展示。
 */
public class MainActivity extends Activity implements MultiThreadDownloader.Listener {

    private static final int C_BRAND = 0xFF2563EB;
    private static final int C_BRAND_DARK = 0xFF1D4ED8;
    private static final int C_BG = 0xFFF5F7FA;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_INPUT = 0xFFF3F4F6;
    private static final int C_GREEN = 0xFF16A34A;
    private static final int C_RED = 0xFFDC2626;

    private EditText urlInput;
    private EditText nameInput;
    private SeekBar threadSeek;
    private TextView threadLabel;
    private TextView savePathLabel;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView logView;
    private Button startBtn;
    private Button stopBtn;
    private Button copyBtn;

    private MultiThreadDownloader downloader;
    private File saveDir;
    private boolean downloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 优先用外部应用专属下载目录，无外部存储时回退到内部存储(避免 NPE)
        File ext = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        saveDir = (ext != null) ? ext : getFilesDir();
        if (!saveDir.exists()) saveDir.mkdirs();
        setContentView(buildUi());
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        View header = buildHeader();
        applyHeaderInsets(header);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(4), dp(14), dp(14));
        content.addView(buildUrlCard());
        content.addView(buildConfigCard());
        content.addView(buildProgressCard());
        content.addView(buildLogCard());
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        View bottomBar = buildBottomBar();
        applyBottomInsets(bottomBar);
        root.addView(bottomBar);
        return root;
    }

    /**
     * header 深蓝背景延伸至状态栏后，内容向下避开状态栏高度。
     * 固定 padding 值（不累加），兼容 edge-to-edge（Android 15/16 强制）。
     */
    private void applyHeaderInsets(final View header) {
        if (Build.VERSION.SDK_INT < 21) return;
        header.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(dp(18), dp(18) + insets.getSystemWindowInsetTop(), dp(18), dp(14));
                return insets;
            }
        });
    }

    /** 底部按钮栏向下避开导航栏(手势条)高度，同样固定值不累加。 */
    private void applyBottomInsets(final View bar) {
        if (Build.VERSION.SDK_INT < 21) return;
        bar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(dp(14), dp(10), dp(14), dp(10) + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(18), dp(18), dp(14));
        header.setBackgroundColor(C_BRAND_DARK);

        TextView title = new TextView(this);
        title.setText("多线程下载器");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("多线程分段 · 高速拉取 · 断点重试");
        sub.setTextColor(0xFFDBEAFE);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        header.addView(sub, subLp);
        return header;
    }

    private View buildUrlCard() {
        LinearLayout card = card();

        TextView lbl = label("下载链接");
        card.addView(lbl);

        urlInput = new EditText(this);
        urlInput.setHint("https://example.com/file.zip");
        urlInput.setTextSize(14);
        urlInput.setTextColor(C_TEXT);
        urlInput.setHintTextColor(C_SUB);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setBackground(rounded(C_INPUT, 10));
        urlInput.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = dp(6);
        card.addView(urlInput, urlLp);
        return card;
    }

    private View buildConfigCard() {
        LinearLayout card = card();

        // 线程数
        TextView tLbl = label("下载线程数");
        card.addView(tLbl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        threadLabel = new TextView(this);
        threadLabel.setText("4 线程");
        threadLabel.setTextColor(C_BRAND);
        threadLabel.setTextSize(14);
        threadLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlLp.setMargins(0, 0, dp(12), 0);
        row.addView(threadLabel, tlLp);

        threadSeek = new SeekBar(this);
        threadSeek.setMax(15);
        threadSeek.setProgress(3); // 4 线程
        threadSeek.setPadding(0, dp(4), 0, dp(4));
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(threadSeek, tsLp);
        threadSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                threadLabel.setText((p + 1) + " 线程");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        card.addView(row);

        // 保存文件名
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(10);
        TextView nLbl = label("保存文件名（可选，留空自动提取）");
        nLbl.setLayoutParams(nLp);
        card.addView(nLbl);

        nameInput = new EditText(this);
        nameInput.setHint("留空则自动从链接提取");
        nameInput.setTextSize(14);
        nameInput.setTextColor(C_TEXT);
        nameInput.setHintTextColor(C_SUB);
        nameInput.setSingleLine(true);
        nameInput.setBackground(rounded(C_INPUT, 10));
        nameInput.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams niLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        niLp.topMargin = dp(6);
        card.addView(nameInput, niLp);

        // 保存路径
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spLp.topMargin = dp(12);
        TextView spLbl = label("保存位置");
        spLbl.setLayoutParams(spLp);
        card.addView(spLbl);

        savePathLabel = new TextView(this);
        savePathLabel.setText(saveDir.getAbsolutePath());
        savePathLabel.setTextColor(C_SUB);
        savePathLabel.setTextSize(11);
        LinearLayout.LayoutParams sPp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sPp.topMargin = dp(2);
        card.addView(savePathLabel, sPp);

        return card;
    }

    private View buildProgressCard() {
        LinearLayout card = card();

        TextView lbl = label("下载进度");
        card.addView(lbl);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = dp(8);
        card.addView(progressBar, pbLp);

        progressText = new TextView(this);
        progressText.setText("等待开始…");
        progressText.setTextColor(C_TEXT);
        progressText.setTextSize(13);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ptLp.topMargin = dp(6);
        card.addView(progressText, ptLp);

        return card;
    }

    private View buildLogCard() {
        LinearLayout card = card();

        TextView lbl = label("运行日志");
        card.addView(lbl);

        logView = new TextView(this);
        logView.setText("就绪，请输入下载链接。");
        logView.setTextColor(C_SUB);
        logView.setTextSize(12);
        logView.setLineSpacing(dp(2), 1f);
        logView.setBackground(rounded(0xFFF8FAFC, 10));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lgLp.topMargin = dp(6);
        card.addView(logView, lgLp);
        return card;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));
        bar.setBackgroundColor(C_CARD);

        startBtn = new Button(this);
        startBtn.setText("开始下载");
        startBtn.setTextColor(Color.WHITE);
        startBtn.setTextSize(15);
        startBtn.setBackground(rounded(C_BRAND, 24));
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDownload(); }
        });
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, dp(50), 1f);
        bar.addView(startBtn, stLp);

        stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setTextColor(C_RED);
        stopBtn.setTextSize(15);
        stopBtn.setEnabled(false);
        stopBtn.setBackground(rounded(0xFFFFF1F2, 24));
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopDownload(); }
        });
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                0, dp(50), 1f);
        spLp.setMargins(dp(10), 0, 0, 0);
        bar.addView(stopBtn, spLp);

        copyBtn = new Button(this);
        copyBtn.setText("复制路径");
        copyBtn.setTextColor(C_SUB);
        copyBtn.setTextSize(14);
        copyBtn.setBackground(rounded(0xFFF1F5F9, 24));
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyPath(); }
        });
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(
                0, dp(50), 1f);
        cpLp.setMargins(dp(10), 0, 0, 0);
        bar.addView(copyBtn, cpLp);
        return bar;
    }

    // ------------------------------------------------------------------ 交互

    private void startDownload() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) { toast("请先输入下载链接"); return; }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("链接需以 http:// 或 https:// 开头"); return;
        }
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) name = extractFileName(url);
        File dest = new File(saveDir, name);
        int nThreads = threadSeek.getProgress() + 1;

        hideKeyboard();
        setDownloading(true);
        logView.setText("");
        log("==== 开始下载 ====");
        log("链接: " + url);
        log("线程: " + nThreads + "，保存: " + name);

        progressBar.setProgress(0);
        progressText.setText("连接中…");

        downloader = new MultiThreadDownloader(url, dest, nThreads, this);
        downloader.start();
    }

    private void stopDownload() {
        if (downloader != null) {
            downloader.stop();
            log("正在停止…");
        }
    }

    private void copyPath() {
        String p = saveDir.getAbsolutePath();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("下载目录", p));
        toast("下载路径已复制");
    }

    // ------------------------------------------------------------------ Listener（工作线程回调 -> 主线程更新）

    @Override
    public void onStatus(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() { log(message); }
        });
    }

    @Override
    public void onProgress(final long done, final long total, final double speed, final int percent) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (percent >= 0) progressBar.setProgress(Math.min(100, percent));
                String s;
                if (percent >= 0) {
                    String size = total > 0
                            ? MultiThreadDownloader.fmtSize(done) + " / " + MultiThreadDownloader.fmtSize(total)
                            : MultiThreadDownloader.fmtSize(done);
                    s = percent + "%  ·  " + size + (speed > 0 ? "  ·  " + fmtSpeed(speed) : "");
                } else {
                    s = "已下载 " + MultiThreadDownloader.fmtSize(done)
                            + (speed > 0 ? "  ·  " + fmtSpeed(speed) : "");
                }
                progressText.setText(s);
            }
        });
    }

    @Override
    public void onComplete(final File file, final long totalBytes) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setDownloading(false);
                progressBar.setProgress(100);
                progressText.setText("完成  ·  " + MultiThreadDownloader.fmtSize(totalBytes));
                log(">>>> 下载完成 <<<<");
                log("文件: " + file.getAbsolutePath());
                toast("下载完成");
                downloader = null;
            }
        });
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setDownloading(false);
                progressText.setText("下载失败");
                log("✗ " + message);
                downloader = null;
            }
        });
    }

    @Override
    public void onStopped(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setDownloading(false);
                log("■ " + message);
                progressText.setText("已停止");
                downloader = null;
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    private void setDownloading(boolean d) {
        downloading = d;
        startBtn.setEnabled(!d);
        stopBtn.setEnabled(d);
        if (d) {
            urlInput.setEnabled(false);
            nameInput.setEnabled(false);
            threadSeek.setEnabled(false);
        } else {
            urlInput.setEnabled(true);
            nameInput.setEnabled(true);
            threadSeek.setEnabled(true);
        }
    }

    private void log(String msg) {
        String cur = logView.getText().toString();
        String next = cur.equals("就绪，请输入下载链接。") || cur.equals("") ? msg : cur + "\n" + msg;
        logView.setText(next);
    }

    private static String fmtSpeed(double bps) {
        if (bps < 1024) return String.format(Locale.US, "%.0f B/s", bps);
        double kb = bps / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.2f MB/s", mb);
    }

    private static String extractFileName(String url) {
        String path = "";
        try {
            path = new java.net.URL(url).getPath();
        } catch (Exception ignored) {}
        String name = "";
        if (path != null && path.contains("/")) {
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        if (name.isEmpty()) name = "download_" + System.currentTimeMillis();
        try {
            name = URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {}
        // 清理非法字符
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.contains("..")) name = name.replace("..", "_");
        if (name.isEmpty()) name = "download_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return name;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(C_CARD, 14));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(12), dp(2), 0);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_TEXT);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
