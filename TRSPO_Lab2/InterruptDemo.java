package TRSPO_Lab2;

/**
 * Лабораторна робота № 2. Завдання 3.
 * Кооперативне переривання потоку (polite) проти ігнорування переривання (stubborn).
 */
public class InterruptDemo {

    public static void main(String[] args) throws InterruptedException {
        Thread polite = new Thread(() -> {
            long iterations = 0;
            while (!Thread.currentThread().isInterrupted()) { // (а) перевірка ознаки переривання
                iterations++;
                busyWork(50); // імітація обчислень
                try {
                    Thread.sleep(100); // (б) блокуючий виклик
                } catch (InterruptedException e) {
                    System.out.println("polite: перервано під час sleep — завершую роботу");
                    Thread.currentThread().interrupt(); // (в) відновлюємо ознаку переривання
                }
            }
            System.out.println("polite: ітерацій виконано = " + iterations);
        }, "polite");

        Thread stubborn = new Thread(() -> {
            long iterations = 0;
            while (iterations < 60) { // ознаку переривання не перевіряє
                iterations++;
                busyWork(50);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // проігноровано — типова помилка
                }
            }
            System.out.println("stubborn: ітерацій виконано = " + iterations);
        }, "stubborn");

        polite.start();
        stubborn.start();

        Thread.sleep(1000); // даємо обом потокам попрацювати 1 секунду
        long t0 = System.nanoTime();
        polite.interrupt();   // (г) надсилаємо запит на переривання
        stubborn.interrupt();

        polite.join();
        System.out.printf("polite завершився через %.0f мс; stubborn.isAlive() = %b%n",
                (System.nanoTime() - t0) / 1e6, stubborn.isAlive());

        stubborn.join(); // доведеться чекати ~8-9 секунд до кінця його 60 ітерацій
        System.out.println("stubborn нарешті завершився");
    }

    /** Імітація обчислень тривалістю ~ms мілісекунд без sleep. */
    static void busyWork(int ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            Math.sqrt(end);
        }
    }
}