package TRSPO_Lab3.deadlock;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Лабораторна робота № 3. Крок 3.
 * Моделювання deadlock, фіксація через ThreadMXBean / jstack та два способи усунення.
 */
public class DeadlockDemo {

    /** 1. Класичний deadlock: захоплення моніторів у протилежному порядку. */
    static class Conflict {
        private final String name;

        Conflict(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }

        synchronized void bow(Conflict other) {
            System.out.printf("[%s] %s: %s вклонився мені%n",
                    Thread.currentThread().getName(), name, other.getName());
            try {
                Thread.sleep(10); // гарантуємо перетин потоків для стабільного deadlock
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            other.bowBack(this);
        }

        synchronized void bowBack(Conflict other) {
            System.out.printf("[%s] %s: %s вклонився у відповідь%n",
                    Thread.currentThread().getName(), name, other.getName());
        }
    }

    /** 2. Усунення 1: глобальне впорядкування замків за унікальним id (порушує циклічне очікування). */
    static class OrderedConflict {
        private static final AtomicInteger SEQ = new AtomicInteger(1);
        private final int id;
        private final String name;

        OrderedConflict(String name) {
            this.id = SEQ.getAndIncrement();
            this.name = name;
        }

        void bow(OrderedConflict other) {
            OrderedConflict first = this.id < other.id ? this : other;
            OrderedConflict second = (first == this) ? other : this;
            synchronized (first) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (second) {
                    System.out.printf("[%s] %s (id=%d): %s (id=%d) вклонився мені%n",
                            Thread.currentThread().getName(), name, id, other.name, other.id);
                    System.out.printf("[%s] %s (id=%d): %s (id=%d) вклонився у відповідь%n",
                            Thread.currentThread().getName(), other.name, other.id, name, id);
                }
            }
        }
    }

    /** 3. Усунення 2: ReentrantLock.tryLock() з випадковим відступом (порушує утримання та очікування). */
    static class TryLockConflict {
        private final String name;
        private final ReentrantLock lock = new ReentrantLock();

        TryLockConflict(String name) {
            this.name = name;
        }

        void bow(TryLockConflict other) throws InterruptedException {
            int attempts = 0;
            while (true) {
                if (lock.tryLock()) {
                    try {
                        Thread.sleep(5);
                        if (other.lock.tryLock()) {
                            try {
                                System.out.printf("[%s] %s: %s вклонився мені (спроб: %d)%n",
                                        Thread.currentThread().getName(), name, other.name, attempts + 1);
                                System.out.printf("[%s] %s: %s вклонився у відповідь%n",
                                        Thread.currentThread().getName(), other.name, name);
                                return;
                            } finally {
                                other.lock.unlock();
                            }
                        }
                    } finally {
                        lock.unlock();
                    }
                }
                attempts++;
                Thread.sleep(ThreadLocalRandom.current().nextInt(1, 20)); // випадковий відступ проти livelock
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        boolean hangOnly = args.length > 0 && args[0].equals("jstack");

        System.out.println("=== 1. Моделювання deadlock та виявлення через ThreadMXBean ===");
        Conflict alice = new Conflict("Аліса");
        Conflict bob = new Conflict("Богдан");

        Thread t1 = new Thread(() -> alice.bow(bob), "T1");
        Thread t2 = new Thread(() -> bob.bow(alice), "T2");
        t1.start();
        t2.start();

        if (hangOnly) {
            System.out.println("Режим очікування jstack: виконайте jps та jstack <pid> в іншому терміналі...");
            t1.join();
            return;
        }

        Thread.sleep(300);
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = mx.findDeadlockedThreads();
        if (deadlockedIds != null) {
            System.out.println("Found one Java-level deadlock (виявлено ThreadMXBean):");
            for (ThreadInfo ti : mx.getThreadInfo(deadlockedIds, true, true)) {
                System.out.printf("  Потік \"%s\" (%s) чекає на %s, який утримує потік \"%s\"%n",
                        ti.getThreadName(), ti.getThreadState(), ti.getLockName(), ti.getLockOwnerName());
            }
        }

        System.out.println("\n=== 2. Усунення 1: Впорядкування замків за унікальним id ===");
        OrderedConflict oAlice = new OrderedConflict("Аліса");
        OrderedConflict oBob = new OrderedConflict("Богдан");
        Thread ot1 = new Thread(() -> oAlice.bow(oBob), "Ordered-T1");
        Thread ot2 = new Thread(() -> oBob.bow(oAlice), "Ordered-T2");
        ot1.start();
        ot2.start();
        ot1.join();
        ot2.join();

        System.out.println("\n=== 3. Усунення 2: tryLock() із випадковим відступом ===");
        TryLockConflict sAlice = new TryLockConflict("Аліса");
        TryLockConflict sBob = new TryLockConflict("Богдан");
        Thread st1 = new Thread(() -> {
            try { sAlice.bow(sBob); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "TryLock-T1");
        Thread st2 = new Thread(() -> {
            try { sBob.bow(sAlice); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "TryLock-T2");
        st1.start();
        st2.start();
        st1.join();
        st2.join();

        System.out.println("\nОбидва способи усунення завершили роботу коректно. Завершення JVM...");
        System.exit(0); // завершуємо JVM, оскільки завислі T1/T2 з пункту 1 не піддаються interrupt()
    }
}