package com.fongmi.android.tv.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal Syncplay (https://syncplay.pl/about/protocol/) client.
 * <p>
 * The protocol is line based JSON over TCP, optionally upgraded to TLS with STARTTLS.
 * Every message is a single JSON object terminated by CRLF.
 */
public class SyncPlayClient {

    public interface Listener {

        default void onHello(String username, String room, String version, String motd) {
        }

        default void onRoomEvent(String type, String username) {
        }

        default void onUsers(List<SyncPlayUser> users) {
        }

        default void onState(SyncPlayState state) {
        }

        default void onFileChanged(String username, String name) {
        }

        default void onRtt(int rtt) {
        }

        default void onClosed(String message) {
        }
    }

    public record SyncPlayState(double position, boolean paused, boolean doSeek, String setBy, int rtt) {
    }

    private static final String VERSION = "1.7.0";
    private static final double PING_WEIGHT = 0.85;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int HANDSHAKE_TIMEOUT = 15000;

    private final Object writeLock = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SyncPlayWriter");
        thread.setDaemon(true);
        return thread;
    });
    private final String host;
    private final int port;
    private final boolean tls;
    private final Listener listener;

    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile InputStream input;
    private volatile boolean closed;
    private volatile String username = "";
    private volatile String room = "";

    private double clientRtt;
    private double serverRtt;
    private double avrRtt;
    private double forwardDelay;
    private double latencyCalculation;
    private double positionSeconds;
    private boolean pausedState = true;
    private int clientIgnore;
    private int serverIgnore;
    private int reportedRtt = -1;

    public SyncPlayClient(@NonNull String host, int port, boolean tls, @NonNull Listener listener) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.listener = listener;
    }

    public static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    public String getUsername() {
        return username;
    }

    public String getRoom() {
        return room;
    }

    public int getRtt() {
        return reportedRtt;
    }

    public boolean isClosed() {
        return closed;
    }

    public void connect() throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
        if (tls) raw = startTls(raw, host, port);
        socket = raw;
        input = raw.getInputStream();
        output = raw.getOutputStream();
        Thread reader = new Thread(this::readLoop, "SyncPlayClient");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Measures the time needed to reach a Syncplay server, including the STARTTLS handshake
     * when the endpoint requires it. Returns -1 on failure.
     */
    public static int measure(String host, int port, boolean tls) {
        long start = System.nanoTime();
        Socket raw = new Socket();
        try {
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            Socket socket = tls ? startTls(raw, host, port) : raw;
            closeQuietly(socket);
            return (int) Math.max(1, Math.round((System.nanoTime() - start) / 1000000.0));
        } catch (Exception e) {
            closeQuietly(raw);
            return -1;
        }
    }

    private static Socket startTls(Socket raw, String host, int port) throws IOException {
        try {
            raw.setSoTimeout(HANDSHAKE_TIMEOUT);
            raw.getOutputStream().write("{\"TLS\":{\"startTLS\":\"send\"}}\r\n".getBytes(StandardCharsets.UTF_8));
            raw.getOutputStream().flush();
            String line = readLine(raw.getInputStream());
            if (line == null) throw new IOException("服务器未响应 STARTTLS");
            JSONObject json = new JSONObject(line);
            String answer = json.optJSONObject("TLS") == null ? "" : json.getJSONObject("TLS").optString("startTLS");
            if (!"true".equals(answer)) throw new IOException("服务器不支持 STARTTLS");
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
            ssl.setUseClientMode(true);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            ssl.setSoTimeout(0);
            return ssl;
        } catch (IOException e) {
            closeQuietly(raw);
            throw e;
        } catch (Exception e) {
            closeQuietly(raw);
            throw new IOException("STARTTLS 握手失败", e);
        }
    }

    private static String readLine(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = stream.read();
            if (value == -1) return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
            if (value == '\n') break;
            if (value != '\r') buffer.write(value);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    public void join(@NonNull String room, @NonNull String username) throws IOException {
        this.room = room;
        this.username = username;
        JSONObject feature = new JSONObject();
        try {
            feature.put("sharedPlaylists", true);
            feature.put("chat", false);
            feature.put("featureList", true);
            feature.put("readiness", true);
            feature.put("managedRooms", false);
            feature.put("persistentRooms", false);
            JSONObject info = new JSONObject();
            info.put("username", username);
            JSONObject roomInfo = new JSONObject();
            roomInfo.put("name", room);
            info.put("room", roomInfo);
            info.put("version", VERSION);
            info.put("realversion", VERSION);
            info.put("features", feature);
            JSONObject hello = new JSONObject();
            hello.put("Hello", info);
            sendNow(hello);
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public void sendFile(@NonNull String name, double duration) {
        try {
            JSONObject file = new JSONObject();
            file.put("name", name);
            file.put("duration", duration);
            file.put("size", 0);
            JSONObject set = new JSONObject();
            set.put("file", file);
            JSONObject message = new JSONObject();
            message.put("Set", set);
            send(message);
        } catch (Exception ignored) {
        }
    }

    public void requestList() {
        try {
            send(new JSONObject().put("List", JSONObject.NULL));
        } catch (Exception ignored) {
        }
    }

    public void setLocalState(double position, boolean paused) {
        positionSeconds = Math.max(0, position);
        pausedState = paused;
    }


    public void sendState(boolean doSeek, boolean stateChange) {
        if (closed) return;
        try {
            if (stateChange) clientIgnore++;
            Integer serverAck = null;
            Integer clientAck = null;
            if (serverIgnore > 0) {
                serverAck = serverIgnore;
                serverIgnore = 0;
            }
            if (clientIgnore > 0) clientAck = clientIgnore;
            JSONObject playstate = new JSONObject();
            playstate.put("position", Math.max(0, positionSeconds));
            playstate.put("paused", pausedState);
            playstate.put("doSeek", doSeek);
            playstate.put("setBy", username);
            JSONObject ping = new JSONObject();
            ping.put("clientRtt", clientRtt);
            ping.put("clientLatencyCalculation", now());
            if (latencyCalculation > 0) ping.put("latencyCalculation", latencyCalculation);
            JSONObject state = new JSONObject();
            state.put("ping", ping);
            state.put("playstate", playstate);
            if (clientAck != null || serverAck != null) {
                JSONObject ignore = new JSONObject();
                if (clientAck != null) ignore.put("client", clientAck);
                if (serverAck != null) ignore.put("server", serverAck);
                state.put("ignoringOnTheFly", ignore);
            }
            JSONObject message = new JSONObject();
            message.put("State", state);
            send(message);
        } catch (Exception ignored) {
        }
    }

    /**
     * Queues a message for the writer thread. Syncplay callbacks run on the main thread, and a TLS
     * write (or close) there throws {@link android.os.NetworkOnMainThreadException} on devices that
     * enable strict mode, which used to silently drop every {@code Set.file} / {@code State}.
     */
    private void send(JSONObject message) {
        final byte[] data;
        try {
            data = (message.toString() + "\r\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        try {
            writer.execute(() -> write(data));
        } catch (Exception ignored) {
        }
    }

    private void write(byte[] data) {
        OutputStream stream = output;
        if (stream == null) return;
        try {
            synchronized (writeLock) {
                stream.write(data);
                stream.flush();
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            if (closed) return;
            closed = true;
            Socket current = socket;
            if (current != null) closeQuietly(current);
            listener.onClosed(message);
        }
    }

    private void sendNow(JSONObject message) throws IOException {
        if (closed) throw new IOException("连接已断开");
        OutputStream stream = output;
        if (stream == null) throw new IOException("尚未连接");
        byte[] data = (message.toString() + "\r\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            stream.write(data);
            stream.flush();
        }
    }

    private void readLoop() {
        String reason = "连接已断开";
        try {
            InputStream stream = input;
            String line;
            while ((line = readLine(stream)) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                handle(line);
            }
        } catch (Exception e) {
            reason = e.getMessage() == null ? reason : e.getMessage();
        }
        Socket current = socket;
        if (closed) return;
        closed = true;
        writer.shutdown();
        if (current != null) closeQuietly(current);
        listener.onClosed(reason);
    }

    public void close() {
        if (!closed) {
            closed = true;
            final Socket current = socket;
            Runnable task = () -> {
                if (current != null) closeQuietly(current);
            };
            try {
                // TLS close writes a close_notify record, so it must run off the main thread too.
                writer.execute(task);
            } catch (Exception e) {
                // The writer was already shut down: close on a throw-away thread instead of the
                // caller (which is usually the main thread).
                Thread thread = new Thread(task, "SyncPlayClose");
                thread.setDaemon(true);
                thread.start();
            }
        }
        writer.shutdown();
    }

    private void handle(String line) {
        try {
            JSONObject json = new JSONObject(line);
            if (json.has("Hello")) handleHello(json.getJSONObject("Hello"));
            else if (json.has("Set")) handleSet(json.getJSONObject("Set"));
            else if (json.has("List")) handleList(json.optJSONObject("List"));
            else if (json.has("State")) handleState(json.getJSONObject("State"));
            else if (json.has("Error")) handleError(json.optJSONObject("Error"));
        } catch (Exception ignored) {
        }
    }

    private void handleHello(JSONObject hello) {
        String name = hello.optString("username", username);
        JSONObject roomInfo = hello.optJSONObject("room");
        String roomName = roomInfo == null ? room : roomInfo.optString("name", room);
        String version = hello.optString("version");
        String motd = hello.optString("motd");
        username = name;
        room = roomName;
        listener.onHello(name, roomName, version, motd);
    }

    private void handleSet(JSONObject set) {
        JSONObject index = set.optJSONObject("playlistIndex");
        if (index != null) {
            String by = index.isNull("user") ? "" : index.optString("user", "");
            listener.onRoomEvent("init", by);
        }
        JSONObject users = set.optJSONObject("user");
        if (users != null) {
            for (java.util.Iterator<String> iterator = users.keys(); iterator.hasNext(); ) {
                String name = iterator.next();
                JSONObject detail = users.optJSONObject(name);
                if (detail == null) continue;
                JSONObject event = detail.optJSONObject("event");
                if (event != null) {
                    if (event.has("joined")) listener.onRoomEvent("joined", name);
                    else if (event.has("left")) listener.onRoomEvent("left", name);
                }
                JSONObject file = detail.optJSONObject("file");
                if (file != null) {
                    String fileName = file.optString("name", "");
                    if (!fileName.isEmpty()) listener.onFileChanged(name, fileName);
                }
            }
        }
        JSONObject roomInfo = set.optJSONObject("room");
        if (roomInfo != null && roomInfo.has("name")) room = roomInfo.optString("name", room);
    }

    private void handleList(@Nullable JSONObject list) {
        List<SyncPlayUser> users = new ArrayList<>();
        if (list != null) {
            for (java.util.Iterator<String> rooms = list.keys(); rooms.hasNext(); ) {
                String roomName = rooms.next();
                JSONObject value = list.optJSONObject(roomName);
                if (value == null) continue;
                for (java.util.Iterator<String> names = value.keys(); names.hasNext(); ) {
                    String name = names.next();
                    JSONObject detail = value.optJSONObject(name);
                    if (detail == null) continue;
                    JSONObject file = detail.optJSONObject("file");
                    String fileName = file == null ? "" : file.optString("name", "");
                    boolean ready = detail.optBoolean("isReady", false);
                    users.add(new SyncPlayUser(roomName, name, fileName, ready));
                }
            }
        }
        listener.onUsers(users);
    }

    private void handleError(@Nullable JSONObject error) {
        String message = error == null ? "" : error.optString("message", "");
        closed = true;
        writer.shutdown();
        Socket current = socket;
        if (current != null) closeQuietly(current);
        listener.onClosed(message.isEmpty() ? "服务器错误" : message);
    }

    private void handleState(JSONObject state) {
        JSONObject ping = state.optJSONObject("ping");
        if (ping != null) {
            double clientLatency = ping.optDouble("clientLatencyCalculation", 0);
            latencyCalculation = ping.optDouble("latencyCalculation", 0);
            serverRtt = ping.optDouble("serverRtt", 0);
            updateRtt(clientLatency, serverRtt);
        }
        JSONObject ignore = state.optJSONObject("ignoringOnTheFly");
        if (ignore != null) {
            if (ignore.has("server")) {
                serverIgnore = ignore.optInt("server", 0);
                clientIgnore = 0;
            } else if (ignore.has("client") && ignore.optInt("client", 0) == clientIgnore) {
                clientIgnore = 0;
            }
        }
        JSONObject playstate = state.optJSONObject("playstate");
        if (playstate != null && clientIgnore == 0) {
            double position = playstate.optDouble("position", 0);
            boolean paused = playstate.optBoolean("paused", true);
            boolean doSeek = playstate.optBoolean("doSeek", false);
            String setBy = playstate.isNull("setBy") ? "" : playstate.optString("setBy", "");
            double calculated = paused ? position : position + forwardDelay;
            positionSeconds = Math.max(0, position);
            pausedState = paused;
            listener.onState(new SyncPlayState(calculated, paused, doSeek, setBy, getRtt()));
        }
        sendState(false, false);
    }

    private void updateRtt(double clientLatency, double senderRtt) {
        if (clientLatency <= 0) return;
        double rtt = now() - clientLatency;
        if (rtt < 0 || senderRtt < 0) return;
        clientRtt = rtt;
        if (avrRtt == 0) avrRtt = clientRtt;
        avrRtt = avrRtt * PING_WEIGHT + clientRtt * (1 - PING_WEIGHT);
        forwardDelay = senderRtt < clientRtt ? avrRtt / 2 + (clientRtt - senderRtt) : avrRtt / 2;
        int value = (int) Math.round(avrRtt * 1000);
        if (value == reportedRtt) return;
        reportedRtt = value;
        listener.onRtt(value);
    }
}
