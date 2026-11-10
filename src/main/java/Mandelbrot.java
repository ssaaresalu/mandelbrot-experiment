import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class Mandelbrot {
    private static final double MIN_REAL = -2.0;
    private static final double MAX_REAL = 1.0;
    private static final double MIN_IMAGINARY = -1.2;
    private static final double MAX_IMAGINARY = 1.2;
    private static final long TIMEOUT_MINUTES = 10;

    private enum Method {SEQUENTIAL, EXECUTOR, FORKJOIN, STREAM}

    private static class Config {
        int width = 1920;
        int height = 1080;
        int maxIterations = 1000;
        int warmups = 5;
        int runs = 10;
        int threshold = 16; // Fork/join leaf task size in rows.
        int[] threadCounts = defaultThreadCounts();
        String selectedMethod = "all";
        Path csv = Path.of("mandelbrot_results.csv");
        Path image = Path.of("mandelbrot.png");
    }

    private static class Result {
        Method method;
        int threads;
        long[] nanos;
        double medianMs;
        double minMs;
        double maxMs;
        double meanMs;
        double stddevMs;

        Result(Method method, int threads, long[] times) {
            this.method = method;
            this.threads = threads;
            this.nanos = times;
            long[] sorted = times.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            medianMs = (n % 2 == 0)
                    ? (sorted[n / 2 - 1] / 2.0 + sorted[n / 2] / 2.0) / 1_000_000.0
                    : sorted[n / 2] / 1_000_000.0;
            minMs = sorted[0] / 1_000_000.0;
            maxMs = sorted[n - 1] / 1_000_000.0;
            double sum = 0;
            for (long time : times) sum += time / 1_000_000.0;
            meanMs = sum / n;
            double squared = 0;
            for (long time : times) {
                double delta = time / 1_000_000.0 - meanMs;
                squared += delta * delta;
            }
            stddevMs = n > 1 ? Math.sqrt(squared / (n - 1)) : 0;
        }
    }

    public static void main(String[] args) throws Exception {
        Config cfg = parseArgs(args);
        if (cfg == null) return;

        System.out.printf(Locale.US,
                "main.java.Mandelbrot: %dx%d, max iterations=%d, warmups=%d, timed runs=%d%n",
                cfg.width, cfg.height, cfg.maxIterations, cfg.warmups, cfg.runs);
        System.out.printf("Java: %s; reported available processors: %d%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());

        // Independent, single-thread reference image. Always check that the
        // parallel methods generate IDENTICAL pixels before timing them.
        int[] reference = new int[cfg.width * cfg.height];
        run(Method.SEQUENTIAL, reference, 1, cfg);
        System.out.println("Checking outputs against the sequential reference...");

        List<Method> methods = selectedMethods(cfg.selectedMethod);
        List<Result> results = new ArrayList<>();
        for (Method method : methods) {
            int[] counts = method == Method.SEQUENTIAL ? new int[]{1} : cfg.threadCounts;
            for (int threads : counts) {
                int[] pixels = new int[reference.length];
                run(method, pixels, threads, cfg);
                if (!Arrays.equals(reference, pixels)) {
                    throw new IllegalStateException("Incorrect pixels: " + method + " (threads=" + threads + ")");
                }
                System.out.printf("  %-10s %2d worker(s): OK%n", method, threads);
            }
        }

        System.out.println("Benchmarking (time excludes allocation, validation, CSV and PNG)...");
        // Run the sequential benchmark first even when only a parallel method
        // was requested, so speedups use a measured sequential baseline.
        Result baseline = benchmark(Method.SEQUENTIAL, 1, cfg);
        results.add(baseline);
        for (Method method : methods) {
            if (method == Method.SEQUENTIAL) continue;
            for (int threads : cfg.threadCounts) {
                results.add(benchmark(method, threads, cfg));
            }
        }

        System.out.printf(Locale.US, "%n%-11s %7s %12s %12s %12s %10s %10s%n",
                "Method", "Workers", "Median(ms)", "Min(ms)", "Max(ms)", "Speedup", "Efficiency");
        for (Result result : results) {
            double speedup = baseline.medianMs / result.medianMs;
            double efficiency = speedup / result.threads;
            System.out.printf(Locale.US, "%-11s %7d %12.3f %12.3f %12.3f %10.3f %9.1f%%%n",
                    result.method, result.threads, result.medianMs,
                    result.minMs, result.maxMs, speedup, efficiency * 100);
        }

        if (cfg.csv != null) {
            writeCsv(cfg.csv, cfg, results, baseline);
            System.out.println("Saved summary CSV: " + cfg.csv);
        }
        if (cfg.image != null) {
            saveImage(reference, cfg.width, cfg.height, cfg.image);
            System.out.println("Saved reference PNG: " + cfg.image);
        }
    }

    private static Result benchmark(Method method, int threads, Config cfg) throws Exception {
        int[] pixels = new int[cfg.width * cfg.height];
        for (int i = 0; i < cfg.warmups; i++) run(method, pixels, threads, cfg);
        long[] times = new long[cfg.runs];
        for (int i = 0; i < cfg.runs; i++) {
            long start = System.nanoTime();
            run(method, pixels, threads, cfg);
            times[i] = System.nanoTime() - start;
        }
        // Running a checksum after timing makes the computed result observable.
        long checksum = 0;
        for (int pixel : pixels) checksum = checksum * 31 + pixel;
        System.out.printf(Locale.US, "  %-10s %2d worker(s): median %.3f ms; checksum %016x%n",
                method, threads, new Result(method, threads, times).medianMs, checksum);
        return new Result(method, threads, times);
    }

    private static void run(Method method, int[] pixels, int workers, Config cfg) throws Exception {
        switch (method) {
            case SEQUENTIAL:
                for (int y = 0; y < cfg.height; y++) calculateRow(pixels, y, cfg);
                break;
            case EXECUTOR:
                runExecutor(pixels, workers, cfg);
                break;
            case FORKJOIN:
                runForkJoin(pixels, workers, cfg);
                break;
            case STREAM:
                runParallelStream(pixels, workers, cfg);
                break;
            default:
                throw new AssertionError(method);
        }
    }

    private static void runExecutor(int[] pixels, int workers, Config cfg) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Void>> tasks = new ArrayList<>(cfg.height);
            for (int y = 0; y < cfg.height; y++) {
                final int row = y;
                tasks.add(() -> {
                    calculateRow(pixels, row, cfg);
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks, TIMEOUT_MINUTES, TimeUnit.MINUTES);
            for (Future<Void> future : futures) {
                // get() propagates task exceptions; invokeAll() enforces a shared deadline.
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static class RowTask extends RecursiveAction {
        private final int[] pixels;
        private final int from, to;
        private final Config cfg;

        RowTask(int[] pixels, int from, int to, Config cfg) {
            this.pixels = pixels;
            this.from = from;
            this.to = to;
            this.cfg = cfg;
        }

        @Override
        protected void compute() {
            if (to - from <= cfg.threshold) {
                for (int y = from; y < to; y++) calculateRow(pixels, y, cfg);
            } else {
                int mid = from + (to - from) / 2;
                invokeAll(new RowTask(pixels, from, mid, cfg),
                        new RowTask(pixels, mid, to, cfg));
            }
        }
    }

    private static void runForkJoin(int[] pixels, int workers, Config cfg) throws Exception {
        ForkJoinPool pool = new ForkJoinPool(workers);
        try {
            pool.submit(new RowTask(pixels, 0, cfg.height, cfg))
                    .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void runParallelStream(int[] pixels, int workers, Config cfg) throws Exception {
        // A custom pool makes the stream's parallelism comparable with the
        // explicit worker counts of ExecutorService and ForkJoinPool.
        ForkJoinPool pool = new ForkJoinPool(workers);
        try {
            pool.submit(() -> IntStream.range(0, cfg.height)
                            .parallel().forEach(y -> calculateRow(pixels, y, cfg)))
                    .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void calculateRow(int[] pixels, int y, Config cfg) {
        double imaginary = MIN_IMAGINARY
                + y * (MAX_IMAGINARY - MIN_IMAGINARY) / (cfg.height - 1);
        int offset = y * cfg.width;
        for (int x = 0; x < cfg.width; x++) {
            double real = MIN_REAL
                    + x * (MAX_REAL - MIN_REAL) / (cfg.width - 1);
            int iterations = calculatePoint(real, imaginary, cfg.maxIterations);
            pixels[offset + x] = colorFor(iterations, cfg.maxIterations);
        }
    }

    private static int calculatePoint(double cReal, double cImaginary, int maxIterations) {
        double zReal = 0.0;
        double zImaginary = 0.0;
        int iteration = 0;
        while (iteration < maxIterations
                && zReal * zReal + zImaginary * zImaginary <= 4.0) {
            double nextReal = zReal * zReal - zImaginary * zImaginary + cReal;
            double nextImaginary = 2.0 * zReal * zImaginary + cImaginary;
            zReal = nextReal;
            zImaginary = nextImaginary;
            iteration++;
        }
        return iteration;
    }

    private static int colorFor(int iterations, int maxIterations) {
        if (iterations == maxIterations) return 0x000000;
        return Color.HSBtoRGB(iterations / 100.0f, 0.9f, 1.0f) & 0x00ffffff;
    }

    private static void saveImage(int[] pixels, int width, int height, Path output) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("No PNG writer available");
        }
    }

    private static void writeCsv(Path output, Config cfg, List<Result> results, Result baseline)
            throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(output)) {
            out.write("method,workers,width,height,max_iterations,forkjoin_threshold,warmups,runs,median_ms,min_ms,max_ms,mean_ms,stddev_ms,speedup,efficiency\n");
            for (Result result : results) {
                double speedup = baseline.medianMs / result.medianMs;
                out.write(String.format(Locale.US,
                        "%s,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        result.method, result.threads, cfg.width, cfg.height, cfg.maxIterations,
                        cfg.threshold, cfg.warmups, cfg.runs, result.medianMs, result.minMs,
                        result.maxMs, result.meanMs, result.stddevMs, speedup,
                        speedup / result.threads));
            }
        }
    }

    private static List<Method> selectedMethods(String name) {
        if ("all".equals(name)) return Arrays.asList(Method.values());
        return Collections.singletonList(Method.valueOf(name.toUpperCase(Locale.ROOT)));
    }

    private static int[] defaultThreadCounts() {
        int available = Runtime.getRuntime().availableProcessors();
        return IntStream.of(1, 2, 4, 8).filter(n -> n <= available).toArray();
    }

    private static Config parseArgs(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (++i >= args.length) throw new IllegalArgumentException("Missing value after " + option);
            String value = args[i];
            switch (option) {
                case "--method":
                    c.selectedMethod = value.toLowerCase(Locale.ROOT);
                    break;
                case "--threads":
                    c.threadCounts = Arrays.stream(value.split(","))
                            .mapToInt(Integer::parseInt).distinct().toArray();
                    break;
                case "--width":
                    c.width = Integer.parseInt(value);
                    break;
                case "--height":
                    c.height = Integer.parseInt(value);
                    break;
                case "--iterations":
                    c.maxIterations = Integer.parseInt(value);
                    break;
                case "--warmups":
                    c.warmups = Integer.parseInt(value);
                    break;
                case "--runs":
                    c.runs = Integer.parseInt(value);
                    break;
                case "--threshold":
                    c.threshold = Integer.parseInt(value);
                    break;
                case "--csv":
                    c.csv = "none".equalsIgnoreCase(value) ? null : Path.of(value);
                    break;
                case "--image":
                    c.image = "none".equalsIgnoreCase(value) ? null : Path.of(value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        if (c.width < 2 || c.height < 2 || (long) c.width * c.height > Integer.MAX_VALUE
                || c.maxIterations < 1 || c.warmups < 0 || c.runs < 1 || c.threshold < 1
                || c.threadCounts.length == 0 || Arrays.stream(c.threadCounts).anyMatch(n -> n < 1)) {
            throw new IllegalArgumentException("Invalid configuration; use --help to see valid options");
        }
        selectedMethods(c.selectedMethod); // Validate method before doing any computation.
        return c;
    }
}
