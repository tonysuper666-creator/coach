package com.coach.android;

/** Uses the guard class compiled by assembleDebugAndroidTest, not a reimplementation. */
public final class ReaderMeasurementPackageCheck {
    public static void main(String[] args) {
        int checks = 0;
        for (String allowed : new String[]{"com.coach.readermeasurement", "com.coach.readermeasurement.ton1463"}) {
            ReaderMeasurementPackage.requireIsolated(allowed);
            System.out.println("ALLOW " + allowed + " guard_returned=true");
            checks++;
        }
        for (String denied : new String[]{"com.coach.android", "com.coach.readermeasurement.ton14630",
                "com.coach.readermeasurement.ton1463.extra", "com.coach.readermeasurement.test",
                "com.coach.readermeasurement.ton1463.test", "COM.coach.readermeasurement.ton1463", "", null}) {
            boolean rejected = false;
            try { ReaderMeasurementPackage.requireIsolated(denied); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Non-allowlisted package accepted: " + denied);
            System.out.println("DENY " + denied);
            checks++;
        }
        System.out.println("CHECKS " + checks);
    }
}
