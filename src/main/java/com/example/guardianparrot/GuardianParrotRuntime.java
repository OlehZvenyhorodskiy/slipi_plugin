package com.example.guardianparrot;

public final class GuardianParrotRuntime {
    private static volatile GuardianParrotModule module;
    private GuardianParrotRuntime() {}

    static void set(GuardianParrotModule m) { module = m; }

    public static GuardianParrotModule get() { return module; }
}
