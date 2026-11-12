package com.debthunter.controlplane.store.queue;

class InMemoryWorkQueueTest extends WorkQueueContract {

  @Override
  protected WorkQueue newQueue(TestClock clock) {
    return new InMemoryWorkQueue(POLICIES, clock);
  }
}
