package com.algorithm.v1_11_15;

public final class NativeAlgorithmLibraryv1_11_15 {
    private NativeAlgorithmLibraryv1_11_15() {}

    public static native String encryptSensitivity(String sensitivity);

    public static native float decryptSensitivity(String encryptedSensitivity);

    public static native String encryptSensitivityFaction(String sensitivity);

    public static native float decryptSensitivityFaction(String encryptedSensitivity);
}
