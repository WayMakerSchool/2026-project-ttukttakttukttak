package com.example.vibekey;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.text.TextUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 버튼 기기(ESP32-S3)와 USB로 이어져 신호를 주고받는 백그라운드 서비스입니다.
 *
 * <h3>개발 과정에서 달라진 것 — 한 방향에서 두 방향으로</h3>
 * 초기에 이 서비스는 기기가 뱉는 평문 한 줄을 받아 읽기만 했고, 손 떨림 방지(3초 디바운스)
 * 같은 안전장치도 전부 여기 있었습니다. 폰이 바쁘면 그대로 무너지는 구조였습니다.
 * 지금은 판단을 기기로 내렸고, 이 서비스는 <b>주고받는 쪽</b>이 되었습니다.
 *
 * <pre>
 *   기기 → 폰   EVT_PRESS  어느 버튼을 어떻게 눌렀는지 (+ 기기가 잰 지연 us)
 *               HELLO      펌웨어 버전·버튼 수
 *               STATS      보낸 수·재전송 수·ACK 실패 수 (실측값)
 *   폰 → 기기   ACK        "잘 받았다" — 이게 없으면 기기가 세 번까지 다시 보냅니다
 *               PING       상태를 물어봄 (자가진단 화면에서 사용)
 * </pre>
 *
 * 기기에는 표시 장치가 없습니다(버튼 3개 + USB가 전부). 그래서 사용자에게 알리는 일은
 * 폰이 맡되, <b>무엇을 알릴지는 기기가 보낸 프레임이 정합니다</b> — 몇 번 버튼인지,
 * 짧게인지 길게인지에 따라 폰이 서로 다른 진동 패턴을 울립니다({@link Haptics}).
 * 부품을 하나도 안 늘리고 "화면을 보지 않아도 안다"를 만드는 방법입니다.
 * 옛 펌웨어("True\n")를 올린 기기도 그대로 동작합니다 —
 * 프레임이 아닌 바이트는 {@link FrameCodec.Decoder}가 따로 넘겨 주고, 예전 방식으로 해석합니다.
 */
public class UsbSerialService extends Service implements SerialInputOutputManager.Listener {

    private static final String TAG = "USB_SERIAL_SERVICE";
    private static final String ACTION_USB_PERMISSION = "com.example.vibekey.USB_PERMISSION";
    private static final String CHANNEL_ID = "UsbSerialMonitorChannel";
    private static final int NOTIFICATION_ID = 999;

    /**
     * 옛 평문 신호("True")를 쓰는 기기에만 적용하는 디바운스입니다.
     * 프레임 신호는 기기 쪽 상태머신이 떨림을 걸러 내고, 여기서는 SEQ로 중복을 막기 때문에
     * 이 시간에 기대지 않습니다. (기기 쪽 값: press_fsm.h 의 repeatGuardMs)
     */
    private static final long DEBOUNCE_DELAY_MS = 3000;

    private UsbManager usbManager;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;

    /** 프레임이 아닌 바이트(= 옛 평문)를 모아 두었다가 줄 단위로 해석합니다. */
    private final StringBuilder incomingBuffer = new StringBuilder();

    /** 들어온 바이트에서 온전한 프레임만 골라 내는 해석기 */
    private final FrameCodec.Decoder frameDecoder = new FrameCodec.Decoder();

    /** 시리얼 쓰기는 블로킹이라 서비스 스레드를 붙들지 않도록 따로 돌립니다. */
    private ExecutorService writer;

    /** PING 을 보낸 시각과 HELLO 가 돌아온 시각 — 둘의 차이가 왕복 시간입니다. */
    private static volatile long pingAtMs = 0;
    private static volatile long helloAtMs = 0;

    /** 마지막으로 실행한 프레임의 SEQ. 기기가 재전송해 와도 두 번 실행하지 않기 위한 표시입니다. */
    private int lastPressSeq = -1;

    /** 시리얼 쓰기 제한 시간(ms). 기기가 빠진 순간 스레드가 붙들리지 않게 짧게 둡니다. */
    private static final int WRITE_TIMEOUT_MS = 200;

    /** 버튼별로 마지막 실행 시각을 따로 기록합니다. */
    private final long[] lastLaunchTime = new long[Prefs.SLOT_COUNT + 1];
    private long lastAiLaunchTime = 0;

    public static final String ACTION_STATUS_CHANGED = "com.example.vibekey.STATUS_CHANGED";
    /** 기기에 저장해 둔 매핑을 되찾아 왔을 때 알립니다. */
    public static final String ACTION_MAP_RESTORED = "com.example.vibekey.MAP_RESTORED";
    public static final String EXTRA_IS_CONNECTED = "is_connected";
    public static final String EXTRA_DESCRIPTION = "description";

    /**
     * 기기 없이도 동작을 확인할 수 있도록, 테스트 화면에서 가짜 신호를 넣을 때 쓰는 명령입니다.
     * (실제 시리얼로 들어온 것과 완전히 같은 경로로 처리됩니다.)
     */
    public static final String ACTION_SIMULATE_SIGNAL = "com.example.vibekey.SIMULATE_SIGNAL";
    public static final String EXTRA_SIGNAL = "signal";

    /** 기기가 보내는 프레임을 그대로 만들어 넣어 보는 시험용 명령입니다. (자가진단 화면) */
    public static final String ACTION_SIMULATE_FRAME = "com.example.vibekey.SIMULATE_FRAME";
    public static final String EXTRA_BUTTON = "button";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_CORRUPT = "corrupt";

    /** 시험용 프레임에 붙일 시퀀스 번호 */
    private int simulationSeq = 0;

    private boolean isConnected = false;
    private boolean isForegroundStarted = false;


    // ---------------------------------------------------------------- 버튼 매핑 동기화
    //
    // "1번 버튼 = 카카오톡" 을 기기 플래시에도 같이 적어 둡니다.
    // 폰을 바꾸거나 앱을 다시 깔아도 어르신이 설정을 다시 잡지 않으셔도 됩니다.

    /** 기기가 조각내어 보내 준 매핑을 모으는 곳 */
    private final SlotMapCodec.Assembler mapAssembler = new SlotMapCodec.Assembler();
    /** 이 기기가 매핑을 저장할 수 있는지 (HELLO 의 caps 비트) */
    private boolean deviceCanStoreMap;

    /** 기기가 주머니 오작동으로 보고 걸러 낸 집계 (자가진단 화면에 보여 줍니다) */
    private static String deviceGuard = "";

    /** 기기가 잰 절전 실측치 (잠들어 있던 시간의 비율) */
    private static String devicePower = "";

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectToUsbDevice(device);
                        }
                    } else {
                        broadcastStatus(false, "기기 사용을 허락해 주세요.");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                findAndConnectUsbDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                disconnectUsbDevice();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        writer = Executors.newSingleThreadExecutor();
        decoderSnapshot = frameDecoder;
        instance = this;

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // 안드로이드 14부터는 USB 기기가 실제로 꽂혀 있을 때만
        // connectedDevice 형식의 포그라운드 서비스를 켤 수 있습니다.
        // 기기가 없을 때 무조건 켜려고 하면 SecurityException 으로 앱이 튕깁니다.
        if (hasUsbDevice(this)) {
            startForegroundNotification();
        }
        findAndConnectUsbDevice();
    }

    /**
     * 이 서비스를 안전하게 시작합니다.
     * 기기가 꽂혀 있을 때만 포그라운드로 올리고, 아닐 때는 보통 서비스로 시작합니다.
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, UsbSerialService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasUsbDevice(context)) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // 백그라운드에서 보통 서비스를 시작할 수 없는 경우가 있습니다.
            // 이때는 기기를 꽂는 순간(USB_DEVICE_ATTACHED)에 다시 시작되므로 그냥 넘어갑니다.
            Log.w(TAG, "Could not start service now: " + e.getMessage());
        }
    }

    /**
     * 기기 없이 동작을 시험해 보기 위해 가짜 신호를 넣습니다. (테스트 화면 전용)
     * 실제 시리얼로 들어온 것과 완전히 같은 경로로 처리됩니다.
     */
    public static void simulate(Context context, String signal) {
        Intent intent = new Intent(context, UsbSerialService.class)
                .setAction(ACTION_SIMULATE_SIGNAL)
                .putExtra(EXTRA_SIGNAL, signal);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not deliver test signal: " + e.getMessage());
        }
    }

    /**
     * 기기가 보내는 것과 똑같은 프레임을 만들어 실제 수신 경로에 넣습니다.
     *
     * @param corrupt true 면 비트 하나를 일부러 뒤집습니다. 이때 앱이 <b>아무 일도 하지 않아야</b>
     *                맞습니다. "깨진 신호는 실행되지 않는다"를 기기 없이 눈으로 보여 주는 시험입니다.
     */
    public static void simulateFrame(Context context, int button, int kind, boolean corrupt) {
        Intent intent = new Intent(context, UsbSerialService.class)
                .setAction(ACTION_SIMULATE_FRAME)
                .putExtra(EXTRA_BUTTON, button)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_CORRUPT, corrupt);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not deliver test frame: " + e.getMessage());
        }
    }

    /** USB 기기가 하나라도 꽂혀 있는지 확인합니다. */
    public static boolean hasUsbDevice(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return manager != null && !manager.getDeviceList().isEmpty();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SIMULATE_SIGNAL.equals(intent.getAction())) {
            String signal = intent.getStringExtra(EXTRA_SIGNAL);
            if (signal != null) {
                // 디바운스 때문에 시험이 막히지 않도록, 테스트 신호는 대기 시간을 지웁니다.
                resetDebounce();
                handleSerialLine(signal);
            }
        } else if (intent != null && ACTION_SIMULATE_FRAME.equals(intent.getAction())) {
            injectTestFrame(intent.getIntExtra(EXTRA_BUTTON, 1),
                    intent.getIntExtra(EXTRA_KIND, FrameCodec.K_SHORT),
                    intent.getBooleanExtra(EXTRA_CORRUPT, false));
        }
        // 서비스가 강제 종료되더라도 시스템이 자동으로 다시 켜 줍니다.
        return START_STICKY;
    }

    /** 테스트 화면에서 연달아 눌러도 매번 실행되도록 디바운스 기록을 지웁니다. */
    private void resetDebounce() {
        for (int i = 0; i < lastLaunchTime.length; i++) {
            lastLaunchTime[i] = 0;
        }
        lastAiLaunchTime = 0;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        disconnectUsbDevice();
        if (writer != null) {
            writer.shutdownNow();
            writer = null;
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered", e);
        }
    }

    // ------------------------------------------------------------------ 알림

    /**
     * 상시 알림을 띄우고 시스템이 서비스를 끄지 못하게 격상시킵니다.
     * USB 기기가 없을 때 호출하면 안드로이드 14 이상에서 예외가 나므로,
     * 실패해도 앱이 죽지 않도록 감싸 두었습니다.
     */
    private void startForegroundNotification() {
        if (isForegroundStarted) {
            return;
        }
        createNotificationChannel();
        Notification notification = buildNotification("버튼을 기다리고 있어요",
                "기기 버튼을 누르면 정해 둔 앱이 열립니다.");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForegroundStarted = true;
        } catch (Exception e) {
            // 기기가 빠진 상태 등에서는 격상이 거부될 수 있습니다. 서비스는 그대로 살려 둡니다.
            Log.w(TAG, "Foreground service not allowed now: " + e.getMessage());
        }
    }

    /** 기기가 빠지면 상시 알림을 내려 다음 격상이 다시 허용되게 합니다. */
    private void stopForegroundNotification() {
        if (!isForegroundStarted) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "stopForeground failed", e);
        }
        isForegroundStarted = false;
    }

    private Notification buildNotification(String title, String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        // 알림에서 바로 AI 도우미를 열 수 있게 해 둡니다.
        Intent aiIntent = new Intent(this, AiAssistantActivity.class);
        aiIntent.putExtra(AiAssistantActivity.EXTRA_AUTO_LISTEN, true);
        aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent aiPending = PendingIntent.getActivity(this, 1, aiIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_sparkle, "AI 도우미", aiPending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String text) {
        if (!isForegroundStarted) {
            return; // 상시 알림이 없을 때는 새로 만들지 않습니다.
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(title, text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "바이브키 버튼 감지",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("기기 버튼을 기다리는 동안 계속 켜져 있는 알림입니다.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    // ------------------------------------------------------------------ USB 연결

    private void findAndConnectUsbDevice() {
        List<UsbSerialDriver> availableDrivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (availableDrivers.isEmpty()) {
            broadcastStatus(false, "기기를 휴대폰에 꽂아 주세요.");
            return;
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();

        if (usbManager.hasPermission(device)) {
            connectToUsbDevice(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private void connectToUsbDevice(UsbDevice device) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            broadcastStatus(false, "이 기기는 알아보지 못했어요.");
            return;
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            broadcastStatus(false, "기기를 여는 데 실패했어요. 케이블을 다시 꽂아 주세요.");
            return;
        }

        usbSerialPort = driver.getPorts().get(0);
        try {
            usbSerialPort.open(connection);
            usbSerialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            try {
                usbSerialPort.setDTR(true);
                usbSerialPort.setRTS(true);
            } catch (UnsupportedOperationException e) {
                Log.w(TAG, "DTR/RTS not supported.");
            }

            usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
            usbIoManager.start();

            // 이어 붙은 시점의 반쪽짜리 바이트를 들고 있지 않도록 해석기를 비웁니다.
            frameDecoder.reset();
            lastPressSeq = -1;
            deviceInfo = null;
            deviceStats = null;

            // 기기가 실제로 붙었으니 이제 상시 알림으로 격상해도 됩니다.
            startForegroundNotification();
            broadcastStatus(true, "이제 버튼을 누르시면 앱이 열려요.");

            // 현재 펌웨어라면 HELLO+STATS로 답합니다. 옛 평문 펌웨어는 무시하고 지나갑니다.
            requestDeviceStatus();

        } catch (IOException e) {
            broadcastStatus(false, "연결에 실패했어요. 케이블을 다시 꽂아 주세요.");
            disconnectUsbDevice();
        }
    }

    private void disconnectUsbDevice() {
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
            usbIoManager = null;
        }
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing port", e);
            }
            usbSerialPort = null;
        }
        broadcastStatus(false, "기기를 휴대폰에 꽂아 주세요.");
        // 기기가 빠진 뒤에도 상시 알림을 붙들고 있으면 다음 격상이 거부됩니다.
        stopForegroundNotification();
    }

    /**
     * 지금 상태를 화면(MainActivity)과 바깥(삼성 루틴)에 함께 알립니다.
     * 연결 상태가 실제로 바뀌었을 때만 소리로 알려 드려, 잔소리처럼 들리지 않게 합니다.
     */
    private void broadcastStatus(boolean connected, String description) {
        boolean changed = this.isConnected != connected;
        this.isConnected = connected;

        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.putExtra(EXTRA_IS_CONNECTED, connected);
        intent.putExtra(EXTRA_DESCRIPTION, description);
        sendBroadcast(intent);

        updateNotification(connected ? "버튼을 기다리고 있어요" : "기기가 빠졌어요", description);

        if (changed) {
            RoutineBridge.notifyDeviceState(this, connected);
            SpeechManager.get(this).speakIfEnabled(this,
                    connected ? "기기가 연결되었어요." : "기기가 빠졌어요.");
        }
    }

    // ------------------------------------------------------------------ 신호 받기

    /**
     * 들어온 바이트를 프레임 해석기에 그대로 흘려 넣습니다.
     *
     * 해석기는 두 갈래로 나눠 돌려 줍니다.
     *   onFrame      — CRC까지 통과한 온전한 프레임 (현재 펌웨어)
     *   onStrayBytes — 프레임이 아니었던 바이트 (잡음, 또는 옛 펌웨어의 평문)
     * 덕분에 새 펌웨어와 옛 펌웨어를 한 경로에서 함께 받을 수 있습니다.
     */
    @Override
    public void onNewData(byte[] data) {
        frameDecoder.push(data, data.length, serialSink);
    }

    private final FrameCodec.Sink serialSink = new FrameCodec.Sink() {
        @Override
        public void onFrame(FrameCodec.Frame frame) {
            handleFrame(frame);
        }

        @Override
        public void onStrayBytes(byte[] bytes) {
            handleLegacyBytes(bytes);
        }
    };

    // ------------------------------------------------------------------ 프레임 해석

    private void handleFrame(FrameCodec.Frame frame) {
        switch (frame.type) {
            case FrameCodec.T_EVT_PRESS:
                handlePressFrame(frame);
                break;
            case FrameCodec.T_HELLO:
                deviceInfo = String.format(Locale.KOREA, "펌웨어 %d.%d · 버튼 %d개 · 프로토콜 v%d",
                        frame.u8(1), frame.u8(2), frame.u8(3), frame.u8(0));
                if (frame.len() >= 6) {
                    deviceInfo += " · 마지막 시작: " + bootReasonText(frame.u8(5));
                }
                helloAtMs = System.currentTimeMillis();
                SerialLog.add(this, "HELLO", deviceInfo);
                syncSlotMapWithDevice(frame.u8(4));
                break;
            case FrameCodec.T_STATS:
                deviceStats = String.format(Locale.KOREA,
                        "보낸 신호 %d개 · 재전송 %d회 · 응답 못 받음 %d회 · 켜진 지 %d초",
                        frame.u16(0), frame.u16(2), frame.u16(4), frame.u16(8));
                if (frame.len() >= 16) {
                    // 놓친 접점이 0이 아니면 "눌렀는데 반응이 없었다"는 뜻이라 따로 강조합니다.
                    int drops = frame.u16(14);
                    deviceStats += String.format(Locale.KOREA,
                            "\n메모리 여유 %dKB (최저 %dKB) · 놓친 버튼 입력 %d회%s",
                            frame.u16(10), frame.u16(12), drops, drops == 0 ? " ✅" : " ⚠️");
                }
                SerialLog.add(this, "STATS", deviceStats);
                break;
            case FrameCodec.T_STATS2:
                // 기기가 "주머니에서 눌린 것으로 보고 걸러 냈다"고 알려 준 숫자입니다.
                // 사용자가 "왜 안 눌렸지?" 하고 물을 때 답할 근거가 됩니다.
                deviceGuard = String.format(Locale.KOREA,
                        "주머니 오작동으로 걸러 낸 것 %d회 (동시 눌림 %d · 오래 눌림 %d · 연달아 %d)",
                        frame.u16(0), frame.u16(2), frame.u16(4), frame.u16(6));
                if (frame.len() >= 12) {
                    // 기기가 잰 절전 실측치입니다. 전류가 아니라 "잠들어 있던 시간"의 비율이라,
                    // 전류계 없이도 절전이 실제로 돌고 있는지 확인할 수 있습니다.
                    devicePower = String.format(Locale.KOREA,
                            "켜져 있는 동안 %.1f%% 를 잠들어 보냈어요 (절전 %d회)",
                            frame.u16(8) / 10.0, frame.u16(10));
                }
                SerialLog.add(this, "STATS2", deviceGuard
                        + (TextUtils.isEmpty(devicePower) ? "" : " · " + devicePower));
                break;
            case FrameCodec.T_MAP:
                handleMapFrame(frame);
                break;
            default:
                SerialLog.add(this, frame.toString(), "모르는 종류의 프레임 (무시함)");
                break;
        }
    }

    /**
     * 버튼 누름 프레임 처리. 순서가 중요합니다.
     *
     *  1. <b>먼저 ACK</b> — 기기의 재전송을 곧바로 멈춥니다. 앱을 여는 데 몇백 ms가 걸릴 수
     *     있는데 그동안 기다리게 하면 기기가 "못 받았다"고 오해해 같은 신호를 또 보냅니다.
     *  2. <b>같은 SEQ면 실행하지 않음</b> — ACK가 도중에 유실돼 기기가 다시 보낸 경우입니다.
     *     같은 누름이므로 앱을 두 번 열면 안 됩니다. (초기의 3초 디바운스를 대신하는 장치)
     */
    private void handlePressFrame(FrameCodec.Frame frame) {
        sendFrame(0, FrameCodec.T_ACK, new byte[]{(byte) frame.seq});

        if (frame.seq == lastPressSeq) {
            SerialLog.add(this, frame.toString(), "같은 신호 다시 옴 — 한 번만 실행 (SEQ " + frame.seq + ")");
            return;
        }
        lastPressSeq = frame.seq;

        SignalParser.Result result = SignalParser.fromFrame(frame);
        if (result.latencyUs >= 0) {
            lastLatencyUs = result.latencyUs;
            if (result.latencyUs > maxLatencyUs) {
                maxLatencyUs = result.latencyUs;
            }
        }
        String detail = describe(result);

        switch (result.action) {
            case OPEN_AI:
                SerialLog.add(this, frame.toString(), detail);
                Haptics.buttonPressed(this, 0, true);   // "길게 누른 것을 받았어요"
                openAiAssistant();
                break;
            case RUN_SLOT:
                SerialLog.add(this, frame.toString(), detail);
                Haptics.buttonPressed(this, result.slot, false);  // 버튼마다 다른 진동
                launchSlot(result.slot);
                break;
            default:
                SerialLog.add(this, frame.toString(), "알 수 없는 신호 (무시함)");
                break;
        }
    }

    /**
     * ESP-IDF 의 재시작 원인 코드를 사람이 읽을 수 있는 말로 바꿉니다.
     * (esp_reset_reason_t — 기기가 왜 다시 켜졌는지가 곧 고장 진단의 출발점입니다)
     */
    private static String bootReasonText(int code) {
        switch (code) {
            case 1:  return "전원 켜짐 (정상)";
            case 3:  return "소프트웨어 재시작";
            case 4:  return "오류로 멈춰 재시작";
            case 5:  return "인터럽트 워치독";
            case 6:  return "태스크 워치독 (멈춰 있어 자동 복구)";
            case 7:  return "워치독";
            case 9:  return "전압이 떨어져 재시작";
            case 11: return "USB 재연결";
            default: return "알 수 없음(" + code + ")";
        }
    }

    /** 기록 화면에 남길 설명. 기기가 잰 지연을 함께 적어 두면 나중에 그대로 근거가 됩니다. */
    private String describe(SignalParser.Result result) {
        String what = result.action == SignalParser.Action.OPEN_AI
                ? "AI 도우미 열기 (길게 누름)"
                : result.slot + "번 버튼 실행";
        if (result.latencyUs >= 0) {
            what += String.format(Locale.KOREA, " · 기기 지연 %.2fms", result.latencyUs / 1000.0);
        }
        return what;
    }

    // ------------------------------------------------------------------ 옛 평문 신호

    /**
     * 프레임이 아니었던 바이트입니다. 옛 평문 펌웨어를 올린 기기는 여기로 들어옵니다.
     * 이 경로에는 CRC도 SEQ도 없으므로, 예전처럼 3초 디바운스로만 떨림을 막습니다.
     */
    private void handleLegacyBytes(byte[] bytes) {
        String incomingText = new String(bytes, StandardCharsets.UTF_8);
        synchronized (incomingBuffer) {
            incomingBuffer.append(incomingText);
            int newlineIndex;
            while ((newlineIndex = incomingBuffer.indexOf("\n")) != -1) {
                String line = incomingBuffer.substring(0, newlineIndex).trim();
                incomingBuffer.delete(0, newlineIndex + 1);
                handleSerialLine(line);
            }
            // 줄바꿈 없이 잡음만 계속 들어오는 경우에 버퍼가 무한히 자라지 않게 합니다.
            if (incomingBuffer.length() > 512) {
                incomingBuffer.setLength(0);
            }
        }
    }

    private void handleSerialLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        Log.d(TAG, "Received: " + line);

        SignalParser.Result result = SignalParser.parse(line);
        switch (result.action) {
            case OPEN_AI:
                if (isTooSoonForAi()) {
                    return;
                }
                SerialLog.add(this, line, "AI 도우미 열기 (옛 방식 신호)");
                openAiAssistant();
                break;
            case RUN_SLOT:
                if (isTooSoonForSlot(result.slot)) {
                    return;
                }
                SerialLog.add(this, line, result.slot + "번 버튼 실행 (옛 방식 신호)");
                launchSlot(result.slot);
                break;
            default:
                SerialLog.add(this, line, "알 수 없는 신호 (무시함)");
                break;
        }
    }

    /** 옛 평문 경로 전용 떨림 방지. 프레임 경로는 기기가 이미 걸러 냅니다. */
    private boolean isTooSoonForSlot(int slot) {
        long now = System.currentTimeMillis();
        if (now - lastLaunchTime[slot] < DEBOUNCE_DELAY_MS) {
            return true;
        }
        lastLaunchTime[slot] = now;
        return false;
    }

    private boolean isTooSoonForAi() {
        long now = System.currentTimeMillis();
        if (now - lastAiLaunchTime < DEBOUNCE_DELAY_MS) {
            return true;
        }
        lastAiLaunchTime = now;
        return false;
    }

    // ------------------------------------------------------------------ 실행

    /** 버튼에 연결된 앱을 엽니다. 실행 결과는 폰이 음성·진동·화면으로 알립니다. */
    private void launchSlot(final int slot) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Prefs prefs = Prefs.with(UsbSerialService.this);
                if (!prefs.hasSlot(slot)) {
                    String message = slot + "번 버튼에 넣을 앱을 먼저 정해 주세요.";
                    Toast.makeText(UsbSerialService.this, message, Toast.LENGTH_LONG).show();
                    SpeechManager.get(UsbSerialService.this)
                            .speakIfEnabled(UsbSerialService.this, message);
                    Haptics.failure(UsbSerialService.this);
                    return;
                }
                // runSlot 은 성공했을 때 스스로 success() 진동을 냅니다.
                if (!AppLauncher.runSlot(UsbSerialService.this, slot, "hardware")) {
                    Haptics.failure(UsbSerialService.this);
                }
            }
        });
    }

    private void openAiAssistant() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(UsbSerialService.this, AiAssistantActivity.class);
                intent.putExtra(AiAssistantActivity.EXTRA_AUTO_LISTEN, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        });
    }

    // ------------------------------------------------------------------ 버튼 매핑 동기화

    /**
     * 기기가 자기소개(HELLO)를 했을 때, 버튼 매핑을 어느 쪽으로 맞출지 정합니다.
     *
     * <p>규칙은 하나뿐입니다 — <b>폰에 정해 둔 것이 있으면 폰이 옳고, 없으면 기기가 옳다.</b>
     * <ul>
     *   <li>폰에 아무것도 없음 (새 폰 · 앱을 다시 깖) → 기기에게 물어봐 되찾아 옵니다.</li>
     *   <li>폰에 정해 둔 것이 있음 → 기기에 같은 값을 적어 둡니다. 다음 폰을 위한 준비입니다.</li>
     * </ul>
     * 기기가 덮어쓰는 일은 없습니다. 어르신이 방금 바꾼 설정이 케이블을 꽂자마자
     * 옛날 것으로 되돌아가면, 왜 그런지 알 방법이 없기 때문입니다.
     *
     * @param caps HELLO 의 5번째 바이트 (기능 비트)
     */
    private void syncSlotMapWithDevice(int caps) {
        deviceCanStoreMap = (caps & FrameCodec.CAP_SLOT_STORE) != 0;
        if (!deviceCanStoreMap) {
            return;     // 옛 펌웨어입니다. 예전처럼 폰만 들고 있습니다.
        }
        mapAssembler.resetAll();

        if (Prefs.with(this).hasAnyExplicitSlot()) {
            SerialLog.add(this, "매핑 보내기", "폰에 정해 둔 설정을 기기에도 적어 둡니다.");
            pushSlotMapToDevice();
        } else {
            SerialLog.add(this, "매핑 물어보기", "폰에 설정이 없어 기기에 저장된 것을 찾아봅니다.");
            sendFrame(0, FrameCodec.T_GET_MAP, null);
        }
    }

    /**
     * 폰이 들고 있는 버튼 매핑을 기기 플래시에 적습니다.
     *
     * <p>값이 16바이트 한도를 넘으므로 조각으로 나눠 보냅니다. 기기는 다 모이면
     * 저장한 값을 그대로 되읽어 보내 주므로, 그 회신이 곧 "잘 저장됐다"는 확인입니다.
     */
    private void pushSlotMapToDevice() {
        Prefs prefs = Prefs.with(this);
        for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
            // 앱 실행이 아닌 빠른 동작(전화·문자·길찾기)은 패키지명이 없어 기기에 담지 않습니다.
            boolean isApp = "APP".equals(prefs.getSlotActionType(slot));
            byte[] value = isApp
                    ? SlotMapCodec.encodeValue(prefs.getSlotPackage(slot), prefs.getSlotLabel(slot))
                    : new byte[0];
            if (value == null) {
                continue;   // 담을 수 없을 만큼 긴 패키지명 (실제로는 없습니다)
            }
            for (byte[] chunk : SlotMapCodec.split(slot, value)) {
                sendFrame(0, FrameCodec.T_SET_MAP, chunk);
            }
        }
    }

    /**
     * 기기가 보내 준 매핑 조각을 모읍니다.
     *
     * <p>다 모였을 때에도 <b>폰에 이미 정해 둔 값이 있으면 덮어쓰지 않습니다.</b>
     * 되찾아 오는 것은 "폰이 비어 있을 때"뿐입니다.
     */
    private void handleMapFrame(FrameCodec.Frame frame) {
        int slot = frame.u8(0);
        int index = frame.u8(1);
        int total = frame.u8(2);
        int dataLen = Math.max(0, frame.len() - SlotMapCodec.HEADER_LEN);
        byte[] data = new byte[dataLen];
        for (int i = 0; i < dataLen; i++) {
            data[i] = (byte) frame.u8(SlotMapCodec.HEADER_LEN + i);
        }

        int result = mapAssembler.accept(slot, index, total, data);
        if (result == SlotMapCodec.REJECTED) {
            SerialLog.add(this, frame.toString(), "매핑 조각이 어긋나 버렸습니다 (" + slot + "번)");
            return;
        }
        if (result != SlotMapCodec.COMPLETE) {
            return;
        }

        byte[] value = mapAssembler.completed(slot);
        SlotMapCodec.Entry entry = SlotMapCodec.decodeValue(value);
        SerialLog.add(this, "매핑 받음", slot + "번 = " + SlotMapCodec.describe(value));

        if (entry.isEmpty()) {
            return;
        }
        Prefs prefs = Prefs.with(this);
        if (prefs.hasAnyExplicitSlot()) {
            return;     // 폰이 옳습니다. 되돌리지 않습니다.
        }

        prefs.setSlot(slot, entry.packageName, entry.label);
        // 기기가 설정을 들고 있었다는 것은 이미 첫 설정을 마치셨다는 뜻입니다.
        // 새 폰에서 같은 것을 또 여쭙지 않습니다.
        prefs.setOnboarded(true);
        RoutineBridge.refreshShortcuts(this);
        sendBroadcast(new Intent(ACTION_MAP_RESTORED).setPackage(getPackageName()));
    }

    /**
     * 매핑이 바뀌었을 때(첫 설정 · 앱 고르기 · AI 추천) 기기에도 적어 두라고 알립니다.
     * 기기가 안 꽂혀 있으면 아무 일도 하지 않고, 다음에 꽂을 때 HELLO 에서 다시 맞춥니다.
     */
    public static void pushSlotMap(Context context) {
        UsbSerialService self = instance;
        if (self == null || !self.deviceCanStoreMap) {
            return;
        }
        self.pushSlotMapToDevice();
    }

    // ------------------------------------------------------------------ 기기로 보내기

    /**
     * 프레임 하나를 기기로 보냅니다. 시리얼 쓰기는 블로킹이라 전용 스레드에서 처리하고,
     * 실패해도 앱이 죽지 않게 조용히 넘어갑니다(기기가 빠진 순간 등).
     */
    private void sendFrame(int seq, int type, byte[] payload) {
        final byte[] bytes = FrameCodec.encode(seq, type, payload);
        if (bytes == null) {
            return;
        }
        // 읽기 담당(SerialInputOutputManager)이 살아 있으면 그쪽 쓰기 큐에 맡깁니다.
        // 같은 포트를 두 스레드가 동시에 건드리지 않게 하는 가장 안전한 길입니다.
        SerialInputOutputManager io = usbIoManager;
        if (io != null) {
            try {
                io.writeAsync(bytes);
                return;
            } catch (Exception e) {
                Log.w(TAG, "writeAsync failed, falling back: " + e.getMessage());
            }
        }
        final UsbSerialPort port = usbSerialPort;
        if (port == null || writer == null || writer.isShutdown()) {
            return;
        }
        writer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    port.write(bytes, WRITE_TIMEOUT_MS);
                } catch (Exception e) {
                    Log.w(TAG, "Could not send frame: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 시험용 프레임을 실제 수신 경로(해석기)에 그대로 흘려 넣습니다.
     * 지름길을 두지 않고 진짜 바이트로 넣기 때문에, CRC 검사·SEQ 중복 차단까지
     * 전부 실제와 똑같이 동작합니다.
     */
    private void injectTestFrame(int button, int kind, boolean corrupt) {
        simulationSeq = (simulationSeq % 255) + 1;
        byte[] payload = new byte[]{(byte) button, (byte) kind, 0, 0};
        byte[] bytes = FrameCodec.encode(simulationSeq, FrameCodec.T_EVT_PRESS, payload);
        if (bytes == null) {
            return;
        }
        if (corrupt) {
            bytes[4] ^= 0x01;   // payload 첫 바이트의 최하위 비트 하나만 뒤집습니다
            SerialLog.add(this, "잡음 시험", "비트 하나를 일부러 뒤집은 프레임을 넣었습니다 — 실행되면 안 됩니다");
        }
        frameDecoder.push(bytes, bytes.length, serialSink);
        if (corrupt) {
            SerialLog.add(this, "잡음 시험 결과",
                    "CRC 오류 " + frameDecoder.crcErrors + "건 · 실행 안 함 ✅");
        }
    }

    /**
     * 기기에 "지금 상태를 알려 달라"고 묻습니다(PING → HELLO + STATS).
     *
     * 자가진단 화면이 이걸 부르면, 프레임을 <b>보내고 받는 왕복 전체</b>가 실제로 도는지
     * 확인됩니다. 즉 이 한 번의 왕복이 CRC·프레이밍·양방향 링크를 한꺼번에 시험합니다.
     */
    public static void requestDeviceStatus() {
        UsbSerialService self = instance;
        if (self == null) {
            return;
        }
        pingAtMs = System.currentTimeMillis();
        helloAtMs = 0;
        self.sendFrame(0, FrameCodec.T_PING, null);
    }

    /** 기기에 물어본 뒤 답이 돌아오기까지 걸린 시간. 아직 답이 없으면 null. */
    /** 기기가 주머니 오작동으로 걸러 낸 집계. 아직 못 받았으면 빈 글자. */
    public static String guardSummary() {
        return deviceGuard;
    }

    /** 기기가 잰 절전 실측치. 아직 못 받았으면 빈 글자. */
    public static String powerSummary() {
        return devicePower;
    }

    public static String roundTripSummary() {
        if (pingAtMs == 0) {
            return null;
        }
        if (helloAtMs == 0 || helloAtMs < pingAtMs) {
            return null;
        }
        return (helloAtMs - pingAtMs) + "ms 만에 답이 왔어요.";
    }

    // ------------------------------------------------------------------ 자가진단용 요약

    /*
     * 아래 값들은 테스트(자가진단) 화면이 그대로 읽어 갑니다.
     * "잘 되는 것 같다"가 아니라 숫자로 보여 줘야 어디가 문제인지 알 수 있습니다.
     */

    private static volatile String deviceInfo = null;    // HELLO 로 받은 기기 정보
    private static volatile String deviceStats = null;   // STATS 로 받은 기기 통계
    private static volatile int lastLatencyUs = -1;      // 기기가 잰 마지막 지연
    private static volatile int maxLatencyUs = -1;       // 그중 최악값

    /** 기기가 알려 준 자기 소개. 아직 못 받았으면 null. */
    public static String deviceInfo() {
        return deviceInfo;
    }

    /** 기기가 알려 준 송신 통계. 아직 못 받았으면 null. */
    public static String deviceStats() {
        return deviceStats;
    }

    /** 접점에서 USB 송출까지 기기가 직접 잰 지연. 아직 없으면 null. */
    public static String latencySummary() {
        if (lastLatencyUs < 0) {
            return null;
        }
        return String.format(Locale.KOREA, "마지막 %.2fms · 최악 %.2fms (기기가 직접 잰 값)",
                lastLatencyUs / 1000.0, maxLatencyUs / 1000.0);
    }

    /** 프레임을 몇 개 받았고 몇 바이트를 버렸는지. 프레임 오류율이 여기서 나옵니다. */
    public static String frameSummary() {
        FrameCodec.Decoder d = decoderSnapshot;
        if (d == null) {
            return null;
        }
        return String.format(Locale.KOREA,
                "받은 프레임 %d개 · CRC 오류 %d · 형식 오류 %d · 버린 바이트 %d (오류율 %.3f%%)",
                d.accepted, d.crcErrors, d.framingErrors, d.discarded, d.errorRate() * 100.0);
    }

    /** 서비스가 살아 있는 동안 자가진단 화면이 볼 수 있도록 해석기를 가리켜 둡니다. */
    private static volatile FrameCodec.Decoder decoderSnapshot = null;

    /** 자가진단 화면이 기기에 직접 물어볼 수 있도록 지금 살아 있는 서비스를 가리킵니다. */
    private static volatile UsbSerialService instance = null;

    @Override
    public void onRunError(Exception e) {
        disconnectUsbDevice();
    }
}
