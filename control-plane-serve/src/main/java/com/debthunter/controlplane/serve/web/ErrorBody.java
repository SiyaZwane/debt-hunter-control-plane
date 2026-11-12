package com.debthunter.controlplane.serve.web;

/**
 * A machine-readable error: a stable code a client can switch on, and a message for humans.
 *
 * @param error the stable code, such as {@code forbidden}
 * @param message what happened, safe to show
 */
public record ErrorBody(String error, String message) {}
