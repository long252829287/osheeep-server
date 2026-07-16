package com.osheeep.server.dinner.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "osheeep.dinner.images")
public record DinnerImageProperties(String publicBaseUrl) {
}
