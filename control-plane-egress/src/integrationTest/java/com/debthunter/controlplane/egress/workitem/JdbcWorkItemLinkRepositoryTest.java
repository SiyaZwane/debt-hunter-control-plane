package com.debthunter.controlplane.egress.workitem;

import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcWorkItemLinkRepositoryTest extends WorkItemLinkRepositoryContract {

  @Override
  protected WorkItemLinkRepository newRepository() {
    return new JdbcWorkItemLinkRepository(JdbcStores.freshDatabase());
  }
}
