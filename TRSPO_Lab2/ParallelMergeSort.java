package TRSPO_Lab2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Лабораторна робота № 2. Завдання 4, 5, 6. Варіант № 12.
 * Паралельне сортування частин масиву (10^7 int) за допомогою Arrays.sort
 * з подальшим k-шляховим злиттям у головному потоці.
 */
public class ParallelMergeSort {

    static final int N = 10_000_000; // розмір масиву за варіантом 12
    static final int WARMUP = 3;     // кількість запусків для прогріву JVM
    static final int RUNS = 5;       // кількість вимірів для медіани

    interface SortRunner {
        int[] sort() throws Exception;
    }

    /** Задача для Варіанту А (Runnable): параметри в final-полях, сортує свій діапазон [from, to). */
    static class SortTask implements Runnable {
        private final int[] array;
        private final int from;
        private final int to;

        SortTask(int[] array, int from, int to) {
            this.array = array;
            this.from = from;
            this.to = to;
        }

        @Override
        public void run() {
            Arrays.sort(array, from, to);
        }

        int getFrom() {
            return from;
        }

        int getTo() {
            return to;
        }
    }

    /** k-шляхове злиття p відсортованих підмасивів [starts[i], ends[i]) в один масив. */
    static int[] mergeSortedRanges(int[] arr, int[] starts, int[] ends) {
        int p = starts.length;
        int[] idx = Arrays.copyOf(starts, p);
        int[] merged = new int[arr.length];

        for (int out = 0; out < arr.length; out++) {
            int bestPart = -1;
            int minVal = Integer.MAX_VALUE;
            for (int k = 0; k < p; k++) {
                if (idx[k] < ends[k] && arr[idx[k]] <= minVal) {
                    minVal = arr[idx[k]];
                    bestPart = k;
                }
            }
            merged[out] = minVal;
            idx[bestPart]++;
        }
        return merged;
    }

    /** Варіант А (Завдання 4): власні потоки Thread + Runnable + join(). */
    static int[] sortWithThreads(int[] data, int p) throws InterruptedException {
        if (p == 1) {
            Arrays.sort(data);
            return data;
        }

        SortTask[] tasks = new SortTask[p];
        Thread[] threads = new Thread[p];
        int chunk = (data.length + p - 1) / p;

        for (int i = 0; i < p; i++) {
            int from = Math.min(i * chunk, data.length);
            int to = Math.min(from + chunk, data.length);
            tasks[i] = new SortTask(data, from, to);
            threads[i] = new Thread(tasks[i], "sort-worker-" + i);
            threads[i].start();
        }

        int[] starts = new int[p];
        int[] ends = new int[p];
        for (int i = 0; i < p; i++) {
            threads[i].join(); // спочатку чекаємо завершення потоку...
            starts[i] = tasks[i].getFrom(); // ...потім беремо межі відсортованої частини
            ends[i] = tasks[i].getTo();
        }

        return mergeSortedRanges(data, starts, ends);
    }

    /** Варіант В (Завдання 5): пул потоків ExecutorService + Callable/Future. */
    static int[] sortWithPool(int[] data, int p) throws Exception {
        if (p == 1) {
            Arrays.sort(data);
            return data;
        }

        ExecutorService pool = Executors.newFixedThreadPool(p);
        try {
            List<Callable<int[]>> jobs = new ArrayList<>();
            int chunk = (data.length + p - 1) / p;

            for (int i = 0; i < p; i++) {
                final int from = Math.min(i * chunk, data.length);
                final int to = Math.min(from + chunk, data.length);
                jobs.add(() -> {
                    Arrays.sort(data, from, to);
                    return new int[]{from, to}; // повертаємо межі відсортованого діапазону
                });
            }

            List<Future<int[]>> futures = pool.invokeAll(jobs);
            int[] starts = new int[p];
            int[] ends = new int[p];
            for (int i = 0; i < p; i++) {
                int[] range = futures.get(i).get();
                starts[i] = range[0];
                ends[i] = range[1];
            }

            return mergeSortedRanges(data, starts, ends);
        } finally {
            pool.shutdown(); // обов'язково закриваємо пул
        }
    }

    /** Перевірка впорядкованості масиву за неспаданням. */
    static boolean isSorted(int[] arr) {
        for (int i = 1; i < arr.length; i++) {
            if (arr[i - 1] > arr[i]) {
                return false;
            }
        }
        return true;
    }

    /** Вимірювання медіани 5 запусків після 3 прогрівів (копіювання масиву винесено за межі таймера). */
    static double medianMillis(int[] source, java.util.function.Function<int[], SortRunner> factory) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            int[] copy = Arrays.copyOf(source, source.length);
            factory.apply(copy).sort();
        }
        double[] times = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            int[] copy = Arrays.copyOf(source, source.length);
            SortRunner runner = factory.apply(copy);
            long t0 = System.nanoTime();
            runner.sort();
            times[i] = (System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(times);
        return times[RUNS / 2];
    }

    /** Демонстрація різних типів пулів потоків (Завдання 6.3). */
    static void testOtherPools(int[] source, int[] expected, int parts) throws Exception {
        ExecutorService[] pools = {
            Executors.newCachedThreadPool(),
            Executors.newSingleThreadExecutor()
        };
        String[] names = {"newCachedThreadPool()", "newSingleThreadExecutor()"};

        System.out.println("\nПорівняння інших типів пулів (розбиття на " + parts + " задач):");
        for (int idx = 0; idx < pools.length; idx++) {
            ExecutorService pool = pools[idx];
            int[] data = Arrays.copyOf(source, source.length);
            long t0 = System.nanoTime();
            try {
                List<Callable<int[]>> jobs = new ArrayList<>();
                int chunk = (data.length + parts - 1) / parts;
                for (int i = 0; i < parts; i++) {
                    final int from = Math.min(i * chunk, data.length);
                    final int to = Math.min(from + chunk, data.length);
                    jobs.add(() -> {
                        Arrays.sort(data, from, to);
                        return new int[]{from, to};
                    });
                }
                List<Future<int[]>> futures = pool.invokeAll(jobs);
                int[] starts = new int[parts];
                int[] ends = new int[parts];
                for (int i = 0; i < parts; i++) {
                    int[] r = futures.get(i).get();
                    starts[i] = r[0];
                    ends[i] = r[1];
                }
                int[] merged = mergeSortedRanges(data, starts, ends);
                double ms = (System.nanoTime() - t0) / 1e6;
                boolean ok = isSorted(merged) && Arrays.equals(merged, expected);
                System.out.printf("  %-26s -> час: %7.2f мс | коректність: %s%n", names[idx], ms, ok ? "ОК" : "ПОМИЛКА");
            } finally {
                pool.shutdown();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Варіант 12: Паралельне сортування масиву int[" + String.format("%,d", N) + "] зі злиттям");
        System.out.println("Логічних процесорів: " + Runtime.getRuntime().availableProcessors());

        // Генерація початкового масиву з фіксованим seed = 42
        SplittableRandom rnd = new SplittableRandom(42);
        int[] source = new int[N];
        for (int i = 0; i < N; i++) {
            source[i] = rnd.nextInt();
        }

        // Еталонне послідовне сортування
        int[] expected = Arrays.copyOf(source, source.length);
        Arrays.sort(expected);

        int[] threadCounts = {1, 2, 4, 8};
        double t1Threads = 0;
        double t1Pool = 0;

        System.out.println("\n---------------------------------------------------------------------------------------");
        System.out.printf("%-4s | %-15s | %-17s | %-13s | %-13s | %-12s%n",
                "p", "Thread+join, мс", "ExecutorPool, мс", "S_p (Thread)", "S_p (Pool)", "Перевірка");
        System.out.println("---------------------------------------------------------------------------------------");

        for (int p : threadCounts) {
            // Перевірка коректності обох реалізацій
            int[] resThreads = sortWithThreads(Arrays.copyOf(source, source.length), p);
            int[] resPool = sortWithPool(Arrays.copyOf(source, source.length), p);

            if (!isSorted(resThreads) || !Arrays.equals(resThreads, expected)
                    || !isSorted(resPool) || !Arrays.equals(resPool, expected)) {
                throw new IllegalStateException("Помилка впорядкованості при p = " + p);
            }

            double timeThreads = medianMillis(source, arr -> () -> sortWithThreads(arr, p));
            double timePool = medianMillis(source, arr -> () -> sortWithPool(arr, p));

            if (p == 1) {
                t1Threads = timeThreads;
                t1Pool = timePool;
            }

            System.out.printf("%-4d | %15.2f | %17.2f | %13.2f | %13.2f | %-12s%n",
                    p, timeThreads, timePool, t1Threads / timeThreads, t1Pool / timePool, "збіг (ОК)");
        }
        System.out.println("---------------------------------------------------------------------------------------");

        // Тест інших типів пулів (Завдання 6.3)
        testOtherPools(source, expected, 8);
    }
}