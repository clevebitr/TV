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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

        boolean hasMedia();

        void play();

        void pause();

        void seekTo(long time);

        String getMediaTitle();

        /**
         * Applies a temporary playback rate multiplier for sync correction. It must not change the
         * speed the user selected, nor the speed shown in the UI or stored in the history.
         *
         * @return false when the player cannot change its speed.
         */
        default boolean setSyncTempo(float value) {
            return false;
        }
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

        default void onBufferingChanged() {
        }
    }

    private static final long SEEK_DRIFT = 4000;
    private static final long SEEK_FASTFORWARD = 5000;
    private static final long SEEK_DEAD_ZONE = 400;
    private static final long SEEK_COOLDOWN = 4000;
    private static final long SEEK_CALM = 12000;
    private static final long SEEK_GUARD = 10000;
    private static final long SEEK_GUARD_DRIFT = 6000;
    private static final long SEEK_BUFFER_GRACE = 6000;
    private static final long TEMPO_DRIFT = 1500;
    private static final long TEMPO_RESET = 150;
    private static final float TEMPO_SLOW = 0.95f;
    private static final float TEMPO_FAST = 1.05f;
    private static final long REMOTE_WINDOW = 1500;
    private static final long JOIN_GRACE = 1500;
    private static final long BUFFERING_DELAY = 800;
    private static final long BUFFERING_TIMEOUT = 20000;

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
    private volatile double roomPosition;
    private volatile long roomPositionAt;
    private volatile boolean roomPaused = true;
    private volatile long remoteUntil;
    private volatile long localUntil;
    private volatile long lastSeekAt;
    private volatile int seekCount;
    private volatile long seekGuardTarget = -1;
    private volatile long seekGuardUntil;
    private volatile long seekBufferingUntil;
    private volatile float tempo = 1f;
    private volatile boolean tempoUnsupported;
    private volatile long joinedAt;
    private final Set<String> remoteBuffering = ConcurrentHashMap.newKeySet();
    private final Map<String, Runnable> bufferingTimeouts = new ConcurrentHashMap<>();
    private volatile boolean localBuffering;
    private volatile boolean localBufferingWasPlaying;
    private volatile boolean bufferingPaused;
    private volatile long bufferingToken;
    private volatile Runnable bufferingHeartbeat;
    private volatile String bufferingClearedBy = "";
    private volatile long bufferingClearedAt;
    private volatile boolean emptyLeaveScheduled;

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
        // Do not leave the room paused for buffering: release everyone first.
        if (localBuffering) resumeRoom();
        close();
        updateStatus(ResUtil.getString(R.string.sync_play_left));
        dispatchSession();
    }

    private void close() {
        session++;
        connecting = false;
        resetTempo();
        tempoUnsupported = false;
        SyncPlayClient current = client;
        client = null;
        if (current != null) current.close();
        users = new SyncPlayUser[0];
        rtt = -1;
        room = "";
        username = "";
        endpoint = "";
        joinedAt = 0;
        lastSeekAt = 0;
        seekCount = 0;
        seekGuardTarget = -1;
        seekGuardUntil = 0;
        seekBufferingUntil = 0;
        roomPosition = 0;
        roomPositionAt = 0;
        roomPaused = true;
        localItem = null;
        pendingRemote = null;
        pendingRemoteBy = "";
        authority = null;
        clearBuffering();
        dispatchUsers();
        dispatchRtt();
    }

    private void clearBuffering() {
        stopBufferingHeartbeat();
        for (Runnable runnable : bufferingTimeouts.values()) App.removeCallbacks(runnable);
        bufferingTimeouts.clear();
        boolean changed = !remoteBuffering.isEmpty() || localBuffering;
        remoteBuffering.clear();
        localBuffering = false;
        localBufferingWasPlaying = false;
        bufferingPaused = false;
        if (changed) dispatchBuffering();
    }

    public void requestUsers() {
        SyncPlayClient current = client;
        if (current != null) current.requestList();
    }

    public boolean isLocalBuffering() {
        return localBuffering;
    }

    public boolean isBuffering(String name) {
        return name != null && remoteBuffering.contains(name);
    }

    public String getFirstBufferingUser() {
        for (String name : remoteBuffering) return name;
        return "";
    }

    /**
     * Reason the room is paused for buffering, ready to be rendered by the player overlay.
     * Empty when nobody buffers: our own buffering already shows the player loading indicator.
     */
    public String getBufferingMessage() {
        String name = getFirstBufferingUser();
        return name.isEmpty() ? "" : ResUtil.getString(R.string.sync_play_overlay_wait, name);
    }

    public void onLocalPlayState(boolean playWhenReady) {
        localPlaying = playWhenReady;
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia()) return;
        // While somebody in the room is buffering the room stays paused, even if this user tries
        // to press play: otherwise the buffering user immediately falls behind.
        if (playWhenReady && localBuffering) {
            // We are still buffering: keep the room paused until the stream is ready.
            sendState(false, true, target.getPosition(), true);
            return;
        }
        if (playWhenReady && !remoteBuffering.isEmpty()) {
            target.pause();
            toast(ResUtil.getString(R.string.sync_play_buffering_wait, remoteBuffering.iterator().next()));
            return;
        }
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
        if (isRemote(playWhenReady)) return;
        sendState(false, true);
    }

    public void onLocalSeek() {
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia()) return;
        long timestamp = SystemClock.elapsedRealtime();
        if (timestamp < remoteUntil) return;
        // The discontinuity of our own catch-up seek (media3 reports it again after the seek
        // resolves) must not be broadcast, otherwise every peer seeks with us and ping-pongs.
        if (isOwnSeek(target.getPosition(), timestamp)) {
            log("skip own sync seek");
            return;
        }
        resetTempo();
        seekGuardTarget = -1;
        seekGuardUntil = 0;
        seekCount = 0;
        localUntil = timestamp + REMOTE_WINDOW;
        localPlaying = target.isPlayWhenReady();
        sendState(true, true);
    }

    /**
     * Publishes the local play state. The room timeline is the slowest member of the room, so a
     * client that is still catching up must not report its own lagging position: it would drag the
     * whole room (and every buffer of the other clients) backwards.
     */
    private void sendState(boolean doSeek, boolean stateChange) {
        Player target = player;
        if (target == null || !target.hasMedia()) return;
        sendState(doSeek, stateChange, target.getPosition(), !target.isPlayWhenReady());
    }

    private void sendState(boolean doSeek, boolean stateChange, long positionMs, boolean paused) {
        SyncPlayClient current = client;
        if (current == null) return;
        double position = Math.max(0, positionMs / 1000.0);
        if (!doSeek) position = Math.max(position, estimateRoomPosition());
        current.setLocalState(position, paused);
        current.sendState(doSeek, stateChange);
    }

    /** Replies to a state push with our live position, which keeps the room timeline honest. */
    private void replyState() {
        SyncPlayClient current = client;
        if (current == null) return;
        Player target = player;
        if (target != null && target.hasMedia()) sendState(false, false);
        else current.sendState(false, false);
    }

    /** Position of the room, extrapolated from the last state the server sent us. */
    private double estimateRoomPosition() {
        if (roomPositionAt == 0) return 0;
        double position = roomPosition;
        if (!roomPaused) position += (SystemClock.elapsedRealtime() - roomPositionAt) / 1000.0;
        return Math.max(0, position);
    }

    private boolean isOwnSeek(long position, long timestamp) {
        long guard = seekGuardTarget;
        if (guard < 0 || timestamp >= seekGuardUntil) return false;
        return Math.abs(position - guard) <= SEEK_GUARD_DRIFT;
    }

    /**
     * Official Syncplay strategy: a small drift is corrected by nudging the playback rate instead of
     * jumping, so the buffer of the stream we are watching is never thrown away. The rate is applied
     * on top of the speed the user picked and disappears as soon as the drift is gone.
     *
     * @param drift positive when the local player is ahead of the room.
     */
    private void updateTempo(Player target, SyncPlayClient.SyncPlayState state, long drift, long timestamp) {
        if (state.doSeek() || state.paused() || !target.isPlayWhenReady() || target.isBuffering()) {
            applyTempo(target, 1f);
            return;
        }
        long magnitude = Math.abs(drift);
        if (magnitude <= TEMPO_RESET) applyTempo(target, 1f);
        else if (magnitude > TEMPO_DRIFT) applyTempo(target, drift > 0 ? TEMPO_SLOW : TEMPO_FAST);
    }

    private void applyTempo(Player target, float value) {
        if (tempoUnsupported) return;
        if (target == null || !target.hasMedia()) return;
        if (!target.setSyncTempo(value)) {
            // Players without a speed command would be retried on every single state.
            tempoUnsupported = true;
            tempo = 1f;
            return;
        }
        if (tempo == value) return;
        tempo = value;
        log("tempo %.2f", value);
        if (value != 1f) toast(ResUtil.getString(R.string.sync_play_tempo, value));
    }

    private void resetTempo() {
        tempo = 1f;
        Player target = player;
        if (target != null && target.hasMedia()) target.setSyncTempo(1f);
    }

    /** Latency aware dead band: a laggy link makes the remote position estimate jitter. */
    private static long seekTolerance(long base, int rtt) {
        return base + (rtt <= 0 ? 0 : Math.min(rtt / 2, 1500));
    }

    /** Growing pause between catch-up seeks so a slow stream is not rewound over and over. */
    private long seekCooldown() {
        return SEEK_COOLDOWN + Math.min(seekCount, 3) * 2000L;
    }

    /**
     * Called from the player state listener. A slow user reports a buffering pause once it has been
     * buffering for a short moment; when the stream is ready again the room is resumed.
     */
    public void onLocalBuffering(boolean buffering) {
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia()) {
            if (localBuffering) {
                localBuffering = false;
                localBufferingWasPlaying = false;
                stopBufferingHeartbeat();
                dispatchBuffering();
            }
            scheduleLeaveWhenEmpty();
            return;
        }
        if (buffering) {
            if (localBuffering || !target.isPlayWhenReady()) return;
            if (joinedAt == 0 || SystemClock.elapsedRealtime() - joinedAt < JOIN_GRACE) return;
            long token = ++bufferingToken;
            App.post(() -> confirmBuffering(token), BUFFERING_DELAY);
            return;
        }
        if (!localBuffering) return;
        boolean resume = localBufferingWasPlaying && target.isPlayWhenReady();
        localBuffering = false;
        localBufferingWasPlaying = false;
        stopBufferingHeartbeat();
        dispatchBuffering();
        if (resume) {
            log("buffering done, resume room");
            publishBuffering(true, true);
            localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
            localPlaying = true;
            sendState(true, true, target.getPosition(), false);
        } else {
            // The user paused during the buffering; make sure the room stays paused.
            log("buffering done while paused");
            publishBuffering(false);
            localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
            localPlaying = false;
            sendState(false, true, target.getPosition(), true);
        }
    }

    private void scheduleLeaveWhenEmpty() {
        SyncPlayClient sessionClient = client;
        if (emptyLeaveScheduled || sessionClient == null) return;
        emptyLeaveScheduled = true;
        App.post(() -> {
            emptyLeaveScheduled = false;
            Player target = player;
            if (client != sessionClient || target == null || target.hasMedia()) return;
            log("player stopped, leaving room");
            leave();
        }, 3000);
    }

    private void confirmBuffering(long token) {
        if (token != bufferingToken || localBuffering) return;
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia() || !target.isBuffering()) return;
        if (!target.isPlayWhenReady()) return;
        long timestamp = SystemClock.elapsedRealtime();
        if (timestamp < seekBufferingUntil) {
            // The stream is only refilling because of our own catch-up seek, not because it is slow.
            long delay = Math.max(500, seekBufferingUntil - timestamp);
            log("buffering after sync seek, recheck in %dms", delay);
            App.post(() -> confirmBuffering(token), delay);
            return;
        }
        localBuffering = true;
        localBufferingWasPlaying = true;
        log("local buffering after %dms, pausing room", BUFFERING_DELAY);
        resetTempo();
        publishBuffering(true, false);
        startBufferingHeartbeat();
        sendState(false, true, target.getPosition(), true);
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
        localPlaying = false;
        dispatchBuffering();
        toast(ResUtil.getString(R.string.sync_play_buffering_self));
    }

    /** Sends an unpause state for the buffering client before it leaves the room. */
    private void resumeRoom() {
        SyncPlayClient current = client;
        Player target = player;
        localBuffering = false;
        localBufferingWasPlaying = false;
        stopBufferingHeartbeat();
        publishBuffering(false);
        dispatchBuffering();
        resetTempo();
        if (current == null || target == null || !target.hasMedia()) return;
        sendState(false, true, target.getPosition(), false);
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
    }

    /**
     * Publishes the buffering flag through Set.file, the only custom field the Syncplay server
     * forwards to every room member (State fields are rebuilt by the server and would be lost).
     */
    private void publishBuffering(boolean buffering) {
        publishBuffering(buffering, false);
    }

    private void publishBuffering(boolean buffering, boolean done) {
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia()) return;
        SyncPlayItem item = getLocalItem();
        if (!item.hasEpisode()) return;
        long duration = target.getDuration();
        SyncPlayItem payload = done ? item.withBufferingDone() : item.withBuffering(buffering);
        log("publish buffering=%s done=%s", buffering, done);
        current.sendFile(payload.encode(), duration > 0 ? duration / 1000.0 : 0);
    }

    private void startBufferingHeartbeat() {
        stopBufferingHeartbeat();
        Runnable heartbeat = new Runnable() {
            @Override
            public void run() {
                if (!localBuffering || client == null) return;
                publishBuffering(true, false);
                App.post(this, 5000);
            }
        };
        bufferingHeartbeat = heartbeat;
        App.post(heartbeat, 5000);
    }

    private void stopBufferingHeartbeat() {
        Runnable heartbeat = bufferingHeartbeat;
        bufferingHeartbeat = null;
        if (heartbeat != null) App.removeCallbacks(heartbeat);
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
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia()) return;
        syncFile();
        resetTempo();
        sendState(true, true);
        localPlaying = target.isPlayWhenReady();
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
    }

    private void syncFile() {
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia()) return;
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
        Player target = player;
        if (target == null || !target.hasMedia()) return;
        String setBy = state.setBy();
        boolean own = !setBy.isEmpty() && setBy.equals(username);
        // The server echoes our own states back; applying them only makes the local player fight itself.
        if (own) return;
        // The room timeline follows the slowest member, so it is the reference for our own reports.
        roomPosition = Math.max(0, state.position());
        roomPositionAt = now;
        roomPaused = state.paused();
        boolean buffering = !setBy.isEmpty() && remoteBuffering.contains(setBy);
        boolean resume = !buffering && !setBy.isEmpty() && setBy.equals(bufferingClearedBy) && now - bufferingClearedAt < 3000;
        // Buffering transitions must not be dropped by the local/remote anti-flap windows.
        if (now - joinedAt < JOIN_GRACE) return;
        if (now < localUntil && !buffering && !resume) return;
        long position = Math.round(state.position() * 1000);
        if (state.paused() && target.isPlayWhenReady()) {
            remotePaused = true;
            remoteUntil = now + REMOTE_WINDOW;
            target.pause();
            localPlaying = false;
            if (buffering) bufferingPaused = true;
            else {
                bufferingPaused = false;
                if (position > 0) toast(ResUtil.getString(R.string.sync_play_paused, setBy));
            }
        } else if (!state.paused() && !target.isPlayWhenReady()) {
            remotePaused = false;
            remoteUntil = now + REMOTE_WINDOW;
            target.play();
            localPlaying = true;
            if (bufferingPaused && remoteBuffering.isEmpty()) {
                bufferingPaused = false;
                toast(ResUtil.getString(R.string.sync_play_buffering_done, setBy));
            } else if (!buffering && position > 0) {
                toast(ResUtil.getString(R.string.sync_play_played, setBy));
            }
        }
        // The buffering period is over; a manual pause from now on belongs to the user.
        if (resume) bufferingPaused = false;
        long duration = target.getDuration();
        if (duration <= 0) return;
        long timestamp = SystemClock.elapsedRealtime();
        long actual = target.getPosition();
        long drift = actual - position;
        // Small drifts are covered by a playback rate nudge, only large ones need a seek.
        updateTempo(target, state, drift, timestamp);
        long limit = state.doSeek() ? SEEK_DEAD_ZONE : seekTolerance(drift > 0 ? SEEK_DRIFT : SEEK_FASTFORWARD, state.rtt());
        if (Math.abs(drift) <= limit) {
            // In sync: drop the backoff of an older, long settled catch-up seek.
            if (timestamp - lastSeekAt > SEEK_CALM) seekCount = 0;
            return;
        }
        // Rewinding a player that is still filling its buffer only restarts the buffering.
        if (!state.doSeek() && target.isBuffering()) return;
        // A catch-up seek has to settle before the next one: every extra seek throws away the
        // buffer of the position we just moved to, so the player never catches up on a slow link.
        if (!state.doSeek() && timestamp - lastSeekAt < seekCooldown()) return;
        if (!state.doSeek()) seekCount++;
        lastSeekAt = timestamp;
        resetTempo();
        seekGuardTarget = Math.min(Math.max(position, 0), duration);
        seekGuardUntil = timestamp + SEEK_GUARD;
        seekBufferingUntil = timestamp + SEEK_BUFFER_GRACE;
        remotePaused = state.paused();
        remoteUntil = timestamp + REMOTE_WINDOW;
        log("seek to %d (drift %d, retry %d)", seekGuardTarget, drift, seekCount);
        target.seekTo(seekGuardTarget);
    }

    private void addRemoteBuffering(String name) {
        if (name == null || name.isEmpty()) return;
        if (!remoteBuffering.add(name)) {
            // Repeated buffering states are heartbeats; keep the timeout alive.
            scheduleBufferingTimeout(name);
            return;
        }
        log("remote buffering: %s", name);
        // Stop right away instead of waiting for the paused state, so nobody runs ahead of the
        // member that fills its buffer.
        pauseForBuffering();
        dispatchBuffering();
        toast(ResUtil.getString(R.string.sync_play_buffering, name));
        scheduleBufferingTimeout(name);
    }

    /** Pauses the local player because another member buffers, without reporting a user pause. */
    private void pauseForBuffering() {
        Player target = player;
        if (target == null || !target.hasMedia() || !target.isPlayWhenReady()) return;
        long timestamp = SystemClock.elapsedRealtime();
        resetTempo();
        bufferingPaused = true;
        remotePaused = true;
        remoteUntil = timestamp + REMOTE_WINDOW;
        localPlaying = false;
        log("pause for remote buffering");
        target.pause();
    }

    private void scheduleBufferingTimeout(String name) {
        Runnable timeout = () -> {
            if (!remoteBuffering.remove(name)) return;
            bufferingTimeouts.remove(name);
            log("remote buffering timeout: %s", name);
            dispatchBuffering();
            toast(ResUtil.getString(R.string.sync_play_buffering_timeout, name));
            resumeIfBufferingPaused();
        };
        Runnable old = bufferingTimeouts.put(name, timeout);
        if (old != null) App.removeCallbacks(old);
        App.post(timeout, BUFFERING_TIMEOUT);
    }

    /** @return true when this state ends a buffering period. */
    private boolean clearRemoteBuffering(String name) {
        if (name == null || name.isEmpty()) return false;
        Runnable timeout = bufferingTimeouts.remove(name);
        if (timeout != null) App.removeCallbacks(timeout);
        if (!remoteBuffering.remove(name)) return false;
        bufferingClearedBy = name;
        bufferingClearedAt = SystemClock.elapsedRealtime();
        log("remote buffering done: %s", name);
        dispatchBuffering();
        return true;
    }

    private void onUserLeft(String name) {
        if (clearRemoteBuffering(name)) resumeIfBufferingPaused();
    }

    private void resumeIfBufferingPaused() {
        if (!remoteBuffering.isEmpty() || !bufferingPaused) return;
        bufferingPaused = false;
        resetTempo();
        SyncPlayClient current = client;
        Player target = player;
        if (current == null || target == null || !target.hasMedia()) return;
        if (target.isPlayWhenReady()) return;
        sendState(false, true, target.getPosition(), false);
        localUntil = SystemClock.elapsedRealtime() + REMOTE_WINDOW;
        localPlaying = true;
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

    private void dispatchBuffering() {
        for (Listener listener : listeners) listener.onBufferingChanged();
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
                SyncPlayManager.this.joinedAt = joinedAt;
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
                    onUserLeft(name);
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
        public void onStateReply() {
            if (!isCurrent()) return;
            App.post(() -> {
                if (!isCurrent()) return;
                replyState();
            });
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
                if (!item.hasEpisode()) return;
                if (item.isBuffering()) addRemoteBuffering(name);
                else if (item.isBufferingDone()) {
                    if (clearRemoteBuffering(name)) resumeIfBufferingPaused();
                } else if (clearRemoteBuffering(name)) {
                    // Buffering ended without an explicit resume: the user paused, stay paused.
                    bufferingPaused = false;
                }
                SyncPlayItem local = getLocalItem();
                if (item.matches(local) || sameEpisode(local, item)) return;
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
