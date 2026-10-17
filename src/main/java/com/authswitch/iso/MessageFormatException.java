package com.authswitch.iso;

/** Thrown when a wire message or an in-memory message violates the (simplified) ISO 8583 spec. */
public class MessageFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    public MessageFormatException(String message) {
        super(message);
    }
}
