package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RenderingTest {

  @Test
  void htmlEscapesEveryCharacterThatCouldStartMarkup() {
    assertThat(Rendering.html("<a href=\"x\">'&'</a>"))
        .isEqualTo("&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
    assertThat(Rendering.html("plain text")).isEqualTo("plain text");
  }

  @Test
  void wikiMarkupCharactersAreRemoved() {
    assertThat(Rendering.wiki("{code}[~user]|!img!")).isEqualTo("code~userimg");
    assertThat(Rendering.wiki("fine")).isEqualTo("fine");
  }

  @Test
  void aValueThatIsOnlyMarkupBecomesADash() {
    assertThat(Rendering.wiki("{}[]|!")).isEqualTo("-");
    assertThat(Rendering.wiki("   ")).isEqualTo("-");
  }
}
