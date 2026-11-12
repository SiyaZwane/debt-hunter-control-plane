package com.debthunter.controlplane.ai.remediation.host;

/**
 * A pull request on the source host.
 *
 * @param url where to see it
 * @param number its number on the host
 */
public record PullRequestRef(String url, long number) {}
