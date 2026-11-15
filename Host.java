package com.linuxops.model;

import java.util.Objects;

/** Stable server identity: endpoint + login account. */
public record Host(String group, String name, String host, int port, String user) {
    public Host {
        group = clean(group, "default");
        name = clean(name, host);
        host = clean(host, null);
        user = clean(user, null);
        if (host == null || user == null) throw new IllegalArgumentException("host and user are required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1..65535");
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }

    public String id() { return host.toLowerCase() + ":" + port + "@" + user; }

    @Override public boolean equals(Object other) {
        return other instanceof Host h && id().equalsIgnoreCase(h.id());
    }

    @Override public int hashCode() { return Objects.hash(id().toLowerCase()); }
}
