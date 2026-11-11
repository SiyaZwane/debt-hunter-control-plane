package com.debthunter.controlplane.store.lifecycle;

class InMemoryTransitionLogTest extends TransitionLogContract {

  @Override
  protected TransitionLogRepository newLog() {
    return new InMemoryTransitionLogRepository();
  }
}
