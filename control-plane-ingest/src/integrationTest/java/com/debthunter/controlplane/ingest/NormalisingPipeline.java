package com.debthunter.controlplane.ingest;

import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.intake.DurableIntakeWorker;
import com.debthunter.controlplane.ingest.intake.DurableRunIntakePublisher;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.NormalisingBundleHandler;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The real intake path, from the queue onward: a bundle published to the durable queue is picked up
 * by the worker, de-duplicated, normalised, and handed to a sink the test can inspect.
 */
final class NormalisingPipeline implements AutoCloseable {

  private final Map<String, NormalisedRunBundle> normalisedByRunId = new ConcurrentHashMap<>();
  private final List<String> quarantinedRunIds = new CopyOnWriteArrayList<>();
  private final DurableRunIntakePublisher publisher;
  private final DurableIntakeWorker worker;

  NormalisingPipeline() throws Exception {
    var queue = IntakeTestQueue.inMemory();
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            new InMemoryRunBundleDeduplicator(100),
            new NormalisingBundleHandler(
                new RunNormaliser(),
                bundle -> normalisedByRunId.put(bundle.scan().run().id(), bundle),
                (envelope, reason) -> quarantinedRunIds.add(envelope.runId())));
    publisher = IntakeTestQueue.publisher(queue);
    worker = IntakeTestQueue.startedWorker(queue, receiver);
  }

  /** Publishes a bundle and waits for the pipeline to finish with it. */
  NormalisedRunBundle deliver(String bundleJson) throws Exception {
    var envelope = IntakeEnvelopes.forBundle(bundleJson);
    publisher.publish(envelope).toCompletableFuture().get(30, TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () ->
                normalisedByRunId.containsKey(envelope.runId())
                    || quarantinedRunIds.contains(envelope.runId()));
    return normalisedByRunId.get(envelope.runId());
  }

  List<String> quarantinedRunIds() {
    return quarantinedRunIds;
  }

  @Override
  public void close() {
    worker.close();
  }
}
