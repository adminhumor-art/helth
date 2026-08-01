package com.algorithm.v1_11_15_1g;

import com.algorithm.v1_1_5_g.AlgorithmContext;

public final class NativeAlgorithmLibraryV1_11_15G {
    private NativeAlgorithmLibraryV1_11_15G() {}

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

    public static native String getJsonAlgorithmContext(AlgorithmContext context);

    public static native int setJsonAlgorithmContext(
            AlgorithmContext context,
            String state);

    public static native String getAlgorithmVersion();

    public static native String getMyAlgorithmLibraryVersion();

    public static native String getSensitivityVersion();
}
