package com.authswitch.observer;

/** Observer: metrics, audit and alerting subscribe here without the switch knowing about them. */
@FunctionalInterface
public interface TransactionListener {
    void onCompleted(TransactionEvent event);
}
