package com.yugabyte.metadata.intake.modeler;


import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class YugabyteModelerService {

    private static final String DEFAULT_KEYSPACE = "odp_modeler";

    public ModelingResponse generateModels(ModelingRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getEntities())) {
            throw new IllegalArgumentException("At least one entity is required to generate a data model.");
        }

        List<EntityModelResponse> responses = request.getEntities().stream()
                .map(this::modelEntity)
                .collect(Collectors.toList());

        ModelingResponse response = new ModelingResponse();
        response.setEntities(responses);
        return response;
    }

    private EntityModelResponse modelEntity(EntityModelRequest entity) {
        validateEntity(entity);
        sanitizeEntity(entity);

        Map<String, FieldMetadata> fieldLookup = entity.getFields().stream()
                .collect(Collectors.toMap(
                        f -> normalize(f.getName()),
                        Function.identity(),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        EntityModelResponse response = new EntityModelResponse();
        response.setEntityName(entity.getEntityName());

        String keyspace = determineKeyspace(entity);
        response.setKeyspace(keyspace);
        response.setTableName(sanitizeIdentifier(entity.getEntityName()));

        List<String> warnings = new ArrayList<>();
        Map<String, String> summary = new LinkedHashMap<>();

        List<String> partitionKey = determinePartitionKey(entity, fieldLookup, warnings, summary);
        response.setPartitionKey(partitionKey);

        List<ClusteringKeyRecommendation> clusteringKeys = determineClusteringKeys(entity, fieldLookup, partitionKey, warnings, summary);
        response.setClusteringKeys(clusteringKeys);

        List<IndexRecommendation> indexRecommendations = determineIndexes(entity, fieldLookup, partitionKey, clusteringKeys, warnings, summary);
        response.setIndexes(indexRecommendations);

        applyConstraintWarnings(entity.getConstraints(), partitionKey, warnings);

        response.setWarnings(warnings);
        response.setSummary(summary);

        String createTableDdl = buildCreateTableDdl(entity, fieldLookup, partitionKey, clusteringKeys);
        response.setCreateTableCql(createTableDdl);

        List<String> indexDdl = buildIndexStatements(response, indexRecommendations);
        response.setIndexCql(indexDdl);

        return response;
    }

    private void applyConstraintWarnings(ConstraintSettings constraints,
                                         List<String> partitionKey,
                                         List<String> warnings) {
        if (constraints == null) {
            return;
        }

        if (constraints.getExpectedPartitionSizeMb() != null && constraints.getExpectedPartitionSizeMb() > 100) {
            warnings.add("Expected partition size exceeds 100MB. Consider adding bucketing to partition key.");
        }

        if (constraints.getPartitionSizeExpectation() == PartitionSizeExpectation.VERY_LARGE && partitionKey.size() == 1) {
            warnings.add("Very large partitions expected but partition key has a single column. Recommend composite PK with bucketing.");
        }
    }

    private void validateEntity(EntityModelRequest entity) {
        if (!StringUtils.hasText(entity.getEntityName())) {
            throw new IllegalArgumentException("Entity name is required.");
        }
        if (CollectionUtils.isEmpty(entity.getFields())) {
            throw new IllegalArgumentException("CSV fields are required for " + entity.getEntityName());
        }
        if (CollectionUtils.isEmpty(entity.getAccessPatterns())) {
            throw new IllegalArgumentException("At least one access pattern is required for " + entity.getEntityName());
        }
        boolean hasAnyFilter = entity.getAccessPatterns().stream()
                .anyMatch(pattern -> pattern != null && !CollectionUtils.isEmpty(pattern.getFilters()));
        if (!hasAnyFilter) {
            throw new IllegalArgumentException("At least one access pattern with filters is required for " + entity.getEntityName());
        }
    }

    private String determineKeyspace(EntityModelRequest entity) {
        if (entity.getConstraints() != null && StringUtils.hasText(entity.getConstraints().getKeyspace())) {
            return sanitizeIdentifier(entity.getConstraints().getKeyspace());
        }
        if (StringUtils.hasText(entity.getKeyspace())) {
            return sanitizeIdentifier(entity.getKeyspace());
        }
        return DEFAULT_KEYSPACE;
    }

    private List<String> determinePartitionKey(EntityModelRequest entity,
                                              Map<String, FieldMetadata> fieldLookup,
                                              List<String> warnings,
                                              Map<String, String> summary) {

        List<String> partitionKey = new ArrayList<>();
        ConstraintSettings constraints = entity.getConstraints();
        List<AccessPattern> allPatterns = entity.getAccessPatterns();

        if (constraints != null && Boolean.TRUE.equals(constraints.getMultiTenant())) {
            FieldMetadata tenantField = resolveTenantField(constraints, fieldLookup);
            if (tenantField != null) {
                partitionKey.add(tenantField.getName());
                summary.put("Multi-tenant", "Included " + tenantField.getName() + " as leading partition key to isolate tenants.");
            } else {
                warnings.add("Multi-tenant flag set but no tenant field provided; tenant isolation not guaranteed.");
            }
        }

        // Analyze all patterns to find the best partition key candidate
        // Count frequency of each field across all patterns in equality/IN filters
        // PK selection is based purely on frequency - most frequently used field becomes PK
        Map<String, Integer> fieldFrequency = new HashMap<>();
        
        System.out.println("=== PK DETERMINATION DEBUG ===");
        System.out.println("Total patterns: " + (allPatterns != null ? allPatterns.size() : 0));
        System.out.println("FieldLookup keys: " + fieldLookup.keySet());
        
        for (int i = 0; i < allPatterns.size(); i++) {
            AccessPattern pattern = allPatterns.get(i);
            System.out.println("\n--- Processing Pattern " + (i + 1) + " ---");
            System.out.println("  Pattern name: " + (pattern != null ? pattern.getName() : "null"));
            System.out.println("  Pattern filters: " + (pattern != null && pattern.getFilters() != null ? pattern.getFilters().size() : 0));
            
            if (pattern == null || CollectionUtils.isEmpty(pattern.getFilters())) {
                System.out.println("  SKIPPED: Pattern is null or has no filters");
                continue;
            }
            
            for (int j = 0; j < pattern.getFilters().size(); j++) {
                PatternField filter = pattern.getFilters().get(j);
                System.out.println("\n  Filter " + (j + 1) + ":");
                System.out.println("    Filter object: " + (filter != null ? "exists" : "null"));
                
                if (filter == null) {
                    System.out.println("    SKIPPED: Filter is null");
                    continue;
                }
                
                FilterType filterType = filter.getType();
                System.out.println("    Filter type: " + filterType);
                
                if (filterType == null || (filterType != FilterType.EQUALITY && filterType != FilterType.IN)) {
                    System.out.println("    SKIPPED: Filter type is not EQUALITY or IN");
                    continue;
                }
                
                String fieldName = filter.getField();
                System.out.println("    Field name from filter: '" + fieldName + "'");
                
                if (!StringUtils.hasText(fieldName)) {
                    System.out.println("    SKIPPED: Field name is empty");
                    continue;
                }
                
                String normalizedFilterField = normalize(fieldName);
                System.out.println("    Normalized field name: '" + normalizedFilterField + "'");
                
                FieldMetadata field = fieldLookup.get(normalizedFilterField);
                System.out.println("    Field lookup result: " + (field != null ? "FOUND - " + field.getName() : "NOT FOUND"));
                
                if (field == null) {
                    System.out.println("    SKIPPED: Field not found in fieldLookup");
                    continue;
                }
                
                if (field.isTimeField()) {
                    System.out.println("    SKIPPED: Field is a time field");
                    continue;
                }
                
                int currentFreq = fieldFrequency.getOrDefault(normalizedFilterField, 0);
                int newFreq = currentFreq + 1;
                fieldFrequency.put(normalizedFilterField, newFreq);
                System.out.println("    ✓ COUNTED: " + normalizedFilterField + " (frequency: " + currentFreq + " -> " + newFreq + ")");
            }
        }
        
        System.out.println("\n=== FINAL FREQUENCY MAP ===");
        fieldFrequency.forEach((key, value) -> System.out.println("  " + key + ": " + value));
        System.out.println("===========================\n");

        // Find best candidate: prioritize frequency only (access pattern driven)
        // Cardinality will be used for warnings/recommendations only
        System.out.println("=== CANDIDATE SELECTION ===");
        System.out.println("Frequency map keys for candidate lookup: " + fieldFrequency.keySet());
        
        List<FieldMetadata> candidates = fieldFrequency.keySet().stream()
                .map(f -> {
                    FieldMetadata field = fieldLookup.get(f);
                    System.out.println("  Looking up key '" + f + "' -> " + (field != null ? field.getName() : "null"));
                    return field;
                })
                .filter(Objects::nonNull)
                .filter(f -> {
                    boolean isTime = f.isTimeField();
                    System.out.println("  Field " + f.getName() + " isTimeField: " + isTime);
                    return !isTime;
                })
                .peek(f -> {
                    String normalizedName = normalize(f.getName());
                    int freq = fieldFrequency.getOrDefault(normalizedName, 0);
                    System.out.println("  Candidate: " + f.getName() + " (normalized: " + normalizedName + ", frequency: " + freq + ", businessKey: " + f.isBusinessKey() + ")");
                })
                .sorted((f1, f2) -> {
                    // Use normalized name to match frequency map keys
                    String normalizedName1 = normalize(f1.getName());
                    String normalizedName2 = normalize(f2.getName());
                    int freq1 = fieldFrequency.getOrDefault(normalizedName1, 0);
                    int freq2 = fieldFrequency.getOrDefault(normalizedName2, 0);
                    System.out.println("    Comparing: " + f1.getName() + " (freq: " + freq1 + ") vs " + f2.getName() + " (freq: " + freq2 + ")");
                    
                    // Compare by frequency (descending - higher frequency first)
                    int freqCompare = Integer.compare(freq2, freq1); // reversed: freq2 - freq1
                    if (freqCompare != 0) {
                        System.out.println("      -> Frequency difference: " + freqCompare);
                        return freqCompare;
                    }
                    
                    // Tie-breaker: business key (true comes first)
                    boolean bk1 = f1.isBusinessKey();
                    boolean bk2 = f2.isBusinessKey();
                    int bkCompare = Boolean.compare(bk2, bk1); // reversed: true comes before false
                    System.out.println("      -> Business key tie-breaker: " + bkCompare);
                    return bkCompare;
                })
                .collect(Collectors.toList());

        System.out.println("\n=== SORTED CANDIDATES ===");
        for (int i = 0; i < candidates.size(); i++) {
            FieldMetadata c = candidates.get(i);
            String normalizedName = normalize(c.getName());
            int freq = fieldFrequency.getOrDefault(normalizedName, 0);
            System.out.println("  " + (i + 1) + ". " + c.getName() + " (frequency: " + freq + ", businessKey: " + c.isBusinessKey() + ")");
        }

        for (FieldMetadata candidate : candidates) {
            if (!containsFieldIgnoreCase(partitionKey, candidate.getName())) {
                partitionKey.add(candidate.getName());
                System.out.println("\n✓ SELECTED PK: " + candidate.getName());
                break;
            }
        }
        System.out.println("===========================\n");

        // Fallback: use first non-time field from entity if no pattern-based candidate found
        if (partitionKey.isEmpty()) {
            Optional<FieldMetadata> firstField = entity.getFields().stream()
                    .filter(f -> !f.isTimeField())
                    .findFirst();
            firstField.ifPresent(field -> {
                partitionKey.add(field.getName());
                warnings.add("No fields found in access patterns; using first non-time field as partition key. Recommendation: Review access patterns to ensure partition key selection.");
            });
        }

        // Last resort: use first field
        if (partitionKey.isEmpty()) {
            partitionKey.add(entity.getFields().get(0).getName());
            warnings.add("No ideal partition key found; defaulted to first CSV field.");
        }

        // Validate and add recommendations based on cardinality (not used for selection)
        FieldMetadata firstPkField = fieldLookup.get(normalize(partitionKey.get(0)));
        if (firstPkField != null) {
            Cardinality pkCardinality = firstPkField.getCardinality();
            int pkFrequency = fieldFrequency.getOrDefault(normalize(partitionKey.get(0)), 0);
            
            if (pkCardinality == Cardinality.LOW) {
                warnings.add("Partition key " + firstPkField.getName() + " has LOW cardinality. Recommendation: Consider adding bucketing or using a composite partition key to improve data distribution.");
            } else if (pkCardinality == Cardinality.UNKNOWN) {
                warnings.add("Partition key " + firstPkField.getName() + " cardinality is UNKNOWN. Recommendation: Verify this field has sufficient cardinality (1000+ unique values) for even data distribution.");
            }
            
            if (pkFrequency == 1 && fieldFrequency.size() > 1) {
                warnings.add("Partition key " + firstPkField.getName() + " appears in only 1 access pattern. Recommendation: Consider if a more frequently used field would be a better partition key.");
            }
        }

        String pkSummary = String.join(", ", partitionKey) + " selected based on access pattern frequency (" + 
                          (firstPkField != null ? fieldFrequency.getOrDefault(normalize(firstPkField.getName()), 0) : 0) + 
                          " occurrences across patterns).";
        summary.put("Partition Key", pkSummary);

        return partitionKey;
    }

    private List<ClusteringKeyRecommendation> determineClusteringKeys(EntityModelRequest entity,
                                                                      Map<String, FieldMetadata> fieldLookup,
                                                                      List<String> partitionKey,
                                                                      List<String> warnings,
                                                                      Map<String, String> summary) {
        List<ClusteringKeyRecommendation> clusteringKeys = new ArrayList<>();
        List<AccessPattern> allPatterns = entity.getAccessPatterns();
        ConstraintSettings constraints = entity.getConstraints();
        Set<String> pkSet = partitionKey.stream().map(this::normalize).collect(Collectors.toSet());
        Set<String> ckSet = new LinkedHashSet<>();

        System.out.println("=== CLUSTERING KEY DETERMINATION ===");
        System.out.println("Partition Key: " + partitionKey);
        System.out.println("PK Set (normalized): " + pkSet);

        // CRITICAL: Clustering keys must be used sequentially - cannot skip a CK in queries
        // Strategy: Build CK list based on patterns WITH the PK, maintaining proper order:
        // 1. Equality filters first (from patterns with PK)
        // 2. Range filters second (from patterns with PK)
        // 3. Sort fields last (from patterns with PK)
        // 
        // IMPORTANT: If a pattern has PK + field X, and another pattern has PK + field Y,
        // we need to determine if they can coexist or if one pattern must include both fields.
        
        // Build a map of patterns with PK and their fields (excluding PK)
        Map<String, Set<String>> patternFields = new LinkedHashMap<>(); // pattern -> set of fields (excluding PK)
        
        for (AccessPattern pattern : allPatterns) {
            if (pattern == null) continue;
            
            // Check if this pattern contains the partition key
            boolean patternHasPk = false;
            Set<String> patternFieldSet = new LinkedHashSet<>();
            
            if (pattern.getFilters() != null) {
                for (PatternField filter : pattern.getFilters()) {
                    if (filter == null || filter.getField() == null) continue;
                    String fieldName = normalize(filter.getField());
                    if (pkSet.contains(fieldName)) {
                        patternHasPk = true;
                    } else {
                        patternFieldSet.add(fieldName);
                    }
                }
            }
            
            if (patternHasPk) {
                String patternKey = pattern.getName() != null ? pattern.getName() : "pattern_" + pattern.hashCode();
                patternFields.put(patternKey, patternFieldSet);
                System.out.println("Pattern with PK: " + patternKey + " -> fields: " + patternFieldSet);
            }
        }
        
        // Determine clustering keys based on pattern analysis
        // Rule: CK order must support all patterns that use the PK
        // If Pattern A has (PK, X) and Pattern B has (PK, Y), we need to decide:
        // - If Pattern B can work with (PK, X, Y) -> add both in order
        // - If Pattern B must work with just (PK, Y) -> conflict, need to prioritize
        
        // For now, we'll use a simple strategy:
        // 1. Collect all equality filters from patterns with PK (in order of pattern appearance)
        // 2. Collect all range filters from patterns with PK
        // 3. Collect all sort fields from patterns with PK
        
        // Step 1: Collect equality filters from patterns that CONTAIN the partition key
        // CRITICAL: If a field is used for ORDER BY in the same pattern, it MUST be a CK
        // and we cannot insert other fields between it and earlier CKs if those fields aren't in that pattern
        for (AccessPattern pattern : allPatterns) {
            if (pattern == null || CollectionUtils.isEmpty(pattern.getFilters())) continue;
            
            // Check if this pattern contains the partition key
            boolean patternHasPk = pattern.getFilters().stream()
                    .filter(f -> f != null && f.getField() != null)
                    .anyMatch(f -> pkSet.contains(normalize(f.getField())));
            
            System.out.println("\nPattern: " + (pattern.getName() != null ? pattern.getName() : "unnamed"));
            System.out.println("  Contains PK: " + patternHasPk);
            
            if (!patternHasPk) {
                System.out.println("  SKIPPED: Pattern does not contain partition key - fields will be indexes");
                continue; // Skip patterns without PK - those fields become indexes
            }
            
            // Check if this pattern has any sort fields (ORDER BY)
            // If a field is used for ORDER BY, it MUST be a clustering key
            Set<String> sortFieldsInPattern = new HashSet<>();
            if (pattern.getSortFields() != null) {
                for (SortField sortField : pattern.getSortFields()) {
                    if (sortField != null && sortField.getField() != null) {
                        sortFieldsInPattern.add(normalize(sortField.getField()));
                    }
                }
            }
            
            // Process equality filters from patterns WITH the PK
            for (PatternField filter : pattern.getFilters()) {
                if (filter == null) continue;
                FilterType filterType = filter.getType();
                if (filterType == null || (filterType != FilterType.EQUALITY && filterType != FilterType.IN)) {
                    continue;
                }
                FieldMetadata metadata = fieldLookup.get(normalize(filter.getField()));
                if (metadata == null || pkSet.contains(normalize(metadata.getName()))) {
                    continue;
                }
                
                String normalizedFieldName = normalize(metadata.getName());
                
                // If this field is used for ORDER BY in this pattern, it MUST be a CK
                // and we should add it immediately (don't add other fields that would come between)
                boolean isSortField = sortFieldsInPattern.contains(normalizedFieldName);
                
                if (isSortField) {
                    // This field is used for ORDER BY - it MUST be a CK
                    // Don't add it here, let Step 3 handle it with proper ordering
                    System.out.println("  ⏭ DEFERRED (equality+sort): " + metadata.getName() + " - will be added in sort step");
                    continue;
                }
                
                // Check if adding this field would break a pattern that has ORDER BY
                // If there's a pattern with ORDER BY on a field that comes after this one,
                // and that pattern doesn't include this field, we cannot add this field as a CK
                boolean wouldBreakSortPattern = false;
                for (AccessPattern otherPattern : allPatterns) {
                    if (otherPattern == null || otherPattern == pattern) continue;
                    if (CollectionUtils.isEmpty(otherPattern.getSortFields())) continue;
                    
                    // Check if otherPattern has PK and uses ORDER BY
                    boolean otherPatternHasPk = otherPattern.getFilters() != null && 
                            otherPattern.getFilters().stream()
                                    .filter(f -> f != null && f.getField() != null)
                                    .anyMatch(f -> pkSet.contains(normalize(f.getField())));
                    
                    if (!otherPatternHasPk) continue;
                    
                    // Check if otherPattern has ORDER BY on a field
                    Set<String> otherPatternFilterFields = new HashSet<>();
                    if (otherPattern.getFilters() != null) {
                        for (PatternField pf : otherPattern.getFilters()) {
                            if (pf != null && pf.getField() != null) {
                                otherPatternFilterFields.add(normalize(pf.getField()));
                            }
                        }
                    }
                    
                    for (SortField sortField : otherPattern.getSortFields()) {
                        if (sortField == null || sortField.getField() == null) continue;
                        String sortFieldName = normalize(sortField.getField());
                        
                        // If otherPattern has ORDER BY on a field, and this pattern's field would come before it,
                        // and otherPattern doesn't include this field, we cannot add this field as a CK
                        if (!otherPatternFilterFields.contains(normalizedFieldName) && 
                            !otherPatternFilterFields.contains(sortFieldName)) {
                            // This is a potential conflict - the sort field would need this field before it
                            // but the pattern with ORDER BY doesn't include this field
                            // Actually, we need to check if the sort field comes after this field in the pattern
                            // For now, let's be conservative: if a pattern has ORDER BY and doesn't include this field,
                            // and this field would come before the sort field, we skip it
                            wouldBreakSortPattern = true;
                            System.out.println("  ⚠ SKIPPED (would break sort pattern): " + metadata.getName());
                            break;
                        }
                    }
                    if (wouldBreakSortPattern) break;
                }
                
                if (!wouldBreakSortPattern && ckSet.add(normalizedFieldName)) {
                    System.out.println("  ✓ CK (equality): " + metadata.getName());
                    clusteringKeys.add(new ClusteringKeyRecommendation(
                            metadata.getName(),
                            "ASC",
                            ClusteringKeyType.EQUALITY,
                            "Supports equality filter from access pattern with partition key."
                    ));
                }
            }
        }

        // Step 2: Collect range filters from patterns that CONTAIN the partition key
        // CRITICAL: Range filters can only be CKs if the pattern includes at least the first CK
        // If we have CKs already (e.g., account_number), the pattern must include them to add a range CK
        for (AccessPattern pattern : allPatterns) {
            if (pattern == null || CollectionUtils.isEmpty(pattern.getFilters())) continue;
            
            // Check if this pattern contains the partition key
            boolean patternHasPk = false;
            Set<String> patternFilterFields = new HashSet<>();
            for (PatternField filter : pattern.getFilters()) {
                if (filter != null && filter.getField() != null) {
                    String fieldName = normalize(filter.getField());
                    patternFilterFields.add(fieldName);
                    if (pkSet.contains(fieldName)) {
                        patternHasPk = true;
                    }
                }
            }
            
            if (!patternHasPk) continue; // Skip patterns without PK
            
            // Check if pattern includes at least the first CK (if any CKs exist)
            // Range filters require sequential CK usage, so we need at least the first CK
            boolean canAddRangeCk = ckSet.isEmpty() || 
                    (clusteringKeys.size() > 0 && patternFilterFields.contains(normalize(clusteringKeys.get(0).getField())));
            
            System.out.println("\nPattern (range): " + (pattern.getName() != null ? pattern.getName() : "unnamed"));
            System.out.println("  Pattern filter fields: " + patternFilterFields);
            System.out.println("  Existing CKs: " + ckSet);
            System.out.println("  Can add range CK: " + canAddRangeCk);
            
            for (PatternField filter : pattern.getFilters()) {
                if (filter == null) continue;
                FilterType filterType = filter.getType();
                if (filterType == null || filterType != FilterType.RANGE) {
                    continue;
                }
                FieldMetadata metadata = fieldLookup.get(normalize(filter.getField()));
                if (metadata != null && !pkSet.contains(normalize(metadata.getName()))) {
                    if (canAddRangeCk && ckSet.add(normalize(metadata.getName()))) {
                        System.out.println("  ✓ CK (range): " + metadata.getName());
                        clusteringKeys.add(new ClusteringKeyRecommendation(
                                metadata.getName(),
                                metadata.isTimeField() ? "DESC" : "ASC",
                                ClusteringKeyType.RANGE,
                                "Supports range filtering from access pattern with partition key."
                        ));
                    } else {
                        System.out.println("  ⚠ SKIPPED: " + metadata.getName() + " cannot be CK (pattern doesn't include first CK)");
                        // This field will be handled by index determination
                    }
                }
            }
        }

        // Step 3: Collect sort fields from patterns that CONTAIN the partition key
        // CRITICAL: When a field is used for ORDER BY, it MUST be a clustering key
        // CRITICAL: We cannot insert other clustering keys between fields that are used together in a pattern
        // if those intermediate fields are not filtered in that pattern
        for (AccessPattern pattern : allPatterns) {
            if (pattern == null || CollectionUtils.isEmpty(pattern.getSortFields())) continue;
            
            // Check if this pattern contains the partition key (in filters)
            boolean patternHasPk = false;
            Set<String> patternFilterFields = new HashSet<>();
            if (pattern.getFilters() != null) {
                for (PatternField filter : pattern.getFilters()) {
                    if (filter != null && filter.getField() != null) {
                        String fieldName = normalize(filter.getField());
                        patternFilterFields.add(fieldName);
                        if (pkSet.contains(fieldName)) {
                            patternHasPk = true;
                        }
                    }
                }
            }
            
            if (!patternHasPk) continue; // Skip patterns without PK
            
            // Check if pattern includes at least the first CK (if any CKs exist)
            // For sort fields, we can skip intermediate CKs when only sorting (ORDER BY)
            // But we must have at least the first CK to maintain sequential access
            boolean hasFirstCk = ckSet.isEmpty() || 
                    (clusteringKeys.size() > 0 && patternFilterFields.contains(normalize(clusteringKeys.get(0).getField())));
            
            System.out.println("\nPattern (sort): " + (pattern.getName() != null ? pattern.getName() : "unnamed"));
            System.out.println("  Pattern filter fields: " + patternFilterFields);
            System.out.println("  Existing CKs: " + ckSet);
            System.out.println("  Has first CK: " + hasFirstCk);
            
            for (SortField sortField : pattern.getSortFields()) {
                FieldMetadata metadata = fieldLookup.get(normalize(sortField.getField()));
                if (metadata == null) {
                    continue;
                }
                String normalizedSortField = normalize(metadata.getName());
                
                // Only add sort field as CK if:
                // 1. It's not already in PK
                // 2. It's not already a CK
                // 3. Either there are no existing CKs, OR this pattern includes at least the first CK
                //    (We can skip intermediate CKs when only sorting)
                if (!pkSet.contains(normalizedSortField) && !ckSet.contains(normalizedSortField)) {
                    if (hasFirstCk) {
                        // CRITICAL: If this sort field is also an equality filter in this pattern,
                        // it MUST be added as a CK immediately after the fields it's used with
                        // We cannot have other CKs between this field and earlier CKs if those
                        // intermediate fields are not in this pattern
                        
                        // Check if this sort field is also an equality filter in this pattern
                        boolean isAlsoEqualityFilter = patternFilterFields.contains(normalizedSortField);
                        
                        if (isAlsoEqualityFilter) {
                            // This field is both equality filter AND sort field - it MUST be a CK
                            // and must come immediately after the fields it's used with
                            // Remove any CKs that come after the first CK but before this field
                            // if those CKs are not in this pattern
                            
                            // Find the position where this field should be inserted
                            // It should come after all fields in patternFilterFields that are already CKs
                            int insertPosition = clusteringKeys.size();
                            for (int i = 0; i < clusteringKeys.size(); i++) {
                                String existingCkField = normalize(clusteringKeys.get(i).getField());
                                if (patternFilterFields.contains(existingCkField)) {
                                    insertPosition = i + 1;
                                } else if (!patternFilterFields.contains(normalizedSortField)) {
                                    // If there's a CK that's not in this pattern, and it comes before
                                    // the sort field, we need to remove it (it will become an index)
                                    // Actually, we should check if removing it would break other patterns
                                    // For now, let's be conservative and just insert after the last matching CK
                                }
                            }
                            
                            // Remove any CKs between the last matching CK and this field
                            // that are not in this pattern
                            List<ClusteringKeyRecommendation> cksToRemove = new ArrayList<>();
                            for (int i = insertPosition; i < clusteringKeys.size(); i++) {
                                String existingCkField = normalize(clusteringKeys.get(i).getField());
                                if (!patternFilterFields.contains(existingCkField)) {
                                    cksToRemove.add(clusteringKeys.get(i));
                                    ckSet.remove(existingCkField);
                                    System.out.println("  ⚠ REMOVING CK (not in sort pattern): " + existingCkField);
                                }
                            }
                            clusteringKeys.removeAll(cksToRemove);
                            
                            if (ckSet.add(normalizedSortField)) {
                                String direction = StringUtils.hasText(sortField.getDirection())
                                        ? sortField.getDirection().trim().toUpperCase()
                                        : metadata.isTimeField() ? "DESC" : "ASC";
                                System.out.println("  ✓ CK (equality+sort): " + metadata.getName() + " " + direction);
                                clusteringKeys.add(insertPosition, new ClusteringKeyRecommendation(
                                        metadata.getName(),
                                        direction,
                                        ClusteringKeyType.ORDERING,
                                        "Required as clustering key (equality filter + ORDER BY) from access pattern with partition key."
                                ));
                            }
                        } else {
                            // This is just a sort field (not equality filter)
                            if (ckSet.add(normalizedSortField)) {
                                String direction = StringUtils.hasText(sortField.getDirection())
                                        ? sortField.getDirection().trim().toUpperCase()
                                        : metadata.isTimeField() ? "DESC" : "ASC";
                                System.out.println("  ✓ CK (sort): " + metadata.getName() + " " + direction);
                                clusteringKeys.add(new ClusteringKeyRecommendation(
                                        metadata.getName(),
                                        direction,
                                        ClusteringKeyType.ORDERING,
                                        "Preserves requested ordering from access pattern with partition key."
                                ));
                            }
                        }
                    } else {
                        System.out.println("  ⚠ WARNING: Cannot add " + metadata.getName() + " as CK");
                        System.out.println("    Reason: Pattern doesn't include first CK: " + 
                                          (clusteringKeys.size() > 0 ? clusteringKeys.get(0).getField() : "none"));
                        System.out.println("    Pattern has: " + patternFilterFields);
                        warnings.add("Pattern '" + (pattern.getName() != null ? pattern.getName() : "unnamed") + 
                                   "' uses sort field " + metadata.getName() + " but doesn't include the first clustering key (" + 
                                   (clusteringKeys.size() > 0 ? clusteringKeys.get(0).getField() : "none") + "). " +
                                   "This pattern will require specifying the first CK before using " + metadata.getName() + 
                                   " in ORDER BY, or consider making " + metadata.getName() + " an index instead.");
                    }
                }
            }
        }
        
        System.out.println("\n=== FINAL CLUSTERING KEYS ===");
        clusteringKeys.forEach(ck -> System.out.println("  " + ck.getField() + " (" + ck.getType() + ", " + ck.getOrder() + ")"));
        System.out.println("=============================\n");

        if (Boolean.TRUE.equals(constraints != null ? constraints.getTimeSeries() : null)) {
            String timeFieldName = constraints.getTimeOrderingField();
            if (!StringUtils.hasText(timeFieldName)) {
                timeFieldName = findFirstTimeField(entity.getFields());
            }
            if (StringUtils.hasText(timeFieldName)) {
                FieldMetadata timeField = fieldLookup.get(normalize(timeFieldName));
                if (timeField != null && ckSet.add(normalize(timeField.getName()))) {
                    clusteringKeys.add(new ClusteringKeyRecommendation(
                            timeField.getName(),
                            "DESC",
                            ClusteringKeyType.ORDERING,
                            "Time-series flag enabled; ordering on " + timeField.getName()
                    ));
                }
            } else {
                warnings.add("Time-series flag enabled but no timestamp field selected for ordering.");
            }
        }

        if (clusteringKeys.isEmpty()) {
            summary.put("Clustering Keys", "No clustering keys required for single-row partitions.");
        } else {
            summary.put("Clustering Keys", clusteringKeys.stream()
                    .map(ck -> ck.getField() + " (" + ck.getType().name().toLowerCase() + ")")
                    .collect(Collectors.joining(" → ")));
        }

        return clusteringKeys;
    }

    private List<IndexRecommendation> determineIndexes(EntityModelRequest entity,
                                                       Map<String, FieldMetadata> fieldLookup,
                                                       List<String> partitionKey,
                                                       List<ClusteringKeyRecommendation> clusteringKeys,
                                                       List<String> warnings,
                                                       Map<String, String> summary) {
        Set<String> keyFields = new HashSet<>(partitionKey.stream().map(this::normalize).collect(Collectors.toSet()));
        keyFields.addAll(clusteringKeys.stream().map(ClusteringKeyRecommendation::getField).map(this::normalize).collect(Collectors.toSet()));

        List<IndexRecommendation> indexes = new ArrayList<>();
        List<AccessPattern> allPatterns = entity.getAccessPatterns();
        
        // Analyze all patterns to find fields that need indexes
        for (AccessPattern pattern : allPatterns) {
            if (pattern == null || CollectionUtils.isEmpty(pattern.getFilters())) continue;
            
            for (PatternField filter : pattern.getFilters()) {
                if (filter == null) continue;
                String filterField = filter.getField();
                if (!StringUtils.hasText(filterField)) continue;
                FieldMetadata metadata = fieldLookup.get(normalize(filterField));
                if (metadata == null) continue;
                
                // Skip if field is already in PK or CK
                if (keyFields.contains(normalize(metadata.getName()))) continue;
                
                // Skip if we already have an index for this field
                if (indexes.stream().anyMatch(idx -> normalize(idx.getField()).equals(normalize(metadata.getName())))) {
                    continue;
                }

                String patternDescription = pattern.getDescription() == null
                        ? (pattern.getName() != null ? pattern.getName() : "")
                        : pattern.getDescription().trim();
                IndexRecommendation recommendation = new IndexRecommendation(
                        metadata.getName(),
                        "Required for access pattern: " + patternDescription,
                        metadata.getCardinality()
                );
                indexes.add(recommendation);

                if (metadata.getCardinality() == Cardinality.LOW) {
                    warnings.add("Index on low-cardinality field " + metadata.getName() + " may not be optimal.");
                }
            }
        }

        if (!indexes.isEmpty()) {
            summary.put("Indexes", indexes.stream()
                    .map(IndexRecommendation::getField)
                    .collect(Collectors.joining(", ")));
        }

        return indexes;
    }

    private String buildCreateTableDdl(EntityModelRequest entity,
                                       Map<String, FieldMetadata> fieldLookup,
                                       List<String> partitionKey,
                                       List<ClusteringKeyRecommendation> clusteringKeys) {

        String schema = determineKeyspace(entity);
        String tableName = sanitizeIdentifier(entity.getEntityName());
        StringBuilder ddl = new StringBuilder();

        ddl.append("CREATE TABLE IF NOT EXISTS ")
                .append(schema).append(".").append(tableName)
                .append(" (\n");

        int fieldCount = 0;
        for (FieldMetadata field : entity.getFields()) {
            if (!StringUtils.hasText(field.getName())) continue;
            String column = "    " + sanitizeIdentifier(field.getName()) + " " + mapToYsqlType(field.getDataType());
            if (fieldCount++ > 0) {
                ddl.append(",\n");
            }
            ddl.append(column);
        }

        ddl.append(",\n    PRIMARY KEY ")
                .append(buildYugabytePrimaryKey(partitionKey, clusteringKeys))
                .append("\n);");

        if (StringUtils.hasText(entity.getDescription())) {
            ddl.append("\nCOMMENT ON TABLE ").append(schema).append(".").append(tableName)
                    .append(" IS '").append(escapeQuotes(entity.getDescription())).append("';");
        }
        for (FieldMetadata field : entity.getFields()) {
            if (!StringUtils.hasText(field.getName()) || !StringUtils.hasText(field.getDescription())) {
                continue;
            }
            ddl.append("\nCOMMENT ON COLUMN ").append(schema).append(".").append(tableName).append(".")
                    .append(sanitizeIdentifier(field.getName()))
                    .append(" IS '").append(escapeQuotes(field.getDescription())).append("';");
        }
        return ddl.toString();
    }

    private String buildYugabytePrimaryKey(List<String> partitionKey, List<ClusteringKeyRecommendation> clusteringKeys) {
        String hash = partitionKey.stream().map(this::sanitizeIdentifier).collect(Collectors.joining(", "));
        StringBuilder key = new StringBuilder("((").append(hash).append(") HASH");
        if (!CollectionUtils.isEmpty(clusteringKeys)) {
            for (ClusteringKeyRecommendation clusteringKey : clusteringKeys) {
                key.append(", ")
                        .append(sanitizeIdentifier(clusteringKey.getField()))
                        .append(" ")
                        .append(defaultOrder(clusteringKey.getOrder()));
            }
        }
        key.append(")");
        return key.toString();
    }

    private List<String> buildIndexStatements(EntityModelResponse response, List<IndexRecommendation> recommendations) {
        if (CollectionUtils.isEmpty(recommendations)) {
            return Collections.emptyList();
        }
        List<String> statements = new ArrayList<>();
        for (IndexRecommendation recommendation : recommendations) {
            String indexName = sanitizeIdentifier(response.getTableName() + "_" + recommendation.getField() + "_idx");
            statements.add(String.format(
                    "CREATE INDEX IF NOT EXISTS %s ON %s.%s (%s);",
                    indexName,
                    response.getKeyspace(),
                    response.getTableName(),
                    sanitizeIdentifier(recommendation.getField())
            ));
        }
        return statements;
    }

    private FieldMetadata resolveTenantField(ConstraintSettings constraints, Map<String, FieldMetadata> fieldLookup) {
        if (constraints != null && StringUtils.hasText(constraints.getTenantField())) {
            FieldMetadata constraintField = fieldLookup.get(normalize(constraints.getTenantField()));
            if (constraintField != null) {
                return constraintField;
            }
        }
        return fieldLookup.values().stream()
                .filter(FieldMetadata::isTenantField)
                .findFirst()
                .orElse(null);
    }

    private String findFirstTimeField(List<FieldMetadata> fields) {
        return fields.stream()
                .filter(FieldMetadata::isTimeField)
                .map(FieldMetadata::getName)
                .findFirst()
                .orElse(null);
    }

    private String buildPrimaryKeyClause(List<String> partitionKey, List<ClusteringKeyRecommendation> clusteringKeys) {
        String partitionClause;
        if (partitionKey.size() == 1) {
            partitionClause = sanitizeIdentifier(partitionKey.get(0));
        } else {
            partitionClause = "(" + partitionKey.stream()
                    .map(this::sanitizeIdentifier)
                    .collect(Collectors.joining(", ")) + ")";
        }

        if (CollectionUtils.isEmpty(clusteringKeys)) {
            return partitionClause;
        }

        String ckClause = clusteringKeys.stream()
                .map(ClusteringKeyRecommendation::getField)
                .map(this::sanitizeIdentifier)
                .collect(Collectors.joining(", "));

        return "(" + partitionClause + "), " + ckClause;
    }

    private void sanitizeEntity(EntityModelRequest entity) {
        entity.setEntityName(sanitizeIdentifier(entity.getEntityName()));
        if (entity.getFields() != null) {
            entity.getFields().forEach(field -> field.setName(sanitizeIdentifier(field.getName())));
        }
        if (entity.getAccessPatterns() != null) {
            entity.getAccessPatterns().forEach(pattern -> {
                if (pattern.getFilters() != null) {
                    pattern.getFilters().forEach(filter -> filter.setField(sanitizeIdentifier(filter.getField())));
                }
                if (pattern.getSortFields() != null) {
                    pattern.getSortFields().forEach(sort -> sort.setField(sanitizeIdentifier(sort.getField())));
                }
            });
        }
        if (entity.getConstraints() != null && StringUtils.hasText(entity.getConstraints().getTenantField())) {
            entity.getConstraints().setTenantField(sanitizeIdentifier(entity.getConstraints().getTenantField()));
        }
        if (entity.getConstraints() != null && StringUtils.hasText(entity.getConstraints().getTimeOrderingField())) {
            entity.getConstraints().setTimeOrderingField(sanitizeIdentifier(entity.getConstraints().getTimeOrderingField()));
        }
    }

    private String sanitizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String sanitized = value.toLowerCase()
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return sanitized.length() <= 63 ? sanitized : sanitized.substring(0, 63);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private boolean containsFieldIgnoreCase(List<String> fields, String candidate) {
        return fields.stream().anyMatch(f -> normalize(f).equals(normalize(candidate)));
    }

    private int cardinalityRank(Cardinality cardinality) {
        if (cardinality == null) return 3;
        switch (cardinality) {
            case HIGH:
                return 0;
            case MEDIUM:
                return 1;
            case LOW:
                return 2;
            default:
                return 3;
        }
    }

    private String mapToYsqlType(String datatype) {
        if (!StringUtils.hasText(datatype)) {
            return "text";
        }
        String lower = datatype.toLowerCase();
        if (lower.contains("uuid")) return "uuid";
        if (lower.contains("bigint") || lower.contains("long")) return "bigint";
        if (lower.contains("int")) return "integer";
        if (lower.contains("double") || lower.contains("float")) return "double precision";
        if (lower.contains("decimal") || lower.contains("numeric")) return "numeric";
        if (lower.contains("bool")) return "boolean";
        if (lower.contains("timestamp") || lower.contains("date") || lower.contains("time")) return "timestamptz";
        if (lower.contains("blob") || lower.contains("binary")) return "bytea";
        if (lower.contains("json")) return "jsonb";
        if (lower.contains("list") || lower.contains("set") || lower.contains("map")) return "jsonb";
        return "text";
    }

    private String escapeQuotes(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    private String defaultOrder(String order) {
        if (!StringUtils.hasText(order)) {
            return "ASC";
        }
        String normalized = order.trim().toUpperCase();
        return normalized.equals("DESC") ? "DESC" : "ASC";
    }

    private Integer determineTTL(ConstraintSettings constraints) {
        if (constraints == null) {
            return null;
        }
        if (constraints.getTtlSeconds() != null && constraints.getTtlSeconds() > 0) {
            return constraints.getTtlSeconds();
        }
        if (constraints.getRetentionDays() != null && constraints.getRetentionDays() > 0) {
            return constraints.getRetentionDays() * 86400;
        }
        return null;
    }
}

