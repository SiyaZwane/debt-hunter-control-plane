package com.debthunter.controlplane.store.evidence;

class InMemoryEvidenceAccessLogTest extends EvidenceAccessLogContract {

  @Override
  protected EvidenceAccessLog newLog() {
    return new InMemoryEvidenceAccessLog();
  }
}
