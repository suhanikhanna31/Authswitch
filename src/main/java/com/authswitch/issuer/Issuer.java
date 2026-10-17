package com.authswitch.issuer;

import com.authswitch.model.AuthRequest;
import com.authswitch.model.IssuerDecision;

/** Strategy: how an authorization is decided. Real issuers are remote hosts; tests use simulations. */
public interface Issuer {
    String id();

    /** Blocking call. A decline (e.g. 51) is a normal return; unreachable/failing issuers throw. */
    IssuerDecision authorize(AuthRequest request) throws IssuerException;

    /** Advice (MTI 0120): tells the issuer about a transaction approved on its behalf while it was down. */
    void advise(AuthRequest request, String standInAuthId) throws IssuerException;
}
