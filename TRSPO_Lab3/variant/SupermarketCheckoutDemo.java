package TRSPO_Lab3.variant;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Лабораторна робота № 3. Крок 5. Варіант № 12.
 * Каса супермаркету: обмежена черга до K кас.
 * Наївна версія (порушення інваріантів) vs BlockingQueue + Semaphore + AtomicLong,
 * а також порівняння швидкодії механізмів синхронізації на 10^7 операцій.
 */
public class SupermarketCheckoutDemo {

    static class Customer {
        final int id;
        final long arrivalNano;

        Customer(int id, long arrivalNano) {
            this.id = id;
            this.arrivalNano = arrivalNano;
        }
    }

    static final Customer POISON = new Customer(-1, 0);

    // ==================== 1. НАЇВНА ВЕРСІЯ (БЕЗ СИНХРОНІЗАЦІЇ) ====================
    static void runNaiveSupermarket(int totalCustomers, int numCashiers) throws InterruptedException {
        Queue<Customer> unsafeQueue = new LinkedList<>();
        int[] servedPerId = new int[totalCustomers];
        int[] activeCashiers = {0};
        int[] maxActive = {0};
        int[] exceptions = {0};
        long[] totalWaitNs = {0};

        long deadline = System.currentTimeMillis() + 1500; // запобіжник від зациклення при пошкодженні LinkedList

        Thread generator = new Thread(() -> {
            for (int i = 0; i < totalCustomers; i++) {
                unsafeQueue.add(new Customer(i, System.nanoTime()));
                try { Thread.sleep(1); } catch (InterruptedException ignored) {}
            }
        });

        Thread[] cashiers = new Thread[numCashiers * 2]; // 6 касирів конкурують за K=3 касові термінали
        for (int c = 0; c < cashiers.length; c++) {
            cashiers[c] = new Thread(() -> {
                while (System.currentTimeMillis() < deadline) {
                    Customer cust = null;
                    try {
                        if (!unsafeQueue.isEmpty()) {
                            Thread.sleep(1); // вікно гонки між перевіркою isEmpty() та poll()
                            cust = unsafeQueue.poll();
                        }
                    } catch (Exception e) {
                        exceptions[0]++;
                    }
                    if (cust != null) {
                        int cur = ++activeCashiers[0];
                        if (cur > maxActive[0]) maxActive[0] = cur;
                        servedPerId[cust.id]++; // неатомарний запис
                        totalWaitNs[0] += (System.nanoTime() - cust.arrivalNano);
                        try { Thread.sleep(3); } catch (InterruptedException ignored) {}
                        activeCashiers[0]--;
                    } else if (!generator.isAlive() && unsafeQueue.isEmpty()) {
                        break;
                    }
                }
            });
        }

        generator.start();
        for (Thread t : cashiers) t.start();
        generator.join();
        for (Thread t : cashiers) t.join();

        int lost = 0, duplicates = 0, totalServed = 0;
        for (int count : servedPerId) {
            totalServed += count;
            if (count == 0) lost++;
            if (count > 1) duplicates++;
        }

        System.out.println("=== 1. Наївна реалізація каси (без синхронізації) ===");
        System.out.printf("  Згенеровано покупців: %d | Обслужено записів: %d%n", totalCustomers, totalServed);
        System.out.printf("  Порушення інваріантів -> Втрачено: %d, Обслужено двічі: %d, Винятків черги: %d%n",
                lost, duplicates, exceptions[0]);
        System.out.printf("  Макс. одночасно зайнятих кас: %d (ліміт K = %d) -> %s%n",
                maxActive[0], numCashiers, maxActive[0] > numCashiers ? "ПЕРЕВИЩЕНО (ПОМИЛКА)" : "ОК");
    }

    // ==================== 2. ПОТОКОБЕЗПЕЧНА ВЕРСІЯ (BlockingQueue + Semaphore + AtomicLong) ====================
    static void runSafeSupermarket(int totalCustomers, int queueCapacity, int numCashiers) throws InterruptedException {
        BlockingQueue<Customer> queue = new ArrayBlockingQueue<>(queueCapacity);
        Semaphore terminals = new Semaphore(numCashiers); // ліміт K одночасно працюючих кас
        AtomicLong totalWaitNs = new AtomicLong(0);
        AtomicInteger servedCount = new AtomicInteger(0);
        AtomicInteger activeCashiers = new AtomicInteger(0);
        AtomicInteger maxActive = new AtomicInteger(0);
        AtomicIntegerArray servedPerId = new AtomicIntegerArray(totalCustomers);

        int workerThreads = numCashiers * 2; // 6 касирів працюють позмінно на K=3 касових терміналах

        Thread generator = new Thread(() -> {
            try {
                for (int i = 0; i < totalCustomers; i++) {
                    queue.put(new Customer(i, System.nanoTime()));
                }
                for (int w = 0; w < workerThreads; w++) {
                    queue.put(POISON);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Shopper-Generator");

        Thread[] cashiers = new Thread[workerThreads];
        for (int c = 0; c < workerThreads; c++) {
            cashiers[c] = new Thread(() -> {
                try {
                    while (true) {
                        Customer cust = queue.take();
                        if (cust == POISON) break;

                        terminals.acquire(); // займаємо один із K касових терміналів
                        try {
                            int cur = activeCashiers.incrementAndGet();
                            maxActive.accumulateAndGet(cur, Math::max);

                            long waitNs = System.nanoTime() - cust.arrivalNano;
                            totalWaitNs.addAndGet(waitNs);
                            servedPerId.incrementAndGet(cust.id);
                            servedCount.incrementAndGet();

                            // Імітація обслуговування на касі (1..4 мс)
                            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                        } finally {
                            activeCashiers.decrementAndGet();
                            terminals.release(); // обов'язково звільняємо касовий термінал
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Cashier-" + (c + 1));
        }

        long t0 = System.nanoTime();
        generator.start();
        for (Thread t : cashiers) t.start();
        generator.join();
        for (Thread t : cashiers) t.join();
        double elapsedMs = (System.nanoTime() - t0) / 1e6;

        // Автоматична перевірка інваріантів
        int lost = 0, duplicates = 0;
        for (int i = 0; i < totalCustomers; i++) {
            int s = servedPerId.get(i);
            if (s == 0) lost++;
            else if (s > 1) duplicates++;
        }

        boolean invariantOk = (lost == 0) && (duplicates == 0)
                && (servedCount.get() == totalCustomers)
                && (maxActive.get() <= numCashiers);

        System.out.println("\n=== 2. Потокобезпечна реалізація (BlockingQueue + Semaphore + AtomicLong) ===");
        System.out.printf("  Згенеровано: %d | Обслужено: %d | Втрачено: %d | Дублікатів: %d%n",
                totalCustomers, servedCount.get(), lost, duplicates);
        System.out.printf("  Макс. одночасно зайнятих кас: %d (ліміт K = %d)%n", maxActive.get(), numCashiers);
        System.out.printf("  Середній час очікування покупця: %.2f мс | Загальний час: %.1f мс%n",
                (totalWaitNs.get() / 1e6) / totalCustomers, elapsedMs);
        System.out.println("  Перевірка інваріантів: " + (invariantOk ? "УСПІШНО (УСІ ІНВАРІАНТИ ЗБЕРЕЖЕНО)" : "ПОМИЛКА"));
    }

    // ==================== 3. СТЕНД ВИМІРЮВАННЯ ШВИДКОДІЇ (10^7 ОПЕРАЦІЙ) ====================
    static class UnsafeStat {
        long count = 0;
        void add() { count++; }
    }

    static class SyncStat {
        private long count = 0;
        synchronized void add() { count++; }
        synchronized long get() { return count; }
    }

    static class LockStat {
        private final Lock lock = new ReentrantLock();
        private long count = 0;
        void add() {
            lock.lock();
            try { count++; } finally { lock.unlock(); }
        }
        long get() {
            lock.lock();
            try { return count; } finally { lock.unlock(); }
        }
    }

    static double runSingleBench(int threads, long totalOps, Runnable op) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long perThread = totalOps / threads;
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (long i = 0; i < perThread; i++) {
                    op.run();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        return (System.nanoTime() - t0) / 1e6;
    }

    static double medianBench(int threads, long totalOps, java.util.function.Supplier<Runnable> opFactory) throws Exception {
        final int WARMUP = 2;
        final int RUNS = 3;
        for (int i = 0; i < WARMUP; i++) {
            runSingleBench(threads, totalOps, opFactory.get());
        }
        double[] times = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            times[i] = runSingleBench(threads, totalOps, opFactory.get());
        }
        Arrays.sort(times);
        return times[RUNS / 2];
    }

    public static void main(String[] args) throws Exception {
        runNaiveSupermarket(120, 3);
        runSafeSupermarket(120, 15, 3);

        final long OPS = 10_000_000L;
        int[] threadCounts = {1, 2, 4, 8};

        System.out.println("\n=== 3. Порівняння швидкодії синхронізації статистики каси (10^7 операцій, медіана, мс) ===");
        System.out.println("--------------------------------------------------------------------------------------");
        System.out.printf("%-4s | %-18s | %-15s | %-15s | %-15s%n",
                "p", "Без синхр. (помил.)", "synchronized", "ReentrantLock", "AtomicLong");
        System.out.println("--------------------------------------------------------------------------------------");

        for (int p : threadCounts) {
            long alignedOps = (OPS / p) * p;

            UnsafeStat usCheck = new UnsafeStat();
            runSingleBench(p, alignedOps, usCheck::add);

            SyncStat scCheck = new SyncStat();
            runSingleBench(p, alignedOps, scCheck::add);

            LockStat lcCheck = new LockStat();
            runSingleBench(p, alignedOps, lcCheck::add);

            AtomicLong acCheck = new AtomicLong(0);
            runSingleBench(p, alignedOps, acCheck::incrementAndGet);

            if (scCheck.get() != alignedOps || lcCheck.get() != alignedOps || acCheck.get() != alignedOps) {
                throw new IllegalStateException("Помилка лічильника при p = " + p);
            }

            double tUnsafe = medianBench(p, alignedOps, () -> new UnsafeStat()::add);
            double tSync = medianBench(p, alignedOps, () -> new SyncStat()::add);
            double tLock = medianBench(p, alignedOps, () -> new LockStat()::add);
            double tAtomic = medianBench(p, alignedOps, () -> new AtomicLong()::incrementAndGet);

            System.out.printf("%-4d | %7.2f (%8d) | %15.2f | %15.2f | %15.2f%n",
                    p, tUnsafe, usCheck.count, tSync, tLock, tAtomic);
        }
        System.out.println("--------------------------------------------------------------------------------------");
    }
}