package com.debthunter.controlplane.egress.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What every channel repository must do. */
public abstract class SquadChannelRepositoryContract {

  protected abstract SquadChannelRepository newRepository();

  @Test
  void aSquadHasNoChannelUntilOneIsSaved() {
    assertThat(newRepository().find("team-a")).isEmpty();
  }

  @Test
  void aSavedChannelReadsBack() {
    SquadChannelRepository repository = newRepository();
    repository.save(new SquadChannel("team-a", SquadChannel.Kind.SLACK, "ref-1"));

    assertThat(repository.find("team-a"))
        .contains(new SquadChannel("team-a", SquadChannel.Kind.SLACK, "ref-1"));
    assertThat(repository.find("team-b")).isEmpty();
  }

  @Test
  void savingAgainReplacesTheChannel() {
    SquadChannelRepository repository = newRepository();
    repository.save(new SquadChannel("team-a", SquadChannel.Kind.SLACK, "ref-1"));

    repository.save(new SquadChannel("team-a", SquadChannel.Kind.TEAMS, "ref-2"));

    assertThat(repository.find("team-a"))
        .contains(new SquadChannel("team-a", SquadChannel.Kind.TEAMS, "ref-2"));
  }
}
