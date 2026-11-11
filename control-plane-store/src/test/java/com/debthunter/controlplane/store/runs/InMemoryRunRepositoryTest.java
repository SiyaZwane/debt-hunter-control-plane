package com.debthunter.controlplane.store.runs;

class InMemoryRunRepositoryTest extends RunRepositoryContract {

  @Override
  protected RunRepository newRepository() {
    return new InMemoryRunRepository();
  }
}
