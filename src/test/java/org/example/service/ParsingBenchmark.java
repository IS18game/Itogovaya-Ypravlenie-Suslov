package org.example.service;

import org.openjdk.jmh.annotations.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Пример JMH-бенчмарка для перебора коллекций: for/stream/parallelStream
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
public class ParsingBenchmark {
    @State(Scope.Benchmark)
    public static class Data {
        public List<String> emails;
        @Setup
        public void setup() {
            emails = java.util.stream.Stream.generate(() -> "user@example.com")
                    .limit(1_000_000)
                    .collect(Collectors.toList());
        }
    }

    @Benchmark
    public long forLoopEmails(Data data) {
        long count = 0;
        List<String> emails = data.emails;
        for (int i = 0; i < emails.size(); i++) {
            if (emails.get(i).contains("@")) count++;
        }
        return count;
    }

    @Benchmark
    public long streamEmails(Data data) {
        return data.emails.stream().filter(e -> e.contains("@")).count();
    }

    @Benchmark
    public long parallelStreamEmails(Data data) {
        return data.emails.parallelStream().filter(e -> e.contains("@")).count();
    }
}

