package com.debthunter.controlplane.ingest.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Converts an {@link IntakeEnvelope} to and from the JSON carried on the intake queue. */
public final class IntakeEnvelopeCodec {

  private final ObjectMapper mapper =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /**
   * Serialises an envelope.
   *
   * @param envelope the envelope to encode
   * @return its JSON text
   */
  public String encode(IntakeEnvelope envelope) {
    try {
      return mapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not encode an intake envelope", e);
    }
  }

  /**
   * Deserialises an envelope.
   *
   * @param json the JSON text taken from the queue
   * @return the envelope
   * @throws UndecodableEnvelopeException if the text is not a well-formed envelope
   */
  public IntakeEnvelope decode(String json) {
    try {
      return mapper.readValue(json, IntakeEnvelope.class);
    } catch (JsonProcessingException | RuntimeException e) {
      throw new UndecodableEnvelopeException("Not a well-formed intake envelope", e);
    }
  }

  /** A queue record that cannot be turned back into an envelope, and never will be. */
  public static final class UndecodableEnvelopeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UndecodableEnvelopeException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
