package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/** Keeps links in PostgreSQL ({@code work_item_link}). */
public final class JdbcWorkItemLinkRepository implements WorkItemLinkRepository {

  private static final String COLUMNS =
      "repository, project_slice, fingerprint, item_key, tracker, item_id, item_url, status,"
          + " updated_at";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource where connections come from
   */
  public JdbcWorkItemLinkRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public Optional<WorkItemLink> find(String repository, String projectSlice, String fingerprint) {
    return jdbc.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM work_item_link WHERE repository = ? AND project_slice = ? AND fingerprint = ?",
        JdbcWorkItemLinkRepository::map,
        repository,
        projectSlice,
        fingerprint);
  }

  @Override
  public WorkItemLink reserve(
      String repository,
      String projectSlice,
      String fingerprint,
      String itemKey,
      String tracker,
      Instant now) {
    return jdbc.inTransaction(
        session -> {
          session.update(
              "INSERT INTO work_item_link (repository, project_slice, fingerprint, item_key,"
                  + " tracker, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                  + " ON CONFLICT DO NOTHING",
              repository,
              projectSlice,
              fingerprint,
              itemKey,
              tracker,
              Timestamp.from(now));
          return session
              .queryOne(
                  "SELECT "
                      + COLUMNS
                      + " FROM work_item_link WHERE repository = ? AND project_slice = ?"
                      + " AND fingerprint = ?",
                  JdbcWorkItemLinkRepository::map,
                  repository,
                  projectSlice,
                  fingerprint)
              .orElseThrow();
        });
  }

  @Override
  public boolean claim(
      String repository,
      String projectSlice,
      String fingerprint,
      String owner,
      Instant now,
      Instant until) {
    return jdbc.update(
            "UPDATE work_item_link SET claimed_by = ?, claimed_until = ?"
                + " WHERE repository = ? AND project_slice = ? AND fingerprint = ?"
                + " AND (claimed_until IS NULL OR claimed_until <= ?)",
            owner,
            Timestamp.from(until),
            repository,
            projectSlice,
            fingerprint,
            Timestamp.from(now))
        == 1;
  }

  @Override
  public void save(WorkItemLink link) {
    int rows =
        jdbc.update(
            "UPDATE work_item_link SET tracker = ?, item_id = ?, item_url = ?, status = ?,"
                + " updated_at = ? WHERE repository = ? AND project_slice = ?"
                + " AND fingerprint = ?",
            link.tracker(),
            link.itemId(),
            link.itemUrl(),
            link.status() == null ? null : link.status().name(),
            Timestamp.from(link.updatedAt()),
            link.repository(),
            link.projectSlice(),
            link.fingerprint());
    if (rows != 1) {
      throw new IllegalStateException("No link has been reserved for this finding");
    }
  }

  @Override
  public void release(String repository, String projectSlice, String fingerprint, String owner) {
    jdbc.update(
        "UPDATE work_item_link SET claimed_by = NULL, claimed_until = NULL"
            + " WHERE repository = ? AND project_slice = ? AND fingerprint = ? AND claimed_by = ?",
        repository,
        projectSlice,
        fingerprint,
        owner);
  }

  private static WorkItemLink map(java.sql.ResultSet row) throws java.sql.SQLException {
    String status = row.getString("status");
    return new WorkItemLink(
        row.getString("repository"),
        row.getString("project_slice"),
        row.getString("fingerprint"),
        row.getString("item_key"),
        row.getString("tracker"),
        row.getString("item_id"),
        row.getString("item_url"),
        status == null ? null : WorkItemStatus.valueOf(status),
        Jdbc.instant(row, "updated_at"));
  }
}
