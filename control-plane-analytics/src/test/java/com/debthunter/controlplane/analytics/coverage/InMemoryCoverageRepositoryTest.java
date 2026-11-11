package com.debthunter.controlplane.analytics.coverage;

class InMemoryCoverageRepositoryTest extends CoverageRepositoryContract {

  @Override
  protected CoverageRepository newRepository() {
    return new InMemoryCoverageRepository();
  }
}
