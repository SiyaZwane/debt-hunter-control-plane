package com.debthunter.controlplane.egress.digest;

class InMemorySquadChannelRepositoryTest extends SquadChannelRepositoryContract {

  @Override
  protected SquadChannelRepository newRepository() {
    return new InMemorySquadChannelRepository();
  }
}
