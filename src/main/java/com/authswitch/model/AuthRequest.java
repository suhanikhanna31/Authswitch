package com.authswitch.model;

import com.authswitch.iso.Iso8583Message;
import com.authswitch.iso.MessageFormatException;

/** Typed view of a 0100 authorization request. */
public record AuthRequest(String pan, String processingCode, long amountMinor, String transmissionTime,
                          String stan, String expiryYYMM, String rrn, String terminalId, String currency) {

    public static AuthRequest from(Iso8583Message m) throws MessageFormatException {
        if (!"0100".equals(m.mti())) throw new MessageFormatException("Expected MTI 0100 but got " + m.mti());
        String pan = require(m, 2);
        if (pan.length() < 13) throw new MessageFormatException("PAN too short");
        return new AuthRequest(pan, require(m, 3), Long.parseLong(require(m, 4)), require(m, 7),
                require(m, 11), require(m, 14), require(m, 37), require(m, 41), require(m, 49));
    }

    private static String require(Iso8583Message m, int de) throws MessageFormatException {
        return m.field(de).orElseThrow(() -> new MessageFormatException("Missing mandatory DE" + de));
    }

    /** Network-level identity of a transmission: a retry of the same message yields the same key. */
    public String idempotencyKey() {
        return terminalId + '|' + stan + '|' + rrn + '|' + transmissionTime;
    }

    public int bin6() {
        return Integer.parseInt(pan.substring(0, 6));
    }
}
