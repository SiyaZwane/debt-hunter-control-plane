package com.debthunter.controlplane.serve.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessTest {

  private static final Caller ALICE = new Caller("alice", Set.of(Roles.SQUAD), Set.of("team-a"));

  @Test
  void aRoleIsRequired() {
    Access.requireRole(ALICE, Roles.SQUAD);

    assertThatThrownBy(() -> Access.requireRole(ALICE, Roles.LEADER))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining(Roles.LEADER);
  }

  @Test
  void aSquadMembershipIsRequiredAsWellAsTheRole() {
    Access.requireSquad(ALICE, Roles.SQUAD, "team-a");

    assertThatThrownBy(() -> Access.requireSquad(ALICE, Roles.SQUAD, "team-b"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> Access.requireSquad(ALICE, Roles.SQUAD, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> Access.requireSquad(ALICE, Roles.EVIDENCE, "team-a"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aCallerNeedsASubjectAndGetsCopiesOfItsSets() {
    assertThatThrownBy(() -> new Caller(" ", Set.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    Caller c = new Caller("x", null, null);
    assertThat(c.roles()).isEmpty();
    assertThat(c.squads()).isEmpty();
    assertThat(c.has(Roles.SQUAD)).isFalse();
    assertThat(c.isIn("team-a")).isFalse();
  }
}
