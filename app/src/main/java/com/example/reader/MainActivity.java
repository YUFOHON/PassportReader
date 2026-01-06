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
    private EpptFragment epptFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Customize ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("📘 Passport Reader");
        }

        // Initialize fragments
        epptFragment = new EpptFragment();

        // Setup Bottom Navigation
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            int itemId = item.getItemId();
            if (itemId == R.id.nav_eppt) {
                selectedFragment = epptFragment;
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle("📘 ePPT Reader");
                }
            } else if (itemId == R.id.nav_eppp) {
                selectedFragment = new EeepFragment();
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle("📄 ePPP Reader");
                }
            } else if (itemId == R.id.nav_smart_detection) {
                selectedFragment = new SmartDetectionFragment();
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle("🔍 Smart Detection");
                }
            }

            if (selectedFragment != null) {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, selectedFragment)
                        .commit();
            }

            return true;
        });

        // Load default fragment (ePPT)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, epptFragment)
                    .commit();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Forward the result to the ePPT fragment
        if (epptFragment != null) {
            epptFragment.onActivityResult(requestCode, resultCode, data);
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

        if (currentFragment instanceof EpptFragment) {
            Log.d("@@>>", "handle eppt nfc intent");
            ((EpptFragment) currentFragment).handleNfcIntent(intent);
        }
        if (currentFragment instanceof EeepFragment) {
            Log.d("@@>>", "handle eppp nfc intent");
            ((EeepFragment) currentFragment).handleNfcIntent(intent);

        }
    }
}