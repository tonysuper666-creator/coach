package com.coach.android;

import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import static org.junit.Assert.*;
import android.security.NetworkSecurityPolicy;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;

import java.util.concurrent.*;
import okhttp3.*;

@RunWith(AndroidJUnit4.class)
public class NetworkSecuritySmoke {
    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build();
    @Test public void testSystemSqliteVersion() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null);
             Cursor result = db.rawQuery("select sqlite_version()", null)) {
            assertTrue(result.moveToFirst());
            android.util.Log.i("CoachSmoke", "SYSTEM SQLite=" + result.getString(0));
        }
    }
    private Request.Builder authorized(String url) {
        String token = InstrumentationRegistry.getArguments().getString("coachToken", "");
        assertFalse("Pass the isolated test token with -e coachToken", token.isEmpty());
        return new Request.Builder().url(url).header("Authorization", "Bearer " + token).header("X-Coach-Device", "desktop");
    }
    @Test public void testAllowlistedHttpAndWs() throws Exception {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(BuildConfig.COACH_TAIL_HOST));
        Request request = authorized("http://" + BuildConfig.COACH_TAIL_HOST + ":18791/v1/hello").build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("\"epoch\""));
        }
        CountDownLatch done = new CountDownLatch(1);
        String[] message = {null};
        WebSocket ws = client.newWebSocket(authorized("ws://" + BuildConfig.COACH_TAIL_HOST + ":18791/v1/ws").build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) { message[0] = text; done.countDown(); }
            @Override public void onFailure(WebSocket socket, Throwable t, Response response) { message[0] = t.toString(); done.countDown(); }
        });
        assertTrue("WS timed out", done.await(10, TimeUnit.SECONDS));
        ws.cancel();
        assertNotNull(message[0]);
        assertTrue(message[0], message[0].contains("\"epoch\""));
        android.util.Log.i("CoachSmoke", "ALLOW HTTP=200 WS=authenticated epoch");
    }
    @Test public void testOtherCleartextBlocked() throws Exception {
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("10.0.2.2"));
        try {
            client.newCall(new Request.Builder().url("http://10.0.2.2:18791/v1/hello").build()).execute().close();
            fail("non-allowlisted HTTP was accepted");
        } catch (java.io.IOException expected) {
            assertTrue(expected.toString(), expected.getMessage().contains("CLEARTEXT"));
        }
        CountDownLatch done = new CountDownLatch(1);
        String[] error = {null};
        WebSocket ws = client.newWebSocket(new Request.Builder().url("ws://10.0.2.2:18791/v1/hello").build(), new WebSocketListener() {
            @Override public void onFailure(WebSocket socket, Throwable t, Response response) { error[0] = t.toString(); done.countDown(); }
            @Override public void onOpen(WebSocket socket, Response response) { error[0] = "unexpected open"; done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        ws.cancel();
        assertTrue(error[0], error[0].contains("CLEARTEXT"));
        android.util.Log.i("CoachSmoke", "DENY HTTP+WS 10.0.2.2 CLEARTEXT");
    }
}
