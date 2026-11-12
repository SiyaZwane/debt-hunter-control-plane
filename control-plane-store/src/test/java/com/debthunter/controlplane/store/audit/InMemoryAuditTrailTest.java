package com.debthunter.controlplane.store.audit;

class InMemoryAuditTrailTest extends AuditTrailContract {

  @Override
  protected AuditTrail newTrail() {
    return new InMemoryAuditTrail();
  }
}
