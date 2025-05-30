/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import static com.codahale.metrics.MetricRegistry.name;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.Metrics;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.security.InvalidKeyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.signal.libsignal.protocol.ServiceId;
import org.signal.libsignal.zkgroup.GenericServerSecretParams;
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse;
import org.signal.libsignal.zkgroup.auth.ServerZkAuthOperations;
import org.signal.libsignal.zkgroup.calllinks.CallLinkAuthCredentialResponse;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.auth.CertificateGenerator;
import org.whispersystems.textsecuregcm.entities.DeliveryCertificate;
import org.whispersystems.textsecuregcm.entities.GroupCredentials;
import org.whispersystems.websocket.auth.ReadOnly;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/certificate")
@Tag(name = "Certificate")
public class CertificateController {

  private final CertificateGenerator certificateGenerator;
  private final ServerZkAuthOperations serverZkAuthOperations;
  private final GenericServerSecretParams genericServerSecretParams;
  private final Clock clock;

  @VisibleForTesting
  public static final Duration MAX_REDEMPTION_DURATION = Duration.ofDays(7);
  private static final String GENERATE_DELIVERY_CERTIFICATE_COUNTER_NAME = name(CertificateGenerator.class, "generateCertificate");
  private static final String INCLUDE_E164_TAG_NAME = "includeE164";

  public CertificateController(
      @Nonnull CertificateGenerator certificateGenerator,
      @Nonnull ServerZkAuthOperations serverZkAuthOperations,
      @Nonnull GenericServerSecretParams genericServerSecretParams,
      @Nonnull Clock clock) {
    this.certificateGenerator = Objects.requireNonNull(certificateGenerator);
    this.serverZkAuthOperations = Objects.requireNonNull(serverZkAuthOperations);
    this.genericServerSecretParams = genericServerSecretParams;
    this.clock = Objects.requireNonNull(clock);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/delivery")
  public DeliveryCertificate getDeliveryCertificate(@ReadOnly @Auth AuthenticatedDevice auth,
      @QueryParam("includeE164") @DefaultValue("true") boolean includeE164)
      throws InvalidKeyException {

    Metrics.counter(GENERATE_DELIVERY_CERTIFICATE_COUNTER_NAME, INCLUDE_E164_TAG_NAME, String.valueOf(includeE164))
        .increment();

    return new DeliveryCertificate(
        certificateGenerator.createFor(auth.getAccount(), auth.getAuthenticatedDevice(), includeE164));
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/auth/group")
  public GroupCredentials getGroupAuthenticationCredentials(
      @ReadOnly @Auth AuthenticatedDevice auth,
      @HeaderParam(HttpHeaders.USER_AGENT) String userAgent,
      @QueryParam("redemptionStartSeconds") long startSeconds,
      @QueryParam("redemptionEndSeconds") long endSeconds) {

    final Instant startOfDay = clock.instant().truncatedTo(ChronoUnit.DAYS);
    final Instant redemptionStart = Instant.ofEpochSecond(startSeconds);
    final Instant redemptionEnd = Instant.ofEpochSecond(endSeconds);

    if (redemptionStart.isAfter(redemptionEnd) ||
        redemptionStart.isBefore(startOfDay) ||
        redemptionEnd.isAfter(startOfDay.plus(MAX_REDEMPTION_DURATION)) ||
        !redemptionStart.equals(redemptionStart.truncatedTo(ChronoUnit.DAYS)) ||
        !redemptionEnd.equals(redemptionEnd.truncatedTo(ChronoUnit.DAYS))) {

      throw new BadRequestException();
    }

    final List<GroupCredentials.GroupCredential> credentials = new ArrayList<>();
    final List<GroupCredentials.CallLinkAuthCredential> callLinkAuthCredentials = new ArrayList<>();

    Instant redemption = redemptionStart;

    ServiceId.Aci aci = new ServiceId.Aci(auth.getAccount().getUuid());
    ServiceId.Pni pni = new ServiceId.Pni(auth.getAccount().getPhoneNumberIdentifier());

    while (!redemption.isAfter(redemptionEnd)) {
      AuthCredentialWithPniResponse authCredentialWithPni = serverZkAuthOperations.issueAuthCredentialWithPniZkc(aci, pni, redemption);
      credentials.add(new GroupCredentials.GroupCredential(
          authCredentialWithPni.serialize(),
          (int) redemption.getEpochSecond()));

      callLinkAuthCredentials.add(new GroupCredentials.CallLinkAuthCredential(
          CallLinkAuthCredentialResponse.issueCredential(aci, redemption, genericServerSecretParams).serialize(),
          redemption.getEpochSecond()));

      redemption = redemption.plus(Duration.ofDays(1));
    }

    return new GroupCredentials(credentials, callLinkAuthCredentials, pni.getRawUUID());
  }
}
