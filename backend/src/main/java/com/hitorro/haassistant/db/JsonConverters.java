package com.hitorro.haassistant.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;
import java.util.Map;

public final class JsonConverters {

    private JsonConverters() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Converter
    public static class StringList implements AttributeConverter<List<String>, String> {
        @Override public String convertToDatabaseColumn(List<String> attribute) {
            if (attribute == null) return null;
            try { return MAPPER.writeValueAsString(attribute); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public List<String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) return null;
            try { return MAPPER.readValue(dbData, new TypeReference<>() {}); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Converter
    public static class StringObjectMap implements AttributeConverter<Map<String, Object>, String> {
        @Override public String convertToDatabaseColumn(Map<String, Object> attribute) {
            if (attribute == null) return null;
            try { return MAPPER.writeValueAsString(attribute); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public Map<String, Object> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) return null;
            try { return MAPPER.readValue(dbData, new TypeReference<>() {}); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
