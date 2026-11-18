package TRSPO_Lab3.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Лабораторна робота № 3. Крок 2.
 * Усунення гонки: synchronized-методи, окремі замки, ReentrantLock з tryLock, AtomicInteger.
 */
public class LocksComparisonDemo {

    /** Спосіб А: synchronized-методи (спільний монітор this). */
    static class SyncCounter {
        private int c = 0;

        public synchronized void increment() throws InterruptedException {
            int a = c;
            Thread.sleep(150);
            c = a + 1;
        }

        public synchronized int value() {
            return c;
        }
    }

    /** Спосіб Б (варіант 1): один спільний монітор для двох незалежних лічильників. */
    static class SharedLockTwoCounters {
        private int a = 0, b = 0;

        public synchronized void incA() throws InterruptedException {
            int t = a;
            Thread.sleep(50);
            a = t + 1;
        }

        public synchronized void incB() throws InterruptedException {
            int t = b;
            Thread.sleep(50);
            b = t + 1;
        }

        public synchronized int a() { return a; }
        public synchronized int b() { return b; }
    }

    /** Спосіб Б (варіант 2): окремі об'єкти-замки для незалежних лічильників. */
    static class SeparateLocksTwoCounters {
        private final Object lockA = new Object();
        private final Object lockB = new Object();
        private int a = 0, b = 0;

        public void incA() throws InterruptedException {
            synchronized (lockA) {
                int t = a;
                Thread.sleep(50);
                a = t + 1;
            }
        }

        public void incB() throws InterruptedException {
            synchronized (lockB) {
                int t = b;
                Thread.sleep(50);
                b = t + 1;
            }
        }

        public int a() { synchronized (lockA) { return a; } }
        public int b() { synchronized (lockB) { return b; } }
    }

    /** Спосіб В: ReentrantLock з tryLock(таймаут) у try-finally. */
    static class LockCounter {
        private final Lock lock = new ReentrantLock();
        private int c = 0;
        private int skipped = 0;

        public void increment(long timeoutMs) throws InterruptedException {
            if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                try {
                    int a = c;
                    Thread.sleep(150);
                    c = a + 1;
                } finally {
                    lock.unlock();
                }
            } else {
                synchronized (this) {
                    skipped++;
                }
                System.out.printf("  [%s] замок зайнятий понад %d мс, операцію пропущено%n",
                        Thread.currentThread().getName(), timeoutMs);
            }
        }

        public int value() {
            lock.lock();
            try {
                return c;
            } finally {
                lock.unlock();
            }
        }

        public synchronized int skipped() {
            return skipped;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // --- Спосіб А ---
        System.out.println("=== Спосіб А: synchronized-методи ===");
        SyncCounter syncCounter = new SyncCounter();
        long t0 = System.nanoTime();
        Thread t1 = new Thread(() -> {
            try { for (int i = 0; i < 5; i++) syncCounter.increment(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "T1");
        Thread t2 = new Thread(() -> {
            try { for (int i = 0; i < 5; i++) syncCounter.increment(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "T2");
        t1.start(); t2.start();
        t1.join(); t2.join();
        System.out.printf("Очікувано: 10, отримано: %d (час: %.0f мс)%n",
                syncCounter.value(), (System.nanoTime() - t0) / 1e6);

        // --- Спосіб Б ---
        System.out.println("\n=== Спосіб Б: Спільний замок проти окремих замків (по 20 інкрементів a і b) ===");
        SharedLockTwoCounters shared = new SharedLockTwoCounters();
        t0 = System.nanoTime();
        Thread sa = new Thread(() -> {
            try { for (int i = 0; i < 20; i++) shared.incA(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread sb = new Thread(() -> {
            try { for (int i = 0; i < 20; i++) shared.incB(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        sa.start(); sb.start(); sa.join(); sb.join();
        long sharedMs = (System.nanoTime() - t0) / 1_000_000;

        SeparateLocksTwoCounters separate = new SeparateLocksTwoCounters();
        t0 = System.nanoTime();
        Thread pa = new Thread(() -> {
            try { for (int i = 0; i < 20; i++) separate.incA(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread pb = new Thread(() -> {
            try { for (int i = 0; i < 20; i++) separate.incB(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        pa.start(); pb.start(); pa.join(); pb.join();
        long separateMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("Спільний замок (this):  a=%d, b=%d, час = %d мс%n", shared.a(), shared.b(), sharedMs);
        System.out.printf("Окремі замки (lockA/B): a=%d, b=%d, час = %d мс (прискорення у %.2f раза)%n",
                separate.a(), separate.b(), separateMs, (double) sharedMs / separateMs);

        // --- Спосіб В ---
        System.out.println("\n=== Спосіб В: ReentrantLock з tryLock(таймаут) ===");
        for (long timeout : new long[]{1000, 100}) {
            LockCounter lc = new LockCounter();
            Thread l1 = new Thread(() -> {
                try { for (int i = 0; i < 5; i++) lc.increment(timeout); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "L1");
            Thread l2 = new Thread(() -> {
                try { for (int i = 0; i < 5; i++) lc.increment(timeout); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "L2");
            l1.start(); l2.start(); l1.join(); l2.join();
            System.out.printf("Таймаут %4d мс -> виконано: %d, пропущено: %d%n",
                    timeout, lc.value(), lc.skipped());
        }

        // --- Спосіб Г ---
        System.out.println("\n=== Спосіб Г: AtomicInteger (без блокувань, CAS) ===");
        AtomicInteger atomicCounter = new AtomicInteger(0);
        Thread a1 = new Thread(() -> { for (int i = 0; i < 100_000; i++) atomicCounter.incrementAndGet(); });
        Thread a2 = new Thread(() -> { for (int i = 0; i < 100_000; i++) atomicCounter.incrementAndGet(); });
        a1.start(); a2.start(); a1.join(); a2.join();
        System.out.printf("Очікувано: 200000, отримано: %d%n", atomicCounter.get());
    }
}