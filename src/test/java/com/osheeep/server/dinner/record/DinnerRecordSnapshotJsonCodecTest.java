package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DinnerRecordSnapshotJsonCodecTest {

    private DinnerRecordSnapshotJsonCodec codec;

    @BeforeEach
    void setUp() {
        codec = new DinnerRecordSnapshotJsonCodec(new ObjectMapper());
    }

    @Test
    void roundTripsOrderedStepsAndNullableIngredientQuantity() {
        String steps = codec.writeSteps(List.of(
                new RecordMethodStepSnapshotResponse("盛盘", 1),
                new RecordMethodStepSnapshotResponse("翻炒", 0)));
        String ingredients = codec.writeIngredients(List.of(
                new RecordIngredientSnapshotResponse(
                        2L, "鸡蛋", null, "枚", true, 0)));

        assertThat(codec.readSteps(steps))
                .extracting(RecordMethodStepSnapshotResponse::sortOrder)
                .containsExactly(0, 1);
        assertThat(codec.readIngredients(ingredients).getFirst().quantity()).isNull();
    }

    @Test
    void readsNullAndBlankLegacyJsonAsEmptyImmutableLists() {
        List<RecordMethodStepSnapshotResponse> steps = codec.readSteps(null);
        List<RecordIngredientSnapshotResponse> ingredients = codec.readIngredients("  ");

        assertThat(steps).isEmpty();
        assertThat(ingredients).isEmpty();
        assertThatThrownBy(() -> steps.add(
                new RecordMethodStepSnapshotResponse("不能修改", 0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ingredients.add(
                new RecordIngredientSnapshotResponse(
                        1L, "不能修改", null, "克", true, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parsedListsAreImmutable() {
        List<RecordMethodStepSnapshotResponse> steps = codec.readSteps(
                "[{\"instruction\":\"翻炒\",\"sortOrder\":0}]");
        List<RecordIngredientSnapshotResponse> ingredients = codec.readIngredients(
                "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                        + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]");

        assertThatThrownBy(() -> steps.clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ingredients.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void malformedAndJsonNullStoredValuesFailSafely() {
        assertInvalid(() -> codec.readSteps("{"));
        assertInvalid(() -> codec.readIngredients("not-json"));
        assertInvalid(() -> codec.readSteps("null"));
        assertInvalid(() -> codec.readIngredients("null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{}]",
            "[null]",
            "[{\"instruction\":\"翻炒\"}]",
            "[{\"instruction\":7,\"sortOrder\":0}]",
            "[{\"instruction\":\"翻炒\",\"sortOrder\":\"0\"}]",
            "[{\"instruction\":\"翻炒\",\"sortOrder\":0.5}]",
            "[{\"instruction\":\"翻炒\",\"sortOrder\":null}]",
            "[{\"instruction\":\"翻炒\",\"sortOrder\":0,\"extra\":true}]",
            "{\"instruction\":\"翻炒\",\"sortOrder\":0}",
            "[[\"翻炒\",0]]",
            "[{\"instruction\":\"  \",\"sortOrder\":0}]",
            "[{\"instruction\":\"翻炒\",\"sortOrder\":-1}]"
    })
    void rejectsInvalidStoredStepJson(String json) {
        assertInvalid(() -> codec.readSteps(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{}]",
            "[null]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\","
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true}]",
            "[{\"ingredientId\":\"1\",\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":7,\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":\"1\","
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":7,\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":\"true\",\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0.5}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0,"
                    + "\"extra\":true}]",
            "[1]",
            "[{\"ingredientId\":0,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"  \",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"  \",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":-0.001,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":0.0001,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":1000000000,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]",
            "[{\"ingredientId\":1,\"name\":\"鸡蛋\",\"quantity\":null,"
                    + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":-1}]"
    })
    void rejectsInvalidStoredIngredientJson(String json) {
        assertInvalid(() -> codec.readIngredients(json));
    }

    @Test
    void rejectsInvalidStepDtosOnWrite() {
        List<List<RecordMethodStepSnapshotResponse>> invalidValues = List.of(
                Arrays.asList((RecordMethodStepSnapshotResponse) null),
                List.of(new RecordMethodStepSnapshotResponse("  ", 0)),
                List.of(new RecordMethodStepSnapshotResponse("翻炒", -1)));

        invalidValues.forEach(values -> assertInvalid(() -> codec.writeSteps(values)));
    }

    @Test
    void rejectsInvalidIngredientDtosOnWrite() {
        List<List<RecordIngredientSnapshotResponse>> invalidValues = List.of(
                Arrays.asList((RecordIngredientSnapshotResponse) null),
                ingredients(0L, "鸡蛋", null, "枚", 0),
                ingredients(1L, "  ", null, "枚", 0),
                ingredients(1L, "鸡蛋", null, "  ", 0),
                ingredients(1L, "鸡蛋", new BigDecimal("-0.001"), "枚", 0),
                ingredients(1L, "鸡蛋", new BigDecimal("0.0001"), "枚", 0),
                ingredients(1L, "鸡蛋", new BigDecimal("1000000000"), "枚", 0),
                ingredients(1L, "鸡蛋", null, "枚", -1));

        invalidValues.forEach(values -> assertInvalid(() -> codec.writeIngredients(values)));
    }

    private List<RecordIngredientSnapshotResponse> ingredients(
            Long ingredientId,
            String name,
            BigDecimal quantity,
            String unit,
            int sortOrder
    ) {
        return List.of(new RecordIngredientSnapshotResponse(
                ingredientId, name, quantity, unit, true, sortOrder));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid dinner record snapshot JSON");
    }
}
