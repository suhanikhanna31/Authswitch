package com.authswitch.routing;

import com.authswitch.issuer.Issuer;
import com.authswitch.resilience.CircuitBreaker;

/** A BIN range owned by one issuer, with that issuer's own circuit breaker (bulkhead per issuer). */
public record Route(int binLow, int binHigh, Issuer issuer, CircuitBreaker breaker) {}
