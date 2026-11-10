import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MandelbrotIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void allImplementationsGenerateExpectedCsvAndPng() throws Exception {
        Path csv = tempDir.resolve("all-results.csv");
        Path png = tempDir.resolve("all.png");

        Mandelbrot.main(new String[] {
                "--method", "all", "--threads", "1,2", "--width", "64", "--height", "48",
                "--iterations", "100", "--threshold", "4", "--warmups", "0", "--runs", "1",
                "--csv", csv.toString(), "--image", png.toString()
        });

        assertTrue(Files.isRegularFile(csv));
        assertTrue(Files.isRegularFile(png));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(8, lines.size(), "Header + sequential baseline + (3 methods x 2 workers)");
        String[] header = lines.get(0).split(",", -1);
        assertEquals(List.of("method", "workers", "width", "height", "max_iterations",
                "forkjoin_threshold", "warmups", "runs", "median_ms", "min_ms", "max_ms",
                "mean_ms", "stddev_ms", "speedup", "efficiency"), Arrays.asList(header));

        Map<String, Long> counts = lines.stream().skip(1)
                .map(line -> line.split(",", -1))
                .collect(Collectors.groupingBy(fields -> fields[0], Collectors.counting()));
        assertEquals(Map.of("SEQUENTIAL", 1L, "EXECUTOR", 2L, "FORKJOIN", 2L, "STREAM", 2L), counts);

        Set<String> uniqueConfigs = lines.stream().skip(1)
                .map(line -> {
                    String[] c = line.split(",", -1);
                    assertEquals(15, c.length);
                    assertEquals("64", c[2]);
                    assertEquals("48", c[3]);
                    assertEquals("100", c[4]);
                    assertEquals("4", c[5]);
                    assertEquals("0", c[6]);
                    assertEquals("1", c[7]);
                    double median = Double.parseDouble(c[8]);
                    assertTrue(Double.isFinite(median) && median >= 0);
                    assertTrue(Double.parseDouble(c[13]) > 0, "Speedup must be positive");
                    return c[0] + ":" + c[1];
                }).collect(Collectors.toSet());
        assertEquals(Set.of("SEQUENTIAL:1", "EXECUTOR:1", "EXECUTOR:2",
                "FORKJOIN:1", "FORKJOIN:2", "STREAM:1", "STREAM:2"), uniqueConfigs);

        BufferedImage image = ImageIO.read(png.toFile());
        assertNotNull(image);
        assertEquals(64, image.getWidth());
        assertEquals(48, image.getHeight());
    }

    @Test
    void outputImageMatchesSequentialReference() throws Exception {
        Path expected = tempDir.resolve("sequential.png");
        Path actual = tempDir.resolve("parallel.png");
        String[] common = {"--threads", "1,2", "--width", "40", "--height", "30",
                "--iterations", "80", "--warmups", "0", "--runs", "1", "--csv", "none"};

        Mandelbrot.main(concat(common, "--method", "sequential", "--image", expected.toString()));
        Mandelbrot.main(concat(common, "--method", "all", "--image", actual.toString()));

        BufferedImage sequential = ImageIO.read(expected.toFile());
        BufferedImage parallel = ImageIO.read(actual.toFile());
        assertNotNull(sequential);
        assertNotNull(parallel);
        assertEquals(sequential.getWidth(), parallel.getWidth());
        assertEquals(sequential.getHeight(), parallel.getHeight());
        for (int y = 0; y < sequential.getHeight(); y++) {
            for (int x = 0; x < sequential.getWidth(); x++) {
                assertEquals(sequential.getRGB(x, y), parallel.getRGB(x, y),
                        "Pixel mismatch at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void singleMethodStillWritesSequentialBaseline() throws Exception {
        Path csv = tempDir.resolve("executor-results.csv");
        Mandelbrot.main(new String[] {"--method", "executor", "--threads", "2",
                "--width", "24", "--height", "16", "--iterations", "50",
                "--warmups", "0", "--runs", "1", "--image", "none", "--csv", csv.toString()});
        List<String> rows = Files.readAllLines(csv);
        assertEquals(3, rows.size());
        assertTrue(rows.get(1).startsWith("SEQUENTIAL,1,"));
        assertTrue(rows.get(2).startsWith("EXECUTOR,2,"));
    }

    @Test
    void invalidArgumentsFailBeforeCreatingOutputs() {
        Path csv = tempDir.resolve("should-not-exist.csv");
        assertThrows(IllegalArgumentException.class, () -> Mandelbrot.main(new String[] {
                "--width", "1", "--csv", csv.toString()
        }));
        assertFalse(Files.exists(csv));
    }

    private static String[] concat(String[] first, String... rest) {
        String[] result = Arrays.copyOf(first, first.length + rest.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }
}
