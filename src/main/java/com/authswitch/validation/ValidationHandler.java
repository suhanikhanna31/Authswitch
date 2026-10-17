package com.authswitch.validation;

import com.authswitch.model.AuthRequest;
import com.authswitch.model.ResponseCode;

import java.util.Optional;

/**
 * Chain of Responsibility: each handler checks one rule and either rejects with a response code
 * or passes the request to the next handler. Rules can be added/reordered without touching the switch.
 */
public abstract class ValidationHandler {
    private ValidationHandler next;

    /** Appends {@code next} to the end of the chain and returns the head, enabling fluent wiring. */
    public ValidationHandler then(ValidationHandler nextHandler) {
        ValidationHandler tail = this;
        while (tail.next != null) tail = tail.next;
        tail.next = nextHandler;
        return this;
    }

    public final Optional<ResponseCode> validate(AuthRequest request) {
        Optional<ResponseCode> rejection = check(request);
        if (rejection.isPresent() || next == null) return rejection;
        return next.validate(request);
    }

    protected abstract Optional<ResponseCode> check(AuthRequest request);
}
