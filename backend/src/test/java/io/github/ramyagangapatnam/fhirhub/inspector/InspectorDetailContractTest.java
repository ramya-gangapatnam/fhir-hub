package io.github.ramyagangapatnam.fhirhub.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessage;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageStatus;
import io.github.ramyagangapatnam.fhirhub.persistence.ValidationErrorRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.ValidationErrorRow;
import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractInspectorWebTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.github.ramyagangapatnam.fhirhub.testsupport.InboundMessageSeed;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * T043 — Contract test for {@code GET /inspector/messages/{messageId}}.
 *
 * <p>Driven from {@code contracts/inspector.openapi.yaml}. Asserts:
 *
 * <ul>
 *   <li>200 happy path: {@code rawHl7} present plus {@code fhirResources.patient} and {@code
 *       fhirResources.encounter} (HAPI-serialized FHIR objects);
 *   <li>failed-message variant: {@code fhirResources} is {@code null} and {@code
 *       validationErrors[]} is populated;
 *   <li>404 on an unknown {@code messageId} with the stable error envelope.
 * </ul>
 *
 * <p>Per Principle IV this was written and observed to fail (404, no handler) before T047.
 */
class InspectorDetailContractTest extends AbstractInspectorWebTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private InboundMessageRepository inboundMessages;
  @Autowired private ValidationErrorRepository validationErrors;

  @Test
  void detailOfPersistedMessageReturnsRawHl7AndBothFhirResources() throws Exception {
    String messageId = ingestGood();
    awaitStatus(messageId, "PERSISTED");

    JsonNode detail = detail(messageId);

    assertThat(detail.path("messageId").asText()).isEqualTo(messageId);
    assertThat(detail.path("status").asText()).isEqualTo("PERSISTED");
    assertThat(detail.path("rawHl7").asText()).isEqualTo(Hl7Fixtures.GOOD);
    assertThat(detail.path("correlationId").asText()).matches("[0-9a-fA-F-]{36}");
    assertThat(detail.path("validationErrors").isArray()).isTrue();
    assertThat(detail.path("validationErrors")).isEmpty();

    JsonNode fhir = detail.path("fhirResources");
    assertThat(fhir.isNull()).isFalse();
    assertThat(fhir.path("patient").path("resourceType").asText()).isEqualTo("Patient");
    assertThat(fhir.path("encounter").path("resourceType").asText()).isEqualTo("Encounter");
    // The Encounter's subject points back at the Patient logical id.
    assertThat(fhir.path("patient").path("id").asText())
        .isEqualTo(
            fhir.path("encounter")
                .path("subject")
                .path("reference")
                .asText()
                .replace("Patient/", ""));
  }

  @Test
  void detailOfFailedMessageReturnsNullFhirResourcesAndValidationErrors() throws Exception {
    InboundMessage failed =
        inboundMessages.save(
            InboundMessageSeed.row(
                Hl7Fixtures.MISSING_PID,
                "ADMIT_SYSTEM",
                "DETAIL_FAIL_1",
                InboundMessageStatus.FAILED,
                OffsetDateTime.now(ZoneOffset.UTC)));

    ValidationErrorRow err = new ValidationErrorRow();
    err.setId(UUID.randomUUID());
    err.setInboundMessageId(failed.getId());
    err.setErrorCode("HL7_PARSE_MISSING_SEGMENT");
    err.setSegmentField("PID");
    err.setSummarySafe("A required HL7 segment was not present.");
    err.setCreatedAtUtc(OffsetDateTime.now(ZoneOffset.UTC));
    validationErrors.save(err);

    JsonNode detail = detail(failed.getId().toString());

    assertThat(detail.path("status").asText()).isEqualTo("FAILED");
    assertThat(detail.path("rawHl7").asText()).isEqualTo(Hl7Fixtures.MISSING_PID);
    assertThat(detail.path("fhirResources").isNull()).isTrue();
    assertThat(detail.path("validationErrors")).hasSize(1);
    assertThat(detail.path("validationErrors").get(0).path("errorCode").asText())
        .isEqualTo("HL7_PARSE_MISSING_SEGMENT");
    assertThat(detail.path("validationErrors").get(0).path("summarySafe").asText()).isNotBlank();
  }

  @Test
  void detailOfUnknownMessageIdReturns404Envelope() {
    String unknown = "00000000-0000-0000-0000-000000000000";
    client
        .get()
        .uri("/inspector/messages/" + unknown)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.error.code")
        .isEqualTo("INSPECTOR_MESSAGE_NOT_FOUND")
        .jsonPath("$.error.correlationId")
        .exists();
  }

  private String ingestGood() throws Exception {
    EntityExchangeResult<byte[]> res =
        client
            .post()
            .uri("/ingest/hl7v2")
            .header(HttpHeaders.CONTENT_TYPE, "application/hl7-v2")
            .bodyValue(Hl7Fixtures.GOOD)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody()
            .returnResult();
    return MAPPER.readTree(res.getResponseBodyContent()).path("messageId").asText();
  }

  private JsonNode detail(String messageId) throws Exception {
    EntityExchangeResult<byte[]> res =
        client
            .get()
            .uri("/inspector/messages/" + messageId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult();
    return MAPPER.readTree(res.getResponseBodyContent());
  }

  private void awaitStatus(String messageId, String wantStatus) throws Exception {
    long deadline = System.currentTimeMillis() + 8_000;
    while (System.currentTimeMillis() < deadline) {
      EntityExchangeResult<byte[]> res =
          client
              .get()
              .uri("/inspector/messages/" + messageId)
              .exchange()
              .expectBody()
              .returnResult();
      if (res.getStatus().value() == 200) {
        JsonNode body = MAPPER.readTree(res.getResponseBodyContent());
        if (wantStatus.equals(body.path("status").asText())) {
          return;
        }
      }
      Thread.sleep(150);
    }
    throw new AssertionError(
        "Message " + messageId + " did not reach status " + wantStatus + " within 8s");
  }
}
