package com.debthunter.controlplane.egress.digest;

class InMemoryDigestLedgerTest extends DigestLedgerContract {

  @Override
  protected DigestLedger newLedger() {
    return new InMemoryDigestLedger();
  }
}
