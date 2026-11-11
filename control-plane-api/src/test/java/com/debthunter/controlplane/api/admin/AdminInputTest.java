package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdminInputTest {

  @Test
  void anOrdinaryIdentifierPassesUnchanged() {
    assertThat(AdminInput.text("repository", "acme/widgets")).isEqualTo("acme/widgets");
    assertThat(AdminInput.text("owner", "x".repeat(200))).hasSize(200);
  }

  @Test
  void nullBlankPaddedLongAndControlCharacterValuesAreAllRejected() {
    for (String bad :
        new String[] {null, "", "   ", " a", "a ", "x".repeat(201), "a\nb", "a\u0000b", "a\tb"}) {
      assertThatThrownBy(() -> AdminInput.text("owner", bad))
          .as("[%s]", bad)
          .isInstanceOf(InvalidAdminInputException.class)
          .satisfies(e -> assertThat(e.getMessage()).startsWith("owner"));
    }
  }

  @Test
  void theMessageNamesTheFieldAndNeverTheValue() {
    assertThatThrownBy(() -> AdminInput.text("owner", "secret value\n"))
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret"));
  }

  @Test
  void aPathCapturedWithItsLeadingSlashYieldsTheIdentifier() {
    assertThat(AdminInput.repositoryFromPath("/acme/widgets")).isEqualTo("acme/widgets");
    assertThat(AdminInput.repositoryFromPath("acme/widgets")).isEqualTo("acme/widgets");
  }

  @Test
  void anEmptyPathIsRejected() {
    assertThatThrownBy(() -> AdminInput.repositoryFromPath("/"))
        .isInstanceOf(InvalidAdminInputException.class);
    assertThatThrownBy(() -> AdminInput.repositoryFromPath(null))
        .isInstanceOf(InvalidAdminInputException.class);
  }
}
