package com.debthunter.controlplane.analytics.readmodel;

class InMemoryReadModelRepositoryTest extends ReadModelRepositoryContract {

  @Override
  protected ReadModelRepository newRepository() {
    return new InMemoryReadModelRepository();
  }
}
