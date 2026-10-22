package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConstraintSettings {
    private PartitionSizeExpectation partitionSizeExpectation;
    private QueryVolume queryVolume;
    private Boolean multiTenant;
    private String tenantField;
    private Boolean timeSeries;
    private String timeOrderingField;
    private Integer ttlSeconds;
    private Integer retentionDays;
    private Integer expectedPartitionSizeMb;
    private String keyspace;

    public PartitionSizeExpectation getPartitionSizeExpectation() {
        return partitionSizeExpectation;
    }

    public void setPartitionSizeExpectation(PartitionSizeExpectation partitionSizeExpectation) {
        this.partitionSizeExpectation = partitionSizeExpectation;
    }

    public QueryVolume getQueryVolume() {
        return queryVolume;
    }

    public void setQueryVolume(QueryVolume queryVolume) {
        this.queryVolume = queryVolume;
    }

    public Boolean getMultiTenant() {
        return multiTenant;
    }

    public void setMultiTenant(Boolean multiTenant) {
        this.multiTenant = multiTenant;
    }

    public String getTenantField() {
        return tenantField;
    }

    public void setTenantField(String tenantField) {
        this.tenantField = tenantField;
    }

    public Boolean getTimeSeries() {
        return timeSeries;
    }

    public void setTimeSeries(Boolean timeSeries) {
        this.timeSeries = timeSeries;
    }

    public String getTimeOrderingField() {
        return timeOrderingField;
    }

    public void setTimeOrderingField(String timeOrderingField) {
        this.timeOrderingField = timeOrderingField;
    }

    public Integer getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Integer ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Integer getExpectedPartitionSizeMb() {
        return expectedPartitionSizeMb;
    }

    public void setExpectedPartitionSizeMb(Integer expectedPartitionSizeMb) {
        this.expectedPartitionSizeMb = expectedPartitionSizeMb;
    }

    public String getKeyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) {
        this.keyspace = keyspace;
    }
}

