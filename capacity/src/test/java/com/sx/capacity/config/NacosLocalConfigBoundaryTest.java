package com.sx.capacity.config;

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
        assertEquals(Map.of("name", "capacity-service"), map(spring.get("application")));
    }

    @Test
    void localConfigImportsOneMandatoryNacosDocument() {
        Map<String, Object> root = loadYaml("application-local.yml");
        assertEquals(Set.of("spring"), root.keySet());

        Map<String, Object> spring = map(root.get("spring"));
        Map<String, Object> config = map(spring.get("config"));
        assertEquals(
                List.of("nacos:capacity-service-local.yml?group=DIDI_TAXI&refreshEnabled=false"),
                config.get("import")
        );

        Map<String, Object> nacos = map(spring.get("nacos"));
        Map<String, Object> nacosConfig = map(nacos.get("config"));
        assertEquals("${NACOS_SERVER_ADDR:127.0.0.1:8848}", nacosConfig.get("server-addr"));
        assertEquals("${NACOS_NAMESPACE}", nacosConfig.get("namespace"));
        assertEquals("${NACOS_USERNAME:nacos}", nacosConfig.get("username"));
        assertEquals("${NACOS_PASSWORD}", nacosConfig.get("password"));

        Map<String, Object> discovery = map(map(map(spring.get("cloud")).get("nacos"))
                .get("discovery"));
        assertEquals("${NACOS_SERVER_ADDR:127.0.0.1:8848}", discovery.get("server-addr"));
        assertEquals("${NACOS_NAMESPACE}", discovery.get("namespace"));
        assertEquals("DIDI_TAXI", discovery.get("group"));
        assertEquals("${NACOS_USERNAME:nacos}", discovery.get("username"));
        assertEquals("${NACOS_PASSWORD}", discovery.get("password"));
        assertEquals(true, discovery.get("register-enabled"));
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
            Object value = new Yaml().load(input);
            return (Map<String, Object>) value;
        } catch (Exception exception) {
            throw new AssertionError("Unable to read " + resourceName, exception);
        }
    }
}
