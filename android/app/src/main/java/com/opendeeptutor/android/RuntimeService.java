package com.opendeeptutor.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service that owns the on-device backend: it unpacks the Linux guest
 * on first run, then starts DeepTutor's FastAPI backend and Next.js web server
 * inside it through proot, and reports readiness to the UI.
 */
public class RuntimeService extends Service {
    static final String ACTION_START = "com.opendeeptutor.android.START";
    static final String ACTION_RESTART = "com.opendeeptutor.android.RESTART";
    static final String ACTION_REINSTALL = "com.opendeeptutor.android.REINSTALL";
    static final String ACTION_STOP = "com.opendeeptutor.android.STOP";

    private static final String CHANNEL = "runtime";
    private static final int NOTIFICATION_ID = 1;
    private static final int PREFERRED_BACKEND_PORT = 18801;
    // The web origin keeps the UI's local storage; keep it stable across runs.
    private static final int PREFERRED_FRONTEND_PORT = 18802;
    private static final long READY_TIMEOUT_MS = 15 * 60 * 1000L;

    private final RuntimeState state = RuntimeState.INSTANCE;
    private final Object lock = new Object();
    private Thread worker;
    private Process process;
    private volatile boolean stopping;

    static void send(Context context, String action) {
        Intent intent = new Intent(context, RuntimeService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        startInForeground(getString(R.string.notif_starting));
        if (ACTION_STOP.equals(action)) {
            new Thread(() -> {
                shutdown();
                state.set(RuntimeState.Phase.STOPPED, "");
                stopForeground(true);
                stopSelf();
            }, "runtime-stop").start();
            return START_NOT_STICKY;
        }
        boolean restart = ACTION_RESTART.equals(action) || ACTION_REINSTALL.equals(action);
        boolean reinstall = ACTION_REINSTALL.equals(action);
        synchronized (lock) {
            boolean running = worker != null && worker.isAlive();
            if (running && !restart) return START_NOT_STICKY;
            Thread previous = worker;
            worker = new Thread(() -> {
                if (previous != null) {
                    shutdown();
                    try { previous.join(15000); } catch (InterruptedException ignored) { }
                }
                run(reinstall);
            }, "runtime");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void run(boolean reinstall) {
        stopping = false;
        File rootfs = new File(getFilesDir(), "rootfs");
        File logFile = new File(getFilesDir(), "logs/runtime.log");
        logFile.getParentFile().mkdirs();
        try {
            state.set(RuntimeState.Phase.INSTALLING, "");
            if (reinstall) PayloadInstaller.deleteTree(rootfs);
            if (!PayloadInstaller.installed(this, rootfs)) {
                state.log("[android] unpacking bundled runtime");
                PayloadInstaller.ensure(this, rootfs, state::installing);
                state.log("[android] runtime unpacked");
            }
            if (stopping) return;
            launch(rootfs, logFile);
        } catch (Exception e) {
            if (stopping) return;
            state.log("[android] " + e);
            state.set(RuntimeState.Phase.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
            updateNotification(getString(R.string.state_failed));
        }
    }

    private void launch(File rootfs, File logFile) throws Exception {
        int backendPort = freePort(PREFERRED_BACKEND_PORT);
        int frontendPort = freePort(PREFERRED_FRONTEND_PORT);
        if (frontendPort == backendPort) frontendPort = freePort(0);
        state.set(RuntimeState.Phase.STARTING, "");

        File home = new File(getFilesDir(), "home");
        File shm = new File(getCacheDir(), "shm");
        File prootTmp = new File(getCacheDir(), "proot-tmp");
        for (File dir : new File[] {home, shm, prootTmp, new File(rootfs, "tmp")}) dir.mkdirs();
        writeNetworkFiles(rootfs);

        String libDir = getApplicationInfo().nativeLibraryDir;
        List<String> cmd = new ArrayList<>();
        cmd.add(libDir + "/libproot.so");
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        for (String bind : new String[] {"/dev", "/proc", "/sys"}) {
            cmd.add("-b");
            cmd.add(bind);
        }
        cmd.add("-b");
        cmd.add(shm.getAbsolutePath() + ":/dev/shm");
        // User data lives outside the guest so app updates can replace the runtime.
        cmd.add("-b");
        cmd.add(home.getAbsolutePath() + ":/home/deeptutor");
        // Android hides parts of /proc from apps; substitute static copies.
        for (String name : new String[] {"stat", "loadavg", "uptime", "version", "vmstat"}) {
            File real = new File("/proc/" + name);
            if (real.canRead()) continue;
            cmd.add("-b");
            cmd.add(new File(rootfs, "opt/deeptutor/proc/" + name).getAbsolutePath() + ":/proc/" + name);
        }
        cmd.add("-w");
        cmd.add("/home/deeptutor");
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("BACKEND_PORT=" + backendPort);
        cmd.add("FRONTEND_PORT=" + frontendPort);
        cmd.add("ANDROID_GUEST=1");
        cmd.add("/bin/bash");
        cmd.add("/opt/deeptutor/start.sh");

        ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", "/system/bin");
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", prootTmp.getAbsolutePath());
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        env.put("PROOT_LOADER", libDir + "/libproot-loader.so");
        env.put("LD_LIBRARY_PATH", libDir);
        env.put("ANDROID_ROOT", "/system");
        env.put("ANDROID_DATA", "/data");

        Process child;
        synchronized (lock) {
            if (stopping) return;
            state.log("[android] starting guest (backend " + backendPort + ", web " + frontendPort + ")");
            child = builder.start();
            process = child;
        }
        updateNotification(getString(R.string.notif_starting));

        try (Writer log = new FileWriter(logFile, StandardCharsets.UTF_8, false)) {
            Thread reader = new Thread(() -> pump(child, log), "runtime-log");
            reader.start();

            String backend = "http://127.0.0.1:" + backendPort;
            String frontend = "http://127.0.0.1:" + frontendPort;
            long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
            boolean backendReady = false;
            while (!stopping && child.isAlive() && System.currentTimeMillis() < deadline) {
                if (!backendReady) backendReady = probe(backend + "/health/ready");
                if (backendReady && probe(frontend + "/")) {
                    state.log("[android] DeepTutor is ready");
                    state.ready(frontend + "/chat");
                    updateNotification(getString(R.string.notif_running));
                    break;
                }
                Thread.sleep(700);
            }
            if (!stopping && state.phase != RuntimeState.Phase.READY) {
                String reason = child.isAlive() ? "等待服务就绪超时" : "后端进程已退出（代码 " + child.exitValue() + "）";
                state.set(RuntimeState.Phase.FAILED, reason);
                updateNotification(getString(R.string.state_failed));
                child.destroy();
            }
            int code = child.waitFor();
            reader.join(2000);
            if (!stopping && state.phase == RuntimeState.Phase.READY) {
                state.log("[android] guest exited with " + code);
                state.set(RuntimeState.Phase.FAILED, "本地服务意外退出（代码 " + code + "）");
                updateNotification(getString(R.string.state_failed));
            }
        } finally {
            synchronized (lock) {
                if (process == child) process = null;
            }
        }
    }

    private void pump(Process child, Writer log) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                state.log(line);
                synchronized (log) {
                    log.write(line);
                    log.write('\n');
                    log.flush();
                }
            }
        } catch (IOException ignored) {
            // The stream closes when the guest exits.
        }
    }

    private void shutdown() {
        Process child;
        synchronized (lock) {
            stopping = true;
            child = process;
        }
        if (child == null) return;
        child.destroy();
        try {
            if (!child.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly();
        } catch (InterruptedException ignored) {
            child.destroyForcibly();
        }
    }

    /** glibc in the guest reads /etc/resolv.conf; Android keeps DNS in system properties instead. */
    private void writeNetworkFiles(File rootfs) throws IOException {
        Set<String> servers = new LinkedHashSet<>();
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network network = cm.getActiveNetwork();
            LinkProperties props = network == null ? null : cm.getLinkProperties(network);
            if (props != null) {
                for (InetAddress address : props.getDnsServers()) {
                    // Link-local IPv6 resolvers need a scope id glibc cannot express here.
                    if (address instanceof Inet4Address || !address.isLinkLocalAddress()) {
                        servers.add(address.getHostAddress());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to public resolvers below.
        }
        servers.add("223.5.5.5");
        servers.add("119.29.29.29");
        servers.add("8.8.8.8");
        StringBuilder resolv = new StringBuilder();
        int count = 0;
        for (String server : servers) {
            if (count++ == 3) break;  // glibc uses at most three
            resolv.append("nameserver ").append(server).append('\n');
        }
        resolv.append("options timeout:2 attempts:2\n");
        File etc = new File(rootfs, "etc");
        Files.write(new File(etc, "resolv.conf").toPath(), resolv.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(etc, "hosts").toPath(),
                "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n".getBytes(StandardCharsets.UTF_8));
    }

    static boolean probe(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            return code >= 200 && code < 400;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int freePort(int preferred) throws IOException {
        if (preferred > 0) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", preferred));
                return preferred;
            } catch (IOException ignored) {
                // Taken: fall through to an ephemeral port.
            }
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private void startInForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stop = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_EXIT)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
                .build();
    }
}
