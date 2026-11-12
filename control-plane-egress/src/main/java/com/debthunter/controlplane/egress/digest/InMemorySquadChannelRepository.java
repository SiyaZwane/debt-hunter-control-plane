package com.debthunter.controlplane.egress.digest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Keeps channels in memory. For tests and for running without a database. */
public final class InMemorySquadChannelRepository implements SquadChannelRepository {

  private final Map<String, SquadChannel> channels = new HashMap<>();

  @Override
  public synchronized void save(SquadChannel channel) {
    channels.put(channel.owner(), channel);
  }

  @Override
  public synchronized Optional<SquadChannel> find(String owner) {
    return Optional.ofNullable(channels.get(owner));
  }
}
