/**
 * Shared test support for the control-plane modules: run-bundle fixtures at each supported schema
 * version, a JWT issuer with a local JWKS endpoint, and a Testcontainers-backed Kafka broker.
 *
 * <p>Consumed in test scope only; never a production dependency.
 */
package com.debthunter.controlplane.testkit;
