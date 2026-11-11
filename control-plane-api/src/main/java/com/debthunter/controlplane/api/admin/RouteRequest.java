package com.debthunter.controlplane.api.admin;

/**
 * Where a repository's work items go, as supplied in a request.
 *
 * @param tracker the tracking system
 * @param target the project, board or queue within it
 */
public record RouteRequest(String tracker, String target) {}
