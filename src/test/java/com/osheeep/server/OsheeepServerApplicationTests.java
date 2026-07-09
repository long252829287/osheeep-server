package com.osheeep.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = OsheeepServerApplication.class)
@Import(TestUserMapperConfig.class)
class OsheeepServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
