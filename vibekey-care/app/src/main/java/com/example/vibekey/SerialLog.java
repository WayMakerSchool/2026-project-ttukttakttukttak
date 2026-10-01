package com.example.vibekey;

import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 기기에서 들어온 신호와 그때 한 일을 최근 것부터 기억해 둡니다.
 * 테스트(자가진단) 화면에서 "기기가 정말 신호를 보내고 있는지"를 눈으로 확인할 때 씁니다.
 */
public final class SerialLog {

    /** 로그가 바뀌면 이 신호를 보냅니다. (테스트 화면이 받아서 새로 그립니다.) */
    public static final String ACTION_LOG_CHANGED = "com.example.vibekey.LOG_CHANGED";

    private static final int MAX_LINES = 60;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.KOREA);

    private SerialLog() {
    }

    /**
     * 한 줄을 기록합니다.
     *
     * @param raw     기기가 보낸 원래 글자
     * @param outcome 그 신호로 무엇을 했는지 (사람이 읽을 수 있는 말)
     */
    public static void add(Context context, String raw, String outcome) {
        String entry = TIME_FORMAT.format(new Date()) + "  " + raw + "  →  " + outcome;
        synchronized (LINES) {
            LINES.addFirst(entry);
            while (LINES.size() > MAX_LINES) {
                LINES.removeLast();
            }
        }
        if (context != null) {
            context.sendBroadcast(new Intent(ACTION_LOG_CHANGED).setPackage(context.getPackageName()));
        }
    }

    /** 최근 기록을 새 것부터 돌려 줍니다. */
    public static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    public static boolean isEmpty() {
        synchronized (LINES) {
            return LINES.isEmpty();
        }
    }
}
