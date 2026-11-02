package com.opendeeptutor.android;

import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide runtime status shared by the service (writer) and the activity (reader). */
final class RuntimeState {
    enum Phase { IDLE, INSTALLING, STARTING, READY, FAILED, STOPPED }

    interface Listener {
        void onStateChanged();
    }

    static final RuntimeState INSTANCE = new RuntimeState();
    private static final int MAX_LINES = 400;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    volatile Phase phase = Phase.IDLE;
    volatile int progress;
    volatile String message = "";
    volatile String url;
    volatile long phaseStartedAt = System.currentTimeMillis();
    volatile String lastLine = "";

    private RuntimeState() {}

    void addListener(Listener listener) { listeners.add(listener); }

    void removeListener(Listener listener) { listeners.remove(listener); }

    void set(Phase next, String text) {
        if (phase != next) phaseStartedAt = System.currentTimeMillis();
        phase = next;
        message = text == null ? "" : text;
        if (next != Phase.READY) url = null;
        notifyListeners();
    }

    void ready(String address) {
        url = address;
        phase = Phase.READY;
        phaseStartedAt = System.currentTimeMillis();
        message = "";
        notifyListeners();
    }

    void installing(int percent) {
        if (phase == Phase.INSTALLING && percent == progress) return;
        progress = percent;
        set(Phase.INSTALLING, "");
    }

    void log(String line) {
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
        if (!line.trim().isEmpty()) lastLine = line;
        if (phase == Phase.STARTING) notifyListeners();
    }

    String logText() {
        synchronized (lines) {
            return String.join("\n", lines);
        }
    }

    private void notifyListeners() {
        for (Listener listener : listeners) listener.onStateChanged();
    }
}
