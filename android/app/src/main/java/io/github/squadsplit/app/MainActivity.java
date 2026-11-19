package io.github.squadsplit.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(IfinexNotifierPlugin.class); // must run BEFORE super.onCreate
        registerPlugin(IfinexUpdatePlugin.class);
        registerPlugin(IfinexBackupPlugin.class);
        super.onCreate(savedInstanceState);
        captureOAuth(getIntent());
        IfinexNotify.ensureChannels(this);
        // v7.0: re-arm the nightly Google Drive backup (Android drops jobs after a force-stop). Harmless if it is already armed.
        if (IfinexAutoBackup.isEnabled(this)) IfinexAutoBackup.schedule(this);
        // Re-arm in case Android dropped our alarm (force-stop / OEM cleaner). Harmless if it is already armed.
        if (IfinexNotify.isEnabled(this)) IfinexNotify.schedule(this, 30000L);
    }

    @Override
    public void onStart() {
        super.onStart();
        IfinexNotify.setAppVisible(true);
    }

    @Override
    public void onStop() {
        IfinexNotify.setAppVisible(false);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        captureOAuth(intent);
    }

    /** v14.2: Google sign-in comes back as <package>://oauth?code=...&state=... (see gdrive-return.html). Park it for the web layer. */
    private void captureOAuth(Intent intent) {
        try {
            Uri u = intent == null ? null : intent.getData();
            if (u != null && getPackageName().equals(u.getScheme()) && "oauth".equals(u.getHost())) IfinexBackupPlugin.pendingOAuth = u.toString();
        } catch (Throwable t) { /* never block start-up */ }
    }
}
