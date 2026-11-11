package com.debthunter.controlplane.store.rollout;

class InMemoryStageRepositoryTest extends StageRepositoryContract {

  @Override
  protected StageRepository newRepository() {
    return new InMemoryStageRepository();
  }
}
