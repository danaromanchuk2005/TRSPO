package TRSPO_Lab2;

/**
 * Лабораторна робота № 2. Завдання 1.
 * Два конкуруючих потоки: головний каже «курка!», потік EggVoice — «яйце!».
 */
public class ChickenOrEgg {

    /** Потік-опонент: п'ять разів через pauseMs мілісекунд каже «яйце!». */
    static class EggVoice extends Thread {
        private final int pauseMs;

        EggVoice(int pauseMs) {
            super("EggVoice"); // задаємо ім'я потоку
            this.pauseMs = pauseMs;
        }

        @Override
        public void run() {
            for (int i = 1; i <= 5; i++) {
                try {
                    Thread.sleep(pauseMs); // перехід у стан TIMED_WAITING
                    System.out.printf("[%s] яйце! (%d)%n", getName(), i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // відновлюємо ознаку переривання
                    return;
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int eggPause = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int chickenPause = args.length > 1 ? Integer.parseInt(args[1]) : 1000;

        EggVoice egg = new EggVoice(eggPause); // об'єкт створено -> стан NEW
        System.out.println("Початок суперечки. Стан опонента: " + egg.getState());

        egg.start(); // запуск нового потоку ОС: NEW -> RUNNABLE

        for (int i = 1; i <= 5; i++) {
            Thread.sleep(chickenPause); // головний потік спить
            System.out.printf("[%s] курка! (%d)%n", Thread.currentThread().getName(), i);
        }

        if (egg.isAlive()) { // перевіряємо, чи потік-опонент ще не завершився
            egg.join();      // чекаємо завершення потоку EggVoice
            System.out.println("Останнє слово за яйцем!");
        } else {
            System.out.println("Останнє слово за куркою!");
        }

        System.out.println("Стан опонента: " + egg.getState() + ". Суперечку завершено.");
    }
}