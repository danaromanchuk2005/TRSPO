package TRSPO_Lab2;

/**
 * Лабораторна робота № 2. Завдання 2.
 * Способи створення потоків (Thread та Runnable), стани життєвого циклу, join(ms), демон-потік.
 */
public class ThreadStatesDemo {

    /** Спосіб 1: успадкування класу Thread. */
    static class Sleeper extends Thread {
        @Override
        public void run() {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Sleeper();
        t1.setName("Sleeper");

        // Спосіб 2: реалізація Runnable через лямбда-вираз
        Thread t2 = new Thread(() -> {
            long s = 0;
            for (long i = 0; i < 3_000_000_000L; i++) {
                s += 1;
            }
            System.out.println("Calc: сума = " + s);
        }, "Calc");

        // Фоновий демон-потік
        Thread daemon = new Thread(() -> {
            while (true) {
                System.out.println(" tick " + java.time.LocalTime.now().withNano(0));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "Ticker");
        daemon.setDaemon(true); // обов'язково викликати ДО start()!

        // 1. Стани одразу після створення (NEW)
        System.out.printf("%s: %s, %s: %s%n", t1.getName(), t1.getState(), t2.getName(), t2.getState());

        t1.start();
        t2.start();
        daemon.start();

        // 2. Стани під час роботи (TIMED_WAITING та RUNNABLE)
        Thread.sleep(100);
        System.out.printf("%s: %s%n", t1.getName(), t1.getState());
        System.out.printf("%s: %s%n", t2.getName(), t2.getState());

        // 3. Очікування з тайм-аутом 300 мс
        t2.join(300);
        System.out.printf("Calc після join(300): isAlive=%b, %s%n", t2.isAlive(), t2.getState());

        // 4. Повне очікування завершення обох потоків (TERMINATED)
        t1.join();
        t2.join();
        System.out.printf("%s: %s, %s: %s%n", t1.getName(), t1.getState(), t2.getName(), t2.getState());
        System.out.println("main завершується; демон Ticker буде зупинений JVM");
    }
}