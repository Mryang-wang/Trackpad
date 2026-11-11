package local.remotevox.trackpadtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ConsolePanel {
    private static final int BACKGROUND = Color.rgb(12, 17, 27);
    private static final int SURFACE = Color.rgb(24, 33, 48);
    private static final int TEXT = Color.rgb(239, 244, 252);
    private static final int MUTED = Color.rgb(145, 162, 187);
    private static final int ACCENT = Color.rgb(115, 221, 196);
    private final Activity activity;
    private final BleLink link;
    private final Dialog dialog;
    private final List<JSONObject> apps = new ArrayList<>();
    private final Map<String, Bitmap> icons = new HashMap<>();
    private final Map<String, ImageView> iconViews = new HashMap<>();
    private final Set<String> failedIcons = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable loadVisibleIcons = this::dispatchNext;
    private final TextView status;
    private final TextView count;
    private TextView detailStatus;
    private Button detailQuit;
    private JSONObject detailApp;
    private final Map<Integer, Integer> pendingStates = new java.util.LinkedHashMap<>();
    private int verifyingOpen = -1;
    private int verifyAttempts;
    private final Map<String, Integer> iconAttempts = new HashMap<>();
    private final EditText search;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final File cache;
    private final AppCatalogCache catalogCache;
    private String cachedHost = "";
    private String cachedRevision = "";
    private boolean catalogReady;
    private boolean forceCatalog;
    private int metadataRetries;
    private int catalogCount;
    private int nextStatePage = -1;
    private final Set<Integer> runningSnapshot = new HashSet<>();
    private int frontSnapshot;
    private final Collator collator = Collator.getInstance(Locale.CHINA);
    private boolean connected;
    private boolean busy;
    private boolean backgroundRead;
    private boolean reportCatalog;
    private Runnable queuedAction;
    private int nextPage = -1;
    private int generation;
    private long catalogEpoch;
    private JSONObject loadingIcon;
    private ByteArrayOutputStream iconBytes;
    private int nextIconOffset = -1;

    ConsolePanel(Activity activity, BleLink link, Runnable dismissed) {
        this.activity = activity;
        this.link = link;
        catalogCache = new AppCatalogCache(activity.getFilesDir());
        cache = new File(activity.getCacheDir(), "mac-app-icons");
        cache.mkdirs();
        dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        dialog.setOnDismissListener(ignored -> {
            ui.removeCallbacks(loadVisibleIcons);
            queuedAction = null;
            dismissed.run();
        });

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), 0);
        content.setBackgroundColor(BACKGROUND);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("‹  触控板", dialog::dismiss), new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(18), 0, 0, 0);
        heading.addView(text("Mac 控制台", 22, TEXT, true));
        count = text("应用与快捷操作", 11, MUTED, false);
        heading.addView(count);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        search = new EditText(activity);
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint("名称 / 拼音 / 首字母");
        search.setPadding(dp(16), 0, dp(12), 0);
        search.setBackground(background(SURFACE, 14, Color.rgb(42, 54, 74)));
        search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        header.addView(search, new LinearLayout.LayoutParams(dp(175), dp(40)));
        Button refresh = button("↻  刷新", this::refresh);
        LinearLayout.LayoutParams refreshLayout = new LinearLayout.LayoutParams(-2, dp(40));
        refreshLayout.leftMargin = dp(10);
        header.addView(refresh, refreshLayout);
        content.addView(header);

        status = text("轻点打开应用 · 长按查看更多操作", 11, MUTED, false);
        status.setPadding(dp(2), dp(10), 0, dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(20));
        scroll.setFillViewport(true);
        scroll.addView(list);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> scheduleIcons());
        scroll.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) renderList();
        });
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int removed, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int after) { renderList(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        dialog.setContentView(content);
        restoreCatalog();
    }

    void show() {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BACKGROUND));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                dialog.getWindow().setAttributes(attributes);
            }
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                dialog.getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController bars = dialog.getWindow().getInsetsController();
                if (bars != null) {
                    bars.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    bars.hide(android.view.WindowInsets.Type.systemBars());
                }
            }
            dialog.getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets edges = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout());
                    View content = ((ViewGroup)dialog.findViewById(android.R.id.content)).getChildAt(0);
                    content.setPadding(dp(24) + edges.left, dp(12) + edges.top, dp(24) + edges.right, edges.bottom);
                }
                return insets;
            });
        }
        connectionChanged(true);
    }

    void connectionChanged(boolean isConnected) {
        if (!dialog.isShowing() || connected == isConnected) return;
        connected = isConnected;
        if (isConnected) {
            metadataRetries = 0;
            checkCatalog(false);
        } else {
            busy = false;
            backgroundRead = false;
            queuedAction = null;
            catalogReady = false;
            generation = 0;
            nextPage = -1;
            nextStatePage = -1;
            loadingIcon = null;
            iconBytes = null;
            pendingStates.clear();
            setStatus("已保留应用缓存，等待重新连接 Mac…");
        }
    }

    private void restoreCatalog() {
        JSONObject saved = catalogCache.read();
        if (saved == null) return;
        cachedHost = saved.optString("host");
        cachedRevision = saved.optString("catalog");
        JSONArray rows = saved.optJSONArray("apps");
        for (int index = 0; index < rows.length(); index++) apps.add(rows.optJSONObject(index));
        renderList();
        setStatus("已显示缓存，连接后更新运行状态…");
    }

    private void refresh() {
        enqueue(() -> {
            metadataRetries = 0;
            catalogReady = false;
            checkCatalog(true);
        });
    }

    private void checkCatalog(boolean force) {
        forceCatalog = force;
        setStatus(apps.isEmpty() ? "正在读取 Mac 应用…" : "已显示缓存，正在核对运行状态…");
        request(TrackpadProtocol.CATALOG_STATUS, 0, 0, reply -> {
            String host = reply.optString("host");
            String revision = reply.optString("catalog");
            int token = reply.optInt("generation");
            int expectedCount = reply.optInt("count", -1);
            if (expectedCount < 0 || expectedCount > Short.MAX_VALUE || host.isEmpty() || !revision.matches("[0-9a-f]{32}") || token <= 0 || token > Short.MAX_VALUE) {
                setStatus("Mac 返回了无效的应用列表信息，请刷新");
                return;
            }
            boolean reuse = !force && host.equals(cachedHost) && revision.equals(cachedRevision) && expectedCount == apps.size();
            if (!reuse) {
                queuedAction = null;
                clearCatalog();
            }
            cachedHost = host;
            cachedRevision = revision;
            catalogCount = expectedCount;
            generation = token;
            catalogReady = true;
            if (reuse) {
                runningSnapshot.clear();
                frontSnapshot = 0;
                nextStatePage = 0;
            } else {
                reportCatalog = true;
                setStatus("正在同步应用列表，完成后会自动缓存…");
                readPage(0);
            }
        });
    }

    private void readRunningPage(int offset) {
        request(TrackpadProtocol.CATALOG_STATE, offset, generation, reply -> {
            if (reply.optInt("generation") != generation) {
                setStatus("运行状态已变化，请刷新");
                return;
            }
            JSONArray running = reply.optJSONArray("running");
            if (running == null) return;
            for (int index = 0; index < running.length(); index++) runningSnapshot.add(running.optInt(index));
            frontSnapshot = reply.optInt("front");
            int next = reply.optInt("next", -1);
            if (next > offset && next <= Short.MAX_VALUE) {
                nextStatePage = next;
                return;
            }
            boolean changed = false;
            try {
                for (JSONObject app : apps) {
                    boolean isRunning = runningSnapshot.contains(app.optInt("id"));
                    changed |= app.optBoolean("running") != isRunning;
                    app.put("running", isRunning);
                    app.put("front", app.optInt("id") == frontSnapshot);
                }
            } catch (org.json.JSONException invalid) { return; }
            if (changed) renderList();
            catalogCache.save(cachedHost, cachedRevision, apps);
            setStatus("已读取缓存 · 运行状态已更新");
        });
    }

    private void clearCatalog() {
        catalogEpoch++;
        generation = 0;
        catalogReady = false;
        nextStatePage = -1;
        verifyingOpen = -1;
        nextPage = -1;
        loadingIcon = null;
        iconBytes = null;
        nextIconOffset = -1;
        failedIcons.clear();
        iconAttempts.clear();
        pendingStates.clear();
        apps.clear();
        renderList();
    }

    private void readPage(int offset) {
        request(TrackpadProtocol.LIST_APPS, offset, generation, reply -> {
            int token = reply.optInt("generation");
            JSONArray rows = reply.optJSONArray("rows");
            if (rows == null || token <= 0 || token > Short.MAX_VALUE || (offset > 0 && token != generation)) {
                if (reportCatalog) setStatus("应用列表已变化，请刷新");
                return;
            }
            generation = token;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && row.optInt("id", 0) > 0) apps.add(row);
            }
            renderList();
            int next = reply.optInt("next", -1);
            if (next > offset && next <= Short.MAX_VALUE) nextPage = next;
            else if (apps.size() == catalogCount) {
                catalogCache.save(cachedHost, cachedRevision, apps);
                if (reportCatalog) setStatus("已缓存 " + apps.size() + " 个应用 · 轻点打开，长按操作");
            } else setStatus("应用列表同步未完成，请刷新重试");
        });
    }

    private void renderList() {
        list.removeAllViews();
        iconViews.clear();
        String filter = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<JSONObject> running = new ArrayList<>();
        List<JSONObject> other = new ArrayList<>();
        for (JSONObject app : apps) {
            if (!AppSearch.matches(app.optString("name"), app.optString("sort"), app.optString("initials"), filter)) continue;
            (app.optBoolean("running") ? running : other).add(app);
        }
        java.util.Comparator<JSONObject> byName = (a, b) -> {
            int order = collator.compare(a.optString("sort", a.optString("name")), b.optString("sort", b.optString("name")));
            return order != 0 ? order : Integer.compare(a.optInt("id"), b.optInt("id"));
        };
        java.util.Collections.sort(running, byName);
        java.util.Collections.sort(other, byName);
        count.setText(apps.size() + " 个应用  ·  长按查看操作");
        addSection("运行中", running, true);
        addSection("其他应用", other, false);
        if (running.isEmpty() && other.isEmpty()) {
            TextView empty = text(apps.isEmpty() ? "正在准备你的应用…" : "没有找到匹配的应用", 16, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(45), 0, dp(45));
            list.addView(empty);
        }
        scheduleIcons();
    }

    private void addSection(String name, List<JSONObject> section, boolean running) {
        if (section.isEmpty()) return;
        int width = scroll.getWidth() > 0 ? scroll.getWidth() : activity.getResources().getDisplayMetrics().widthPixels - dp(48);
        int columns = Math.max(2, width / dp(126));
        for (int start = 0; start < section.size(); start += columns) {
            LinearLayout row = new LinearLayout(activity);
            for (int col = 0; col < columns; col++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(118), 1);
                cell.setMargins(dp(4), 0, dp(4), dp(8));
                row.addView(start + col < section.size() ? appTile(section.get(start + col)) : new View(activity), cell);
            }
            list.addView(row);
        }
    }

    private View appTile(JSONObject app) {
        String name = app.optString("name", "应用");
        String key = app.optString("key");
        boolean running = app.optBoolean("running");
        int id = app.optInt("id");
        long epoch = catalogEpoch;
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(8), dp(10), dp(8), dp(8));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new TileBackground(true));
        states.addState(new int[]{}, new TileBackground(running));
        tile.setBackground(states);
        tile.setContentDescription(name + (running ? "，运行中" : "，未运行") + "。轻点打开，长按操作");
        ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bitmap = icons.get(key);
        if (bitmap == null && key.matches("[0-9a-f]{24}")) {
            bitmap = BitmapFactory.decodeFile(new File(cache, key + ".png").getPath());
            if (bitmap != null) icons.put(key, bitmap);
        }
        if (bitmap != null) icon.setImageBitmap(bitmap);
        else icon.setImageDrawable(new AppPlaceholder(name));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        if (!key.isEmpty()) iconViews.put(key, icon);
        TextView title = text(name, 11, TEXT, false);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(7), 0, 0);
        tile.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
        tile.setOnClickListener(view -> appAction(TrackpadProtocol.OPEN_APP, id, epoch));
        tile.setOnLongClickListener(view -> { showActions(app, epoch); return true; });
        return tile;
    }

    private void showActions(JSONObject app, long epoch) {
        String name = app.optString("name", "应用");
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(app.optBoolean("running") ? "打开 / 切换到应用" : "打开应用");
        actions.add(() -> appAction(TrackpadProtocol.OPEN_APP, app.optInt("id"), epoch));
        labels.add("退出应用");
        actions.add(() -> confirmQuit(name, app.optInt("id"), epoch));
        labels.add("应用详情");
        actions.add(() -> showAppDetails(app, epoch));
        Dialog sheet = new Dialog(activity);
        sheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(18), dp(22), dp(18));
        panel.setBackground(background(SURFACE, 22, Color.rgb(47, 63, 84)));
        TextView title = text(name, 22, TEXT, true);
        panel.addView(title);
        TextView hint = text("应用操作", 12, MUTED, false);
        hint.setPadding(0, dp(3), 0, dp(14));
        panel.addView(hint);
        for (int start = 0; start < labels.size(); start += 2) {
            LinearLayout row = new LinearLayout(activity);
            for (int col = 0; col < 2; col++) {
                int index = start + col;
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(48), 1);
                cell.setMargins(dp(3), 0, dp(3), dp(8));
                if (index < labels.size()) {
                    Button choice = button(labels.get(index), () -> {
                        sheet.dismiss();
                        actions.get(index).run();
                    });
                    choice.setTextSize(14);
                    if (index == 1 && !app.optBoolean("running")) {
                        choice.setEnabled(false);
                        choice.setAlpha(0.4f);
                    }
                    row.addView(choice, cell);
                } else row.addView(new View(activity), cell);
            }
            panel.addView(row);
        }
        Button cancel = button("返回", sheet::dismiss);
        panel.addView(cancel, new LinearLayout.LayoutParams(-1, dp(40)));
        sheet.setContentView(panel);
        sheet.show();
        if (sheet.getWindow() != null) {
            sheet.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(540), activity.getResources().getDisplayMetrics().widthPixels - dp(70));
            sheet.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showAppDetails(JSONObject app, long epoch) {
        Dialog details = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(18), dp(28), dp(18));
        page.setBackgroundColor(BACKGROUND);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("‹  返回应用网格", details::dismiss), new LinearLayout.LayoutParams(-2, dp(40)));
        TextView title = text(app.optString("name", "应用"), 24, TEXT, true);
        title.setPadding(dp(22), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);
        detailApp = app;
        detailStatus = text("选择要执行的应用操作", 13, MUTED, false);
        detailStatus.setPadding(0, dp(16), 0, dp(18));
        detailStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(detailStatus);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("打开 / 切换到应用");
        actions.add(() -> appAction(TrackpadProtocol.OPEN_APP, app.optInt("id"), epoch));
        labels.add("退出应用");
        actions.add(() -> confirmQuit(app.optString("name"), app.optInt("id"), epoch));
        if (app.optBoolean("codex")) {
            String[] names = {"开始 / 结束听写", "发送草稿", "停止回复", "新建对话"};
            byte[] kinds = {TrackpadProtocol.CODEX_DICTATION, TrackpadProtocol.CODEX_SEND, TrackpadProtocol.CODEX_STOP, TrackpadProtocol.CODEX_NEW};
            for (int i = 0; i < names.length; i++) {
                final byte kind = kinds[i];
                labels.add(names[i]);
                actions.add(() -> enqueue(() -> action(kind, 0, 0)));
            }
        }
        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.VERTICAL);
        for (int start = 0; start < labels.size(); start += 2) {
            LinearLayout row = new LinearLayout(activity);
            for (int col = 0; col < 2; col++) {
                int index = start + col;
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(68), 1);
                cell.setMargins(dp(5), 0, dp(5), dp(10));
                if (index < labels.size()) {
                    Button control = button(labels.get(index), actions.get(index));
                    control.setTextSize(16);
                    if (index == 1) {
                        detailQuit = control;
                        control.setEnabled(app.optBoolean("running"));
                        control.setAlpha(app.optBoolean("running") ? 1f : 0.4f);
                    }
                    row.addView(control, cell);
                } else row.addView(new View(activity), cell);
            }
            controls.addView(row);
        }
        ScrollView body = new ScrollView(activity);
        body.addView(controls);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        details.setContentView(page);
        details.setOnDismissListener(ignored -> {
            detailStatus = null;
            detailQuit = null;
            detailApp = null;
        });
        details.show();
        android.view.Window window = details.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BACKGROUND));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(attributes);
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController bars = window.getInsetsController();
                if (bars != null) {
                    bars.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    bars.hide(android.view.WindowInsets.Type.systemBars());
                }
                page.setOnApplyWindowInsetsListener((view, insets) -> {
                    android.graphics.Insets edges = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout());
                    page.setPadding(dp(28) + edges.left, dp(18) + edges.top, dp(28) + edges.right, dp(18) + edges.bottom);
                    return insets;
                });
            }
        }
    }

    private void setStatus(String message) {
        status.setText(message);
        if (detailStatus != null) detailStatus.setText(message);
    }

    private void confirmQuit(String name, int id, long epoch) {
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("退出 " + name + "？")
                .setMessage("如果有未保存的内容，请在 Mac 上处理保存提示。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (sheet, which) -> appAction(TrackpadProtocol.QUIT_APP, id, epoch)).show();
    }

    private void appAction(byte kind, int id, long epoch) {
        enqueue(() -> {
            if (epoch != catalogEpoch || !catalogReady || generation == 0) {
                setStatus("应用列表已变更，请重新选择");
                dispatchNext();
                return;
            }
            action(kind, id, generation);
        });
    }

    private void enqueue(Runnable action) {
        if (!connected) { setStatus("请先连接 Mac 接收端"); return; }
        reportCatalog = false;
        if (busy) {
            if (backgroundRead && queuedAction == null) {
                queuedAction = action;
                setStatus("正在准备操作…");
            } else setStatus("上一项操作正在完成，请稍候");
            return;
        }
        action.run();
    }

    private void action(byte kind, int argument, int token) {
        if (!catalogReady) { setStatus("正在核对应用列表，请稍候再试"); return; }
        setStatus("正在执行…");
        request(kind, argument, token, reply -> {
            setStatus(reply.optString("message", "Mac 已接受操作"));
            if (kind == TrackpadProtocol.OPEN_APP || kind == TrackpadProtocol.QUIT_APP) {
                if (kind == TrackpadProtocol.OPEN_APP) { verifyingOpen = argument; verifyAttempts = 0; }
                ui.postDelayed(() -> {
                    if (dialog.isShowing() && connected && token == generation) {
                        pendingStates.put(argument, token);
                        dispatchNext();
                    }
                }, 700);
            }
        });
    }

    private interface OnReply { void handle(JSONObject reply); }

    private void request(byte kind, int argument, int token, OnReply callback) {
        boolean background = kind == TrackpadProtocol.LIST_APPS || kind == TrackpadProtocol.APP_ICON || kind == TrackpadProtocol.ICON_CHUNK || kind == TrackpadProtocol.APP_STATE || kind == TrackpadProtocol.CATALOG_STATUS || kind == TrackpadProtocol.CATALOG_STATE;
        busy = true;
        backgroundRead = background;
        if (!link.console(kind, argument, token, (reply, error) -> {
            busy = false;
            backgroundRead = false;
            if (!dialog.isShowing()) return;
            if (error != null || reply == null || !reply.optBoolean("ok")) {
                if (!background || reportCatalog) setStatus(error != null ? error : reply == null ? "Mac 没有返回结果" : reply.optString("message", "Mac 未执行操作"));
                if (kind == TrackpadProtocol.APP_ICON || kind == TrackpadProtocol.ICON_CHUNK) finishIcon(false);
            } else callback.handle(reply);
            dispatchNext();
        })) {
            busy = false;
            backgroundRead = false;
            queuedAction = null;
            if (kind == TrackpadProtocol.CATALOG_STATUS && metadataRetries++ < 8) {
                ui.postDelayed(() -> {
                    if (dialog.isShowing() && connected && !busy && !catalogReady) checkCatalog(forceCatalog);
                }, 250);
            } else setStatus("连接暂不可用，请重连后刷新");
        }
    }

    private void dispatchNext() {
        if (busy || !connected || !dialog.isShowing()) return;
        if (nextStatePage >= 0) {
            int offset = nextStatePage;
            nextStatePage = -1;
            readRunningPage(offset);
        } else if (queuedAction != null) {
            Runnable action = queuedAction;
            queuedAction = null;
            action.run();
        } else if (!pendingStates.isEmpty()) {
            int id = pendingStates.keySet().iterator().next();
            int token = pendingStates.remove(id);
            if (token == generation) request(TrackpadProtocol.APP_STATE, id, token, this::updateAppState);
            else dispatchNext();
        } else if (nextPage >= 0) {
            int offset = nextPage;
            nextPage = -1;
            readPage(offset);
        } else if (loadingIcon != null) {
            requestIconChunk();
        } else if (catalogReady) {
            Rect visible = new Rect();
            for (JSONObject app : apps) {
                String key = app.optString("key");
                ImageView image = iconViews.get(key);
                if (key.isEmpty() || icons.containsKey(key) || failedIcons.contains(key) || image == null || !image.getGlobalVisibleRect(visible)) continue;
                loadingIcon = app;
                iconBytes = new ByteArrayOutputStream();
                nextIconOffset = 0;
                request(TrackpadProtocol.APP_ICON, app.optInt("id"), generation, this::readIcon);
                return;
            }
        }
    }

    private void updateAppState(JSONObject reply) {
        if (reply.optInt("generation") != generation) return;
        for (JSONObject app : apps) {
            if (app.optInt("id") != reply.optInt("id")) continue;
            if (verifyingOpen == reply.optInt("id")) {
                if (!reply.optString("error").isEmpty()) {
                    setStatus("打开失败：" + reply.optString("error"));
                    verifyingOpen = -1;
                } else if (reply.optBoolean("front")) {
                    setStatus("已切换到 " + app.optString("name"));
                    verifyingOpen = -1;
                } else if (++verifyAttempts < 3) {
                    int id = reply.optInt("id");
                    int token = generation;
                    ui.postDelayed(() -> {
                        if (connected && token == generation && verifyingOpen == id) {
                            pendingStates.put(id, token);
                            dispatchNext();
                        }
                    }, 700);
                } else {
                    setStatus("应用未切到前台，请重试");
                    verifyingOpen = -1;
                }
            }
            boolean changed = app.optBoolean("running") != reply.optBoolean("running");
            try {
                app.put("running", reply.optBoolean("running"));
                app.put("front", reply.optBoolean("front"));
            } catch (org.json.JSONException ignored) { return; }
            if (detailApp == app && detailQuit != null) {
                detailQuit.setEnabled(app.optBoolean("running"));
                detailQuit.setAlpha(app.optBoolean("running") ? 1f : 0.4f);
            }
            if (changed) renderList();
            return;
        }
    }

    private void requestIconChunk() {
        request(TrackpadProtocol.ICON_CHUNK, nextIconOffset, generation, this::readIcon);
    }

    private void readIcon(JSONObject reply) {
        try {
            if (loadingIcon == null || reply.optInt("generation") != generation) { finishIcon(false); return; }
            byte[] bytes = Base64.decode(reply.getString("icon"), Base64.DEFAULT);
            int next = reply.optInt("next", -1);
            if (bytes.length == 0 || bytes.length > 240 || iconBytes.size() + bytes.length > 32767
                    || (next != -1 && next != iconBytes.size() + bytes.length)) { finishIcon(false); return; }
            iconBytes.write(bytes, 0, bytes.length);
            nextIconOffset = next;
            if (next == -1) finishIcon(true);
        } catch (org.json.JSONException | IllegalArgumentException error) { finishIcon(false); }
    }

    private void finishIcon(boolean success) {
        if (loadingIcon == null) return;
        String key = loadingIcon.optString("key");
        if (success && iconBytes != null) {
            byte[] bytes = iconBytes.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null) {
                icons.put(key, bitmap);
                ImageView image = iconViews.get(key);
                if (image != null) image.setImageBitmap(bitmap);
                if (key.matches("[0-9a-f]{24}")) {
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(cache, key + ".png"))) { output.write(bytes); }
                    catch (IOException ignored) { /* Icons can be loaded again next time. */ }
                }
            } else failedIcons.add(key);
        } else failedIcons.add(key);
        if (failedIcons.contains(key)) {
            int attempts = iconAttempts.containsKey(key) ? iconAttempts.get(key) + 1 : 1;
            iconAttempts.put(key, attempts);
            if (attempts < 2) {
                long epoch = catalogEpoch;
                ui.postDelayed(() -> {
                    if (epoch == catalogEpoch && dialog.isShowing()) {
                        failedIcons.remove(key);
                        dispatchNext();
                    }
                }, 1500);
            }
        }
        loadingIcon = null;
        iconBytes = null;
        nextIconOffset = -1;
    }

    private void scheduleIcons() {
        ui.removeCallbacks(loadVisibleIcons);
        ui.postDelayed(loadVisibleIcons, 120);
    }

    private final class TileBackground extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final boolean glowing;
        TileBackground(boolean glowing) { this.glowing = glowing; }
        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.RectF box = new android.graphics.RectF(getBounds());
            box.inset(dp(4), dp(4));
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(SURFACE);
            if (glowing) paint.setShadowLayer(dp(7), 0, 0, Color.argb(145, 115, 221, 196));
            canvas.drawRoundRect(box, dp(17), dp(17), paint);
            paint.clearShadowLayer();
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(glowing ? Color.argb(195, 115, 221, 196) : Color.rgb(37, 48, 66));
            canvas.drawRoundRect(box, dp(17), dp(17), paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final class AppPlaceholder extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final String initial;
        AppPlaceholder(String name) {
            initial = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        }
        @Override public void draw(android.graphics.Canvas canvas) {
            Rect bounds = getBounds();
            paint.setColor(Color.rgb(42, 62, 79));
            canvas.drawRoundRect(new android.graphics.RectF(bounds), bounds.width() * .22f, bounds.height() * .22f, paint);
            paint.setColor(ACCENT);
            paint.setTextSize(bounds.height() * .47f);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            canvas.drawText(initial, bounds.exactCenterX(), bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private GradientDrawable background(int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private Button button(String title, Runnable clicked) {
        Button result = new Button(activity);
        result.setText(title);
        result.setTextSize(12);
        result.setAllCaps(false);
        result.setTextColor(TEXT);
        result.setMinHeight(0);
        result.setMinimumHeight(0);
        result.setPadding(dp(14), 0, dp(14), 0);
        result.setBackground(background(SURFACE, 13, Color.rgb(42, 54, 74)));
        result.setOnClickListener(view -> clicked.run());
        return result;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
