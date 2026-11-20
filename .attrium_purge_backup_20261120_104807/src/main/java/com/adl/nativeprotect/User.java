package com.adl.nativeprotect;

import java.util.HashMap;
import java.util.Map;

public class User {

    private static final User instance = new User();

    public static User getInstance() {
        return instance;
    }

    private final Map<String, String> cache = new HashMap<>();

    private boolean nativeFailed = false;

    private User() {
        try {
            cache.put("username", safe(getUsername()));
            cache.put("hwid", safe(getHwid()));
            cache.put("role", safe(getRole()));
            cache.put("uid", safe(getUid()));
            cache.put("subTime", safe(getSubsTime()));
        } catch (Throwable e) {
            nativeFailed = true;
            cache.put("username", "KODEK");
            cache.put("hwid", "hwid-1231294809786-2348786");
            cache.put("role", "Admin");
            cache.put("uid", "777");
            cache.put("subTime", "2025-24-05");
        }
    }
    
    @Native
    private native String getUsername();
    @Native
    private native String getHwid();
    @Native
    private native String getRole();
    @Native
    private native String getUid();
    @Native
    private native String getSubsTime();

    public String profile(String profile) {
        String value = cache.get(profile);
        return value == null ? "" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
