package com.authswitch.observer;

import com.authswitch.model.ResponseCode;

/** Immutable record of a finished transaction. Contains only the masked PAN. */
public record TransactionEvent(String maskedPan, long amountMinor, ResponseCode code, String issuerId,
                               Path path, long latencyMicros) {
    /** Which branch of the pipeline produced the answer. */
    public enum Path { FORMAT_ERROR, VALIDATION, NO_ROUTE, ISSUER, STAND_IN }
}
