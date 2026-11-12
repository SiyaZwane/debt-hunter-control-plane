package com.debthunter.controlplane.serve.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.serve.query.QueryParams;
import com.debthunter.controlplane.store.jdbc.StoreException;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebTest {

  @Test
  void aTokensSubjectRolesAndSquadsBecomeTheCaller() {
    Caller caller =
        Web.caller(ControllerSupport.token("alice", List.of("a", "b"), List.of("team-a")));

    assertThat(caller.subject()).isEqualTo("alice");
    assertThat(caller.roles()).containsExactlyInAnyOrder("a", "b");
    assertThat(caller.squads()).containsExactly("team-a");
  }

  @Test
  void aPrincipalThatIsNotATokenIsNotAcceptedAsACaller() {
    Principal plain = () -> "someone";

    assertThatThrownBy(() -> Web.caller(plain)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void eachFailureMapsToItsStatusAndNeverLeaksInternals() {
    assertThat(status(new AccessDeniedException("x"))).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(status(new NotFoundException("x"))).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(status(new InvalidRequestException("x"))).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(status(new ServiceUnavailableException("x")))
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(status(new StoreException("jdbc:postgresql://secret-host", null)))
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(status(new IllegalStateException("boom")))
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    Object body =
        Web.toError(new StoreException("jdbc:postgresql://secret-host", null)).block().getBody();
    assertThat(body.toString()).doesNotContain("secret-host");
    Object internal = Web.toError(new IllegalStateException("password=hunter2")).block().getBody();
    assertThat(internal.toString()).doesNotContain("hunter2");
  }

  private static HttpStatus status(Throwable t) {
    return HttpStatus.valueOf(Web.toError(t).block().getStatusCode().value());
  }

  @Test
  void parametersParseAndBlankMeansAbsent() {
    QueryParams p = Params.of(" ", "", null, "open", "2026-07-01T00:00:00Z", null, "25", "");

    assertThat(p.repository()).isNull();
    assertThat(p.severity()).isNull();
    assertThat(p.state()).isNotNull();
    assertThat(p.observedFrom()).isNotNull();
    assertThat(p.limit()).isEqualTo(25);
    assertThat(p.cursor()).isNull();
  }
}
