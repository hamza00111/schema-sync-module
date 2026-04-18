package com.sync.cdc.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Converts a PostgreSQL JSONB column value (returned by the JDBC driver as {@code PGobject}
 * or {@code String}) into a {@code Map<String,Object>}.
 */
public class PostgresJsonReader {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;

    public PostgresJsonReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> readObject(Object jsonbValue) {
        if (jsonbValue == null) {
            return Map.of();
        }
        String json = jsonbValue.toString();
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse JSON: " + json, e);
        }
    }
}
