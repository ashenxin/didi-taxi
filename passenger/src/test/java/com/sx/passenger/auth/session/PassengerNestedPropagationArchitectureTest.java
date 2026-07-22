package com.sx.passenger.auth.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PassengerNestedPropagationArchitectureTest {

    @Test
    void passengerMainCodeMustNotUseNestedTransactionPropagation() throws IOException {
        Path sourceRoot = resolvePassengerMainSource();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<String> violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(PassengerNestedPropagationArchitectureTest::containsNestedPropagation)
                    .map(sourceRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertThat(violations)
                    .as("passenger 认证/生命周期 epoch 指标无法观察 savepoint 回滚，禁止 PROPAGATION_NESTED")
                    .isEmpty();
        }
    }

    private static Path resolvePassengerMainSource() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path moduleSource = workingDirectory.resolve("src/main/java");
        if (Files.isDirectory(moduleSource)) {
            return moduleSource;
        }
        Path reactorSource = workingDirectory.resolve("passenger/src/main/java");
        assertThat(reactorSource).isDirectory();
        return reactorSource;
    }

    private static boolean containsNestedPropagation(Path source) {
        try {
            String content = Files.readString(source);
            return content.contains("Propagation.NESTED") || content.contains("PROPAGATION_NESTED");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to inspect passenger transaction propagation", e);
        }
    }
}
