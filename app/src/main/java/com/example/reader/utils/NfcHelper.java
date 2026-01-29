package com.example.reader.utils;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.provider.Settings;

public class NfcHelper {

    private final Activity activity;
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;

    public NfcHelper(Activity activity) {
        this.activity = activity;
        init();
    }

    private void init() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(activity);

        if (nfcAdapter != null) {
            Intent intent = new Intent(activity, activity.getClass());
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE
                    : 0;

            pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags);
        }
    }

    public boolean isNfcAvailable() {
        return nfcAdapter != null;
    }

    public boolean isNfcEnabled() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    public void enableForegroundDispatch() {
        if (nfcAdapter == null) return;

        try {
            String[][] techList = new String[][]{{IsoDep.class.getName()}};
            nfcAdapter.enableForegroundDispatch(activity, pendingIntent, null, techList);
        } catch (Exception ignored) {}
    }

    public void disableForegroundDispatch() {
        if (nfcAdapter == null) return;

        try {
            nfcAdapter.disableForegroundDispatch(activity);
        } catch (Exception ignored) {}
    }

    public Tag getTagFromIntent(Intent intent) {
        if (intent == null) return null;

        String action = intent.getAction();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        }
        return null;
    }

    public void openNfcSettings() {
        Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
        activity.startActivity(intent);
    }
}