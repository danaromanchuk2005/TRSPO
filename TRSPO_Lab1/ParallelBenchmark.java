import java.util.Arrays;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * Лабораторна робота № 1. Варіант 12.
 * Підрахунок кількості входжень підрядка (довжина 3-5) у рядку з алфавіту 4 символів.
 */
public class ParallelBenchmark {

    static final int WARMUP = 3; // кількість запусків для прогріву JIT-компілятора
    static final int RUNS = 5;   // кількість вимірів для обчислення медіани

    // Алфавіт із 4 символів та шуканий підрядок довжиною m = 4 (входить у діапазон 3-5)
    static final char[] ALPHABET = {'A', 'C', 'G', 'T'};
    static final char[] PATTERN = {'A', 'C', 'G', 'T'};

    interface Task {
        void run() throws Exception;
    }

    // 1. Послідовна версія (еталон)
    static long countSeq(char[] text, char[] pattern) {
        long count = 0;
        int n = text.length;
        int m = pattern.length;

        for (int i = 0; i <= n - m; i++) {
            boolean match = true;
            for (int j = 0; j < m; j++) {
                if (text[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                count++;
            }
        }
        return count;
    }

    // 2. Паралельна версія на потоках Java (Thread + join)
    static long countPar(char[] text, char[] pattern, int p) throws InterruptedException {
        int n = text.length;
        int m = pattern.length;
        long[] partial = new long[p];   // окрема комірка для кожного потоку (без гонитви даних)
        Thread[] threads = new Thread[p];
        int chunk = n / p;

        for (int t = 0; t < p; t++) {
            final int id = t;
            final int from = t * chunk;
            final int baseTo = (t == p - 1) ? n : from + chunk;

            // Розширюємо праву межу читання на (m - 1) символів,
            // щоб врахувати входження підрядка, які перетинають межу між потоками
            final int readEnd = Math.min(baseTo + m - 1, n);

            threads[t] = new Thread(() -> {
                long localCount = 0;
                for (int i = from; i <= readEnd - m; i++) {
                    boolean match = true;
                    for (int j = 0; j < m; j++) {
                        if (text[i + j] != pattern[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        localCount++;
                    }
                }
                partial[id] = localCount;
            });
            threads[t].start();
        }

        // Чекаємо завершення всіх потоків
        for (Thread th : threads) {
            th.join();
        }

        // Редукція (підсумовування) часткових результатів у головному потоці
        long total = 0;
        for (long c : partial) {
            total += c;
        }
        return total;
    }

    // 3. Функція вимірювання часу: 3 прогріви + медіана 5 запусків (з r повторами для малих задач)
    static double medianMillis(Task task, int repeats) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            for (int r = 0; r < repeats; r++) {
                task.run();
            }
        }
        double[] times = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            for (int r = 0; r < repeats; r++) {
                task.run();
            }
            times[i] = (System.nanoTime() - t0) / 1e6 / repeats;
        }
        Arrays.sort(times);
        return times[RUNS / 2];
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("Логічних процесорів (JVM): " + cores);
        System.out.println("Шуканий підрядок: " + new String(PATTERN) + " (довжина m = " + PATTERN.length + ")");

        // Формуємо набір кількості потоків: 1, 2, 4, 8, 16... до 2*cores + самі значення cores і 2*cores
        Set<Integer> pSet = new TreeSet<>();
        for (int p = 1; p <= 2 * cores; p *= 2) {
            pSet.add(p);
        }
        pSet.add(cores);
        pSet.add(2 * cores);

        // Малий (10^6), середній (10^7), великий (2.5*10^8 — збільшено, щоб T1 > 500 мс)
        int[] sizes = {1_000_000, 10_000_000, 250_000_000};
        SplittableRandom rnd = new SplittableRandom(42);

        for (int n : sizes) {
            // Для малого розміру повторюємо обчислення 10 разів за один вимір для точності
            int repeats = (n == 1_000_000) ? 10 : 1;

            // Генерація вхідного рядка виконується ОДИН раз поза вимірами часу
            char[] text = new char[n];
            for (int i = 0; i < n; i++) {
                text[i] = ALPHABET[rnd.nextInt(ALPHABET.length)];
            }

            long ref = countSeq(text, PATTERN);
            double t1 = medianMillis(() -> countSeq(text, PATTERN), repeats);

            System.out.printf("%n====================================================================%n");
            System.out.printf("Розмір рядка L = %,d | Знайдено збігів = %,d | T1 (посл.) = %.2f мс%n", n, ref, t1);
            System.out.printf("--------------------------------------------------------------------%n");
            System.out.printf("%-10s | %-10s | %-8s | %-8s | %-12s%n", "p", "Tp, мс", "Sp", "Ep", "f (Карп-Фл.)");
            System.out.printf("--------------------------------------------------------------------%n");
            System.out.printf("%-10s | %10.2f | %8.2f | %8.2f | %-12s%n", "1 (посл.)", t1, 1.00, 1.00, "-");

            for (int p : pSet) {
                // Перевірка коректності результату
                long res = countPar(text, PATTERN, p);
                if (res != ref) {
                    throw new IllegalStateException(
                        "ПОМИЛКА при p=" + p + ": очікувалось " + ref + ", отримано " + res
                    );
                }

                double tp = medianMillis(() -> countPar(text, PATTERN, p), repeats);
                double sp = t1 / tp;
                double ep = sp / p;

                if (p == 1) {
                    System.out.printf("%-10s | %10.2f | %8.2f | %8.2f | %-12s%n", "1 (пар.)", tp, sp, ep, "-");
                } else {
                    double f = (1.0 / sp - 1.0 / p) / (1.0 - 1.0 / p);
                    System.out.printf("%-10d | %10.2f | %8.2f | %8.2f | %12.3f%n", p, tp, sp, ep, f);
                }
            }
        }
    }
}