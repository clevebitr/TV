package com.fongmi.android.tv.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;

public final class SyncPlayEndpoint {

    public static final String DEFAULT = "syncplay.pl:8996";
    public static final List<String> OFFICIAL = List.of("syncplay.pl:8995", "syncplay.pl:8996", "syncplay.pl:8997", "syncplay.pl:8998", "syncplay.pl:8999");

    private final String host;
    private final int port;

    private SyncPlayEndpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isOfficial() {
        return OFFICIAL.contains(toString().toLowerCase(Locale.ROOT));
    }

    @Nullable
    public static SyncPlayEndpoint parse(@Nullable String value) {
        if (value == null) return null;
        String input = value.trim();
        if (input.isEmpty()) return null;
        if (input.startsWith("[")) return parseIpv6(input);
        return parseHost(input);
    }

    private static SyncPlayEndpoint parseIpv6(String input) {
        int close = input.indexOf(']');
        if (close < 0) return null;
        String host = input.substring(1, close).trim();
        String rest = input.substring(close + 1);
        if (!rest.startsWith(":")) return null;
        return build(host, rest.substring(1));
    }

    private static SyncPlayEndpoint parseHost(String input) {
        int index = input.lastIndexOf(':');
        if (index < 0) return null;
        return build(input.substring(0, index), input.substring(index + 1));
    }

    private static SyncPlayEndpoint build(String host, String port) {
        host = host.trim();
        port = port.trim();
        if (host.isEmpty() || port.isEmpty()) return null;
        int value;
        try {
            value = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value <= 0 || value > 65535) return null;
        return new SyncPlayEndpoint(host, value);
    }

    public static boolean isOfficial(@Nullable String endpoint) {
        SyncPlayEndpoint value = parse(endpoint);
        return value != null && value.isOfficial();
    }

    @NonNull
    @Override
    public String toString() {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
