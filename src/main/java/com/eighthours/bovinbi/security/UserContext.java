package com.eighthours.bovinbi.security;

public class UserContext {
    private static final ThreadLocal<Long> UID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    public static void set(Long uid, String username) {
        UID.set(uid);
        USERNAME.set(username);
    }

    public static Long uid() {
        return UID.get();
    }

    public static String username() {
        return USERNAME.get();
    }

    public static void clear() {
        UID.remove();
        USERNAME.remove();
    }
}
