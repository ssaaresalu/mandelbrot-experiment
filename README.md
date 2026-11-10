# Mandelbrot Benchmark

Java 25 CPU benchmark comparing sequential execution, `ExecutorService`, `ForkJoinPool`, and parallel streams. All methods calculate the same Mandelbrot image, parallel results are checked against the sequential reference. The program reports execution time, speedup, and efficiency, and can export CSV results and a PNG image.

## Run

```bash
javac Mandelbrot.java
java Mandelbrot --threads 1,2,4,6,8,12,16 --image none
```

## Arguments

| Argument | Default | Meaning |
|---|---|---|
| `--method` | `all` | `all`, `sequential`, `executor`, `forkjoin`, `stream` |
| `--threads` | `1,2,4,8`* | Worker counts, comma-separated |
| `--width` | `1920` | Image width (≥ 2) |
| `--height` | `1080` | Image height (≥ 2) |
| `--iterations` | `1000` | Maximum iterations per pixel |
| `--warmups` | `5` | Untimed runs before measurement |
| `--runs` | `10` | Timed runs per configuration |
| `--threshold` | `16` | Fork/Join leaf-task size in rows |
| `--csv` | `mandelbrot_results.csv` | CSV path; `none` disables output |
| `--image` | `mandelbrot.png` | PNG path; `none` disables output |

*Default worker counts are restricted to the processor count detected by Java; explicitly supplied counts may be higher. The sequential baseline is always measured.

## Tests

```bash
mvn test
```
