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
        return mapper.selectList(wrapper).stream().map(this::toResponse).toList();
    }

    public ImageAssetResponse requireApproved(Long imageAssetId) {
        DinnerImageAssetEntity asset = mapper.selectById(imageAssetId);
        if (asset == null || !APPROVED.equals(asset.getStatus())) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_IMAGE_INVALID);
        }
        return toResponse(asset);
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
                .map(this::toResponse)
                .collect(Collectors.toMap(
                        ImageAssetResponse::id,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private ImageAssetResponse toResponse(DinnerImageAssetEntity asset) {
        return new ImageAssetResponse(
                asset.getId(), asset.getDisplayName(), objectUrl(asset.getListObjectKey()),
                objectUrl(asset.getDetailObjectKey()), asset.getSourcePageUrl(), asset.getAuthor(),
                asset.getLicenseName(), asset.getLicenseUrl(), asset.getAcquiredOn(),
                asset.getOriginalWidth(), asset.getOriginalHeight());
    }

    private String objectUrl(String objectKey) {
        return publicBaseUrl + "/" + objectKey.replaceFirst("^/+", "");
    }

    private String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
