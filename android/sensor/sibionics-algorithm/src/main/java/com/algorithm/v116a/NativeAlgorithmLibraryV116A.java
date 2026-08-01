package com.algorithm.v116a;

public final class NativeAlgorithmLibraryV116A {
    private NativeAlgorithmLibraryV116A() {}

    public static native AlgorithmContext getAlgorithmContextFromNative();

    public static native int initAlgorithmContext(
            AlgorithmContext context,
            int mode,
            String sensitivityToken);

    public static native int initAlgorithmContextFaction(
            AlgorithmContext context,
            int mode,
            String sensitivityToken);

    public static native double processAlgorithmContext(
            AlgorithmContext context,
            int index,
            double signal,
            double temperature,
            double zero,
            double low,
            double high);

    public static native int releaseAlgorithmContext(AlgorithmContext context);

    public static native byte[] getBinaryStructAlgorithmContext(AlgorithmContext context);

    public static native int setBinaryStructAlgorithmContext(
            AlgorithmContext context,
            byte[] state);

    public static native String getAlgorithmVersion();

    public static native String getMyAlgorithmLibraryVersion();

    public static native String getSensitivityVersion();

    public static native String encryptSensitivity(String sensitivity);

    public static native float decryptSensitivity(String encryptedSensitivity);

    public static native String encryptSensitivityFaction(String sensitivity);

    public static native float decryptSensitivityFaction(String encryptedSensitivity);
}
