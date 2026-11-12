package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.store.registry.WorkItemRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tracker with real state: items by key, their state and comments. It records every call, and it
 * can be told to be down, to refuse, or to fail after it has done a thing (a worker that dies
 * between creating an item and recording that it did).
 */
public final class FakeTracker implements WorkItemTracker {

  /** An item. */
  public static final class Item {
    public final String id;
    public final String key;
    public final WorkItemRoute route;
    public final WorkItemContent content;
    public volatile WorkItemStatus status = WorkItemStatus.OPEN;
    public final List<String> comments = new CopyOnWriteArrayList<>();
    public final List<WorkItemStatus> history = new CopyOnWriteArrayList<>();

    Item(String id, String key, WorkItemRoute route, WorkItemContent content) {
      this.id = id;
      this.key = key;
      this.route = route;
      this.content = content;
      history.add(WorkItemStatus.OPEN);
    }
  }

  public final Map<String, Item> items = new LinkedHashMap<>();
  public final AtomicInteger creates = new AtomicInteger();
  public final AtomicInteger finds = new AtomicInteger();
  public final AtomicInteger moves = new AtomicInteger();
  public final List<String> calls = new CopyOnWriteArrayList<>();

  /** When true every call fails as if the tracker were down. */
  public volatile boolean down;

  /** When set, the next create succeeds in the tracker and then throws. */
  public volatile boolean failAfterNextCreate;

  /** When set, creating is refused. */
  public volatile boolean refuseCreate;

  /** When set, called inside every create (to widen a race in a test). */
  public volatile Runnable duringCreate = () -> {};

  private final AtomicInteger ids = new AtomicInteger(100);

  private void check() {
    if (down) {
      throw new TrackerUnavailableException("the tracker is down", null);
    }
  }

  @Override
  public Optional<TrackedItem> find(WorkItemRoute route, String key) {
    check();
    finds.incrementAndGet();
    calls.add("find " + key);
    synchronized (this) {
      return Optional.ofNullable(items.get(key)).map(FakeTracker::view);
    }
  }

  @Override
  public TrackedItem create(WorkItemRoute route, WorkItemContent content) {
    check();
    duringCreate.run();
    if (refuseCreate) {
      throw new TrackerRejectedException("the tracker refused the item");
    }
    Item item;
    synchronized (this) {
      item = new Item("T-" + ids.incrementAndGet(), content.key(), route, content);
      items.put(content.key(), item);
      creates.incrementAndGet();
      calls.add("create " + content.key());
    }
    if (failAfterNextCreate) {
      failAfterNextCreate = false;
      throw new IllegalStateException("died after creating the item");
    }
    return view(item);
  }

  @Override
  public void move(WorkItemRoute route, String itemId, WorkItemStatus to, String comment) {
    check();
    Item item = byId(itemId);
    synchronized (this) {
      moves.incrementAndGet();
      calls.add("move " + itemId + " " + to);
      if (item.status != to) {
        item.status = to;
        item.history.add(to);
      }
      item.comments.add(comment);
    }
  }

  public synchronized Item byId(String id) {
    return items.values().stream()
        .filter(i -> i.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new TrackerRejectedException("no such item"));
  }

  public synchronized List<Item> all() {
    return new ArrayList<>(items.values());
  }

  private static TrackedItem view(Item i) {
    return new TrackedItem(i.id, "https://tracker.example/" + i.id, i.status);
  }
}
