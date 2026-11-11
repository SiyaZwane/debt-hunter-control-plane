package com.debthunter.controlplane.analytics.coverage.estate;

import java.util.List;

/**
 * Lists every repository that exists in the source-control estate, so coverage can tell a
 * repository that is quiet from one nobody has onboarded.
 */
public interface SourceControlEstateProvider {

  /**
   * Lists the repositories that should be covered: active ones, not archived or disabled.
   *
   * @return their identifiers, in the form the registry uses ({@code owner/name}, or {@code
   *     project/name} on Azure Repos)
   * @throws EstateUnavailableException if the listing could not be obtained or understood; callers
   *     must not treat that as "the estate is empty"
   */
  List<String> repositories();
}
