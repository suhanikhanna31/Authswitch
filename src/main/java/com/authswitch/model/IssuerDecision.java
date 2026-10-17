package com.authswitch.model;

/** Outcome of an authorization, from the issuer or from stand-in processing. */
public record IssuerDecision(ResponseCode code, String authId) {}
