package com.eighthours.bovinbi.security;

/**
 * 请求级用户身份(ThreadLocal,AuthInterceptor 进入时写入、请求结束清理):
 * uid/username/role 三要素;role 随 JWT 携带,端点级鉴权(如 ADMIN 专用)无需回表。
 */
public class UserContext {
    private static final ThreadLocal<Long> UID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    public static void set(Long uid, String username, String role) {
        UID.set(uid);
        USERNAME.set(username);
        ROLE.set(role);
    }

    public static Long uid() {
        return UID.get();
    }

    public static String username() {
        return USERNAME.get();
    }

    public static String role() {
        return ROLE.get();
    }

    /** ADMIN 判定:数据集管理等全局配置端点用 */
    public static boolean isAdmin() {
        return "ADMIN".equals(ROLE.get());
    }

    public static void clear() {
        UID.remove();
        USERNAME.remove();
        ROLE.remove();
    }
}
