package com.sx.order.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NacosLocalConfigBoundaryTest {

    @Test
    void baseConfigContainsOnlyApplicationIdentity() {
        Map<String, Object> root = loadYaml("application.yml");
        assertEquals(Set.of("spring"), root.keySet());

        Map<String, Object> spring = map(root.get("spring"));
        assertEquals(Set.of("application"), spring.keySet());
        assertEquals(Map.of("name", "order-service"), map(spring.get("application")));
    }

    @Test
    void localConfigImportsOneMandatoryNacosDocument() {
        Map<String, Object> root = loadYaml("application-local.yml");
        assertEquals(Set.of("spring"), root.keySet());

        Map<String, Object> spring = map(root.get("spring"));
        Map<String, Object> config = map(spring.get("config"));
        assertEquals(
                List.of("nacos:order-service-local.yml?group=DIDI_TAXI&refreshEnabled=false"),
                config.get("import")
        );

        Map<String, Object> nacosConfig = map(map(spring.get("nacos")).get("config"));
        assertEquals("${NACOS_SERVER_ADDR:127.0.0.1:8848}", nacosConfig.get("server-addr"));
        assertEquals("${NACOS_NAMESPACE}", nacosConfig.get("namespace"));
        assertEquals("${NACOS_USERNAME:nacos}", nacosConfig.get("username"));
        assertEquals("${NACOS_PASSWORD}", nacosConfig.get("password"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String resourceName) {
        try (InputStream input = NacosLocalConfigBoundaryTest.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, () -> resourceName + " must exist");
            return (Map<String, Object>) new Yaml().load(input);
        } catch (Exception exception) {
            throw new AssertionError("Unable to read " + resourceName, exception);
        }
    }
}
