package com.osheeep.server.dinner.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.image.entity.DinnerImageAssetEntity;
import com.osheeep.server.dinner.image.mapper.DinnerImageAssetMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerImageAssetServiceTest {

    @Mock private DinnerImageAssetMapper mapper;

    private DinnerImageAssetService service;

    @BeforeAll
    static void initializeMybatisTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                DinnerImageAssetEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DinnerImageAssetService(
                mapper, new DinnerImageProperties("https://assets.test/"));
    }

    @Test
    void searchReturnsOnlyApprovedAssetsWithGroupedTextSearchAndSelfHostedUrls() {
        when(mapper.selectList(any())).thenReturn(List.of(asset(9L, "APPROVED")));

        List<ImageAssetResponse> result = service.search("  番茄  ");

        assertThat(result).singleElement().satisfies(asset -> {
            assertThat(asset.id()).isEqualTo(9L);
            assertThat(asset.listUrl()).isEqualTo(
                    "https://assets.test/media/recipes/tomato-with-egg-list.webp");
            assertThat(asset.detailUrl()).isEqualTo(
                    "https://assets.test/media/recipes/tomato-with-egg-detail.webp");
            assertThat(asset.sourcePageUrl())
                    .isEqualTo("https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg");
            assertThat(asset.licenseName()).isEqualTo("CC0 1.0");
        });

        ArgumentCaptor<Wrapper<DinnerImageAssetEntity>> wrapper = wrapperCaptor();
        verify(mapper).selectList(wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertThat(sql).contains("status", "display_name", "search_keywords", "LIKE", "OR");
        assertThat(sql).containsPattern(
                "\\(display_name\\s+LIKE.+OR\\s+search_keywords\\s+LIKE.+\\)");
        assertThat(parameterValues(wrapper.getValue()).values())
                .contains("APPROVED", "%番茄%");
    }

    @Test
    void blankSearchStillRestrictsStatusWithoutAddingLikePredicates() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(service.search("  ")).isEmpty();

        ArgumentCaptor<Wrapper<DinnerImageAssetEntity>> wrapper = wrapperCaptor();
        verify(mapper).selectList(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment())
                .contains("status")
                .doesNotContain("LIKE");
        assertThat(parameterValues(wrapper.getValue()).values()).contains("APPROVED");
    }

    @Test
    void approvedAssetsAreResolvedInOneBatchWithoutPerRowQueries() {
        when(mapper.selectList(any())).thenReturn(List.of(asset(9L, "APPROVED")));

        Map<Long, ImageAssetResponse> result =
                service.findApprovedByIds(List.of(9L, 9L, 10L));

        assertThat(result).containsOnlyKeys(9L);
        assertThat(result.get(9L).listUrl())
                .isEqualTo("https://assets.test/media/recipes/tomato-with-egg-list.webp");
        ArgumentCaptor<Wrapper<DinnerImageAssetEntity>> wrapper = wrapperCaptor();
        verify(mapper).selectList(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("id IN", "status");
        assertThat(parameterValues(wrapper.getValue()).values()).contains("APPROVED", 9L, 10L);
        verify(mapper, never()).selectById(any());
    }

    @Test
    void approvedBatchOmitsAssetsWithBlankListOrDetailObjectKeys() {
        DinnerImageAssetEntity blankList = asset(8L, "APPROVED");
        blankList.setListObjectKey("");
        DinnerImageAssetEntity blankDetail = asset(10L, "APPROVED");
        blankDetail.setDetailObjectKey("  ");
        when(mapper.selectList(any())).thenReturn(List.of(
                blankList, asset(9L, "APPROVED"), blankDetail));

        Map<Long, ImageAssetResponse> result =
                service.findApprovedByIds(List.of(8L, 9L, 10L));

        assertThat(result).containsOnlyKeys(9L);
        assertThat(result.values()).allSatisfy(image -> {
            assertThat(image.listUrl()).isNotBlank();
            assertThat(image.detailUrl()).isNotBlank();
        });
    }

    @Test
    void requireApprovedRejectsApprovedAssetWithIncompleteDerivedObjectKeys() {
        DinnerImageAssetEntity blankList = asset(8L, "APPROVED");
        blankList.setListObjectKey(" ");
        when(mapper.selectById(8L)).thenReturn(blankList);

        assertThatThrownBy(() -> service.requireApproved(8L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));
    }

    @Test
    void searchDoesNotExposeApprovedAssetWithNullDerivedObjectKey() {
        DinnerImageAssetEntity missingList = asset(8L, "APPROVED");
        missingList.setListObjectKey(null);
        when(mapper.selectList(any())).thenReturn(List.of(
                missingList, asset(9L, "APPROVED")));

        assertThat(service.search("番茄"))
                .extracting(ImageAssetResponse::id)
                .containsExactly(9L);
    }

    @Test
    void disabledOrMissingAssetIsUnavailableForDraftSelection() {
        when(mapper.selectById(8L)).thenReturn(asset(8L, "DISABLED"));
        when(mapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireApproved(8L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));
        assertThatThrownBy(() -> service.requireApproved(404L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));
    }

    private DinnerImageAssetEntity asset(Long id, String status) {
        DinnerImageAssetEntity asset = new DinnerImageAssetEntity();
        asset.setId(id);
        asset.setDisplayName("番茄炒鸡蛋");
        asset.setSearchKeywords("番茄 西红柿 鸡蛋 家常菜");
        asset.setListObjectKey("media/recipes/tomato-with-egg-list.webp");
        asset.setDetailObjectKey("media/recipes/tomato-with-egg-detail.webp");
        asset.setOriginalObjectKey("internal/recipes/tomato-with-egg/original.jpg");
        asset.setOriginalFileUrl(
                "https://upload.wikimedia.org/wikipedia/commons/5/56/Tomato_with_egg.jpg");
        asset.setSourcePageUrl(
                "https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg");
        asset.setAuthor("Kaap bij Sneeuw");
        asset.setLicenseName("CC0 1.0");
        asset.setLicenseUrl("https://creativecommons.org/publicdomain/zero/1.0/");
        asset.setAcquiredOn(LocalDate.of(2026, 7, 16));
        asset.setOriginalWidth(1198);
        asset.setOriginalHeight(1091);
        asset.setStatus(status);
        return asset;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static Map<String, Object> parameterValues(Wrapper<?> wrapper) {
        assertThat(wrapper).isInstanceOf(AbstractWrapper.class);
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }
}
