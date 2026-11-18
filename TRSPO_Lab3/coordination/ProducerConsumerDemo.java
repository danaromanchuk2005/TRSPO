package TRSPO_Lab3.coordination;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Лабораторна робота № 3. Крок 4.
 * Координація потоків: захищений блок wait()/notifyAll() проти ArrayBlockingQueue.
 */
public class ProducerConsumerDemo {

    static class BoundedBuffer<T> {
        private final Object[] items;
        private int head = 0, tail = 0, count = 0;

        BoundedBuffer(int capacity) {
            items = new Object[capacity];
        }

        public synchronized void put(T x) throws InterruptedException {
            while (count == items.length) { // буфер повний — чекаємо
                wait();
            }
            items[tail] = x;
            tail = (tail + 1) % items.length;
            count++;
            notifyAll(); // пробуджуємо споживачів
        }

        @SuppressWarnings("unchecked")
        public synchronized T take() throws InterruptedException {
            while (count == 0) { // буфер порожній — чекаємо
                wait();
            }
            T x = (T) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            notifyAll(); // пробуджуємо виробників
            return x;
        }

        /** Демонстрація помилки № 3: виклик wait() без synchronized. */
        public void unsafePutWithoutSync(T x) throws InterruptedException {
            if (count == items.length) {
                wait(); // кине IllegalMonitorStateException
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        final int N = 100;
        final int CAPACITY = 5;
        final long expectedSum = 2L * N * (N + 1) / 2; // 2 виробники по 1..100 -> 10100

        // 1. Захищений блок wait() / notifyAll()
        System.out.println("=== 1. BoundedBuffer на wait() / notifyAll() (2 виробники, 2 споживачі) ===");
        BoundedBuffer<Integer> buffer = new BoundedBuffer<>(CAPACITY);
        AtomicLong sumGuard = new AtomicLong(0);

        Runnable prodGuard = () -> {
            try {
                for (int i = 1; i <= N; i++) buffer.put(i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable consGuard = () -> {
            try {
                long localSum = 0;
                for (int i = 0; i < N; i++) localSum += buffer.take();
                sumGuard.addAndGet(localSum);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread p1 = new Thread(prodGuard, "P1");
        Thread p2 = new Thread(prodGuard, "P2");
        Thread c1 = new Thread(consGuard, "C1");
        Thread c2 = new Thread(consGuard, "C2");
        p1.start(); p2.start(); c1.start(); c2.start();
        p1.join(); p2.join(); c1.join(); c2.join();
        System.out.printf("Очікувана сума: %d, отримана сума: %d -> %s%n",
                expectedSum, sumGuard.get(), sumGuard.get() == expectedSum ? "ЗБІГ (ОК)" : "ПОМИЛКА");

        // 2. Перевірка винятку при вилученні synchronized (помилка № 3 з методички)
        System.out.println("\n=== 2. Перевірка вилучення synchronized з put() ===");
        BoundedBuffer<Integer> brokenBuffer = new BoundedBuffer<>(0);
        try {
            brokenBuffer.unsafePutWithoutSync(1);
        } catch (IllegalMonitorStateException e) {
            System.out.println("Отримано очікуваний виняток: " + e);
        }

        // 3. Реалізація на ArrayBlockingQueue + «отруйна пігулка» (POISON)
        System.out.println("\n=== 3. ArrayBlockingQueue<Integer> + маркер завершення POISON ===");
        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(CAPACITY);
        final int POISON = -1;
        AtomicLong sumQueue = new AtomicLong(0);

        Runnable prodQueue = () -> {
            try {
                for (int i = 1; i <= N; i++) queue.put(i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable consQueue = () -> {
            try {
                long localSum = 0;
                for (int x = queue.take(); x != POISON; x = queue.take()) {
                    localSum += x;
                }
                sumQueue.addAndGet(localSum);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread qp1 = new Thread(prodQueue, "QP1");
        Thread qp2 = new Thread(prodQueue, "QP2");
        Thread qc1 = new Thread(consQueue, "QC1");
        Thread qc2 = new Thread(consQueue, "QC2");
        qp1.start(); qp2.start(); qc1.start(); qc2.start();

        qp1.join();
        qp2.join();
        // Кладемо по одній «отруйній пігулці» на кожного споживача
        queue.put(POISON);
        queue.put(POISON);

        qc1.join();
        qc2.join();
        System.out.printf("Очікувана сума: %d, отримана сума: %d -> %s%n",
                expectedSum, sumQueue.get(), sumQueue.get() == expectedSum ? "ЗБІГ (ОК)" : "ПОМИЛКА");
    }
}