package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.sync.SyncPlayEndpoint;
import com.github.catvod.utils.Prefers;

import java.util.Random;

public class SyncPlaySetting {

    private static final String KEY_ENDPOINT = "sync_play_endpoint";
    private static final String KEY_NICKNAME = "sync_play_nickname";
    private static final String KEY_TLS = "sync_play_tls";

    public static String getEndpoint() {
        String value = Prefers.getString(KEY_ENDPOINT, SyncPlayEndpoint.DEFAULT);
        return SyncPlayEndpoint.parse(value) == null ? SyncPlayEndpoint.DEFAULT : value;
    }

    public static void putEndpoint(String endpoint) {
        Prefers.put(KEY_ENDPOINT, endpoint);
    }

    public static String getNickname() {
        String value = Prefers.getString(KEY_NICKNAME);
        if (TextUtils.isEmpty(value)) value = randomNickname();
        return value;
    }

    public static void putNickname(String nickname) {
        Prefers.put(KEY_NICKNAME, nickname);
    }

    public static boolean isTls() {
        return Prefers.getBoolean(KEY_TLS, SyncPlayEndpoint.isOfficial(getEndpoint()));
    }

    public static void putTls(boolean tls) {
        Prefers.put(KEY_TLS, tls);
    }

    private static String randomNickname() {
        return "TV" + (1000 + new Random().nextInt(9000));
    }
}
