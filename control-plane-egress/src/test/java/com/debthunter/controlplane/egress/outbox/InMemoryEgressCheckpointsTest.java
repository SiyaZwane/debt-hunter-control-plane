package com.debthunter.controlplane.egress.outbox;

class InMemoryEgressCheckpointsTest extends EgressCheckpointsContract {

  @Override
  protected EgressCheckpoints newCheckpoints() {
    return new InMemoryEgressCheckpoints();
  }
}
