package org.openphc.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.GatewaySyncResult;
import org.openphc.cce.admin.exception.SyncFailedException;

class GatewaySyncServiceTest {

  private final GatewaySyncClient client = mock(GatewaySyncClient.class);
  private final GatewaySyncService service = new GatewaySyncService(client);

  private static GatewaySyncResult result(List<String> failed) {
    return new GatewaySyncResult(5, failed.size(), 0, failed, "msg");
  }

  @Test
  void aFullSuccessPasses() {
    when(client.sync()).thenReturn(result(List.of()));

    assertThatCode(() -> service.syncFor("FACILITY_ADMIN")).doesNotThrowAnyException();
  }

  @Test
  void aPartialFailureThatNamesThisRoleIsAFailure() {
    when(client.sync()).thenReturn(result(List.of("OTHER", "FACILITY_ADMIN")));

    assertThatThrownBy(() -> service.syncFor("FACILITY_ADMIN"))
        .isInstanceOf(SyncFailedException.class)
        .hasMessageContaining("FACILITY_ADMIN");
  }

  @Test
  void aPartialFailureOfOnlyOtherRolesDoesNotBlockThisOne() {
    when(client.sync()).thenReturn(result(List.of("OTHER")));

    assertThatCode(() -> service.syncFor("FACILITY_ADMIN")).doesNotThrowAnyException();
  }

  @Test
  void anUnreachableGatewayPropagatesAsAFailure() {
    when(client.sync()).thenThrow(new SyncFailedException("down"));

    assertThatThrownBy(() -> service.syncFor("FACILITY_ADMIN")).isInstanceOf(SyncFailedException.class);
  }

  @Test
  void trySyncSwallowsFailuresAndReportsThem() {
    when(client.sync()).thenThrow(new SyncFailedException("down"));
    assertThat(service.trySync()).isFalse();

    doReturn(result(List.of())).when(client).sync(); // when(...) would re-throw the old stub
    assertThat(service.trySync()).isTrue();
  }
}
