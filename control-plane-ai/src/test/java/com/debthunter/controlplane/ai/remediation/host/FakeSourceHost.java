package com.debthunter.controlplane.ai.remediation.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** An in-memory source host that remembers what was done to it and can be made to fail. */
public final class FakeSourceHost implements SourceHostClient {

  /** A pull request on the fake. */
  public record Pr(
      PullRequestRef ref,
      String repository,
      String head,
      String base,
      String title,
      String body,
      List<String> labels) {}

  public final Map<String, String> branches = new HashMap<>();
  public final Set<String> protectedBranches = new HashSet<>();
  public final Map<String, String> files = new HashMap<>();
  public final List<Pr> pullRequests = new CopyOnWriteArrayList<>();
  public final List<String> calls = new CopyOnWriteArrayList<>();
  public volatile RuntimeException failWith;
  private final AtomicLong numbers = new AtomicLong(1);

  public FakeSourceHost withBranch(String branch, String sha) {
    branches.put(branch, sha);
    return this;
  }

  private void maybeFail() {
    RuntimeException failure = failWith;
    if (failure != null) {
      throw failure;
    }
  }

  @Override
  public Optional<String> branchHead(String repository, String branch) {
    calls.add("branchHead " + branch);
    maybeFail();
    return Optional.ofNullable(branches.get(branch));
  }

  @Override
  public boolean isProtected(String repository, String branch) {
    calls.add("isProtected " + branch);
    maybeFail();
    return protectedBranches.contains(branch);
  }

  @Override
  public void ensureBranchWithFile(
      String repository,
      String branch,
      String baseSha,
      String path,
      String content,
      String commitMessage) {
    calls.add("ensureBranchWithFile " + branch);
    maybeFail();
    branches.putIfAbsent(branch, baseSha);
    files.put(branch + ":" + path, content);
  }

  @Override
  public Optional<PullRequestRef> findOpenPullRequest(String repository, String headBranch) {
    calls.add("findOpenPullRequest " + headBranch);
    maybeFail();
    return pullRequests.stream()
        .filter(p -> p.repository().equals(repository) && p.head().equals(headBranch))
        .map(Pr::ref)
        .findFirst();
  }

  @Override
  public PullRequestRef openDraftPullRequest(
      String repository,
      String headBranch,
      String baseBranch,
      String title,
      String body,
      List<String> labels) {
    calls.add("openDraftPullRequest " + headBranch);
    maybeFail();
    long n = numbers.getAndIncrement();
    PullRequestRef ref = new PullRequestRef("https://host.example/" + repository + "/pull/" + n, n);
    pullRequests.add(
        new Pr(ref, repository, headBranch, baseBranch, title, body, new ArrayList<>(labels)));
    return ref;
  }

  /** Whether anything on the host was written to. */
  public boolean wasWrittenTo() {
    return calls.stream()
        .anyMatch(
            c -> c.startsWith("ensureBranchWithFile") || c.startsWith("openDraftPullRequest"));
  }
}
