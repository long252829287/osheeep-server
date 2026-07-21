package com.osheeep.server.dinner.image;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.image.entity.DinnerImageAssetEntity;
import com.osheeep.server.dinner.image.mapper.DinnerImageAssetMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DinnerImageAssetService {

    private static final String APPROVED = "APPROVED";

    private final DinnerImageAssetMapper mapper;
    private final String publicBaseUrl;

    public DinnerImageAssetService(
            DinnerImageAssetMapper mapper,
            DinnerImageProperties properties
    ) {
        this.mapper = mapper;
        this.publicBaseUrl = trimTrailingSlashes(properties.publicBaseUrl());
    }

    public List<ImageAssetResponse> search(String query) {
        var wrapper = Wrappers.<DinnerImageAssetEntity>lambdaQuery()
                .eq(DinnerImageAssetEntity::getStatus, APPROVED);
        if (StringUtils.hasText(query)) {
            String normalized = query.strip();
            wrapper.and(search -> search
                    .like(DinnerImageAssetEntity::getDisplayName, normalized)
                    .or()
                    .like(DinnerImageAssetEntity::getSearchKeywords, normalized));
        }
        wrapper.orderByAsc(DinnerImageAssetEntity::getId);
        return mapper.selectList(wrapper).stream()
                .map(this::toApprovedResponse)
                .flatMap(Optional::stream)
                .toList();
    }

    public ImageAssetResponse requireApproved(Long imageAssetId) {
        return toApprovedResponse(mapper.selectById(imageAssetId))
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));
    }

    public Map<Long, ImageAssetResponse> findApprovedByIds(List<Long> imageAssetIds) {
        List<Long> distinctIds = imageAssetIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return mapper.selectList(Wrappers.<DinnerImageAssetEntity>lambdaQuery()
                        .in(DinnerImageAssetEntity::getId, distinctIds)
                        .eq(DinnerImageAssetEntity::getStatus, APPROVED))
                .stream()
                .map(this::toApprovedResponse)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(
                        ImageAssetResponse::id,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private Optional<ImageAssetResponse> toApprovedResponse(
            DinnerImageAssetEntity asset
    ) {
        if (asset == null
                || !APPROVED.equals(asset.getStatus())
                || !validObjectKey(asset.getListObjectKey())
                || !validObjectKey(asset.getDetailObjectKey())) {
            return Optional.empty();
        }
        return Optional.of(new ImageAssetResponse(
                asset.getId(), asset.getDisplayName(), objectUrl(asset.getListObjectKey()),
                objectUrl(asset.getDetailObjectKey()), asset.getSourcePageUrl(), asset.getAuthor(),
                asset.getLicenseName(), asset.getLicenseUrl(), asset.getAcquiredOn(),
                asset.getOriginalWidth(), asset.getOriginalHeight()));
    }

    private String objectUrl(String objectKey) {
        String normalized = normalizeObjectKey(objectKey);
        return normalized == null ? null : publicBaseUrl + "/" + normalized;
    }

    private boolean validObjectKey(String objectKey) {
        return normalizeObjectKey(objectKey) != null;
    }

    private String normalizeObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        String normalized = objectKey.strip().replaceFirst("^/+", "");
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
