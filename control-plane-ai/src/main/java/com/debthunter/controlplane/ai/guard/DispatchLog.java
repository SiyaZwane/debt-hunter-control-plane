package com.debthunter.controlplane.ai.guard;

/**
 * The record of every dispatch to a model.
 *
 * <p>Failure contract: if {@code record} throws, the dispatch does not happen. Nothing is sent that
 * has not been recorded.
 */
public interface DispatchLog {

  /**
   * Records a dispatch about to happen.
   *
   * @param record what will be sent
   */
  void record(DispatchRecord record);
}
