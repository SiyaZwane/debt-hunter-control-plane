package com.debthunter.controlplane.ai.guard;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A {@link DispatchLog} held in memory, for tests and for wiring before a durable log exists. */
public final class InMemoryDispatchLog implements DispatchLog {

  private final List<DispatchRecord> records = new CopyOnWriteArrayList<>();

  @Override
  public void record(DispatchRecord record) {
    records.add(record);
  }

  /** Everything recorded, oldest first. */
  public List<DispatchRecord> records() {
    return List.copyOf(records);
  }
}
