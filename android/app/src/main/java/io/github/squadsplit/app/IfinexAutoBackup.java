package io.github.squadsplit.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/**
 * iFiNeX v7.0 - STANDALONE daily backup to the user's own Google Drive (hidden appDataFolder), around 12:00 AM, even when the app is closed.
 *
 * How it works (no Firebase, no server):
 *   1. The web layer (ifinex-gdrive.js) writes the whole on-device data as ONE JSON file: <filesDir>/ifx_snapshot.json
 *      (every time the app is left, every 5 minutes while it is open, and right after any manual backup).
 *   2. The web layer also hands us the Google refresh token (configure()). It is stored in this app's private SharedPreferences only.
 *   3. A one-shot JobScheduler job is set for the next local midnight (+ up to 3 h window: Android decides the exact minute, it needs network).
 *      The job (IfinexBackupJob) uploads the snapshot to Drive, keeps the newest 14 backups, and schedules the next midnight.
 *   4. Same data as the last upload (same checksum) = nothing is uploaded.
 *
 * Honest limits: Android may run the job a little after midnight (battery saver, no network, phone off) - it then runs as soon as it can.
 * A force-stop by the user or a "kill all apps" cleaner cancels jobs until the app is opened once.
 */
final class IfinexAutoBackup {
    private IfinexAutoBackup() {}

    static final String TAG = "iFiNeX-backup";
    static final String PREFS = "ifinex_autobackup";
    static final int JOB_ID = 7301;
    static final String SNAPSHOT = "ifx_snapshot.json";
    static final String PREFIX = "iFiNeX-standalone-";
    static final int KEEP = 14;
    static final long MAX_UPLOAD = (long) (4.8 * 1048576);
    private static final Object LOCK = new Object();

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("enabled", false) && p.getString("refresh", "").length() > 0;
    }

    /** milliseconds from now to the next local 00:00 (never less than one minute) */
    static long msToNextMidnight() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long d = cal.getTimeInMillis() - System.currentTimeMillis();
        return Math.max(60000L, d);
    }

    static void cancel(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getApplicationContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable t) {
            Log.w(TAG, "cancel: " + t);
        }
    }

    /** One job, always the same id: scheduling again simply replaces it. latencyMs = earliest start, window = how long Android may wait after that. */
    static void scheduleIn(Context c, long latencyMs, long windowMs) {
        if (Build.VERSION.SDK_INT < 21) return;
        Context app = c.getApplicationContext();
        if (!isEnabled(app)) {
            cancel(app);
            return;
        }
        try {
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            ComponentName cn = new ComponentName(app, IfinexBackupJob.class);
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, cn);
            b.setMinimumLatency(Math.max(1000L, latencyMs));
            b.setOverrideDeadline(Math.max(1000L, latencyMs) + windowMs);
            b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            try {
                b.setPersisted(true);
            } catch (Throwable t) {
                Log.w(TAG, "persist not allowed: " + t);
            }
            int r = js.schedule(b.build());
            if (r != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "schedule refused: " + r);
        } catch (Throwable t) {
            Log.w(TAG, "schedule: " + t);
        }
    }

    /** next local midnight, Android may run it up to 3 hours later */
    static void schedule(Context c) {
        scheduleIn(c, msToNextMidnight(), 3L * 3600000L);
    }

    // ------------------------------------------------------------------ one backup
    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    private static byte[] readFile(File f) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream is = new FileInputStream(f);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            is.close();
        }
        return bo.toByteArray();
    }

    private static String form(String[][] kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i++) {
            if (i > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i][0], "UTF-8")).append('=').append(URLEncoder.encode(kv[i][1], "UTF-8"));
        }
        return sb.toString();
    }

    /** returns {httpCode, body} */
    private static Object[] http(String method, String url, String bearer, String contentType, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod(method);
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                conn.setRequestProperty("Content-Type", contentType);
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            return new Object[]{Integer.valueOf(code), text};
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] gzip(byte[] in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bo);
        gz.write(in);
        gz.close();
        return bo.toByteArray();
    }

    private static String accessToken(SharedPreferences p) throws Exception {
        String body = form(new String[][]{
                {"client_id", p.getString("client_id", "")},
                {"client_secret", p.getString("client_secret", "")},
                {"refresh_token", p.getString("refresh", "")},
                {"grant_type", "refresh_token"}});
        Object[] r = http("POST", "https://oauth2.googleapis.com/token", null, "application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
        int code = ((Integer) r[0]).intValue();
        String text = (String) r[1];
        JSONObject j = new JSONObject(text.length() > 0 ? text : "{}");
        if (code == 200 && j.optString("access_token", "").length() > 0) return j.getString("access_token");
        if ("invalid_grant".equals(j.optString("error", ""))) throw new IllegalStateException("revoked");
        throw new Exception("token HTTP " + code);
    }

    private static void prune(String tk) {
        try {
            String url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&orderBy=createdTime%20desc&pageSize=60&fields="
                    + URLEncoder.encode("files(id,name)", "UTF-8");
            Object[] r = http("GET", url, tk, null, null);
            if (((Integer) r[0]).intValue() != 200) return;
            JSONArray fs = new JSONObject((String) r[1]).optJSONArray("files");
            if (fs == null) return;
            int seen = 0;
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.getJSONObject(i);
                if (!f.optString("name", "").startsWith(PREFIX)) continue;
                seen++;
                if (seen > KEEP) http("DELETE", "https://www.googleapis.com/drive/v3/files/" + URLEncoder.encode(f.getString("id"), "UTF-8"), tk, null, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "prune: " + t);
        }
    }

    /**
     * Upload the snapshot if it changed. Returns true = done (or nothing to do), false = try again later (network / Google problem).
     */
    static boolean runOnce(Context ctx) {
        synchronized (LOCK) {
            Context c = ctx.getApplicationContext();
            SharedPreferences p = prefs(c);
            if (!isEnabled(c)) return true;
            try {
                File snap = new File(c.getFilesDir(), SNAPSHOT);
                if (!snap.exists() || snap.length() < 50L) {
                    p.edit().putString("last_err", "no snapshot yet - open the app once").commit();
                    return true;
                }
                byte[] data = readFile(snap);
                CRC32 crc = new CRC32();
                crc.update(data, 0, data.length);
                String hash = Long.toHexString(crc.getValue()) + "-" + data.length;
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                if (hash.equals(p.getString("last_hash", ""))) {
                    p.edit().putLong("last_ok", System.currentTimeMillis()).putString("last_day", today).putString("last_err", "").putInt("fails", 0).commit();
                    return true;   // nothing changed since the last upload
                }
                String tk = accessToken(p);
                String mime = "application/json";
                String name = PREFIX + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".json";
                byte[] bytes = data;
                if (bytes.length > MAX_UPLOAD) {
                    bytes = gzip(data);
                    mime = "application/gzip";
                    name = name + ".gz";
                }
                if (bytes.length > MAX_UPLOAD) {
                    p.edit().putString("last_err", "backup too large for the app (use Create backup file)").commit();
                    return true;
                }
                String b = "ifxb" + Long.toHexString(System.nanoTime());
                JSONObject meta = new JSONObject();
                meta.put("name", name);
                meta.put("parents", new JSONArray().put("appDataFolder"));
                byte[] head = ("--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta.toString()
                        + "\r\n--" + b + "\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] tail = ("\r\n--" + b + "--").getBytes(StandardCharsets.UTF_8);
                byte[] all = new byte[head.length + bytes.length + tail.length];
                System.arraycopy(head, 0, all, 0, head.length);
                System.arraycopy(bytes, 0, all, head.length, bytes.length);
                System.arraycopy(tail, 0, all, head.length + bytes.length, tail.length);
                Object[] r = http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size",
                        tk, "multipart/related; boundary=" + b, all);
                int code = ((Integer) r[0]).intValue();
                if (code < 200 || code >= 300) throw new Exception("Drive HTTP " + code);
                p.edit().putString("last_hash", hash).putLong("last_ok", System.currentTimeMillis()).putString("last_day", today)
                        .putString("last_err", "").putInt("fails", 0).commit();
                prune(tk);
                return true;
            } catch (IllegalStateException revoked) {
                // Google access was removed by the user: stop quietly, the app asks to connect again when it is opened
                p.edit().putBoolean("enabled", false).remove("refresh").putString("last_err", "Google access was removed - connect again").commit();
                cancel(c);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "backup failed: " + t);
                String m = String.valueOf(t.getMessage());
                p.edit().putString("last_err", m.length() > 160 ? m.substring(0, 160) : m).putInt("fails", p.getInt("fails", 0) + 1).commit();
                return false;
            }
        }
    }
}
