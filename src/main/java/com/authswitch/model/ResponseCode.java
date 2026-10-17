package com.authswitch.model;

/** Subset of standard ISO 8583 DE39 response codes used by the switch. */
public enum ResponseCode {
    APPROVED("00"),
    NO_SUCH_ISSUER("15"),
    INVALID_TRANSACTION("12"),
    INVALID_AMOUNT("13"),
    INVALID_CARD("14"),
    INSUFFICIENT_FUNDS("51"),
    EXPIRED_CARD("54"),
    FORMAT_ERROR("30"),
    ISSUER_UNAVAILABLE("91"),
    SYSTEM_MALFUNCTION("96");

    private final String code;

    ResponseCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
