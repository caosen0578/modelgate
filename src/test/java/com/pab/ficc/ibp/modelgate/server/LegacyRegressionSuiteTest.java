package com.pab.ficc.ibp.modelgate.server;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 把历史 main 回归用例纳入 mvn test；独立进程避免 System.exit 和静态状态污染 JUnit。 */
class LegacyRegressionSuiteTest {
    @TestFactory Stream<DynamicTest> legacyMainTests() throws Exception {
        var root = Path.of("src/test/java");
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith("Test.java"))
                .filter(path -> { try { return java.util.regex.Pattern.compile("(?m)^\\s*public static void main\\(").matcher(Files.readString(path)).find(); } catch (Exception e) { throw new IllegalStateException(e); } })
                .sorted().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                    String name = root.relativize(path).toString().replace('\\', '.').replace('/', '.').replace(".java", "");
                    var output = Files.createTempFile("modelgate-regression-", ".log");
                    try {
                        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), name)
                            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                        boolean complete = process.waitFor(60, TimeUnit.SECONDS);
                        if (!complete) process.destroyForcibly();
                        assertTrue(complete, "main 回归超时: " + name);
                        assertEquals(0, process.exitValue(), () -> { try { return Files.readString(output); } catch (Exception e) { return e.toString(); } });
                    } finally { Files.deleteIfExists(output); }
                })).toList().stream();
        }
    }
}
