package com.sx.passenger.lifecycle.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LifecyclePlanLoader {
    private final YAMLMapper mapper;

    public LifecyclePlanLoader() {
        mapper = YAMLMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public List<LoadedLifecyclePlan> load(ResourcePatternResolver resolver, String pattern) {
        try {
            Resource[] resources = resolver.getResources(pattern);
            List<LoadedLifecyclePlan> plans = new ArrayList<>(resources.length);
            for (Resource resource : resources) {
                plans.add(new LoadedLifecyclePlan(resource.getFilename(),
                        mapper.readValue(resource.getInputStream(), LifecyclePlanDefinition.class)));
            }
            plans.sort(Comparator.comparing(LoadedLifecyclePlan::sourceName));
            return List.copyOf(plans);
        } catch (IOException e) {
            throw new InvalidLifecyclePlanException("Failed to load lifecycle plan resources: " + pattern, e);
        }
    }
}
