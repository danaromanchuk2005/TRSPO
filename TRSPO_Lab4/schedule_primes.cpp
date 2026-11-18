#include <omp.h>
#include <cstdio>
#include <cstdlib>

bool is_prime(long n) {
    if (n < 2) return false;
    for (long d = 2; d * d <= n; ++d) {
        if (n % d == 0) return false;
    }
    return true;
}

long count_primes_seq(long n) {
    long cnt = 0;
    for (long i = 2; i <= n; ++i) {
        if (is_prime(i)) ++cnt;
    }
    return cnt;
}

long count_primes_par(long n) {
    long cnt = 0;
    #pragma omp parallel for reduction(+: cnt) schedule(runtime)
    for (long i = 2; i <= n; ++i) {
        if (is_prime(i)) ++cnt;
    }
    return cnt;
}

int main(int argc, char** argv) {
    long n = argc > 1 ? std::atol(argv[1]) : 20'000'000L;
    bool seq_only = (argc > 2 && std::atoi(argv[2]) == 1);

    double t0 = omp_get_wtime();
    long c = seq_only ? count_primes_seq(n) : count_primes_par(n);
    double dt = omp_get_wtime() - t0;

    if (seq_only) {
        std::printf("sequential        | primes(%ld) = %ld | time = %.4f s\n", n, c, dt);
    } else {
        const char* sched = std::getenv("OMP_SCHEDULE");
        std::printf("%-17s | primes(%ld) = %ld | time = %.4f s | threads = %d\n",
                    sched ? sched : "default", n, c, dt, omp_get_max_threads());
    }
    return 0;
}
