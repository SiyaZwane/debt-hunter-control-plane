package com.debthunter.controlplane.egress.digest;

import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcSquadChannelRepositoryTest extends SquadChannelRepositoryContract {

  @Override
  protected SquadChannelRepository newRepository() {
    return new JdbcSquadChannelRepository(JdbcDigestParts.freshDatabase());
  }
}
