import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

class MandelbrotTest {
    private static final Class<?> APP = Mandelbrot.class;
    private static final Class<?> CFG = nested("Config");
    private static final Class<?> METHOD = nested("Method");
    private static final Class<?> RESULT = nested("Result");

    @TempDir
    Path tempDir;

    @Test
    void knownEscapeCounts() throws Exception {
        eq(1, point(2, 2, 100), "c=2+2i escapes in one iteration");
        eq(2, point(2, 0, 100), "c=2 escapes after second iteration");
        eq(3, point(1, 0, 100), "c=1 escapes after three iterations");
        eq(100, point(0, 0, 100), "c=0 remains bounded");
        eq(100, point(-1, 0, 100), "c=-1 periodic and bounded");
    }

    @Test
    void iterationCap() throws Exception {
        eq(1, point(0, 0, 1), "max iterations 1");
        eq(7, point(0, 0, 7), "max iterations 7");
        eq(2, point(2, 0, 2), "escape exactly at cap");
    }

    @Test
    void colorMapping() throws Exception {
        eq(0, color(100, 100), "bounded points are black");
        eq(color(5, 100), color(5, 200), "escaping point color independent of cap");
        for (int i = 0; i < 100; i++) {
            int rgb = color(i, 100);
            yes((rgb & ~0x00FFFFFF) == 0, "RGB outside 24 bits for i=" + i);
        }
    }

    @Test
    void knownSmallImage() throws Exception {
        Object cfg = config(9, 7, 50, 2);
        int[] actual = execute("SEQUENTIAL", cfg, 1);
        int[] expected = new int[9 * 7];
        for (int y = 0; y < 7; y++) {
            double imaginary = -1.2 + y * 2.4 / 6;
            for (int x = 0; x < 9; x++) {
                double real = -2.0 + x * 3.0 / 8;
                expected[y * 9 + x] = color(point(real, imaginary, 50), 50);
            }
        }
        same(expected, actual, "sequential grid");
    }

    @Test
    void parallelEquivalence() throws Exception {
        for (int[] dims : new int[][]{{2, 2}, {9, 7}, {32, 24}, {51, 37}}) {
            Object cfg = config(dims[0], dims[1], 80, 4);
            int[] baseline = execute("SEQUENTIAL", cfg, 1);
            for (String method : new String[]{"EXECUTOR", "FORKJOIN", "STREAM"}) {
                for (int workers : new int[]{1, 2, 4, 6}) {
                    same(baseline, execute(method, cfg, workers),
                            method + " workers=" + workers + " grid=" + Arrays.toString(dims));
                }
            }
        }
    }

    @Test
    void forkJoinThresholds() throws Exception {
        Object cfg = config(43, 21, 60, 1);
        int[] baseline = execute("SEQUENTIAL", cfg, 1);
        for (int threshold : new int[]{1, 2, 4, 16, 64}) {
            set(cfg, "threshold", threshold);
            same(baseline, execute("FORKJOIN", cfg, 4), "threshold=" + threshold);
        }
    }

    @Test
    void validArguments() throws Exception {
        Object cfg = parse("--method", "stream", "--threads", "1,2,2,4", "--width", "30",
                "--height", "20", "--iterations", "25", "--warmups", "0", "--runs", "2",
                "--threshold", "1", "--csv", "none", "--image", "none");
        eq(30, getInt(cfg, "width"), "width");
        eq(20, getInt(cfg, "height"), "height");
        eq(25, getInt(cfg, "maxIterations"), "iterations");
        eq(0, getInt(cfg, "warmups"), "warmups");
        eq(2, getInt(cfg, "runs"), "runs");
        same(new int[]{1, 2, 4}, (int[]) get(cfg, "threadCounts"), "deduplicated threads");
        yes(get(cfg, "csv") == null && get(cfg, "image") == null, "none disables output");
    }

    @Test
    void invalidArguments() {
        String[][] invalid = {
            {"--width", "1"}, {"--height", "1"}, {"--iterations", "0"},
            {"--warmups", "-1"}, {"--runs", "0"}, {"--threshold", "0"},
            {"--threads", "0"}, {"--threads", ""}, {"--method", "unknown"},
            {"--unexpected", "3"}, {"--height"}, {"--width", "50000", "--height", "50000"}
        };
        for (String[] args : invalid) {
            assertThrows(IllegalArgumentException.class, () -> parse(args),
                    "Should reject " + Arrays.toString(args));
        }
    }

    @Test
    void summaryStatistics() throws Exception {
        Constructor<?> ctor = RESULT.getDeclaredConstructor(METHOD, int.class, long[].class);
        ctor.setAccessible(true);
        Object result = ctor.newInstance(method("EXECUTOR"), 4,
                new long[]{1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L});
        close(2.5, getDouble(result, "medianMs"), 1e-9, "even median");
        close(1.0, getDouble(result, "minMs"), 1e-9, "minimum");
        close(4.0, getDouble(result, "maxMs"), 1e-9, "maximum");
        close(2.5, getDouble(result, "meanMs"), 1e-9, "mean");
        close(Math.sqrt(5.0 / 3.0), getDouble(result, "stddevMs"), 1e-9, "sample stddev");
        Object odd = ctor.newInstance(method("EXECUTOR"), 2,
                new long[]{7_000_000L, 3_000_000L, 5_000_000L});
        close(5.0, getDouble(odd, "medianMs"), 1e-9, "odd median");
    }

    @Test
    void csvAndPng() throws Exception {
        Path dir = tempDir.resolve("all-methods");
        Files.createDirectories(dir);
        Path csv = dir.resolve("result.csv");
        Path png = dir.resolve("result.png");
        invokeMain("--method", "all", "--threads", "1,2", "--width", "12",
                "--height", "8", "--iterations", "40", "--warmups", "0",
                "--runs", "2", "--csv", csv.toString(), "--image", png.toString());
        List<String> lines = Files.readAllLines(csv);
        eq(8, lines.size(), "header + 1 sequential + 2x3 parallel methods");
        String[] names = {"SEQUENTIAL", "EXECUTOR", "EXECUTOR", "FORKJOIN", "FORKJOIN", "STREAM", "STREAM"};
        eq(15, lines.get(0).split(",").length, "CSV header width");
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",");
            eq(15, fields.length, "CSV columns row " + i);
            eq(names[i - 1], fields[0], "method row " + i);
            eq("12", fields[2], "width row " + i);
            eq("8", fields[3], "height row " + i);
            eq("40", fields[4], "iterations row " + i);
            yes(Double.parseDouble(fields[8]) > 0, "positive median");
            yes(Double.parseDouble(fields[13]) > 0, "positive speedup");
        }
        close(1.0, Double.parseDouble(lines.get(1).split(",")[13]), 1e-9, "sequential speedup");
        BufferedImage image = ImageIO.read(png.toFile());
        yes(image != null, "PNG decodes");
        eq(12, image.getWidth(), "PNG width");
        eq(8, image.getHeight(), "PNG height");
        Object cfg = config(12, 8, 40, 2);
        int[] expected = execute("SEQUENTIAL", cfg, 1);
        int[] imagePixels = new int[96];
        image.getRGB(0, 0, 12, 8, imagePixels, 0, 12);
        for (int i = 0; i < 96; i++) {
            eq(expected[i], imagePixels[i] & 0x00FFFFFF, "PNG pixel " + i);
        }
    }

    @Test
    void singleMethodBenchmark() throws Exception {
        Path dir = tempDir.resolve("single-method");
        Files.createDirectories(dir);
        Path csv = dir.resolve("stream.csv");
        invokeMain("--method", "stream", "--threads", "2", "--width", "10",
                "--height", "10", "--iterations", "10", "--warmups", "0",
                "--runs", "1", "--csv", csv.toString(), "--image", "none");
        List<String> lines = Files.readAllLines(csv);
        eq(3, lines.size(), "header, baseline, selected method");
        yes(lines.get(1).startsWith("SEQUENTIAL,1,"), "sequential baseline");
        yes(lines.get(2).startsWith("STREAM,2,"), "selected stream");
    }

    private static Class<?> nested(String simple) {
        for (Class<?> c : APP.getDeclaredClasses()) if (c.getSimpleName().equals(simple)) return c;
        throw new AssertionError("Missing nested type " + simple);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object method(String name) { return Enum.valueOf((Class<Enum>) METHOD, name); }
    private static Method reflected(Class<?> owner, String name, Class<?>... args) throws Exception {
        Method m = owner.getDeclaredMethod(name, args); m.setAccessible(true); return m;
    }
    private static Object call(Method m, Object target, Object... args) throws Exception {
        try { return m.invoke(target, args); }
        catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw ex;
        }
    }
    private static int point(double r, double i, int cap) throws Exception {
        return (int) call(reflected(APP, "calculatePoint", double.class, double.class, int.class), null, r, i, cap);
    }
    private static int color(int iterations, int max) throws Exception {
        return (int) call(reflected(APP, "colorFor", int.class, int.class), null, iterations, max);
    }
    private static Object config(int w, int h, int cap, int threshold) throws Exception {
        Constructor<?> ctor = CFG.getDeclaredConstructor(); ctor.setAccessible(true);
        Object cfg = ctor.newInstance();
        set(cfg, "width", w); set(cfg, "height", h);
        set(cfg, "maxIterations", cap); set(cfg, "threshold", threshold);
        return cfg;
    }
    private static int[] execute(String name, Object cfg, int workers) throws Exception {
        int[] pixels = new int[getInt(cfg, "width") * getInt(cfg, "height")];
        call(reflected(APP, "run", METHOD, int[].class, int.class, CFG), null,
                method(name), pixels, workers, cfg);
        return pixels;
    }
    private static Object parse(String... args) throws Exception {
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            return call(reflected(APP, "parseArgs", String[].class), null, (Object) args);
        } finally { System.setOut(old); }
    }
    private static void invokeMain(String... args) throws Exception {
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            Mandelbrot.main(args);
        } finally { System.setOut(old); }
    }
    private static void set(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); f.set(obj, value);
    }
    private static Object get(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj);
    }
    private static int getInt(Object obj, String name) throws Exception { return (int) get(obj, name); }
    private static double getDouble(Object obj, String name) throws Exception { return (double) get(obj, name); }
    private static void yes(boolean ok, String message) { assertTrue(ok, message); }
    private static void same(int[] expected, int[] actual, String message) {
        assertArrayEquals(expected, actual, message);
    }
    private static void eq(long expected, long actual, String msg) {
        assertEquals(expected, actual, msg);
    }
    private static void eq(String expected, String actual, String msg) {
        assertEquals(expected, actual, msg);
    }
    private static void close(double expected, double actual, double tolerance, String msg) {
        assertEquals(expected, actual, tolerance, msg);
    }
}
