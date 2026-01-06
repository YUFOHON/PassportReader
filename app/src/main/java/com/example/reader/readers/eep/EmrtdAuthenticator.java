package com.example.reader.readers.eep;

import android.util.Log;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SecurityInfo;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles PACE and BAC authentication for eMRTD chips
 */
public class EmrtdAuthenticator {

    private static final String TAG = "@@>> EmrtdAuthenticator";

    public enum AuthMethod { PACE, BAC }

    public static class AuthResult {
        public final AuthMethod method;
        public final boolean success;
        public final String errorMessage;

        private AuthResult(AuthMethod method, boolean success, String errorMessage) {
            this.method = method;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public static AuthResult success(AuthMethod method) {
            return new AuthResult(method, true, null);
        }

        public static AuthResult failure(String message) {
            return new AuthResult(null, false, message);
        }
    }

    /**
     * Perform authentication using PACE (preferred) or BAC (fallback)
     */
    public AuthResult authenticate(PassportService service, BACKeySpec bacKey) throws Exception {
        // Try PACE first
        AuthResult paceResult = tryPace(service, bacKey);
        if (paceResult.success) {
            return paceResult;
        }

        // Fallback to BAC
        return tryBac(service, bacKey);
    }

    private AuthResult tryPace(PassportService service, BACKeySpec bacKey) {
        try {
            byte[] caBytes = readCardAccess(service);
            if (caBytes == null) {
                Log.d(TAG, "No CardAccess file, PACE not available");
                return AuthResult.failure("No CardAccess");
            }

            CardAccessFile cardAccessFile = new CardAccessFile(new ByteArrayInputStream(caBytes));
            List<PACEInfo> paceInfos = extractPaceInfos(cardAccessFile);

            for (PACEInfo paceInfo : paceInfos) {
                try {
                    String oid = paceInfo.getObjectIdentifier();
                    BigInteger parameterId = paceInfo.getParameterId();

                    Log.d(TAG, "Attempting PACE: " + paceInfo.getProtocolOIDString());
                    service.doPACE(bacKey, oid, PACEInfo.toParameterSpec(parameterId), null);

                    // Reselect applet with secure messaging
                    service.sendSelectApplet(true);
                    Log.d(TAG, "PACE succeeded");

                    return AuthResult.success(AuthMethod.PACE);

                } catch (Exception e) {
                    Log.w(TAG, "PACE attempt failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            Log.d(TAG, "PACE not available: " + e.getMessage());
        }

        return AuthResult.failure("PACE failed");
    }

    private AuthResult tryBac(PassportService service, BACKeySpec bacKey) {
        try {
            service.doBAC(bacKey);
            service.sendSelectApplet(true);

            Log.d(TAG, "BAC succeeded");
            return AuthResult.success(AuthMethod.BAC);

        } catch (Exception e) {
            Log.e(TAG, "BAC failed: " + e.getMessage());
            return AuthResult.failure("BAC failed: " + e.getMessage());
        }
    }

    private byte[] readCardAccess(PassportService service) {
        try {
            return StreamUtils.readAllBytes(
                    service.getInputStream(PassportService.EF_CARD_ACCESS)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private List<PACEInfo> extractPaceInfos(CardAccessFile cardAccessFile) {
        List<PACEInfo> paceInfos = new ArrayList<>();
        for (SecurityInfo si : cardAccessFile.getSecurityInfos()) {
            if (si instanceof PACEInfo) {
                paceInfos.add((PACEInfo) si);
            }
        }
        return paceInfos;
    }
}