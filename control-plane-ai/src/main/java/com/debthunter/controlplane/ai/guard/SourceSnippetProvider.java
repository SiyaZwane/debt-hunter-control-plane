package com.debthunter.controlplane.ai.guard;

import java.util.Optional;

/**
 * Supplies the source code around a finding. The control plane does not hold source, so this is the
 * one place it would come from. The guard asks only after a repository has opted in.
 *
 * <p>Failure contract: it may throw, and the guard then proceeds without source.
 */
public interface SourceSnippetProvider {

  /**
   * The source around a finding.
   *
   * @param repository the repository
   * @param path the file
   * @param startLine the line the finding starts at
   * @return a snippet, or empty if none is available
   */
  Optional<String> snippet(String repository, String path, int startLine);
}
