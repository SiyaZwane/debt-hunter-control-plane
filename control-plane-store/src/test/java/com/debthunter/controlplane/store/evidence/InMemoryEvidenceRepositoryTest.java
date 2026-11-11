package com.debthunter.controlplane.store.evidence;

class InMemoryEvidenceRepositoryTest extends EvidenceRepositoryContract {

  @Override
  protected EvidenceRepository newRepository() {
    return new InMemoryEvidenceRepository();
  }
}
