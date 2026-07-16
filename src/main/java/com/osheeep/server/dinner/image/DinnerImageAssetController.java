package com.osheeep.server.dinner.image;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/image-assets")
public class DinnerImageAssetController {

    private final DinnerImageAssetService service;

    public DinnerImageAssetController(DinnerImageAssetService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ImageAssetResponse>> search(
            @RequestParam(required = false) String query
    ) {
        return ApiResponse.ok(service.search(query));
    }
}
