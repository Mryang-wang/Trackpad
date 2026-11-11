package local.remotevox.trackpadtest;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Main-thread BLE central. The activity owns the runtime permissions and lifetime. */
@SuppressLint("MissingPermission")
final class BleLink {
    interface Listener {
        void status(String message, boolean ready);
        void codeRejected();
    }

    interface ConsoleCallback {
        void complete(JSONObject reply, String error);
    }

    private static final long HEARTBEAT_MS = 2000;
    private static final long DRAG_HEARTBEAT_MS = 250;
    private static final long MIN_SCAN_INTERVAL_MS = 6500;
    private static final long MIN_SEND_INTERVAL_MS = 8;
    private static final long MAX_MOTION_AGE_MS = 40;
    private static final String SERVICE_MISSING = "已发现 Mac，但未读到控制台服务。请确认 Mac 只运行控制台接收端。";
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Input> pending = new ArrayDeque<>();
    private final Set<String> refreshedDevices = new HashSet<>();
    private final Runnable scanAgain = this::startScan;
    private final Runnable pump = this::sendNext;
    private final Runnable timeout = () -> retry("蓝牙暂时无响应，正在重连…");

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commands;
    private BluetoothGattCharacteristic consoleResult;
    private ConsoleCallback consoleCallback;
    private long consoleSequence;
    private TrackpadProtocol protocol;
    private String code;
    private boolean ready;
    private boolean writing;
    private boolean dragging;
    private boolean sentWithResponse;
    private boolean motionAwaitingResponse;
    private byte sentKind;
    private int sequence;
    private boolean missingService;
    private long lastScan = -MIN_SCAN_INTERVAL_MS;
    private long lastSent;
    private long lastConfirmed;

    private static final class Input {
        final byte kind;
        final long enqueued = SystemClock.elapsedRealtime();
        int x;
        int y;

        Input(byte kind, int x, int y) {
            this.kind = kind;
            this.x = x;
            this.y = y;
        }

        boolean isMotion() {
            return isMotionKind(kind);
        }

        boolean expired(long now) {
            return isMotion() && now - enqueued > MAX_MOTION_AGE_MS;
        }
    }

    BleLink(Context context, BluetoothAdapter adapter, Listener listener) {
        this.context = context;
        this.adapter = adapter;
        this.listener = listener;
    }

    void start(String code) {
        stop();
        refreshedDevices.clear();
        missingService = false;
        this.code = code;
        scheduleScan(0);
    }

    void stop() {
        code = null;
        handler.removeCallbacks(scanAgain);
        closeTransport();
    }

    void input(byte kind, int x, int y) {
        if (!ready) return;
        if (kind == TrackpadProtocol.DRAG) dragging = true;
        Input last = pending.peekLast();
        boolean motion = isMotionKind(kind);
        if (last != null && last.expired(SystemClock.elapsedRealtime())) {
            pending.removeLast();
            last = pending.peekLast();
        }
        if (motion && last != null && last.kind == kind) {
            last.x = bound(last.x + x);
            last.y = bound(last.y + y);
        } else if (pending.size() < 16) {
            pending.addLast(new Input(kind, bound(x), bound(y)));
        }
        schedulePump();
    }

    boolean console(byte kind, int argument, int generation, ConsoleCallback callback) {
        if (!ready || consoleResult == null || consoleCallback != null || !TrackpadProtocol.isConsole(kind)) return false;
        if (argument < 0 || argument > Short.MAX_VALUE || generation < 0 || generation > Short.MAX_VALUE) return false;
        finishMotion();
        if (pending.size() >= 16) return false;
        consoleCallback = callback;
        pending.addLast(new Input(kind, argument, generation));
        schedulePump();
        return true;
    }

    void finishMotion() {
        for (Iterator<Input> inputs = pending.iterator(); inputs.hasNext(); ) {
            if (inputs.next().isMotion()) inputs.remove();
        }
    }

    void finishDrag() {
        if (!dragging) return;
        dragging = false;
        finishMotion();
        Input first = pending.peekFirst();
        if (first == null || first.kind != TrackpadProtocol.END_DRAG) {
            pending.addFirst(new Input(TrackpadProtocol.END_DRAG, 0, 0));
        }
        schedulePump();
    }

    private static boolean isMotionKind(byte kind) {
        return kind == TrackpadProtocol.MOVE || kind == TrackpadProtocol.SCROLL || kind == TrackpadProtocol.DRAG;
    }

    private long heartbeatInterval() {
        return dragging ? DRAG_HEARTBEAT_MS : HEARTBEAT_MS;
    }

    private static int bound(int value) {
        return Math.max(-4096, Math.min(4096, value));
    }

    private void scheduleScan(long delay) {
        if (code == null) return;
        long sinceScan = SystemClock.elapsedRealtime() - lastScan;
        handler.removeCallbacks(scanAgain);
        handler.postDelayed(scanAgain, Math.max(delay, MIN_SCAN_INTERVAL_MS - sinceScan));
    }

    private void startScan() {
        if (code == null) return;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                listener.status("请打开手机蓝牙，开启后会自动搜索", false);
                scheduleScan(1000);
                return;
            }
            ScanCallback callback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    handler.post(() -> {
                        if (scanCallback != this) return;
                        connect(result.getDevice());
                    });
                }

                @Override public void onScanFailed(int errorCode) {
                    handler.post(() -> {
                        if (scanCallback == this) retry("搜索蓝牙失败（" + errorCode + "），正在重试…");
                    });
                }
            };
            scanCallback = callback;
            lastScan = SystemClock.elapsedRealtime();
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(TrackpadProtocol.SERVICE)).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            listener.status(missingService ? SERVICE_MISSING : "正在搜索 Mac 接收端…", false);
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            armTimeout(9000);
        } catch (IllegalStateException | SecurityException error) {
            retry("蓝牙暂不可用，请检查手机蓝牙和权限");
        }
    }

    private void connect(BluetoothDevice device) {
        stopScan();
        listener.status("发现 Mac，正在连接…", false);
        try {
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                retry("连接未建立，正在重试…");
                return;
            }
            armTimeout(10000);
        } catch (IllegalStateException | SecurityException error) {
            retry("连接失败，正在重试…");
        }
    }

    private boolean refreshServiceCache(BluetoothGatt link) {
        BluetoothDevice device = link.getDevice();
        try {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED
                    || !refreshedDevices.add(device.getAddress())) return false;
            // Android 12 can reuse a bonded host's GATT database after the receiver changes.
            // This method is optional because the platform does not expose it in the SDK.
            return Boolean.TRUE.equals(BluetoothGatt.class.getMethod("refresh").invoke(link))
                    && link.discoverServices();
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return false;
        }
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt link, int status, int state) {
            handler.post(() -> {
                if (link != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS || state != BluetoothProfile.STATE_CONNECTED) {
                    retry("连接断开，正在重连…");
                    return;
                }
                try {
                    link.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    if (!link.discoverServices()) {
                        retry("无法读取 Mac 蓝牙服务，正在重试…");
                        return;
                    }
                    armTimeout(6000);
                } catch (IllegalStateException | SecurityException error) {
                    retry("蓝牙服务暂不可用，正在重连…");
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt link, int status) {
            handler.post(() -> {
                if (link != gatt) return;
                BluetoothGattService service = link.getService(TrackpadProtocol.SERVICE);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retry("读取 Mac 蓝牙服务失败，正在重连…");
                    return;
                }
                if (service == null) {
                    missingService = true;
                    if (refreshServiceCache(link)) {
                        listener.status("正在重新读取这台 Mac 的蓝牙服务…", false);
                        armTimeout(6000);
                    } else {
                        retry(SERVICE_MISSING);
                    }
                    return;
                }
                missingService = false;
                BluetoothGattCharacteristic challenge = service.getCharacteristic(TrackpadProtocol.CHALLENGE);
                commands = service.getCharacteristic(TrackpadProtocol.COMMAND);
                consoleResult = service.getCharacteristic(TrackpadProtocol.CONSOLE_REPLY);
                try {
                    if (challenge == null || commands == null || !link.readCharacteristic(challenge)) {
                        retry("无法读取连接验证信息，正在重连…");
                        return;
                    }
                    armTimeout(5000);
                } catch (IllegalStateException | SecurityException error) {
                    retry("蓝牙连接发生变化，正在重连…");
                }
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt link, BluetoothGattCharacteristic characteristic,
                                                    byte[] value, int status) {
            readResult(link, characteristic, value, status);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt link, BluetoothGattCharacteristic characteristic,
                                                    int status) {
            readResult(link, characteristic, characteristic.getValue(), status);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt link, BluetoothGattCharacteristic characteristic,
                                                     int status) {
            handler.post(() -> {
                if (link != gatt || !writing || !TrackpadProtocol.COMMAND.equals(characteristic.getUuid())) return;
                writing = false;
                handler.removeCallbacks(timeout);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // ATT 0x08 is insufficient authorization. The Mac uses it for a rejected code.
                    if (sentKind == TrackpadProtocol.AUTH && status == 0x08) {
                        stop();
                        listener.codeRejected();
                    } else {
                        retry("Mac 未确认蓝牙指令，正在重连…");
                    }
                    return;
                }
                if (sentWithResponse) {
                    motionAwaitingResponse = false;
                    lastConfirmed = SystemClock.elapsedRealtime();
                }
                if (TrackpadProtocol.isConsole(sentKind)) {
                    try {
                        writing = true;
                        if (consoleResult != null && link.readCharacteristic(consoleResult)) {
                            armTimeout(4000);
                            return;
                        }
                    } catch (IllegalStateException | SecurityException ignored) {
                        // The link may have disconnected after the command was accepted.
                    }
                    retry("无法读取控制台执行结果");
                    return;
                }
                if (sentKind == TrackpadProtocol.AUTH) {
                    ready = true;
                    listener.status("已连接 · 支持三指拖移和四指切换桌面", true);
                }
                schedulePump();
            });
        }
    };

    private void readResult(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        if (!TrackpadProtocol.CONSOLE_REPLY.equals(characteristic.getUuid())) {
            challengeRead(link, characteristic, value, status);
            return;
        }
        byte[] bytes = value == null ? null : value.clone();
        handler.post(() -> {
            if (link != gatt || consoleCallback == null || !writing || !TrackpadProtocol.isConsole(sentKind)) return;
            handler.removeCallbacks(timeout);
            writing = false;
            JSONObject reply = null;
            String error = "未读到匹配的执行结果";
            if (status == BluetoothGatt.GATT_SUCCESS && bytes != null && bytes.length <= 480) {
                try {
                    JSONObject parsed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    if (parsed.optLong("sequence", -1) == consoleSequence) {
                        reply = parsed;
                        error = null;
                    }
                } catch (JSONException ignored) {
                    // A malformed response cannot acknowledge a command.
                }
            }
            finishConsole(reply, error);
            schedulePump();
        });
    }

    private void finishConsole(JSONObject reply, String error) {
        ConsoleCallback callback = consoleCallback;
        consoleCallback = null;
        if (callback != null) callback.complete(reply, error);
    }

    private void challengeRead(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        if (!TrackpadProtocol.CHALLENGE.equals(characteristic.getUuid())) return;
        byte[] nonce = value == null ? null : value.clone();
        handler.post(() -> {
            if (link != gatt) return;
            if (status != BluetoothGatt.GATT_SUCCESS || nonce == null || nonce.length != 16) {
                retry("连接验证失败，正在重连…");
                return;
            }
            protocol = new TrackpadProtocol(code, nonce);
            sequence = 0;
            write(new Input(TrackpadProtocol.AUTH, 0, 0));
        });
    }

    private void schedulePump() {
        if (!ready || writing) return;
        long now = SystemClock.elapsedRealtime();
        long delay = pending.isEmpty()
                ? Math.max(0, heartbeatInterval() - (now - lastConfirmed))
                : Math.max(0, MIN_SEND_INTERVAL_MS - (now - lastSent));
        handler.removeCallbacks(pump);
        handler.postDelayed(pump, delay);
    }

    private void sendNext() {
        if (!ready || writing) return;
        long now = SystemClock.elapsedRealtime();
        Input input = pending.pollFirst();
        while (input != null && input.expired(now)) input = pending.pollFirst();
        if (input != null) {
            write(input);
        } else if (now - lastConfirmed >= heartbeatInterval()) {
            write(new Input(TrackpadProtocol.HEARTBEAT, dragging ? 1 : 0, 0));
        } else {
            schedulePump();
        }
    }

    @SuppressWarnings("deprecation")
    private void write(Input input) {
        writing = true;
        sentKind = input.kind;
        lastSent = SystemClock.elapsedRealtime();
        // A confirmed write follows each unconfirmed motion before another can be queued.
        sentWithResponse = !input.isMotion() || motionAwaitingResponse;
        motionAwaitingResponse = true;
        int type = sentWithResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        int next = input.kind == TrackpadProtocol.AUTH ? 0 : ++sequence;
        if (TrackpadProtocol.isConsole(input.kind)) consoleSequence = next & 0xffffffffL;
        byte[] packet = protocol.packet(input.kind, next, input.x, input.y);
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = gatt.writeCharacteristic(commands, packet, type) == 0;
            } else {
                commands.setWriteType(type);
                commands.setValue(packet);
                accepted = gatt.writeCharacteristic(commands);
            }
            if (accepted) {
                armTimeout(4000);
            } else {
                retry("蓝牙写入失败，正在重连…");
            }
        } catch (IllegalStateException | SecurityException error) {
            retry("蓝牙写入失败，正在重连…");
        }
    }

    private void armTimeout(long delay) {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, delay);
    }

    private void retry(String message) {
        closeTransport();
        listener.status(message, false);
        scheduleScan(1400);
    }

    private void stopScan() {
        ScanCallback callback = scanCallback;
        BluetoothLeScanner active = scanner;
        scanCallback = null;
        scanner = null;
        if (active != null && callback != null) {
            try {
                active.stopScan(callback);
            } catch (IllegalStateException | SecurityException ignored) {
                // Bluetooth may have been switched off since the scan started.
            }
        }
    }

    private void closeTransport() {
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(pump);
        stopScan();
        ready = false;
        writing = false;
        dragging = false;
        motionAwaitingResponse = false;
        pending.clear();
        commands = null;
        consoleResult = null;
        protocol = null;
        BluetoothGatt old = gatt;
        gatt = null;
        finishConsole(null, "蓝牙已断开，未确认执行结果");
        if (old != null) {
            try {
                old.close();
            } catch (SecurityException ignored) {
                // Nearby devices permission may have been revoked.
            }
        }
    }
}
