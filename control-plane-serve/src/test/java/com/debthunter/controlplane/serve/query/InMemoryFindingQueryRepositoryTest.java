package com.debthunter.controlplane.serve.query;

class InMemoryFindingQueryRepositoryTest extends FindingQueryRepositoryContract {

  private InMemoryFindingQueryRepository memory;

  @Override
  protected FindingQueryRepository newRepository() {
    memory = new InMemoryFindingQueryRepository();
    return memory;
  }

  @Override
  protected void seed(FindingRow... rows) {
    for (FindingRow r : rows) {
      memory.add(r);
    }
  }
}
