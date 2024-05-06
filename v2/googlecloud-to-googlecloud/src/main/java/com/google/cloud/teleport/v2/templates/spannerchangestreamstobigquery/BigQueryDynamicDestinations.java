/*
 * Copyright (C) 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.auto.value.AutoValue;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.schemautils.BigQueryUtils;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.schemautils.SpannerToBigQueryUtils;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters;
import com.google.common.collect.ImmutableSet;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.sdk.io.gcp.bigquery.DynamicDestinations;
import org.apache.beam.sdk.io.gcp.bigquery.TableDestination;
import org.apache.beam.sdk.io.gcp.spanner.SpannerAccessor;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.ValueInSingleWindow;

/**
 * The {@link BigQueryDynamicDestinations} loads into BigQuery tables in a dynamic fashion. The
 * destination table is inferred from the provided {@link TableRow}.
 */
public final class BigQueryDynamicDestinations
    extends DynamicDestinations<TableRow, KV<TableId, TableRow>> {
  private final String bigQueryProject, bigQueryDataset, bigQueryTableTemplate;
  private final Boolean useStorageWriteApi;
  private final ImmutableSet<String> ignoreFields;

  public static BigQueryDynamicDestinations of(
      BigQueryDynamicDestinationsOptions bigQueryDynamicDestinationsOptions) {
    return new BigQueryDynamicDestinations(bigQueryDynamicDestinationsOptions);
  }

  private BigQueryDynamicDestinations(
      BigQueryDynamicDestinationsOptions bigQueryDynamicDestinationsOptions) {
    this.ignoreFields = bigQueryDynamicDestinationsOptions.getIgnoreFields();
    this.bigQueryProject = bigQueryDynamicDestinationsOptions.getBigQueryProject();
    this.bigQueryDataset = bigQueryDynamicDestinationsOptions.getBigQueryDataset();
    this.bigQueryTableTemplate = bigQueryDynamicDestinationsOptions.getBigQueryTableTemplate();
    this.useStorageWriteApi = bigQueryDynamicDestinationsOptions.getUseStorageWriteApi();
  }

  private TableId getTableId(String bigQueryTableTemplate, TableRow tableRow) {
    String bigQueryTableName =
        BigQueryConverters.formatStringTemplate(bigQueryTableTemplate, tableRow);

    return TableId.of(bigQueryProject, bigQueryDataset, bigQueryTableName);
  }

  @Override
  public KV<TableId, TableRow> getDestination(ValueInSingleWindow<TableRow> element) {
    TableRow tableRow = element.getValue();
    return KV.of(getTableId(bigQueryTableTemplate, tableRow), tableRow);
  }

  @Override
  public TableDestination getTable(KV<TableId, TableRow> destination) {
    TableId tableId = getTableId(bigQueryTableTemplate, destination.getValue());
    String tableName =
        String.format("%s:%s.%s", tableId.getProject(), tableId.getDataset(), tableId.getTable());

    return new TableDestination(tableName, "BigQuery changelog table.");
  }

  @Override
  public TableSchema getSchema(KV<TableId, TableRow> destination) {
    TableRow tableRow = destination.getValue();
    // Get List<TableFieldSchema> for both user columns and metadata columns.
    List<TableFieldSchema> fields = getFields(tableRow);
    List<TableFieldSchema> filteredFields = new ArrayList<>();
    for (TableFieldSchema field : fields) {
      if (!ignoreFields.contains(field.getName())) {
        filteredFields.add(field);
      }
    }

    return new TableSchema().setFields(filteredFields);
  }

  // Returns List<TableFieldSchema> for user columns and metadata columns based on the parameter
  // TableRow.
  private List<TableFieldSchema> getFields(TableRow tableRow) {
    // Add all user data fields (excluding metadata fields stored in metadataColumns).
    List<TableFieldSchema> fields =
        SpannerToBigQueryUtils.tableRowColumnsToBigQueryIOFields(tableRow, this.useStorageWriteApi);

    // Add all metadata fields.
    String requiredMode = Field.Mode.REQUIRED.name();
    fields.add(
        new TableFieldSchema()
            .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_MOD_TYPE)
            .setType(StandardSQLTypeName.STRING.name())
            .setMode(requiredMode));
    fields.add(
        new TableFieldSchema()
            .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_TABLE_NAME)
            .setType(StandardSQLTypeName.STRING.name())
            .setMode(requiredMode));
    fields.add(
        new TableFieldSchema()
            .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_SPANNER_COMMIT_TIMESTAMP)
            .setType(StandardSQLTypeName.TIMESTAMP.name())
            .setMode(requiredMode));
    fields.add(
        new TableFieldSchema()
            .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_SERVER_TRANSACTION_ID)
            .setType(StandardSQLTypeName.STRING.name())
            .setMode(requiredMode));
    fields.add(
        new TableFieldSchema()
            .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_RECORD_SEQUENCE)
            .setType(StandardSQLTypeName.STRING.name())
            .setMode(requiredMode));
    fields.add(
        new TableFieldSchema()
            .setName(
                BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_IS_LAST_RECORD_IN_TRANSACTION_IN_PARTITION)
            .setType(StandardSQLTypeName.BOOL.name())
            .setMode(requiredMode));
    fields.add(
        new TableFieldSchema()
            .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_NUMBER_OF_RECORDS_IN_TRANSACTION)
            .setType(StandardSQLTypeName.INT64.name())
            .setMode(requiredMode));
    fields.add(
        new TableFieldSchema()
            .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_NUMBER_OF_PARTITIONS_IN_TRANSACTION)
            .setType(StandardSQLTypeName.INT64.name())
            .setMode(requiredMode));
    if (!useStorageWriteApi) {
      fields.add(
          new TableFieldSchema()
              .setName(BigQueryUtils.BQ_CHANGELOG_FIELD_NAME_BIGQUERY_COMMIT_TIMESTAMP)
              .setType(StandardSQLTypeName.TIMESTAMP.name())
              .setMode(requiredMode));
    }

    return fields;
  }

  /**
   * {@link BigQueryDynamicDestinationsOptions} provides options to initialize {@link
   * BigQueryDynamicDestinations}.
   */
  @AutoValue
  public abstract static class BigQueryDynamicDestinationsOptions implements Serializable {
    public abstract SpannerConfig getSpannerConfig();

    public abstract String getChangeStreamName();

    public abstract ImmutableSet<String> getIgnoreFields();

    public abstract String getBigQueryProject();

    public abstract String getBigQueryDataset();

    public abstract String getBigQueryTableTemplate();

    public abstract Boolean getUseStorageWriteApi();

    static Builder builder() {
      return new AutoValue_BigQueryDynamicDestinations_BigQueryDynamicDestinationsOptions.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setSpannerConfig(SpannerConfig spannerConfig);

      abstract Builder setChangeStreamName(String changeStreamName);

      abstract Builder setIgnoreFields(ImmutableSet<String> ignoreFields);

      abstract Builder setBigQueryProject(String bigQueryProject);

      abstract Builder setBigQueryDataset(String bigQueryDataset);

      abstract Builder setBigQueryTableTemplate(String bigQueryTableTemplate);

      abstract Builder setUseStorageWriteApi(Boolean useStorageWriteApi);

      abstract BigQueryDynamicDestinationsOptions build();
    }
  }

  private static Dialect getDialect(SpannerConfig spannerConfig) {
    DatabaseClient databaseClient = SpannerAccessor.getOrCreate(spannerConfig).getDatabaseClient();
    return databaseClient.getDialect();
  }
}
