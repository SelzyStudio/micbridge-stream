package com.limelight.binding.audio;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/** Bounded PCM ownership across nonblocking device writes. No worker or waiting. */
final class PcmPlaybackBuffer {
    interface Writer { int write(short[] pcm, int offset, int count); }
    private static final int MAX_WRITES_PER_CALLBACK = 8;
    private static final class Chunk {
        final short[] pcm;
        int offset;
        boolean started;
        Chunk(short[] source, int from, boolean started) {
            pcm = Arrays.copyOfRange(source, from, source.length);
            this.started = started;
        }
        int remaining() { return pcm.length - offset; }
    }
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private final int capacity;
    private int queuedSamples;
    private long droppedSamples, partialWrites, zeroWrites;

    PcmPlaybackBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("PCM capacity must be positive");
        this.capacity = capacity;
    }

    int write(short[] pcm, Writer writer) {
        if (queue.isEmpty() && pcm.length > 0) {
            int amount = writer.write(pcm, 0, pcm.length);
            if (amount < 0) { clear(); return amount; }
            amount = Math.min(amount, pcm.length);
            countShortWrite(amount, pcm.length);
            if (amount < pcm.length) offer(pcm, amount, amount > 0);
            return amount;
        }
        if (pcm.length > 0) offer(pcm, 0, false);
        int accepted = 0;
        for (int calls = 0; calls < MAX_WRITES_PER_CALLBACK && !queue.isEmpty(); calls++) {
            Chunk head = queue.peekFirst();
            int wanted = head.remaining();
            int amount = writer.write(head.pcm, head.offset, wanted);
            if (amount < 0) { clear(); return amount; }
            amount = Math.min(amount, wanted);
            countShortWrite(amount, wanted);
            if (amount == 0) break;
            head.offset += amount;
            head.started = true;
            queuedSamples -= amount;
            accepted += amount;
            if (head.remaining() == 0) queue.removeFirst();
        }
        return accepted;
    }

    private void countShortWrite(int amount, int wanted) {
        if (amount == 0) zeroWrites++;
        else if (amount < wanted) partialWrites++;
    }

    private void offer(short[] pcm, int from, boolean started) {
        int count = pcm.length - from;
        if (count > capacity) { droppedSamples += count; return; }
        while (queuedSamples + count > capacity) {
            Iterator<Chunk> it = queue.iterator();
            Chunk discard = it.next();
            // Finish an already-started packet before any later PCM. On a real
            // overrun, drop whole unstarted packets to bound latency and memory.
            if (discard.started) {
                if (!it.hasNext()) { droppedSamples += count; return; }
                discard = it.next();
            }
            queuedSamples -= discard.remaining();
            droppedSamples += discard.remaining();
            it.remove();
        }
        queue.addLast(new Chunk(pcm, from, started));
        queuedSamples += count;
    }

    void clear() { queue.clear(); queuedSamples = 0; }
    int queuedSamples() { return queuedSamples; }
    long droppedSamples() { return droppedSamples; }
    long partialWrites() { return partialWrites; }
    long zeroWrites() { return zeroWrites; }
}
