package com.example.reader.probe;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Pure IsoDep probe to identify card/application type.
 *
 * What it does:
 * 1) Connect + print max transceive, historical bytes, hi-layer response
 * 2) SELECT by name: PPSE (2PAY.SYS.DDF01) and common eMRTD AID (A0000002471001)
 * 3) If eMRTD selected: try SELECT EF.COM (011E), EF.SOD (011D), DG1 (0101), DG2 (0102)
 * 4) Also tries SELECT MF (3F00) then DF1 (if present) style (best-effort)
 *
 * Notes:
 * - Many non-payment cards don't support PPSE. 6A82 is OK.
 * - 6A82 on LDS files usually means "not in this DF/app" or "not ICAO LDS".
 */
public final class IsoDepProbe {

    private static final String TAG = "IsoDepProbe";

    // Common eMRTD application AID used by many ePassports/eIDs (ICAO 9303 family)
    // A0 00 00 02 47 10 01
    private static final byte[] AID_EMRTD = hex("A0000002471001");

    // PPSE (Payment system directory). Often absent on ID documents.
    private static final byte[] DF_PPSE = "2PAY.SYS.DDF01".getBytes(StandardCharsets.US_ASCII);

    // Some cards also respond to 1PAY.SYS.DDF01 (less common)
    private static final byte[] DF_1PAY = "1PAY.SYS.DDF01".getBytes(StandardCharsets.US_ASCII);

    // ICAO LDS file IDs (FID)
    private static final byte[] FID_EF_COM = hex("011E");
    private static final byte[] FID_EF_SOD = hex("011D");
    private static final byte[] FID_DG1 = hex("0101");
    private static final byte[] FID_DG2 = hex("0102");
    private static final byte[] FID_DG11 = hex("010B");
    private static final byte[] FID_DG12 = hex("010C");
    private static final byte[] FID_DG15 = hex("010F");

    // Master File (MF) - sometimes selectable on classic ISO7816 FS cards
    private static final byte[] FID_MF = hex("3F00");

    public static void probe(Tag tag) throws IOException {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) throw new IOException("Tag does not support IsoDep");

        try {
            iso.connect();
            iso.setTimeout(20000);

            Log.d(TAG, "Connected IsoDep");
            Log.d(TAG, "Tech=" + Arrays.toString(tag.getTechList()));
            Log.d(TAG, "maxTransceiveLength=" + iso.getMaxTransceiveLength());

            byte[] hb = iso.getHistoricalBytes();
            byte[] hlr = iso.getHiLayerResponse();
            if (hb != null) Log.d(TAG, "HistoricalBytes: " + hex(hb));
            if (hlr != null) Log.d(TAG, "HiLayerResponse: " + hex(hlr));

            // 0) Optional: GET DATA (ATC / Card data) - many cards will 6A88/6A81; it's fine.
            // Try a couple of common GET DATA tags (best-effort).
            sendAndLog(iso, "GET DATA 9F7F (try)", apduGetData(hex("9F7F")));
            sendAndLog(iso, "GET DATA 5F52 (try)", apduGetData(hex("5F52")));

            // 1) Try PPSE / 1PAY (directory listing, only if supported)
            ApduResp rPPSE = sendAndLog(iso, "SELECT PPSE (2PAY.SYS.DDF01)", apduSelectByName(DF_PPSE));
            ApduResp r1PAY = sendAndLog(iso, "SELECT 1PAY.SYS.DDF01", apduSelectByName(DF_1PAY));

            // 2) Try eMRTD AID (ICAO)
            ApduResp rEmrtd = sendAndLog(iso, "SELECT eMRTD AID A0000002471001", apduSelectByName(AID_EMRTD));

            if (rEmrtd.isSuccess()) {
                Log.d(TAG, "eMRTD AID selected. Trying ICAO LDS files (EF.COM/SOD/DG1/DG2...)");

                // Try SELECT + READ BINARY from each FID (short read first)
                selectAndReadFile(iso, "EF.COM (011E)", FID_EF_COM, 256);
                selectAndReadFile(iso, "EF.SOD (011D)", FID_EF_SOD, 256);
                selectAndReadFile(iso, "DG1 (0101)", FID_DG1, 256);
                selectAndReadFile(iso, "DG2 (0102)", FID_DG2, 256);
                selectAndReadFile(iso, "DG11 (010B)", FID_DG11, 256);
                selectAndReadFile(iso, "DG12 (010C)", FID_DG12, 256);
                selectAndReadFile(iso, "DG15 (010F)", FID_DG15, 256);

                // If DG1/DG2 reads fail with security status, that suggests BAC/PACE needed.
            } else {
                Log.d(TAG, "eMRTD AID not selectable (or different AID). This likely isn't ICAO LDS, or uses another AID.");
            }

            // 3) Best-effort classic FS: SELECT MF then see if any known FIDs exist (often not applicable)
            ApduResp rMF = sendAndLog(iso, "SELECT MF (3F00)", apduSelectByFid(FID_MF));
            if (rMF.isSuccess()) {
                // Try a couple of common DF/EF patterns (highly card-specific; these are just probes)
                // Many cards will 6A82 and that's fine.
                selectAndReadFile(iso, "Try EF(2F00)", hex("2F00"), 64);
                selectAndReadFile(iso, "Try EF(2F01)", hex("2F01"), 64);
            }

            // 4) If PPSE worked, optionally parse AIDs from FCI (very basic TLV scanning)
            if (rPPSE.isSuccess()) {
                Log.d(TAG, "PPSE FCI (raw) maybe contains AIDs. Attempting to extract 4F tags...");
                for (byte[] aid : extractTLVValues(rPPSE.dataNoSW(), (byte) 0x4F)) {
                    Log.d(TAG, "Found AID via PPSE: " + hex(aid));
                    sendAndLog(iso, "SELECT AID from PPSE: " + hex(aid), apduSelectByName(aid));
                }
            }
            if (r1PAY.isSuccess()) {
                Log.d(TAG, "1PAY FCI (raw) maybe contains AIDs. Attempting to extract 4F tags...");
                for (byte[] aid : extractTLVValues(r1PAY.dataNoSW(), (byte) 0x4F)) {
                    Log.d(TAG, "Found AID via 1PAY: " + hex(aid));
                    sendAndLog(iso, "SELECT AID from 1PAY: " + hex(aid), apduSelectByName(aid));
                }
            }

        } finally {
            try { iso.close(); } catch (Exception ignore) {}
        }
    }

    // -------------------------
    // Core helpers
    // -------------------------

    private static void selectAndReadFile(IsoDep iso, String name, byte[] fid, int readLen) throws IOException {
        ApduResp sel = sendAndLog(iso, "SELECT " + name + " FID=" + hex(fid), apduSelectByFid(fid));
        if (!sel.isSuccess()) return;

        // Try short read at offset 0
        ApduResp rb = sendAndLog(iso, "READ BINARY " + name + " (offset 0, " + readLen + " bytes)",
                apduReadBinary(0, Math.min(readLen, 0xFF)));

        // If card indicates "wrong length" (6Cxx), retry with suggested Le
        if (rb.sw1 == (byte) 0x6C) {
            int le = rb.sw2 & 0xFF;
            sendAndLog(iso, "READ BINARY retry with Le=" + le, apduReadBinary(0, le));
        }

        // Some cards require READ BINARY with extended length (not supported on all Android stacks).
        // Keep it simple here.
    }

    private static ApduResp sendAndLog(IsoDep iso, String label, byte[] capdu) throws IOException {
        Log.d(TAG, ">> " + label);
        Log.d(TAG, "CAPDU: " + hex(capdu));

        byte[] rapdu = iso.transceive(capdu);

        ApduResp r = new ApduResp(rapdu);
        Log.d(TAG, "RAPDU: " + hex(rapdu));
        Log.d(TAG, String.format(Locale.US, "SW=%02X%02X", r.sw1 & 0xFF, r.sw2 & 0xFF));

        // If response data looks ASCII printable, show a preview
        byte[] data = r.dataNoSW();
        if (data.length > 0) {
            String ascii = toPrintableAscii(data, 128);
            if (!ascii.isEmpty()) Log.d(TAG, "RDATA(ASCII): " + ascii);
        }
        return r;
    }

    // -------------------------
    // APDU builders (ISO 7816-4)
    // -------------------------

    /** SELECT by DF name (AID or DDF name). */
    private static byte[] apduSelectByName(byte[] name) {
        // 00 A4 04 00 Lc [name] 00
        return concat(new byte[]{
                (byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00,
                (byte) (name.length & 0xFF)
        }, name, new byte[]{(byte) 0x00});
    }

    /** SELECT by File ID (FID). */
    private static byte[] apduSelectByFid(byte[] fid2bytes) {
        if (fid2bytes.length != 2) throw new IllegalArgumentException("FID must be 2 bytes");
        // 00 A4 02 0C 02 [fid]
        return new byte[]{
                (byte) 0x00, (byte) 0xA4, (byte) 0x02, (byte) 0x0C,
                (byte) 0x02, fid2bytes[0], fid2bytes[1]
        };
    }

    /** READ BINARY short (offset up to 32767 using P1/P2). */
    private static byte[] apduReadBinary(int offset, int le) {
        // 00 B0 P1 P2 Le
        int p1 = (offset >> 8) & 0x7F; // short EF, bit8=0
        int p2 = offset & 0xFF;
        return new byte[]{
                (byte) 0x00, (byte) 0xB0, (byte) p1, (byte) p2, (byte) (le & 0xFF)
        };
    }

    /** GET DATA (best-effort): 00 CA P1 P2 Le */
    private static byte[] apduGetData(byte[] tag) {
        // Accept 1-2 bytes tags (common)
        if (tag.length == 1) {
            return new byte[]{(byte) 0x00, (byte) 0xCA, (byte) 0x00, tag[0], (byte) 0x00};
        } else if (tag.length == 2) {
            return new byte[]{(byte) 0x00, (byte) 0xCA, tag[0], tag[1], (byte) 0x00};
        } else {
            throw new IllegalArgumentException("GET DATA tag must be 1 or 2 bytes for this helper");
        }
    }

    // -------------------------
    // Response wrapper
    // -------------------------

    private static final class ApduResp {
        final byte[] rapdu;
        final byte sw1;
        final byte sw2;

        ApduResp(byte[] rapdu) {
            this.rapdu = rapdu != null ? rapdu : new byte[0];
            if (this.rapdu.length >= 2) {
                this.sw1 = this.rapdu[this.rapdu.length - 2];
                this.sw2 = this.rapdu[this.rapdu.length - 1];
            } else {
                this.sw1 = 0;
                this.sw2 = 0;
            }
        }

        boolean isSuccess() {
            return (sw1 == (byte) 0x90 && sw2 == (byte) 0x00);
        }

        byte[] dataNoSW() {
            if (rapdu.length <= 2) return new byte[0];
            return Arrays.copyOf(rapdu, rapdu.length - 2);
        }
    }

    // -------------------------
    // Minimal TLV extraction (single-byte tag only, for quick AID (4F) scanning)
    // This is intentionally simple; PPSE FCI can be nested/constructed.
    // -------------------------

    private static byte[][] extractTLVValues(byte[] data, byte tag) {
        if (data == null) return new byte[0][];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // We'll store as: [len][value...] repeated, then split.
        int i = 0;
        while (i < data.length) {
            int t = data[i] & 0xFF;
            i++;
            if (i >= data.length) break;

            int len = data[i] & 0xFF;
            i++;

            if ((len & 0x80) != 0) { // long form length
                int n = len & 0x7F;
                if (n == 0 || i + n > data.length) break;
                len = 0;
                for (int k = 0; k < n; k++) {
                    len = (len << 8) | (data[i] & 0xFF);
                    i++;
                }
            }

            if (i + len > data.length) break;

            if ((byte) t == tag) {
                out.write(len & 0xFF);
                out.write(data, i, len);
            }

            i += len;
        }

        byte[] flat = out.toByteArray();
        // Split
        int p = 0;
        int count = 0;
        while (p < flat.length) {
            int len = flat[p] & 0xFF;
            p += 1 + len;
            if (p <= flat.length) count++;
            else break;
        }

        byte[][] res = new byte[count][];
        p = 0;
        for (int idx = 0; idx < count; idx++) {
            int len = flat[p] & 0xFF;
            p++;
            res[idx] = Arrays.copyOfRange(flat, p, p + len);
            p += len;
        }
        return res;
    }

    // -------------------------
    // Byte utilities
    // -------------------------

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] r = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        System.arraycopy(c, 0, r, a.length + b.length, c.length);
        return r;
    }

    private static String toPrintableAscii(byte[] data, int max) {
        int n = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int v = data[i] & 0xFF;
            if (v >= 32 && v <= 126) sb.append((char) v);
            else if (v == 0x0A) sb.append("\\n");
            else if (v == 0x0D) sb.append("\\r");
            else if (v == 0x09) sb.append("\\t");
            else sb.append('.');
        }
        // If it's basically all dots, don't spam logs
        int dots = 0;
        for (int i = 0; i < sb.length(); i++) if (sb.charAt(i) == '.') dots++;
        if (sb.length() > 0 && dots > sb.length() * 0.9) return "";
        return sb.toString();
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format(Locale.US, "%02X ", v));
        return sb.toString().trim();
    }

    private static byte[] hex(String s) {
        String t = s.replaceAll("[^0-9A-Fa-f]", "");
        if ((t.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}