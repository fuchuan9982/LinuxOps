package com.linuxops.security;

import java.util.Arrays;

/** Per-task credential copy. Always close it after the task completes. */
public final class CredentialSnapshot implements AutoCloseable {
    private char[] password;

    public CredentialSnapshot(char[] source) {
        this.password = source == null ? new char[0] : source.clone();
    }

    /** Returns a defensive copy; caller must clear the returned array. */
    public synchronized char[] copyPassword() {
        ensureOpen();
        return password.clone();
    }

    public synchronized boolean hasPassword() {
        ensureOpen();
        return password.length > 0;
    }

    private void ensureOpen() {
        if (password == null) throw new IllegalStateException("credential snapshot is closed");
    }

    @Override public synchronized void close() {
        if (password != null) {
            Arrays.fill(password, '\0');
            password = null;
        }
    }
}
