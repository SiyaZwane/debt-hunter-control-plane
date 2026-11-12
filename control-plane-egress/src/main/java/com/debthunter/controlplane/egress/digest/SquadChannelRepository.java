package com.debthunter.controlplane.egress.digest;

import java.util.Optional;

/** Where each squad's digest goes. */
public interface SquadChannelRepository {

  /** Inserts or replaces a squad's channel. */
  void save(SquadChannel channel);

  /** Looks a squad's channel up. */
  Optional<SquadChannel> find(String owner);
}
