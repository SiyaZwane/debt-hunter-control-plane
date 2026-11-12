package com.debthunter.controlplane.egress.digest;

import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcDigestLedgerTest extends DigestLedgerContract {

  @Override
  protected DigestLedger newLedger() {
    return new JdbcDigestLedger(JdbcDigestParts.freshDatabase());
  }
}
