package com.osheeep.server.dinner.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DinnerImageProperties.class)
public class DinnerImageConfig {
}
