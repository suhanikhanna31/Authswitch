package com.authswitch.issuer;

/** The issuer could not be reached or failed to answer (as opposed to answering with a decline). */
public class IssuerException extends Exception {
    private static final long serialVersionUID = 1L;

    public IssuerException(String message) {
        super(message);
    }
}
