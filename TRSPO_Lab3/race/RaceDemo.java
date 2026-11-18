package TRSPO_Lab3.race;

/**
 * Лабораторна робота № 3. Крок 1.
 * Демонстрація стану гонитви (race condition) та втрачених оновлень (lost updates).
 */
public class RaceDemo {

    static class Counter {
        private int c = 0;

        public void increment() throws InterruptedException {
            int a = c;          // читання спільного значення в локальну змінну
            Thread.sleep(150);  // штучна затримка: вікно для гонки
            a++;                // зміна локальної копії
            c = a;              // запис назад (може затерти чужий результат)
        }

        public void decrement() throws InterruptedException {
            int a = c;
            Thread.sleep(100);
            a--;
            c = a;
        }

        public int value() {
            return c;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Експеримент 1: Два потоки викликають increment() по 5 разів (очікувано 10) ===");
        for (int run = 1; run <= 3; run++) {
            Counter counter = new Counter();
            final boolean showSteps = (run == 1); // ефективно фінальна змінна для лямбди

            Runnable incTask = () -> {
                try {
                    for (int i = 0; i < 5; i++) {
                        counter.increment();
                        if (showSteps) {
                            System.out.printf("  %s: c = %d%n", Thread.currentThread().getName(), counter.value());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            Thread t1 = new Thread(incTask, "T1");
            Thread t2 = new Thread(incTask, "T2");
            t1.start();
            t2.start();
            t1.join();
            t2.join();
            System.out.printf("Запуск #%d -> Очікувано: 10, отримано: %d (втрачено оновлень: %d)%n",
                    run, counter.value(), 10 - counter.value());
        }

        System.out.println("\n=== Експеримент 2: T1 викликає increment(), T2 викликає decrement() по 5 разів (очікувано 0) ===");
        for (int run = 1; run <= 3; run++) {
            Counter counter = new Counter();
            Thread t1 = new Thread(() -> {
                try {
                    for (int i = 0; i < 5; i++) counter.increment();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "T1-inc");

            Thread t2 = new Thread(() -> {
                try {
                    for (int i = 0; i < 5; i++) counter.decrement();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "T2-dec");

            t1.start();
            t2.start();
            t1.join();
            t2.join();
            System.out.printf("Запуск #%d -> Очікувано: 0, отримано: %d%n", run, counter.value());
        }
    }
}