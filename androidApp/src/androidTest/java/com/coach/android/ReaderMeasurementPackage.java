package com.coach.android;

/** Test-only package gate, also executable from the Android build's class file on a host JVM. */
public final class ReaderMeasurementPackage {
    private ReaderMeasurementPackage() {}

    public static void requireIsolated(String packageName) {
        if (!"com.coach.readermeasurement".equals(packageName)
                && !"com.coach.readermeasurement.ton1463".equals(packageName)) {
            throw new IllegalArgumentException("Use the isolated measurement applicationId");
        }
    }
}
