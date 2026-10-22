package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityModelResponse {
    private String entityName;
    private String keyspace;
    private String tableName;
    private List<String> partitionKey = new ArrayList<>();
    private List<ClusteringKeyRecommendation> clusteringKeys = new ArrayList<>();
    private List<IndexRecommendation> indexes = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private Map<String, String> summary = new LinkedHashMap<>();
    private String createTableCql;
    private List<String> indexCql = new ArrayList<>();

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getKeyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) {
        this.keyspace = keyspace;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<String> getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(List<String> partitionKey) {
        this.partitionKey = partitionKey;
    }

    public List<ClusteringKeyRecommendation> getClusteringKeys() {
        return clusteringKeys;
    }

    public void setClusteringKeys(List<ClusteringKeyRecommendation> clusteringKeys) {
        this.clusteringKeys = clusteringKeys;
    }

    public List<IndexRecommendation> getIndexes() {
        return indexes;
    }

    public void setIndexes(List<IndexRecommendation> indexes) {
        this.indexes = indexes;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public Map<String, String> getSummary() {
        return summary;
    }

    public void setSummary(Map<String, String> summary) {
        this.summary = summary;
    }

    public String getCreateTableCql() {
        return createTableCql;
    }

    public void setCreateTableCql(String createTableCql) {
        this.createTableCql = createTableCql;
    }

    public List<String> getIndexCql() {
        return indexCql;
    }

    public void setIndexCql(List<String> indexCql) {
        this.indexCql = indexCql;
    }
}

