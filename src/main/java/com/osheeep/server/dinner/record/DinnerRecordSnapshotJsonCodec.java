package com.osheeep.server.dinner.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class DinnerRecordSnapshotJsonCodec {

    private static final String INVALID_JSON = "Invalid dinner record snapshot JSON";
    private static final TypeReference<List<RecordMethodStepSnapshotResponse>> STEP_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<RecordIngredientSnapshotResponse>> INGREDIENT_LIST =
            new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public DinnerRecordSnapshotJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeSteps(List<RecordMethodStepSnapshotResponse> values) {
        List<RecordMethodStepSnapshotResponse> ordered = safe(values).stream()
                .sorted(Comparator.comparingInt(
                        RecordMethodStepSnapshotResponse::sortOrder))
                .toList();
        return write(ordered);
    }

    public String writeIngredients(List<RecordIngredientSnapshotResponse> values) {
        List<RecordIngredientSnapshotResponse> ordered = safe(values).stream()
                .sorted(Comparator.comparingInt(
                        RecordIngredientSnapshotResponse::sortOrder))
                .toList();
        return write(ordered);
    }

    public List<RecordMethodStepSnapshotResponse> readSteps(String json) {
        return read(json, STEP_LIST);
    }

    public List<RecordIngredientSnapshotResponse> readIngredients(String json) {
        return read(json, INGREDIENT_LIST);
    }

    private String write(List<?> values) {
        try {
            return objectMapper.writeValueAsString(List.copyOf(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_JSON, exception);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<T> values = objectMapper.readValue(json, type);
            if (values == null) {
                throw new IllegalStateException(INVALID_JSON);
            }
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_JSON, exception);
        } catch (NullPointerException exception) {
            throw new IllegalStateException(INVALID_JSON, exception);
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
