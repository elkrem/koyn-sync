package ru.creditnet.progressbar;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author antivoland
 */
class ProgressState {
    private final long max;
    private final long initial;
    private final AtomicLong current = new AtomicLong();
    private final LocalDateTime start = LocalDateTime.now();
    private final String tag;

    ProgressState(long max) {
        this(max, "");
    }

    ProgressState(long max, String tag) {
        this(max, 0, tag);
    }

    ProgressState(long max, long initial, String tag) {
        this.max = max;
        this.tag = tag;
        this.initial = initial > max ? max : initial;
        current.set(initial);
    }

    void step() {
        current.incrementAndGet();
    }

    void stepBy(long delta) {
        current.addAndGet(delta);
    }

    void stepTo(long value) {
        current.set(value > max ? max : value);
    }

    long max() {
        return max;
    }

    long current() {
        return current.get();
    }

    String tag() {
        return tag;
    }

    LocalDateTime start() {
        return start;
    }

    Duration elapsed() {
        return Duration.between(start, LocalDateTime.now());
    }

    Duration estimated() {
        if (max <= 0) return null;
        if (current.get() - initial <= 0) return Duration.ZERO;
        return elapsed().dividedBy(current.get() - initial).multipliedBy(max - current.get());
    }
}