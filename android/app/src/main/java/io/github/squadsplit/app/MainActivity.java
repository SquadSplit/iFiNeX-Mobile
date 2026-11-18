package io.github.squadsplit.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(IfinexNotifierPlugin.class); // must run BEFORE super.onCreate
        registerPlugin(IfinexUpdatePlugin.class);
        registerPlugin(IfinexBackupPlugin.class);
        super.onCreate(savedInstanceState);
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
}
