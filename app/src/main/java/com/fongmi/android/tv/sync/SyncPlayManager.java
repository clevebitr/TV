package com.fongmi.android.tv.sync;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * Keeps the Syncplay session of the playing video in sync with a room and exposes it to the UI.
 */
public final class SyncPlayManager {

    public interface Player {

        long getPosition();

        long getDuration();

        boolean isPlaying();

        boolean isPlayWhenReady();

        boolean isBuffering();

        void play();

        void pause();

        void seekTo(long time);

        String getMediaTitle();
    }

    public interface EpisodeHandler {

        /** Episode and source that are currently playing, published as the Syncplay file name. */
        SyncPlayItem getCurrentItem();

        /** Switch to the remote item, changing the crawler source when needed. */
        boolean switchTo(SyncPlayItem item);
    }

    public interface Listener {

        default void onSessionChanged() {
        }

        default void onUsersChanged() {
        }

        default void onRttChanged() {
        }

        default void onStatusChanged(String status) {
        }
    }

    private static final long SEEK_DRIFT = 1500;
    private static final long REMOTE_WINDOW = 1500;
    private static final long JOIN_GRACE = 1500;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SyncPlay");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Loader {
        private static final SyncPlayManager INSTANCE = new SyncPlayManager();
    }

    public static SyncPlayManager get() {
        return Loader.INSTANCE;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile SyncPlayClient client;
    private volatile SyncPlayUser[] users = new SyncPlayUser[0];
    private volatile String room = "";
    private volatile String username = "";
    private volatile String endpoint = "";
    private volatile String status = "";
    private volatile int rtt = -1;
    private volatile boolean connecting;
    private volatile int session;
    private volatile Player player;
    private volatile boolean localPlaying = true;
    private volatile EpisodeHandler episodeHandler;
    private volatile SyncPlayItem localItem;
    private volatile SyncPlayItem pendingRemote;
    private volatile String pendingRemoteBy = "";
    private volatile boolean applyingRemote;
    private volatile String authority;
    private volatile boolean remotePaused;
    private volatile long remoteUntil;
    private volatile long localUntil;
    private volatile long lastSeekAt;

    private SyncPlayManager() {
    }

    public void setPlayer(@Nullable Player player) {
        this.player = player;
    }

    public void setEpisodeHandler(@Nullable EpisodeHandler handler) {
        this.episodeHandler = handler;
        if (handler == null) return;
        // A file change may have arrived while the player screen was paused or still loading,
        // retry it as soon as the screen can switch episodes again.
        applyPendingRemote(false);
        if (pendingRemote == null) adoptAuthority();
    }

    public void clearEpisodeHandler(@Nullable EpisodeHandler handler) {
        if (this.episodeHandler == handler) this.episodeHandler = null;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isConnecting() {
        return connecting;
    }

    public boolean isConnected() {
        SyncPlayClient current = client;
        return current != null && !current.isClosed();
    }

    public String getRoom() {
        return room;
    }

    public String getUsername() {
        return username;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getStatus() {
        return status;
    }

    public int getRtt() {
        return rtt;
    }

    public List<SyncPlayUser> getUsers() {
        List<SyncPlayUser> list = new ArrayList<>(List.of(users));
        String mine = room;
        list.sort(Comparator.comparing((SyncPlayUser user) -> !user.getRoom().equals(mine)).thenComparing(SyncPlayUser::getRoom).thenComparing(SyncPlayUser::getName));
        return list;
    }

    public int getUserCount() {
        return users.length;
    }

    public void connect(@NonNull SyncPlayEndpoint endpoint, boolean tls, @NonNull String room, @NonNull String nickname) {
        if (connecting) return;
        close();
        int id = ++session;
        this.endpoint = endpoint.toString();
        this.room = room;
        this.username = nickname;
        log("connect %s room=%s nick=%s tls=%s", endpoint, room, nickname, tls);
        this.users = new SyncPlayUser[0];
        this.rtt = -1;
        this.connecting = true;
        updateStatus(ResUtil.getString(R.string.sync_play_connecting, endpoint.toString()));
        dispatchSession();
        // The player must only be touched on the main thread, so snapshot it before connecting.
        Player target = player;
        double startPosition = target == null ? 0 : Math.max(0, target.getPosition() / 1000.0);
        boolean startPaused = target == null || !target.isPlayWhenReady();
        localPlaying = !startPaused;
        EXECUTOR.execute(() -> {
            Session current = new Session(id);
            SyncPlayClient created = new SyncPlayClient(endpoint.getHost(), endpoint.getPort(), tls, current);
            current.client = created;
            try {
                created.connect();
                if (id != session) {
                    created.close();
                    return;
                }
                client = created;
                created.setLocalState(startPosition, startPaused);
                created.join(room, nickname);
                if (!current.isCurrent()) {
                    created.close();
                    return;
                }
                connecting = false;
                // Do not publish our file yet: a newcomer must adopt the room's episode first,
                // and the only-user case is handled by the init event.
                App.post(() -> {
                    dispatchSession();
                    requestUsers();
                });
            } catch (Exception e) {
                created.close();
                if (id != session) return;
                client = null;
                connecting = false;
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                App.post(() -> {
                    if (id != session) return;
                    updateStatus(ResUtil.getString(R.string.sync_play_connect_fail, message));
                    dispatchSession();
                    Notify.show(ResUtil.getString(R.string.sync_play_connect_fail, message));
                });
            }
        });
    }

    public void leave() {
        if (client == null && !connecting) return;
        close();
        updateStatus(ResUtil.getString(R.string.sync_play_left));
        dispatchSession();
    }

    private void close() {
        session++;
        connecting = false;
        SyncPlayClient current = client;
        client = null;
        if (current != null) current.close();
        users = new SyncPlayUser[0];
        rtt = -1;
        room = "";
        username = "";
        endpoint = "";
        localItem = null;
        pendingRemote = null;
        pendingRemoteBy = "";
        authority = null;
        dispatchUsers();
        dispatchRtt();
    }

    public void requestUsers() {
        SyncPlayClient current = client;
        if (current != null) current.requestList();
    }

    public void onLocalPlayState(boolean playWhenReady) {
        localPlaying = playWhenReady;
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null) return;
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
        if (isRemote(playWhenReady)) return;
        current.setLocalState(target.getPosition() / 1000.0, !playWhenReady);
        current.sendState(false, true);
    }

    public void onLocalSeek() {
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null) return;
        if (SystemClock.elapsedRealtime() < remoteUntil) return;
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
        localPlaying = target.isPlayWhenReady();
        current.setLocalState(target.getPosition() / 1000.0, !localPlaying);
        current.sendState(true, true);
    }

    public void onLocalMediaChanged() {
        SyncPlayItem item = getLocalItem();
        if (item.hasEpisode()) localItem = item;
        pushLocalMediaChange();
    }

    /**
     * Publishes the episode that just started playing. The activity passes the item explicitly so a
     * paused screen (or a playback started while the screen is not resumed) cannot fall back to the
     * generic media title.
     */
    public void onLocalMediaChanged(@Nullable SyncPlayItem item) {
        if (item != null && item.hasEpisode()) localItem = item;
        pushLocalMediaChange();
    }

    private void pushLocalMediaChange() {
        syncFile();
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null) return;
        current.setLocalState(Math.max(0, target.getPosition() / 1000.0), !target.isPlayWhenReady());
        current.sendState(true, true);
        localPlaying = target.isPlayWhenReady();
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
    }

    private void syncFile() {
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null) return;
        SyncPlayItem item = getLocalItem();
        if (!item.hasEpisode()) return;
        long duration = target.getDuration();
        log("publish file=%s", item.encode());
        current.sendFile(item.encode(), duration > 0 ? duration / 1000.0 : 0);
    }

    private SyncPlayItem getLocalItem() {
        EpisodeHandler handler = episodeHandler;
        if (handler != null) {
            SyncPlayItem item = handler.getCurrentItem();
            if (item != null && item.hasEpisode()) return item;
        }
        SyncPlayItem cached = localItem;
        if (cached != null && cached.hasEpisode()) return cached;
        Player target = player;
        return SyncPlayItem.local(target == null ? "" : target.getMediaTitle());
    }

    private static boolean sameEpisode(@Nullable SyncPlayItem left, @Nullable SyncPlayItem right) {
        return left != null && right != null && left.hasEpisode() && right.hasEpisode() && left.getEpisode().equalsIgnoreCase(right.getEpisode());
    }

    private boolean isRemote(boolean playWhenReady) {
        return SystemClock.elapsedRealtime() < remoteUntil && playWhenReady == !remotePaused;
    }

    private void applyState(SyncPlayClient.SyncPlayState state, @Nullable SyncPlayClient source, long joinedAt) {
        if (source == null || source != client) return;
        long now = SystemClock.elapsedRealtime();
        if (now - joinedAt < JOIN_GRACE || now < localUntil) return;
        Player target = player;
        if (target == null) return;
        String setBy = state.setBy();
        boolean mine = setBy.isEmpty() || setBy.equals(username);
        long position = Math.round(state.position() * 1000);
        if (state.paused() && target.isPlayWhenReady()) {
            remotePaused = true;
            remoteUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
            target.pause();
            localPlaying = false;
            if (!mine && position > 0) toast(ResUtil.getString(R.string.sync_play_paused, setBy));
        } else if (!state.paused() && !target.isPlayWhenReady()) {
            remotePaused = false;
            remoteUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
            target.play();
            localPlaying = true;
            if (!mine && position > 0) toast(ResUtil.getString(R.string.sync_play_played, setBy));
        }
        long duration = target.getDuration();
        if (duration <= 0) return;
        long drift = Math.abs(target.getPosition() - position);
        if (!state.doSeek() && drift <= SEEK_DRIFT) return;
        if (!state.doSeek() && target.isBuffering()) return;
        long timestamp = SystemClock.elapsedRealtime();
        if (!state.doSeek() && timestamp - lastSeekAt < REMOTE_WINDOW) return;
        lastSeekAt = timestamp;
        remotePaused = state.paused();
        remoteUntil = timestamp + REMOTE_WINDOW;
        target.seekTo(Math.min(Math.max(position, 0), duration));
    }

    public void testLatency(@Nullable String value, boolean tls, IntConsumer callback) {
        SyncPlayEndpoint endpoint = SyncPlayEndpoint.parse(value);
        if (endpoint == null) {
            if (callback != null) callback.accept(-1);
            return;
        }
        EXECUTOR.execute(() -> {
            int result = SyncPlayClient.measure(endpoint.getHost(), endpoint.getPort(), tls);
            if (callback != null) App.post(() -> callback.accept(result));
        });
    }

    private void adoptAuthority() {
        String name = authority;
        if (TextUtils.isEmpty(name) || name.equals(username)) return;
        for (SyncPlayUser user : users) {
            if (!name.equals(user.getName())) continue;
            SyncPlayItem item = SyncPlayItem.decode(user.getRawFile());
            if (!item.hasEpisode() || item.matches(getLocalItem()) || sameEpisode(getLocalItem(), item)) {
                authority = null;
                return;
            }
            // Keep the authority until the screen is actually able to switch, so a later resume retries.
            if (episodeHandler == null) return;
            if (episodeHandler.switchTo(item)) authority = null;
            return;
        }
    }

    /** Applies a remote file that could not be handled yet (screen paused, episodes not loaded...). */
    public void flushPendingRemote() {
        if (applyingRemote) return;
        applyPendingRemote(false);
        if (pendingRemote == null) adoptAuthority();
    }

    private void applyPendingRemote(boolean notifyFailure) {
        SyncPlayItem item = pendingRemote;
        if (item == null) return;
        EpisodeHandler handler = episodeHandler;
        if (handler == null) return;
        if (applyingRemote) return;
        SyncPlayItem local = getLocalItem();
        if (item.matches(local) || sameEpisode(local, item)) {
            pendingRemote = null;
            pendingRemoteBy = "";
            return;
        }
        applyingRemote = true;
        try {
            log("apply remote %s (local %s)", item.encode(), local.encode());
            if (handler.switchTo(item)) {
                String by = pendingRemoteBy;
                pendingRemote = null;
                pendingRemoteBy = "";
                authority = null;
                if (!TextUtils.isEmpty(by)) toast(ResUtil.getString(R.string.sync_play_file, by, item.getEpisode()));
            } else if (notifyFailure) {
                toast(ResUtil.getString(R.string.sync_play_switch_fail, item.getEpisode()));
            }
        } finally {
            applyingRemote = false;
        }
    }

    private static void log(String format, Object... args) {
        Log.d("SyncPlay", args.length == 0 ? format : String.format(Locale.ROOT, format, args));
    }

    private void addUser(String name) {
        if (TextUtils.isEmpty(name)) return;
        List<SyncPlayUser> list = new ArrayList<>(List.of(users));
        for (SyncPlayUser user : list) if (user.getName().equals(name)) return;
        list.add(new SyncPlayUser(room, name, "", false));
        users = list.toArray(new SyncPlayUser[0]);
        dispatchUsers();
    }

    /** Keeps the cached user list in sync with {@code Set.user.file} updates, not only with List replies. */
    private void updateUserFile(String name, String file) {
        List<SyncPlayUser> list = new ArrayList<>(List.of(users));
        for (int i = 0; i < list.size(); i++) {
            SyncPlayUser user = list.get(i);
            if (!user.getName().equals(name)) continue;
            if (user.getRawFile().equals(file)) return;
            list.set(i, new SyncPlayUser(user.getRoom(), user.getName(), file, user.isReady()));
            users = list.toArray(new SyncPlayUser[0]);
            dispatchUsers();
            return;
        }
    }

    private void removeUser(String name) {
        List<SyncPlayUser> list = new ArrayList<>(List.of(users));
        if (!list.removeIf(user -> user.getName().equals(name))) return;
        users = list.toArray(new SyncPlayUser[0]);
        dispatchUsers();
    }

    private void updateStatus(String status) {
        this.status = status;
        for (Listener listener : listeners) listener.onStatusChanged(status);
    }

    private void dispatchSession() {
        for (Listener listener : listeners) listener.onSessionChanged();
    }

    private void dispatchUsers() {
        for (Listener listener : listeners) listener.onUsersChanged();
    }

    private void dispatchRtt() {
        for (Listener listener : listeners) listener.onRttChanged();
    }

    private void toast(String message) {
        if (!TextUtils.isEmpty(message)) Notify.show(message);
    }

    private final class Session implements SyncPlayClient.Listener {

        private final int id;
        private volatile SyncPlayClient client;
        private volatile long joinedAt;

        private Session(int id) {
            this.id = id;
        }

        private boolean isCurrent() {
            return id == session && client == SyncPlayManager.this.client;
        }

        @Override
        public void onHello(String name, String roomName, String version, String motd) {
            if (!isCurrent()) return;
            App.post(() -> {
                if (!isCurrent()) return;
                username = name;
                room = roomName;
                joinedAt = SystemClock.elapsedRealtime();
                connecting = false;
                log("hello user=%s room=%s version=%s", name, roomName, version);
                updateStatus(ResUtil.getString(R.string.sync_play_server_info, endpoint));
                dispatchSession();
                requestUsers();
                if (!TextUtils.isEmpty(motd)) toast(motd);
            });
        }

        @Override
        public void onRoomEvent(String type, String name) {
            if (!isCurrent()) return;
            App.post(() -> {
                if (!isCurrent()) return;
                if ("init".equals(type)) {
                    if (TextUtils.isEmpty(name)) {
                        authority = null;
                        toast(ResUtil.getString(R.string.sync_play_only_user));
                        // The empty room must adopt our own episode as the room timeline.
                        onLocalMediaChanged();
                    } else {
                        authority = name;
                        toast(ResUtil.getString(R.string.sync_play_follow_user, name));
                        requestUsers();
                    }
                    return;
                }
                if (name.equals(username)) return;
                if ("joined".equals(type)) {
                    addUser(name);
                    toast(ResUtil.getString(R.string.sync_play_joined, name));
                } else if ("left".equals(type)) {
                    removeUser(name);
                    toast(ResUtil.getString(R.string.sync_play_left_user, name));
                }
                requestUsers();
            });
        }

        @Override
        public void onUsers(List<SyncPlayUser> list) {
            if (!isCurrent()) return;
            App.post(() -> {
                if (!isCurrent()) return;
                List<SyncPlayUser> filtered = new ArrayList<>();
                for (SyncPlayUser user : list) if (!user.isBlank()) filtered.add(user);
                users = filtered.toArray(new SyncPlayUser[0]);
                dispatchUsers();
                adoptAuthority();
                applyPendingRemote(false);
            });
        }

        @Override
        public void onState(SyncPlayClient.SyncPlayState state) {
            if (!isCurrent()) return;
            App.post(() -> applyState(state, client, joinedAt));
        }

        @Override
        public void onFileChanged(String name, String file) {
            if (!isCurrent()) return;
            App.post(() -> {
                if (!isCurrent()) return;
                updateUserFile(name, file);
                if (name.equals(username)) return;
                SyncPlayItem item = SyncPlayItem.decode(file);
                log("file from %s: %s", name, file);
                SyncPlayItem local = getLocalItem();
                if (!item.hasEpisode() || item.matches(local) || sameEpisode(local, item)) return;
                pendingRemote = item;
                pendingRemoteBy = name;
                applyPendingRemote(true);
            });
        }

        @Override
        public void onRtt(int value) {
            if (!isCurrent()) return;
            App.post(() -> {
                if (!isCurrent()) return;
                rtt = value;
                dispatchRtt();
            });
        }

        @Override
        public void onClosed(String message) {
            syncplayClosed(this, message);
        }
    }

    private void syncplayClosed(Session session, String message) {
        if (!session.isCurrent()) return;
        App.post(() -> {
            if (!session.isCurrent()) return;
            log("closed: %s", message);
            close();
            String text = ResUtil.getString(R.string.sync_play_disconnected, message);
            updateStatus(text);
            dispatchSession();
            toast(text);
        });
    }
}
