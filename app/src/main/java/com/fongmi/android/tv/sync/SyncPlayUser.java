package com.fongmi.android.tv.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class SyncPlayUser {

    private final String room;
    private final String name;
    private final String file;
    private final boolean ready;

    public SyncPlayUser(String room, String name, @Nullable String file, boolean ready) {
        this.room = room == null ? "" : room;
        this.name = name == null ? "" : name;
        this.file = file == null ? "" : file;
        this.ready = ready;
    }

    public String getRoom() {
        return room;
    }

    public String getName() {
        return name;
    }

    public String getFile() {
        return file;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isBlank() {
        return name.trim().isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SyncPlayUser other)) return false;
        return ready == other.ready && room.equals(other.room) && name.equals(other.name) && file.equals(other.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(room, name, file, ready);
    }
}
