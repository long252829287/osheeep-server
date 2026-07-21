package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid dinner record snapshot JSON");
    }
}
