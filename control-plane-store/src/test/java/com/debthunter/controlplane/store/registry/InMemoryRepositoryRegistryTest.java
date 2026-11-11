package com.debthunter.controlplane.store.registry;

class InMemoryRepositoryRegistryTest extends RepositoryRegistryContract {

  @Override
  protected RepositoryRegistry newRegistry() {
    return new InMemoryRepositoryRegistry();
  }
}
