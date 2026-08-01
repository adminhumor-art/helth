package com.no.sisense.enanddecryption;

public final class CGMDataHandle130 {
    private CGMDataHandle130() {}

    public static native int v120RegisterKey(byte[] input, int length, byte[] output);

    public static native int V120ApplyAuthentication(
            int command,
            boolean encrypted,
            int value,
            byte[] input,
            byte[] output,
            int outputLength);

    public static native int V120Activation(
            int command,
            boolean encrypted,
            byte[] input,
            long unixTime,
            int value,
            byte[] output,
            int outputLength);

    public static native int V120IsecUpdate(
            int command,
            boolean encrypted,
            byte[] input,
            long unixTime,
            byte[] output,
            int outputLength);

    public static native int V120RawData(
            int command,
            boolean encrypted,
            byte[] input,
            long value,
            int index,
            byte[] output,
            int outputLength);

    public static native int V120Reset(
            int command,
            boolean encrypted,
            byte[] input,
            int value,
            byte[] output,
            int outputLength);

    public static native int V120SpiltData(
            int command,
            byte[] packet,
            int[] parsedMeta,
            byte[] parsedPayload,
            boolean encrypted,
            byte[] workspace,
            int workspaceLength);
}
