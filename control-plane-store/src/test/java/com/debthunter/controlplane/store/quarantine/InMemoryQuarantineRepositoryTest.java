package com.debthunter.controlplane.store.quarantine;

class InMemoryQuarantineRepositoryTest extends QuarantineRepositoryContract {

  @Override
  protected QuarantineRepository newRepository() {
    return new InMemoryQuarantineRepository();
  }
}
