package com.example.wifi;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AppLogBuffer {
    private static final int MAX_LINES = 200;
    private static final List<String> LINES = new ArrayList<>();

    private AppLogBuffer() {
    }

    public static synchronized void add(String tag, String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + timestamp + "] " + tag + ": " + message;
        LINES.add(line);
        if (LINES.size() > MAX_LINES) {
            LINES.subList(0, LINES.size() - MAX_LINES).clear();
        }
    }

    public static synchronized String getText() {
        StringBuilder builder = new StringBuilder();
        for (String line : LINES) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
