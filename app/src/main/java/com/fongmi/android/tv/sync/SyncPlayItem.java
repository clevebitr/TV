package com.fongmi.android.tv.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Playable item exchanged through the Syncplay {@code Set.file} message.
 * <p>
 * The wire format is {@code episode \u0001 siteKey \u0001 vodId \u0001 index \u0001 vodName}: the first
 * field stays readable for other Syncplay clients, while the extra fields let our clients find the
 * same episode locally (same drama, possibly another crawler site) or switch to the remote source.
 */
public final class SyncPlayItem {

    private static final char SEPARATOR = '\u0001';
    private static final int MAX_LENGTH = 240;

    private final String episode;
    private final String site;
    private final String vodId;
    private final String vodName;
    private final int index;

    public SyncPlayItem(@Nullable String episode, @Nullable String site, @Nullable String vodId) {
        this(episode, site, vodId, -1);
    }

    public SyncPlayItem(@Nullable String episode, @Nullable String site, @Nullable String vodId, int index) {
        this(episode, site, vodId, index, "");
    }

    public SyncPlayItem(@Nullable String episode, @Nullable String site, @Nullable String vodId, int index, @Nullable String vodName) {
        this.episode = normalize(episode);
        this.site = normalize(site);
        this.vodId = normalize(vodId);
        this.index = index;
        this.vodName = normalize(vodName);
    }

    public static SyncPlayItem local(@Nullable String episode) {
        return new SyncPlayItem(episode, "", "");
    }

    public static SyncPlayItem decode(@Nullable String value) {
        if (value == null) return new SyncPlayItem("", "", "");
        String[] parts = value.split(String.valueOf(SEPARATOR), -1);
        if (parts.length < 3) return new SyncPlayItem(value, "", "");
        int index = parts.length > 3 ? parseInt(parts[3]) : -1;
        String vodName = parts.length > 4 ? parts[4] : "";
        return new SyncPlayItem(parts[0], parts[1], parts[2], index, vodName);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Readable name for user list rows and notifications. */
    public static String displayName(@Nullable String value) {
        return decode(value).getEpisode();
    }

    private static String normalize(@Nullable String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == SEPARATOR || Character.isISOControl(c)) continue;
            builder.append(c);
        }
        return builder.toString().trim();
    }

    public String getEpisode() {
        return episode;
    }

    public String getSite() {
        return site;
    }

    public String getVodId() {
        return vodId;
    }

    public int getIndex() {
        return index;
    }

    public String getVodName() {
        return vodName;
    }

    public boolean hasVodName() {
        return !vodName.isEmpty();
    }

    public boolean hasEpisode() {
        return !episode.isEmpty();
    }

    public boolean hasSource() {
        return !site.isEmpty() && !vodId.isEmpty();
    }

    public boolean matches(@Nullable SyncPlayItem other) {
        if (other == null) return false;
        return episode.equals(other.episode) && site.equals(other.site) && vodId.equals(other.vodId);
    }

    @NonNull
    @Override
    public String toString() {
        return episode;
    }

    public String encode() {
        if (!hasSource()) return episode;
        String source = episode + SEPARATOR + site + SEPARATOR + vodId + SEPARATOR + index;
        String full = source + SEPARATOR + vodName;
        if (full.length() <= MAX_LENGTH) return full;
        return source.length() <= MAX_LENGTH ? source : episode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SyncPlayItem other)) return false;
        return matches(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(episode, site, vodId);
    }
}
