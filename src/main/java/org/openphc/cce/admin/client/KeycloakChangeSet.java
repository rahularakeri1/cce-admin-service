package org.openphc.cce.admin.client;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An ordered list of Keycloak changes, each paired with the call that reverses it. Executing it
 * applies them in order; if one fails, the ones already applied are reversed (best effort) so
 * Keycloak is not left half-changed while the local transaction rolls back. If the LOCAL commit
 * fails afterwards, {@link #undoApplied()} does the same.
 */
public class KeycloakChangeSet {

  private static final Logger log = LoggerFactory.getLogger(KeycloakChangeSet.class);

  private record Change(String description, Runnable apply, Runnable undo) {}

  private final List<Change> changes = new ArrayList<>();
  private final List<Change> applied = new ArrayList<>();

  public KeycloakChangeSet add(String description, Runnable apply, Runnable undo) {
    changes.add(new Change(description, apply, undo));
    return this;
  }

  public boolean isEmpty() {
    return changes.isEmpty();
  }

  /** Applies every change; on the first failure reverses those already applied and rethrows. */
  public void execute() {
    for (Change change : changes) {
      try {
        change.apply().run();
        applied.add(change);
      } catch (RuntimeException e) {
        undoApplied();
        throw e;
      }
    }
  }

  /**
   * Reverses what has been applied, newest first.
   *
   * @return true if every reversal succeeded (or nothing needed reversing); false means Keycloak
   *     may now differ from the database and a reconciliation record is needed
   */
  public boolean undoApplied() {
    boolean clean = true;
    for (int i = applied.size() - 1; i >= 0; i--) {
      Change change = applied.get(i);
      try {
        change.undo().run();
      } catch (RuntimeException e) {
        clean = false;
        log.error("Could not reverse Keycloak change '{}'", change.description(), e);
      }
    }
    applied.clear();
    undoFailed = undoFailed || !clean;
    return clean;
  }

  private boolean undoFailed;

  /** True if any reversal ever failed — Keycloak and the database may disagree. */
  public boolean hasUnreversedChanges() {
    return undoFailed;
  }
}
