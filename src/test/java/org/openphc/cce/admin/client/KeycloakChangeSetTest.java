package org.openphc.cce.admin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeycloakChangeSetTest {

  private final List<String> log = new ArrayList<>();

  private KeycloakChangeSet set(String failOn) {
    KeycloakChangeSet changes = new KeycloakChangeSet();
    for (String name : List.of("a", "b", "c")) {
      changes.add(
          name,
          () -> {
            if (name.equals(failOn)) throw new RuntimeException("apply " + name + " failed");
            log.add("apply " + name);
          },
          () -> log.add("undo " + name));
    }
    return changes;
  }

  @Test
  void appliesEveryChangeInOrder() {
    KeycloakChangeSet changes = set(null);
    changes.execute();
    assertThat(log).containsExactly("apply a", "apply b", "apply c");
    assertThat(changes.hasUnreversedChanges()).isFalse();
  }

  @Test
  void aFailureReversesOnlyWhatWasAlreadyAppliedNewestFirstAndRethrows() {
    KeycloakChangeSet changes = set("c");

    assertThatThrownBy(changes::execute).hasMessage("apply c failed");

    assertThat(log).containsExactly("apply a", "apply b", "undo b", "undo a"); // c never applied, never undone
    assertThat(changes.hasUnreversedChanges()).isFalse();
  }

  @Test
  void afterAFullSuccessALaterCommitFailureCanStillReverseEverything() {
    KeycloakChangeSet changes = set(null);
    changes.execute();
    log.clear();

    assertThat(changes.undoApplied()).isTrue();

    assertThat(log).containsExactly("undo c", "undo b", "undo a");
  }

  @Test
  void aFailedReversalIsReportedSoItCanBeReconciled() {
    KeycloakChangeSet changes = new KeycloakChangeSet();
    changes.add("a", () -> log.add("apply a"), () -> { throw new RuntimeException("cannot undo a"); });
    changes.add("b", () -> { throw new RuntimeException("apply b failed"); }, () -> {});

    assertThatThrownBy(changes::execute).hasMessage("apply b failed");

    assertThat(changes.hasUnreversedChanges()).isTrue();
  }

  @Test
  void anEmptySetDoesNothing() {
    KeycloakChangeSet changes = new KeycloakChangeSet();
    assertThat(changes.isEmpty()).isTrue();
    changes.execute();
    assertThat(changes.undoApplied()).isTrue();
  }
}
