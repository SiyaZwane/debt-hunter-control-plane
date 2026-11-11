package com.debthunter.controlplane.governance.publication;

import com.debthunter.controlplane.governance.policy.PolicyIssue;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import java.util.List;

/** What publishing a bundle did. */
public sealed interface PublishOutcome {

  /** Why a publication did not complete. */
  enum Stage {
    /** The bundle failed validation. Nothing was signed or pushed. */
    VALIDATION,
    /** The version is already in the catalogue or the registry holds other content for it. */
    VERSION_CONFLICT,
    /** The key store would not sign. Nothing was pushed. */
    SIGNING,
    /** The registry refused or failed. The catalogue is untouched. */
    REGISTRY,
    /** The artefact is in the registry but the catalogue could not be updated. Safe to retry. */
    CATALOGUE
  }

  /**
   * The bundle is signed, in the registry, and in the catalogue.
   *
   * @param record the catalogue entry
   * @param warnings validation warnings, which did not block publication
   */
  record Published(PolicyBundleRecord record, List<PolicyIssue> warnings)
      implements PublishOutcome {

    /** Copies the warnings. */
    public Published {
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * The bundle was not published.
   *
   * @param stage where it stopped
   * @param message why
   * @param issues the validation errors, when the stage is {@link Stage#VALIDATION}
   * @param artefactInRegistry whether the registry already holds the content, so a retry knows
   */
  record Failed(Stage stage, String message, List<PolicyIssue> issues, boolean artefactInRegistry)
      implements PublishOutcome {

    /** Copies the issues. */
    public Failed {
      issues = List.copyOf(issues);
    }
  }
}
