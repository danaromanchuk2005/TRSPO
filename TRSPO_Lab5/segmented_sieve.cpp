#include <omp.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <sstream>
#include <string>
#include <vector>

struct TaskConfig {
    long long N = 1000000000LL;        // Верхня межа пошуку (до 10^11)
    long long segment_size = 524288LL; // Розмір сегмента (має бути парним, 524288 -> 256 КБ бітового/байтового масиву непарних)
    int tail_count = 15;               // Скільки останніх простих чисел виводити
    int threads = 0;                   // 0 -> omp_get_max_threads()
    std::string mode = "both";         // seq | par | both
    std::string schedule_type = "dynamic"; // static | dynamic
    std::string input_file = "";
    std::string output_file = "output_primes.txt";
    std::vector<long long> checkpoints; // Контрольні межі для перевірки у файлі
};

struct ThreadStat {
    int thread_id = 0;
    double work_time = 0.0;
    long long segments = 0;
    long long elements = 0;
    long long primes_found = 0;
};

struct SieveResult {
    long long N = 0;
    long long total_primes = 0;
    std::vector<long long> last_primes;
    std::vector<std::pair<long long, long long>> checkpoint_counts;
    double time_base_primes = 0.0; // Послідовна підготовка простих до sqrt(N)
    double time_sieve = 0.0;       // Час просіювання сегментів
    double time_total = 0.0;       // Загальний час обчислень
    std::vector<ThreadStat> thread_stats;
};

// Генератор вхідного файлу конфігурації та контрольних точок
bool generate_input_file(const std::string& filename, long long N, long long seg_size, int tail_k) {
    std::ofstream out(filename);
    if (!out.is_open()) return false;

    out << "# Segmented Sieve of Eratosthenes Input File (Variant 12)\n";
    out << "N " << N << "\n";
    out << "SEGMENT_SIZE " << seg_size << "\n";
    out << "TAIL_COUNT " << tail_k << "\n";

    // Додаємо 10 рівномірних контрольних точок для перевірки розподілу простих
    int num_cp = 10;
    out << "CHECKPOINTS " << num_cp << "\n";
    for (int i = 1; i <= num_cp; ++i) {
        long long cp = (N / num_cp) * i;
        if (cp < 2) cp = 2;
        out << cp << "\n";
    }
    return true;
}

// Буферизоване читання вхідного файлу
bool read_input_file(const std::string& filename, TaskConfig& cfg, double& read_time) {
    auto t0 = std::chrono::steady_clock::now();
    std::ifstream in(filename);
    if (!in.is_open()) return false;

    std::vector<char> io_buf(1 << 16); // Буфер 64 КБ
    in.rdbuf()->pubsetbuf(io_buf.data(), io_buf.size());

    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream iss(line);
        std::string key;
        iss >> key;
        if (key == "N") {
            iss >> cfg.N;
        } else if (key == "SEGMENT_SIZE") {
            iss >> cfg.segment_size;
            if (cfg.segment_size < 1024) cfg.segment_size = 1024;
            if (cfg.segment_size % 2 != 0) cfg.segment_size += 1;
        } else if (key == "TAIL_COUNT") {
            iss >> cfg.tail_count;
        } else if (key == "CHECKPOINTS") {
            int k = 0;
            iss >> k;
            cfg.checkpoints.clear();
            for (int i = 0; i < k && std::getline(in, line); ++i) {
                if (!line.empty() && line[0] != '#') {
                    cfg.checkpoints.push_back(std::stoll(line));
                }
            }
        }
    }
    auto t1 = std::chrono::steady_clock::now();
    read_time = std::chrono::duration<double>(t1 - t0).count();
    return true;
}

// Послідовне обчислення базових непарних простих чисел <= sqrt(N)
std::vector<int> compute_base_primes(long long N) {
    int limit = static_cast<int>(std::sqrt(static_cast<long double>(N))) + 1;
    std::vector<uint8_t> is_comp(limit + 1, 0);
    std::vector<int> primes;
    primes.reserve(static_cast<size_t>(limit / std::max(1.0, std::log(limit) - 1.0)));

    for (int i = 3; 1LL * i * i <= limit; i += 2) {
        if (!is_comp[i]) {
            for (int j = i * i; j <= limit; j += 2 * i) {
                is_comp[j] = 1;
            }
        }
    }
    for (int i = 3; i <= limit; i += 2) {
        if (!is_comp[i]) primes.push_back(i);
    }
    return primes;
}

// Просіювання одного сегмента [seg_low, seg_high] (тільки непарні числа)
long long sieve_single_segment(long long seg_low, long long seg_high,
                               const std::vector<int>& base_primes,
                               std::vector<uint8_t>& mark,
                               std::vector<long long>& seg_tail,
                               int tail_k) {
    long long low = (seg_low < 3) ? 3 : ((seg_low % 2 == 0) ? seg_low + 1 : seg_low);
    long long high = (seg_high % 2 == 0) ? seg_high - 1 : seg_high;
    if (low > high) return 0;

    size_t len = static_cast<size_t>((high - low) / 2 + 1);
    std::memset(mark.data(), 0, len);

    for (int p : base_primes) {
        long long p2 = 1LL * p * p;
        if (p2 > high) break;
        long long start = std::max(p2, ((low + p - 1) / p) * 1LL * p);
        if ((start & 1LL) == 0) start += p;
        for (size_t idx = static_cast<size_t>((start - low) >> 1); idx < len; idx += static_cast<size_t>(p)) {
            mark[idx] = 1;
        }
    }

    long long count = 0;
    for (size_t idx = 0; idx < len; ++idx) {
        if (!mark[idx]) ++count;
    }

    // Зберігаємо останні tail_k простих цього сегмента
    if (tail_k > 0 && count > 0) {
        seg_tail.clear();
        for (size_t idx = len; idx > 0 && static_cast<int>(seg_tail.size()) < tail_k; --idx) {
            if (!mark[idx - 1]) {
                seg_tail.push_back(low + 2LL * static_cast<long long>(idx - 1));
            }
        }
        std::reverse(seg_tail.begin(), seg_tail.end());
    }
    return count;
}

// Послідовна еталонна реалізація сегментованого решета
SieveResult run_sieve_sequential(const TaskConfig& cfg) {
    SieveResult res;
    res.N = cfg.N;
    if (cfg.N < 2) return res;

    double t_start = omp_get_wtime();
    std::vector<int> base_primes = compute_base_primes(cfg.N);
    double t_base_end = omp_get_wtime();
    res.time_base_primes = t_base_end - t_start;

    long long total_primes = 1; // число 2
    std::vector<long long> tail_buf;
    if (cfg.tail_count > 0) tail_buf.push_back(2);

    long long delta = cfg.segment_size;
    long long num_segments = (cfg.N - 2) / delta + 1;
    std::vector<uint8_t> mark(static_cast<size_t>(delta / 2 + 2), 0);
    std::vector<long long> seg_tail;

    for (long long s = 0; s < num_segments; ++s) {
        long long seg_low = 3 + s * delta;
        long long seg_high = std::min(cfg.N, seg_low + delta - 1);
        long long cnt = sieve_single_segment(seg_low, seg_high, base_primes, mark, seg_tail, cfg.tail_count);
        total_primes += cnt;

        for (long long p : seg_tail) {
            tail_buf.push_back(p);
        }
        if (static_cast<int>(tail_buf.size()) > cfg.tail_count * 2) {
            tail_buf.erase(tail_buf.begin(), tail_buf.end() - cfg.tail_count);
        }
    }

    if (static_cast<int>(tail_buf.size()) > cfg.tail_count) {
        tail_buf.erase(tail_buf.begin(), tail_buf.end() - cfg.tail_count);
    }

    double t_end = omp_get_wtime();
    res.time_sieve = t_end - t_base_end;
    res.time_total = t_end - t_start;
    res.total_primes = total_primes;
    res.last_primes = tail_buf;

    ThreadStat st;
    st.thread_id = 0;
    st.work_time = res.time_sieve;
    st.segments = num_segments;
    st.elements = std::max(0LL, cfg.N - 1);
    st.primes_found = total_primes;
    res.thread_stats.push_back(st);
    return res;
}

// Паралельна реалізація сегментованого решета (OpenMP)
SieveResult run_sieve_parallel(const TaskConfig& cfg, int num_threads) {
    SieveResult res;
    res.N = cfg.N;
    if (cfg.N < 2) return res;

    double t_start = omp_get_wtime();
    std::vector<int> base_primes = compute_base_primes(cfg.N);
    double t_base_end = omp_get_wtime();
    res.time_base_primes = t_base_end - t_start;

    long long delta = cfg.segment_size;
    long long num_segments = (cfg.N - 2) / delta + 1;

    if (cfg.schedule_type == "static") {
        omp_set_schedule(omp_sched_static, 0);
    } else {
        omp_set_schedule(omp_sched_dynamic, 1);
    }

    std::vector<ThreadStat> stats(num_threads);
    std::vector<std::vector<long long>> thread_tails(num_threads);
    long long parallel_primes = 0;

    #pragma omp parallel num_threads(num_threads) reduction(+: parallel_primes)
    {
        int tid = omp_get_thread_num();
        stats[tid].thread_id = tid;
        if (tid == 0 && cfg.tail_count > 0) {
            thread_tails[tid].push_back(2);
        }

        // Локальний буфер сегмента потоку (виділяється 1 раз, поміщається в L1/L2 кеш)
        std::vector<uint8_t> local_mark(static_cast<size_t>(delta / 2 + 2), 0);
        std::vector<long long> local_seg_tail;

        #pragma omp for schedule(runtime)
        for (long long s = 0; s < num_segments; ++s) {
            double seg_t0 = omp_get_wtime();
            long long seg_low = 3 + s * delta;
            long long seg_high = std::min(cfg.N, seg_low + delta - 1);

            long long cnt = sieve_single_segment(seg_low, seg_high, base_primes,
                                                 local_mark, local_seg_tail, cfg.tail_count);
            parallel_primes += cnt;

            for (long long p : local_seg_tail) {
                thread_tails[tid].push_back(p);
            }
            if (cfg.tail_count > 0 && static_cast<int>(thread_tails[tid].size()) > cfg.tail_count * 2) {
                thread_tails[tid].erase(thread_tails[tid].begin(),
                                        thread_tails[tid].end() - cfg.tail_count);
            }

            double seg_t1 = omp_get_wtime();
            stats[tid].work_time += (seg_t1 - seg_t0);
            stats[tid].segments += 1;
            stats[tid].elements += (seg_high - seg_low + 1);
            stats[tid].primes_found += cnt;
        }
    }

    // Об'єднання останніх K простих чисел з усіх потоків
    std::vector<long long> merged_tail;
    for (int t = 0; t < num_threads; ++t) {
        merged_tail.insert(merged_tail.end(), thread_tails[t].begin(), thread_tails[t].end());
    }
    std::sort(merged_tail.begin(), merged_tail.end());
    merged_tail.erase(std::unique(merged_tail.begin(), merged_tail.end()), merged_tail.end());
    if (static_cast<int>(merged_tail.size()) > cfg.tail_count) {
        merged_tail.erase(merged_tail.begin(), merged_tail.end() - cfg.tail_count);
    }

    double t_end = omp_get_wtime();
    res.time_sieve = t_end - t_base_end;
    res.time_total = t_end - t_start;
    res.total_primes = parallel_primes + 1; // + число 2
    res.last_primes = merged_tail;
    res.thread_stats = stats;
    return res;
}

// Запис результатів у вихідний файл
bool write_output_file(const std::string& filename, const SieveResult& res,
                       double read_time, double& write_time) {
    auto t0 = std::chrono::steady_clock::now();
    std::ofstream out(filename);
    if (!out.is_open()) return false;

    out << "=== Segmented Sieve of Eratosthenes Result (Variant 12) ===\n";
    out << "Upper bound N: " << res.N << "\n";
    out << "Total primes pi(N): " << res.total_primes << "\n";
    out << "Input read time (s): " << std::fixed << std::setprecision(6) << read_time << "\n";
    out << "Base primes prep time (s): " << res.time_base_primes << "\n";
    out << "Compute time (s): " << res.time_total << "\n";
    out << "Last " << res.last_primes.size() << " primes up to N:\n";
    for (size_t i = 0; i < res.last_primes.size(); ++i) {
        out << res.last_primes[i] << (i + 1 == res.last_primes.size() ? "\n" : " ");
    }
    out.flush();
    auto t1 = std::chrono::steady_clock::now();
    write_time = std::chrono::duration<double>(t1 - t0).count();
    return true;
}

double median_of(std::vector<double> v) {
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

// Автоматичний запуск серій експериментів та збереження CSV
void run_benchmarks() {
    std::cout << "=== Запуск серії експериментів (прогрів + 3 виміри для медіани) ===\n";
    std::cout << "Логічних ядер CPU: " << omp_get_num_procs() << "\n\n";

    std::vector<long long> sizes = {100000000LL, 1000000000LL, 10000000000LL}; // 10^8, 10^9, 10^10
    std::vector<int> thread_counts = {1, 2, 4, 8, 10, 16, 20}; // до 2x логічних ядер (20)

    std::ofstream csv("benchmark_results.csv");
    csv << "N,pi_N,threads,T_seq,T_base,T_par,S_p,E_p\n";

    for (long long N : sizes) {
        TaskConfig cfg;
        cfg.N = N;
        cfg.segment_size = 524288LL;
        cfg.tail_count = 15;
        cfg.schedule_type = "dynamic";

        // Прогрів
        volatile long long sink = run_sieve_sequential(cfg).total_primes;
        (void)sink;

        std::vector<double> seq_runs;
        long long pi_N = 0;
        for (int r = 0; r < 3; ++r) {
            auto r_seq = run_sieve_sequential(cfg);
            seq_runs.push_back(r_seq.time_total);
            pi_N = r_seq.total_primes;
        }
        double T_seq = median_of(seq_runs);

        std::cout << "--- N = " << N << " | pi(N) = " << pi_N
                  << " | T_seq (медіана) = " << std::fixed << std::setprecision(4) << T_seq << " s ---\n";
        std::cout << "p    | T_base, s | T(p), s   | S(p)   | E(p)\n";
        std::cout << "------------------------------------------------\n";

        for (int p : thread_counts) {
            std::vector<double> par_runs, base_runs;
            for (int r = 0; r < 3; ++r) {
                auto r_par = run_sieve_parallel(cfg, p);
                par_runs.push_back(r_par.time_total);
                base_runs.push_back(r_par.time_base_primes);
            }
            double T_p = median_of(par_runs);
            double T_b = median_of(base_runs);
            double S_p = T_seq / T_p;
            double E_p = S_p / p;

            std::printf("%-4d | %-9.5f | %-9.4f | %-6.2f | %-6.2f\n", p, T_b, T_p, S_p, E_p);
            csv << N << "," << pi_N << "," << p << ","
                << std::fixed << std::setprecision(6) << T_seq << "," << T_b << "," << T_p << ","
                << std::setprecision(4) << S_p << "," << E_p << "\n";
        }
        std::cout << "\n";
    }
    csv.close();

    // Дослідження балансу навантаження (p = 8, N = 10^10, static vs dynamic)
    std::cout << "=== Оцінка балансу навантаження (N = 10^10, p = 8) ===\n";
    std::ofstream bal_csv("load_balance.csv");
    bal_csv << "schedule,thread_id,work_time,segments,elements,primes_found\n";

    for (const std::string& sched : {"static", "dynamic"}) {
        TaskConfig cfg;
        cfg.N = 10000000000LL;
        cfg.segment_size = 524288LL;
        cfg.tail_count = 15;
        cfg.schedule_type = sched;

        auto res = run_sieve_parallel(cfg, 8);
        double t_max = 0.0, t_sum = 0.0;
        for (const auto& st : res.thread_stats) {
            t_max = std::max(t_max, st.work_time);
            t_sum += st.work_time;
            bal_csv << sched << "," << st.thread_id << ","
                    << std::fixed << std::setprecision(6) << st.work_time << ","
                    << st.segments << "," << st.elements << "," << st.primes_found << "\n";
        }
        double t_avg = t_sum / 8.0;
        std::printf("Schedule %-7s | T_total = %.4f s | T_max = %.4f s | T_avg = %.4f s | Дисбаланс T_max/T_avg = %.3f\n",
                    sched.c_str(), res.time_total, t_max, t_avg, t_max / t_avg);
        for (const auto& st : res.thread_stats) {
            std::printf("  Потік %d: %.4f s (%lld сегментів, %lld ел.)\n",
                        st.thread_id, st.work_time, st.segments, st.elements);
        }
    }
    bal_csv.close();
    std::cout << "\nДані збережено у benchmark_results.csv та load_balance.csv\n";
}

int main(int argc, char* argv[]) {
    TaskConfig cfg;
    cfg.threads = omp_get_max_threads();
    bool do_generate = false;
    bool do_benchmark = false;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--n" && i + 1 < argc) cfg.N = std::stoll(argv[++i]);
        else if (arg == "--seg-size" && i + 1 < argc) cfg.segment_size = std::stoll(argv[++i]);
        else if (arg == "--tail" && i + 1 < argc) cfg.tail_count = std::stoi(argv[++i]);
        else if (arg == "--threads" && i + 1 < argc) cfg.threads = std::stoi(argv[++i]);
        else if (arg == "--mode" && i + 1 < argc) cfg.mode = argv[++i];
        else if (arg == "--schedule" && i + 1 < argc) cfg.schedule_type = argv[++i];
        else if (arg == "--input" && i + 1 < argc) cfg.input_file = argv[++i];
        else if (arg == "--output" && i + 1 < argc) cfg.output_file = argv[++i];
        else if (arg == "--generate" && i + 1 < argc) {
            do_generate = true;
            cfg.N = std::stoll(argv[++i]);
        }
        else if (arg == "--benchmark") do_benchmark = true;
    }

    if (do_benchmark) {
        run_benchmarks();
        return 0;
    }

    if (do_generate) {
        if (generate_input_file(cfg.output_file, cfg.N, cfg.segment_size, cfg.tail_count)) {
            std::cout << "Згенеровано вхідний файл: " << cfg.output_file
                      << " (N = " << cfg.N << ", segment_size = " << cfg.segment_size << ")\n";
            return 0;
        } else {
            std::cerr << "Помилка створення файлу: " << cfg.output_file << "\n";
            return 1;
        }
    }

    double read_time = 0.0;
    if (!cfg.input_file.empty()) {
        if (!read_input_file(cfg.input_file, cfg, read_time)) {
            std::cerr << "Помилка читання вхідного файлу: " << cfg.input_file << "\n";
            return 1;
        }
    }

    std::cout << "Задача: Сегментоване решето Ератосфена (Варіант 12), N = " << cfg.N << "\n";
    if (!cfg.input_file.empty()) {
        std::cout << "Читання вхідного файлу (" << cfg.input_file << "): "
                  << std::fixed << std::setprecision(6) << read_time << " s\n";
    }

    SieveResult seq_res, par_res;
    if (cfg.mode == "seq" || cfg.mode == "both") {
        seq_res = run_sieve_sequential(cfg);
        std::cout << "Послідовна версія: " << std::fixed << std::setprecision(4) << seq_res.time_total
                  << " s (підготовка базових простих: " << seq_res.time_base_primes
                  << " s) | pi(N) = " << seq_res.total_primes << "\n";
    }

    if (cfg.mode == "par" || cfg.mode == "both") {
        par_res = run_sieve_parallel(cfg, cfg.threads);
        std::cout << "Паралельна версія (p=" << cfg.threads << ", schedule=" << cfg.schedule_type << "): "
                  << std::fixed << std::setprecision(4) << par_res.time_total << " s";
        if (cfg.mode == "both") {
            double S = seq_res.time_total / par_res.time_total;
            double E = S / cfg.threads;
            std::cout << " | прискорення S = " << std::setprecision(2) << S
                      << ", ефективність E = " << E;
        }
        std::cout << "\n";

        for (const auto& st : par_res.thread_stats) {
            std::cout << "  потік " << st.thread_id << ": " << std::fixed << std::setprecision(4)
                      << st.work_time << " s (" << st.segments << " сегментів, "
                      << st.elements << " ел., " << st.primes_found << " простих)\n";
        }
    }

    const SieveResult& final_res = (cfg.mode == "seq") ? seq_res : par_res;
    if (cfg.mode == "both") {
        bool ok = (seq_res.total_primes == par_res.total_primes) &&
                  (seq_res.last_primes == par_res.last_primes);
        std::cout << "Коректність: " << (ok ? "РЕЗУЛЬТАТИ ПОВНІСТЮ ЗБІГАЮТЬСЯ." : "ПОМИЛКА РОЗБІЖНОСТІ!") << "\n";
    }

    double write_time = 0.0;
    if (write_output_file(cfg.output_file, final_res, read_time, write_time)) {
        std::cout << "Запис результату у " << cfg.output_file << ": "
                  << std::fixed << std::setprecision(6) << write_time << " s\n";
    }
    std::cout << "Останні " << final_res.last_primes.size() << " простих чисел до " << cfg.N << ": ";
    for (long long p : final_res.last_primes) std::cout << p << " ";
    std::cout << "\n";

    return 0;
}
