package com.example.reader.probe;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class   IsoDepFsProbe {
    private static final String TAG = "IsoDepFsProbe";

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

            // 1) Select MF
            ApduResp rMF = send(iso, "SELECT MF (3F00)", apduSelectByFid(hex("3F00")));
            if (!rMF.isSuccess()) {
                Log.d(TAG, "MF not selectable; stop.");
                return;
            }

            // 2) Try some common DFs (you can expand this list)
            byte[][] candidateDFs = new byte[][]{
                    hex("DF01"), hex("DF02"), hex("DF03"),
                    hex("DF10"), hex("DF11"), hex("DF12"),
                    hex("DF20"), hex("DF30"),
                    hex("5F00"), hex("5F01"), hex("5F10"), hex("5F20"),
                    hex("7F10"), hex("7F20"), hex("7F21"), hex("7F22")
            };

            // 3) Try some common EFs under MF and then under each DF
            byte[][] candidateEFs = new byte[][]{
                    hex("2F00"), hex("2F01"), hex("2F02"),
                    hex("011E"), hex("011D"), hex("0101"), hex("0102"), // keep for reference
                    hex("0001"), hex("0002"), hex("0003"),
                    hex("0015"), hex("0016"),
                    hex("1A01"), hex("1A02"), hex("1A03"),
                    hex("EF01"), hex("EF02") // (not real FID notation, just placeholders if you have hints)
            };

            // 3a) Probe EFs directly under MF
            Log.d(TAG, "---- Probing EFs under MF ----");
            probeEFList(iso, candidateEFs);

            // 3b) For each DF: select DF, then probe same EF list
            for (byte[] df : candidateDFs) {
                ApduResp rDF = send(iso, "SELECT DF " + hex(df), apduSelectByFid(df));
                if (!rDF.isSuccess()) continue;

                Log.d(TAG, "---- Probing EFs under DF " + hex(df) + " ----");
                probeEFList(iso, candidateEFs);

                // Go back to MF between DFs (some cards require it)
                send(iso, "SELECT MF (back)", apduSelectByFid(hex("3F00")));
                sleepSilently(30);
            }

            // 4) Optional: try SELECT by name with different P1/P2 if your card returns 6A86.
            // Some cards expect P2=00 instead of 00/0C, or omit Le. We'll provide a couple variants.
            Log.d(TAG, "---- Optional: SELECT by name variants (to investigate 6A86) ----");
            byte[] emrtdAid = hex("A0000002471001");
            send(iso, "SELECT by name variant P2=00 (A0000002471001)", apduSelectByNameVariant(emrtdAid, (byte)0x00, true));
            send(iso, "SELECT by name variant P2=0C no Le (A0000002471001)", apduSelectByNameVariant(emrtdAid, (byte)0x0C, false));
            send(iso, "SELECT by name variant P2=00 no Le (A0000002471001)", apduSelectByNameVariant(emrtdAid, (byte)0x00, false));

        } finally {
            try { iso.close(); } catch (Exception ignore) {}
        }
    }

    private static void probeEFList(IsoDep iso, byte[][] efs) throws IOException {
        for (byte[] ef : efs) {
            ApduResp sel = send(iso, "SELECT EF " + hex(ef), apduSelectByFid(ef));
            if (!sel.isSuccess()) continue;

            // Try READ BINARY with Le=0 to trigger 6Cxx (some cards respond with correct length)
            ApduResp rb0 = send(iso, "READ BINARY offset=0 Le=00 (probe length)", apduReadBinary(0, 0x00));

            if (rb0.sw1 == (byte)0x6C) {
                int le = rb0.sw2 & 0xFF;
                ApduResp rb = send(iso, "READ BINARY offset=0 Le=" + le, apduReadBinary(0, le));
                dumpPreview(rb.dataNoSW());
            } else if (rb0.isSuccess()) {
                dumpPreview(rb0.dataNoSW());
            } else {
                // If 6982/6985 shows up -> security status not satisfied (needs auth)
                // If 6A82 -> file not found (but we just selected success, so not likely)
            }

            sleepSilently(15);
        }
    }

    private static void dumpPreview(byte[] data) {
        if (data == null || data.length == 0) return;

        int n = Math.min(data.length, 64);
        Log.d(TAG, "DATA[0.." + (n-1) + "] HEX: " + hex(Arrays.copyOf(data, n)));

        String ascii = toMostlyPrintableAscii(data, 128);
        if (!ascii.isEmpty()) Log.d(TAG, "DATA ASCII: " + ascii);

        // quick TLV hint
        if ((data[0] & 0xFF) == 0x30 || (data[0] & 0xFF) == 0x6F || (data[0] & 0xFF) == 0x77) {
            Log.d(TAG, "Hint: looks like ASN.1/BER-TLV (starts with 0x" + String.format(Locale.US,"%02X", data[0]) + ")");
        }
    }

    private static void sleepSilently(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---------- APDU / response ----------

    private static ApduResp send(IsoDep iso, String label, byte[] capdu) throws IOException {
        Log.d(TAG, ">> " + label);
        Log.d(TAG, "CAPDU: " + hex(capdu));
        byte[] rapdu = iso.transceive(capdu);
        ApduResp r = new ApduResp(rapdu);
        Log.d(TAG, "RAPDU: " + hex(rapdu));
        Log.d(TAG, String.format(Locale.US, "SW=%02X%02X", r.sw1 & 0xFF, r.sw2 & 0xFF));
        return r;
    }

    private static byte[] apduSelectByFid(byte[] fid2) {
        return new byte[]{ 0x00, (byte)0xA4, 0x02, 0x0C, 0x02, fid2[0], fid2[1] };
    }

    private static byte[] apduReadBinary(int offset, int le) {
        int p1 = (offset >> 8) & 0x7F;
        int p2 = offset & 0xFF;
        return new byte[]{ 0x00, (byte)0xB0, (byte)p1, (byte)p2, (byte)(le & 0xFF) };
    }

    /**
     * SELECT by name with controllable P2 and optional Le.
     * Many cards accept 00 A4 04 00 Lc [name] 00 (Le present),
     * but yours returns 6A86; try variants.
     */
    private static byte[] apduSelectByNameVariant(byte[] name, byte p2, boolean withLe) {
        // CLA 00, INS A4, P1 04 (select by name), P2 varies, Lc, data, optional Le(00)
        if (withLe) {
            byte[] out = new byte[5 + name.length + 1];
            out[0] = 0x00; out[1] = (byte)0xA4; out[2] = 0x04; out[3] = p2; out[4] = (byte)(name.length & 0xFF);
            System.arraycopy(name, 0, out, 5, name.length);
            out[out.length - 1] = 0x00;
            return out;
        } else {
            byte[] out = new byte[5 + name.length];
            out[0] = 0x00; out[1] = (byte)0xA4; out[2] = 0x04; out[3] = p2; out[4] = (byte)(name.length & 0xFF);
            System.arraycopy(name, 0, out, 5, name.length);
            return out;
        }
    }

    private static final class ApduResp {
        final byte[] rapdu;
        final byte sw1, sw2;

        ApduResp(byte[] rapdu) {
            this.rapdu = rapdu != null ? rapdu : new byte[0];
            if (this.rapdu.length >= 2) {
                this.sw1 = this.rapdu[this.rapdu.length - 2];
                this.sw2 = this.rapdu[this.rapdu.length - 1];
            } else {
                this.sw1 = 0; this.sw2 = 0;
            }
        }
        boolean isSuccess() { return sw1 == (byte)0x90 && sw2 == 0x00; }
        byte[] dataNoSW() {
            if (rapdu.length <= 2) return new byte[0];
            return Arrays.copyOf(rapdu, rapdu.length - 2);
        }
    }

    // ---------- utils ----------

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
            out[i] = (byte)((hi << 4) | lo);
        }
        return out;
    }

    private static String toMostlyPrintableAscii(byte[] data, int max) {
        int n = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder();
        int printable = 0;
        for (int i = 0; i < n; i++) {
            int v = data[i] & 0xFF;
            if (v >= 32 && v <= 126) { sb.append((char)v); printable++; }
            else if (v == 0x0A) sb.append("\\n");
            else if (v == 0x0D) sb.append("\\r");
            else if (v == 0x09) sb.append("\\t");
            else sb.append('.');
        }
        if (printable < n * 0.3) return ""; // don't print if mostly binary
        return sb.toString();
    }
}