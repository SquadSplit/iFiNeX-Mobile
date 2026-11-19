package io.github.squadsplit.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JS bridge: window.Capacitor.Plugins.IfinexBackup  (Android only).
 * configure({enabled, clientId, clientSecret, refreshToken, email}) - turn the midnight backup on/off and hand over the Google refresh token.
 * status() - when did the last native backup run, any error.  runNow() - upload the current snapshot right now (used by "Back up now").
 */
@CapacitorPlugin(name = "IfinexBackup")
public class IfinexBackupPlugin extends Plugin {

    private JSObject statusObject() {
        SharedPreferences p = IfinexAutoBackup.prefs(getContext());
        JSObject o = new JSObject();
        o.put("available", true);
        o.put("enabled", IfinexAutoBackup.isEnabled(getContext()));
        o.put("email", p.getString("email", ""));
        o.put("lastOkAt", p.getLong("last_ok", 0L));
        o.put("lastDay", p.getString("last_day", ""));
        o.put("lastError", p.getString("last_err", ""));
        return o;
    }

    /** v14.2: the Google sign-in page sends the browser to <package>://oauth?code=...; MainActivity parks that link here, the web layer picks it up. */
    static volatile String pendingOAuth = null;

    @PluginMethod
    public void takeOAuth(PluginCall call) {
        JSObject o = new JSObject();
        String u = pendingOAuth; pendingOAuth = null;
        o.put("url", u == null ? "" : u);
        call.resolve(o);
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(statusObject());
    }

    @PluginMethod
    public void configure(PluginCall call) {
        Context c = getContext();
        Boolean en = call.getBoolean("enabled", Boolean.valueOf(true));
        boolean enabled = en == null || en.booleanValue();
        SharedPreferences.Editor ed = IfinexAutoBackup.prefs(c).edit();
        if (!enabled) {
            ed.putBoolean("enabled", false).remove("refresh").remove("client_secret").commit();
            IfinexAutoBackup.cancel(c);
            call.resolve(statusObject());
            return;
        }
        String cid = call.getString("clientId");
        String sec = call.getString("clientSecret");
        String rt = call.getString("refreshToken");
        String email = call.getString("email");
        if (cid == null || cid.isEmpty() || sec == null || sec.isEmpty() || rt == null || rt.isEmpty()) {
            call.reject("clientId, clientSecret and refreshToken are required");
            return;
        }
        ed.putBoolean("enabled", true).putString("client_id", cid).putString("client_secret", sec).putString("refresh", rt)
                .putString("email", email == null ? "" : email).putInt("fails", 0).commit();
        IfinexAutoBackup.schedule(c);
        call.resolve(statusObject());
    }

    /** writeSnapshot({data}) - the web layer hands over the complete on-device backup JSON; saved atomically as <filesDir>/ifx_snapshot.json for the midnight job. */
    @PluginMethod
    public void writeSnapshot(PluginCall call) {
        String data = call.getString("data");
        if (data == null || data.length() < 20) {
            call.reject("data is required");
            return;
        }
        try {
            File dir = getContext().getFilesDir();
            File tmp = new File(dir, IfinexAutoBackup.SNAPSHOT + ".tmp");
            File dst = new File(dir, IfinexAutoBackup.SNAPSHOT);
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(data.getBytes(StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
            if (dst.exists() && !dst.delete()) {
                call.reject("could not replace the old snapshot");
                return;
            }
            if (!tmp.renameTo(dst)) {
                call.reject("could not save the snapshot");
                return;
            }
            JSObject o = new JSObject();
            o.put("ok", true);
            o.put("bytes", (long) dst.length());
            call.resolve(o);
        } catch (Throwable t) {
            call.reject("snapshot failed: " + t.getMessage());
        }
    }

    @PluginMethod
    public void runNow(final PluginCall call) {
        final Context c = getContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = IfinexAutoBackup.runOnce(c);
                JSObject o = statusObject();
                o.put("ok", ok);
                call.resolve(o);
            }
        }, "ifinex-backup-now").start();
    }
}
