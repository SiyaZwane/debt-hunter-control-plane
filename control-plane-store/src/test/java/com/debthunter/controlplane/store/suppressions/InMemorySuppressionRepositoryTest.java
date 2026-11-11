package com.debthunter.controlplane.store.suppressions;

class InMemorySuppressionRepositoryTest extends SuppressionRepositoryContract {

  @Override
  protected SuppressionRepository newRepository() {
    return new InMemorySuppressionRepository();
  }
}
