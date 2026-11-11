package local.remotevox.trackpadtest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivity extends Activity implements BleLink.Listener {
    private static final int BLUETOOTH_PERMISSIONS = 1;
    private static final String SAVED_CODE = "savedCode";
    private static final String AUTO_CONNECT = "autoConnect";
    private static final String POINTER_SPEED = "pointerSpeedPercent";
    private static final int MIN_POINTER_SPEED = 50, MAX_POINTER_SPEED = 300, DEFAULT_POINTER_SPEED = 180;
    private static final float ORIGINAL_POINTER_GAIN = 1.65f;
    private SharedPreferences preferences;
    private int pointerSpeedPercent;
    private float pointerGain;
    private BleLink link;
    private TouchSurface touch;
    private TextView landing;
    private AlertDialog settings;
    private ConsolePanel console;
    private EditText settingsCode;
    private TextView settingsStatus;
    private String draftCode = "";
    private String requestedCode;
    private String connectionMessage = "点右上角设置，输入 Mac 的 6 位连接码";
    private boolean ready;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("trackpad", MODE_PRIVATE);
        pointerSpeedPercent = Math.max(MIN_POINTER_SPEED, Math.min(MAX_POINTER_SPEED,
                preferences.getInt(POINTER_SPEED, DEFAULT_POINTER_SPEED)));
        pointerGain = ORIGINAL_POINTER_GAIN * (pointerSpeedPercent / 100f);
        createLayout();
        BluetoothManager bluetooth = getSystemService(BluetoothManager.class);
        if (bluetooth == null || bluetooth.getAdapter() == null) {
            status("这台手机没有可用的蓝牙适配器", false);
            return;
        }
        link = new BleLink(this, bluetooth.getAdapter(), this);
        String saved = preferences.getString(SAVED_CODE, "");
        if (saved.matches("[0-9]{6}")) {
            draftCode = saved;
            if (preferences.getBoolean(AUTO_CONNECT, false)) requestedCode = saved;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (requestedCode != null) startWithPermission();
    }

    @Override protected void onStop() {
        if (link != null) link.stop();
        if (requestedCode != null) status("返回此页面后会自动重连", false);
        super.onStop();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (link != null) link.finishMotion();
        touch.resetGesture();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideBars();
        else if (touch != null) touch.resetGesture();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void createLayout() {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(231, 238, 249));
        touch = new TouchSurface(this);
        touch.setContentDescription("触控区。单指移动、轻点左键。双指滚动、轻点右键。三指拖移。四指横滑切换桌面。");
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        landing = new TextView(this);
        landing.setText(connectionMessage);
        landing.setTextSize(16);
        landing.setTextColor(Color.rgb(55, 78, 112));
        landing.setGravity(Gravity.CENTER);
        root.addView(landing, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        ImageButton gear = new ImageButton(this);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setColorFilter(Color.rgb(55, 78, 112));
        gear.setContentDescription("触控板菜单");
        gear.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable gearBackground = new GradientDrawable();
        gearBackground.setColor(Color.rgb(211, 223, 241));
        gearBackground.setCornerRadius(dp(22));
        gear.setBackground(gearBackground);
        gear.setOnClickListener(view -> showMenu());
        FrameLayout.LayoutParams gearPosition = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        gearPosition.setMargins(0, dp(8), dp(8), 0);
        root.addView(gear, gearPosition);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
                int top = dp(8) + cutout.top;
                int right = dp(8) + cutout.right;
                if (gearPosition.topMargin != top || gearPosition.rightMargin != right) {
                    gearPosition.topMargin = top;
                    gearPosition.rightMargin = right;
                    gear.setLayoutParams(gearPosition);
                }
                return insets;
            });
        }

        setContentView(root);
        hideBars();
    }

    private void showMenu() {
        if (!ready) {
            showSettings();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Trackpad")
                .setItems(new String[] {"Mac 控制台", "触控板设置"}, (dialog, choice) -> {
                    if (choice == 0 && ready && console == null) {
                        touch.resetGesture();
                        console = new ConsolePanel(this, link, () -> console = null);
                        console.show();
                    } else if (choice == 1) {
                        showSettings();
                    }
                })
                .show();
    }

    private void showSettings() {
        if (settings != null) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(6));

        settingsStatus = new TextView(this);
        settingsStatus.setTextSize(15);
        content.addView(settingsStatus);

        settingsCode = new EditText(this);
        settingsCode.setSingleLine(true);
        settingsCode.setHint("Mac 上的 6 位连接码");
        settingsCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        settingsCode.setFilters(new InputFilter[] { new InputFilter.LengthFilter(6) });
        settingsCode.setText(requestedCode != null ? requestedCode : draftCode);
        content.addView(settingsCode);

        TextView speedLabel = new TextView(this);
        speedLabel.setTextSize(16);
        speedLabel.setPadding(0, dp(12), 0, dp(4));
        speedLabel.setText(pointerSpeedLabel());
        content.addView(speedLabel);

        SeekBar speed = new SeekBar(this);
        speed.setMax((MAX_POINTER_SPEED - MIN_POINTER_SPEED) / 10);
        speed.setProgress((pointerSpeedPercent - MIN_POINTER_SPEED) / 10);
        speed.setContentDescription("指针速度");
        content.addView(speed, new LinearLayout.LayoutParams(-1, dp(48)));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                pointerSpeedPercent = MIN_POINTER_SPEED + progress * 10;
                pointerGain = ORIGINAL_POINTER_GAIN * (pointerSpeedPercent / 100f);
                preferences.edit().putInt(POINTER_SPEED, pointerSpeedPercent).apply();
                speedLabel.setText(pointerSpeedLabel());
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        Button originalSpeed = new Button(this);
        originalSpeed.setText("恢复原速度（1.0×）");
        originalSpeed.setOnClickListener(view -> speed.setProgress((100 - MIN_POINTER_SPEED) / 10));
        content.addView(originalSpeed);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(content);
        settings = new AlertDialog.Builder(this)
                .setTitle("触控板设置")
                .setView(scroller)
                .setNegativeButton("关闭", null)
                .setPositiveButton("连接", null)
                .create();
        settings.setOnShowListener(dialog -> {
            settings.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            settings.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> toggleConnection());
            refreshSettings();
        });
        settings.setOnDismissListener(dialog -> {
            if (requestedCode == null) draftCode = settingsCode.getText().toString();
            settings = null;
            settingsCode = null;
            settingsStatus = null;
        });
        settings.show();
    }

    private String pointerSpeedLabel() {
        return String.format(Locale.ROOT, "指针速度  %.1f×", pointerSpeedPercent / 100f);
    }

    private void refreshSettings() {
        if (settings == null) return;
        settingsStatus.setText(connectionMessage);
        settingsCode.setEnabled(requestedCode == null && link != null);
        Button action = settings.getButton(AlertDialog.BUTTON_POSITIVE);
        if (action != null) {
            action.setEnabled(link != null);
            action.setText(requestedCode == null ? "连接" : "断开");
        }
    }

    private void toggleConnection() {
        if (requestedCode != null) {
            requestedCode = null;
            link.stop();
            preferences.edit().putBoolean(AUTO_CONNECT, false).apply();
            status("已断开，可在设置中重新连接", false);
            return;
        }
        String code = settingsCode.getText().toString().trim();
        if (!code.matches("[0-9]{6}")) {
            settingsCode.setError("请输入 Mac 显示的 6 位连接码");
            return;
        }
        draftCode = code;
        requestedCode = code;
        settings.dismiss();
        startWithPermission();
    }

    private void startWithPermission() {
        String[] needed = Build.VERSION.SDK_INT >= 31
                ? new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }
                : new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
        for (String permission : needed) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(needed, BLUETOOTH_PERMISSIONS);
                return;
            }
        }
        status("正在准备连接…", false);
        link.start(requestedCode);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != BLUETOOTH_PERMISSIONS || requestedCode == null) return;
        if (results.length == 0) {
            permissionDenied();
            return;
        }
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                permissionDenied();
                return;
            }
        }
        startWithPermission();
    }

    private void permissionDenied() {
        requestedCode = null;
        status("请在手机设置里允许蓝牙扫描所需权限", false);
    }

    @Override public void status(String message, boolean isReady) {
        if (isReady && requestedCode != null) {
            preferences.edit().putString(SAVED_CODE, requestedCode).putBoolean(AUTO_CONNECT, true).apply();
        }
        connectionMessage = message;
        ready = isReady;
        landing.setText(message);
        landing.setVisibility(isReady ? View.GONE : View.VISIBLE);
        if (!isReady) touch.resetGesture();
        refreshSettings();
        if (console != null) console.connectionChanged(isReady);
        if (requestedCode != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override public void codeRejected() {
        requestedCode = null;
        preferences.edit().remove(SAVED_CODE).remove(AUTO_CONNECT).apply();
        status("连接码不匹配，请到右上角设置核对", false);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class TouchSurface extends TextView {
        private final float density = getResources().getDisplayMetrics().density;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private static final int NONE = 0, ONE = 1, TWO = 2, TWO_LIFTED = 3, THREE = 4, FOUR = 5;

        private int fingers;
        private boolean moved;
        private float lastX;
        private float lastY;
        private float remainderX;
        private float remainderY;
        private long started;

        TouchSurface(Context context) {
            super(context);
            setClickable(true);
        }

        void resetGesture() {
            if (fingers == THREE) link.finishDrag();
            fingers = NONE;
            moved = false;
            remainderX = 0;
            remainderY = 0;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!ready) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    requestUnbufferedDispatch(event);
                    resetGesture();
                    fingers = ONE;
                    started = SystemClock.uptimeMillis();
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    link.finishMotion();
                    if (fingers == ONE && event.getPointerCount() == 2) {
                        fingers = TWO;
                        beginMultiTouch(event);
                    } else if (fingers == TWO && event.getPointerCount() == 3) {
                        fingers = THREE;
                        moved = false;
                        beginMultiTouch(event);
                    } else if (fingers == THREE && !moved && event.getPointerCount() == 4) {
                        fingers = FOUR;
                        beginMultiTouch(event);
                    } else {
                        resetGesture();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (fingers == ONE && event.getPointerCount() == 1) {
                        move(TrackpadProtocol.MOVE, event.getX(), event.getY(), pointerGain);
                    } else if (fingers == TWO && event.getPointerCount() == 2) {
                        move(TrackpadProtocol.SCROLL, centerX(event), centerY(event), 1.2f);
                    } else if (fingers == THREE && event.getPointerCount() == 3) {
                        move(TrackpadProtocol.DRAG, centerX(event), centerY(event), pointerGain);
                    } else if (fingers == FOUR && event.getPointerCount() == 4) {
                        float dx = centerX(event) - lastX;
                        float dy = centerY(event) - lastY;
                        if (Math.abs(dx) >= dp(56) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            link.input(TrackpadProtocol.SWITCH_SPACE, dx < 0 ? 1 : -1, 0);
                            resetGesture();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    link.finishMotion();
                    if (fingers == THREE || fingers == FOUR) {
                        resetGesture();
                    } else if (fingers == TWO && event.getPointerCount() == 2) {
                        fingers = TWO_LIFTED;
                        lastX = event.getX(1 - event.getActionIndex());
                        lastY = event.getY(1 - event.getActionIndex());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    link.finishMotion();
                    if (!moved && SystemClock.uptimeMillis() - started < 330
                            && Math.hypot(event.getX() - lastX, event.getY() - lastY) <= slop) {
                        if (fingers == ONE) {
                            performClick();
                        } else if (fingers == TWO_LIFTED) {
                            link.input(TrackpadProtocol.RIGHT_CLICK, 0, 0);
                        }
                    }
                    resetGesture();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    link.finishMotion();
                    resetGesture();
                    return true;
                default:
                    return true;
            }
        }

        private void beginMultiTouch(MotionEvent event) {
            lastX = centerX(event);
            lastY = centerY(event);
            remainderX = 0;
            remainderY = 0;
        }

        private float centerX(MotionEvent event) {
            float sum = 0;
            for (int i = 0; i < event.getPointerCount(); i++) sum += event.getX(i);
            return sum / event.getPointerCount();
        }

        private float centerY(MotionEvent event) {
            float sum = 0;
            for (int i = 0; i < event.getPointerCount(); i++) sum += event.getY(i);
            return sum / event.getPointerCount();
        }

        private void move(byte kind, float x, float y, float gain) {
            float dx = x - lastX;
            float dy = y - lastY;
            if (!moved && Math.hypot(dx, dy) <= Math.min(slop, dp(4))) return;
            moved = true;
            lastX = x;
            lastY = y;

            remainderX += dx * gain / density;
            remainderY += dy * gain / density;
            int wholeX = (int) remainderX;
            int wholeY = (int) remainderY;
            remainderX -= wholeX;
            remainderY -= wholeY;
            if (wholeX != 0 || wholeY != 0) link.input(kind, wholeX, wholeY);
        }

        @Override public boolean performClick() {
            super.performClick();
            if (ready) link.input(TrackpadProtocol.LEFT_CLICK, 0, 0);
            return true;
        }
    }
}
