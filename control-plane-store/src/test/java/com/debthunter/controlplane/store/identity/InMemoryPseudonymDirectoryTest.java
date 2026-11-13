package com.debthunter.controlplane.store.identity;

class InMemoryPseudonymDirectoryTest extends PseudonymDirectoryContract {

  @Override
  protected PseudonymDirectory newDirectory() {
    return new InMemoryPseudonymDirectory();
  }
}
