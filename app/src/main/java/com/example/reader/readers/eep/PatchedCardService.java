package com.example.reader.readers.eep;

import android.util.Log;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;

/**
 * CardService wrapper that patches APDU commands for compatibility
 *
 * Specifically handles the SELECT-by-name Le=00 quirk where some cards
 * fail when a trailing Le=00 byte is included in the SELECT command.
 */
public class PatchedCardService extends CardService {

    private static final String TAG = "@@>> PatchedCardService";

    // APDU instruction codes
    private static final int CLA_ISO = 0x00;
    private static final int INS_SELECT = 0xA4;
    private static final int P1_SELECT_BY_NAME = 0x04;

    private final CardService delegate;

    public PatchedCardService(CardService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void open() throws CardServiceException {
        delegate.open();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } catch (Exception ignored) {
            // Ignore close errors
        }
    }

    @Override
    public boolean isConnectionLost(Exception e) {
        return delegate.isConnectionLost(e);
    }

    @Override
    public byte[] getATR() throws CardServiceException {
        return delegate.getATR();
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU commandApdu) throws CardServiceException {
        CommandAPDU patchedApdu = patchSelectCommand(commandApdu);
        return delegate.transmit(patchedApdu);
    }

    /**
     * Patch SELECT-by-name commands by removing trailing Le=00 if present
     *
     * Some cards fail with 6700 (wrong length) when SELECT command includes
     * a trailing Le=00 byte. This method detects and removes it.
     *
     * APDU structure for SELECT:
     * [CLA=00] [INS=A4] [P1=04] [P2=xx] [Lc] [Data...] [Le]?
     */
    private CommandAPDU patchSelectCommand(CommandAPDU apdu) {
        byte[] bytes = apdu.getBytes();

        if (!isSelectByNameCommand(bytes)) {
            return apdu;
        }

        if (!hasTrailingLeZero(bytes)) {
            return apdu;
        }

        // Remove the trailing Le=00 byte
        byte[] trimmedBytes = new byte[bytes.length - 1];
        System.arraycopy(bytes, 0, trimmedBytes, 0, trimmedBytes.length);

        Log.w(TAG, "Patched SELECT-by-name: removed trailing Le=00");
        return new CommandAPDU(trimmedBytes);
    }

    /**
     * Check if this is a SELECT-by-name command (CLA=00, INS=A4, P1=04)
     */
    private boolean isSelectByNameCommand(byte[] bytes) {
        if (bytes.length < 5) {
            return false;
        }

        int cla = bytes[0] & 0xFF;
        int ins = bytes[1] & 0xFF;
        int p1 = bytes[2] & 0xFF;

        return cla == CLA_ISO && ins == INS_SELECT && p1 == P1_SELECT_BY_NAME;
    }

    /**
     * Check if the APDU has a trailing Le=00 byte (Case 4 with Le=00)
     *
     * For SELECT-by-name: [00 A4 04 P2 Lc Data... Le]
     * Header = 4 bytes, Lc = 1 byte, Data = Lc bytes, Le = 1 byte
     * Expected length for Case 4: 4 + 1 + Lc + 1 = 6 + Lc
     */
    private boolean hasTrailingLeZero(byte[] bytes) {
        if (bytes.length < 6) {
            return false;
        }

        int lc = bytes[4] & 0xFF;
        int expectedCase4Length = 5 + lc + 1;  // Header(4) + Lc(1) + Data(Lc) + Le(1)

        boolean isCase4 = (bytes.length == expectedCase4Length);
        boolean hasLeZero = (bytes[bytes.length - 1] == (byte) 0x00);

        return isCase4 && hasLeZero;
    }
}