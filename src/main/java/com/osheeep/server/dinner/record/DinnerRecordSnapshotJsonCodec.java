package com.osheeep.server.dinner.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
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
        List<RecordMethodStepSnapshotResponse> safeValues = safe(values);
        validateSteps(safeValues);
        List<RecordMethodStepSnapshotResponse> ordered = safeValues.stream()
                .sorted(Comparator.comparingInt(
                        RecordMethodStepSnapshotResponse::sortOrder))
                .toList();
        return write(ordered);
    }

    public String writeIngredients(List<RecordIngredientSnapshotResponse> values) {
        List<RecordIngredientSnapshotResponse> safeValues = safe(values);
        validateIngredients(safeValues);
        List<RecordIngredientSnapshotResponse> ordered = safeValues.stream()
                .sorted(Comparator.comparingInt(
                        RecordIngredientSnapshotResponse::sortOrder))
                .toList();
        return write(ordered);
    }

    public List<RecordMethodStepSnapshotResponse> readSteps(String json) {
        return read(json, STEP_LIST, this::validateSteps);
    }

    public List<RecordIngredientSnapshotResponse> readIngredients(String json) {
        return read(json, INGREDIENT_LIST, this::validateIngredients);
    }

    private String write(List<?> values) {
        try {
            return objectMapper.writeValueAsString(List.copyOf(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_JSON, exception);
        }
    }

    private <T> List<T> read(
            String json,
            TypeReference<List<T>> type,
            Consumer<List<T>> validator
    ) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<T> values = objectMapper.readValue(json, type);
            if (values == null) {
                throw invalidJson();
            }
            validator.accept(values);
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_JSON, exception);
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void validateSteps(List<RecordMethodStepSnapshotResponse> values) {
        for (RecordMethodStepSnapshotResponse value : values) {
            if (value == null
                    || !StringUtils.hasText(value.instruction())
                    || value.sortOrder() < 0) {
                throw invalidJson();
            }
        }
    }

    private void validateIngredients(List<RecordIngredientSnapshotResponse> values) {
        for (RecordIngredientSnapshotResponse value : values) {
            if (value == null
                    || value.ingredientId() == null
                    || value.ingredientId() <= 0
                    || !StringUtils.hasText(value.name())
                    || !StringUtils.hasText(value.unit())
                    || !validQuantity(value.quantity())
                    || value.sortOrder() < 0) {
                throw invalidJson();
            }
        }
    }

    private boolean validQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return true;
        }
        int integerDigits = Math.max(quantity.precision() - quantity.scale(), 0);
        return quantity.signum() >= 0
                && quantity.scale() <= 3
                && integerDigits <= 9;
    }

    private IllegalStateException invalidJson() {
        return new IllegalStateException(INVALID_JSON);
    }
}
