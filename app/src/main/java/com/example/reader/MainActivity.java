package com.example.reader;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.reader.probe.IsoDepFsProbe;
import com.example.reader.probe.IsoDepProbe;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_CAMERA = 100;
    SmartDetectionFragment smartDetectionFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Customize ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("📘 Passport Reader");
        }

        // Initialize fragments
        smartDetectionFragment = new SmartDetectionFragment();

        // Load default fragment (ePPT)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, smartDetectionFragment)
                    .commit();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Forward the result to the ePPT fragment
        if (smartDetectionFragment != null) {
            smartDetectionFragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
//        Log.d("MainActivity", "New intent received: " + intent);
        // Forward NFC intent to the active fragment
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);

        if (currentFragment instanceof SmartDetectionFragment) {
            Log.d("@@>>", "handle eppt nfc intent");
            ((SmartDetectionFragment) currentFragment).handleNfcIntent(intent);
        }


    }
}