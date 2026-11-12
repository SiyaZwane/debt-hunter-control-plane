package com.debthunter.controlplane.egress.workitem;

class InMemoryWorkItemLinkRepositoryTest extends WorkItemLinkRepositoryContract {

  @Override
  protected WorkItemLinkRepository newRepository() {
    return new InMemoryWorkItemLinkRepository();
  }
}
