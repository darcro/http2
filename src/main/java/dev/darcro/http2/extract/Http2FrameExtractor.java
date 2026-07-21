package dev.darcro.http2.extract;

import dev.darcro.http2.frame.Http2FrameObservation;
import dev.darcro.http2.frame.Http2FrameParser;
import dev.darcro.http2.frame.Http2FrameTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Incrementally extracts HTTP/2 frames from ordered payload bytes for one
 * connection direction. Input bytes are copied when they must be retained.
 * This class is not thread-safe.
 */
public final class Http2FrameExtractor {
    private static final int FRAME_HEADER_LENGTH = 9;
    private static final int INITIAL_BUFFER_SIZE = 8_192;
    private static final byte[] CONNECTION_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Http2FrameParser parser;
    private final Http2ExtractionEventSink eventSink;
    private final PriorityQueue<SearchCandidate> candidates = new PriorityQueue<>(
            Comparator.comparingLong(candidate -> candidate.requiredOffset));
    private final ArrayDeque<Http2ExtractionEvent> pendingEvents = new ArrayDeque<>();

    private byte[] buffer = new byte[INITIAL_BUFFER_SIZE];
    private int start;
    private int end;
    private long bufferOffset;
    private long acceptedByteCount;
    private long nextSearchOffset;
    private long skippedOffset = -1;
    private long skippedByteCount;
    private Http2ExtractionState state = Http2ExtractionState.PROBING_PREFACE;
    private FrameBoundaryProvenance boundaryProvenance;
    private boolean delivering;

    public Http2FrameExtractor(Http2ExtractionEventSink eventSink) {
        this(new Http2FrameParser(), eventSink);
    }

    public Http2FrameExtractor(Http2FrameParser parser,
                               Http2ExtractionEventSink eventSink) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public Http2FrameParser parser() {
        return parser;
    }

    public Http2ExtractionState state() {
        return state;
    }

    /** Total bytes accepted since this extractor was created. */
    public long acceptedByteCount() {
        return acceptedByteCount;
    }

    /** Bytes currently retained while awaiting a boundary or more payload. */
    public int bufferedByteCount() {
        return end - start;
    }

    public void accept(byte[] payloadBytes) {
        Objects.requireNonNull(payloadBytes, "payloadBytes");
        accept(payloadBytes, 0, payloadBytes.length);
    }

    /** Accepts the next ordered range of payload bytes for this direction. */
    public void accept(byte[] payloadBytes, int offset, int length) {
        Objects.requireNonNull(payloadBytes, "payloadBytes");
        Objects.checkFromIndexSize(offset, length, payloadBytes.length);
        requireAccepting();
        requireNotReentrant();
        drainEvents();
        long updatedByteCount = Math.addExact(acceptedByteCount, length);
        append(payloadBytes, offset, length);
        acceptedByteCount = updatedByteCount;
        process(false);
    }

    /**
     * Finishes the payload stream and reports any unextractable trailing bytes.
     * Repeated calls are harmless; subsequent calls to {@code accept} fail.
     */
    public void finish() {
        requireNotReentrant();
        if (state == Http2ExtractionState.FINISHED) {
            drainEvents();
            return;
        }
        drainEvents();
        process(true);
        state = Http2ExtractionState.FINISHED;
        flushSkipped();
        int remaining = bufferedByteCount();
        if (remaining != 0) {
            enqueue(new Http2ExtractionDiagnostic(
                    Http2ExtractionDiagnosticReason.TRAILING_INCOMPLETE_DATA,
                    bufferOffset, remaining,
                    "Payload stream ended before remaining bytes could be extracted"));
            consume(remaining);
        }
        candidates.clear();
        drainEvents();
    }

    private void process(boolean finishing) {
        boolean progressed;
        do {
            progressed = switch (state) {
                case PROBING_PREFACE -> probePreface(finishing);
                case SEARCHING -> search(finishing);
                case SYNCHRONIZED -> extractSynchronized();
                case FINISHED -> false;
            };
        } while (progressed);
        drainEvents();
    }

    private boolean probePreface(boolean finishing) {
        int available = bufferedByteCount();
        int compared = Math.min(available, CONNECTION_PREFACE.length);
        for (int i = 0; i < compared; i++) {
            if (buffer[start + i] != CONNECTION_PREFACE[i]) {
                beginSearching();
                return true;
            }
        }
        if (available < CONNECTION_PREFACE.length) {
            if (finishing) {
                beginSearching();
                return true;
            }
            return false;
        }

        long prefaceOffset = bufferOffset;
        consume(CONNECTION_PREFACE.length);
        state = Http2ExtractionState.SYNCHRONIZED;
        boundaryProvenance = FrameBoundaryProvenance.CONNECTION_PREFACE;
        enqueue(new Http2ConnectionPrefaceExtracted(prefaceOffset));
        drainEvents();
        return true;
    }

    private boolean search(boolean finishing) {
        long availableEnd = endOffset();
        while (nextSearchOffset + FRAME_HEADER_LENGTH <= availableEnd) {
            int index = indexOf(nextSearchOffset);
            int type = unsignedByte(index + 3);
            int length = unsigned24(index);
            if (isStandardType(type) && length <= parser.maxFrameSize()) {
                candidates.add(new SearchCandidate(nextSearchOffset,
                        nextSearchOffset + FRAME_HEADER_LENGTH + length,
                        SearchStage.FIRST_FRAME));
            }
            nextSearchOffset++;
        }

        SearchCandidate confirmed = processReadyCandidates(availableEnd);
        if (confirmed != null) {
            synchronize(confirmed);
            return true;
        }

        trimSearchBuffer();
        if (finishing) {
            return false;
        }
        return false;
    }

    private SearchCandidate processReadyCandidates(long availableEnd) {
        SearchCandidate confirmed = null;
        while (!candidates.isEmpty()
                && candidates.peek().requiredOffset <= availableEnd) {
            SearchCandidate candidate = candidates.poll();
            if (candidate.startOffset < bufferOffset) {
                continue;
            }
            switch (candidate.stage) {
                case FIRST_FRAME -> {
                    int length = Math.toIntExact(candidate.requiredOffset
                            - candidate.startOffset);
                    if (!observe(candidate.startOffset, length).valid()) {
                        continue;
                    }
                    candidate.firstEndOffset = candidate.requiredOffset;
                    candidate.requiredOffset = candidate.firstEndOffset
                            + FRAME_HEADER_LENGTH;
                    candidate.stage = SearchStage.SECOND_HEADER;
                    candidates.add(candidate);
                }
                case SECOND_HEADER -> {
                    int index = indexOf(candidate.firstEndOffset);
                    int type = unsignedByte(index + 3);
                    int length = unsigned24(index);
                    if (!isStandardType(type) || length > parser.maxFrameSize()) {
                        continue;
                    }
                    candidate.secondEndOffset = candidate.firstEndOffset
                            + FRAME_HEADER_LENGTH + length;
                    candidate.requiredOffset = candidate.secondEndOffset;
                    candidate.stage = SearchStage.SECOND_FRAME;
                    candidates.add(candidate);
                }
                case SECOND_FRAME -> {
                    int length = Math.toIntExact(candidate.secondEndOffset
                            - candidate.firstEndOffset);
                    if (observe(candidate.firstEndOffset, length).valid()
                            && (confirmed == null
                            || candidate.startOffset < confirmed.startOffset)) {
                        confirmed = candidate;
                    }
                }
            }
        }
        return confirmed;
    }

    private void synchronize(SearchCandidate confirmed) {
        long skipped = confirmed.startOffset - bufferOffset;
        if (skipped > 0) {
            recordSkipped(bufferOffset, skipped);
            consume(Math.toIntExact(skipped));
        }

        long firstOffset = bufferOffset;
        int firstLength = Math.toIntExact(confirmed.firstEndOffset - firstOffset);
        Http2FrameObservation first = observeOwned(firstOffset, firstLength);
        consume(firstLength);

        long secondOffset = bufferOffset;
        int secondLength = Math.toIntExact(confirmed.secondEndOffset - secondOffset);
        Http2FrameObservation second = observeOwned(secondOffset, secondLength);
        consume(secondLength);

        candidates.clear();
        state = Http2ExtractionState.SYNCHRONIZED;
        boundaryProvenance =
                FrameBoundaryProvenance.MIDSTREAM_STANDARD_FRAME_SEQUENCE;
        flushSkipped();
        enqueue(new Http2FrameExtracted(firstOffset, first, boundaryProvenance));
        enqueue(new Http2FrameExtracted(secondOffset, second, boundaryProvenance));
        drainEvents();
    }

    private boolean extractSynchronized() {
        if (bufferedByteCount() < FRAME_HEADER_LENGTH) {
            return false;
        }
        int payloadLength = unsigned24(start);
        long frameLength = FRAME_HEADER_LENGTH + (long) payloadLength;
        if (payloadLength > parser.maxFrameSize()) {
            long rejectedOffset = bufferOffset;
            enqueue(new Http2ExtractionDiagnostic(
                    Http2ExtractionDiagnosticReason.FRAME_SIZE_LIMIT,
                    rejectedOffset, FRAME_HEADER_LENGTH,
                    "Declared frame payload exceeds the configured parser maximum"));
            consume(1);
            recordSkipped(rejectedOffset, 1);
            beginSearching();
            drainEvents();
            return true;
        }
        if (bufferedByteCount() < frameLength) {
            return false;
        }

        int checkedLength = Math.toIntExact(frameLength);
        long frameOffset = bufferOffset;
        Http2FrameObservation observation = observeOwned(frameOffset, checkedLength);
        if (!observation.valid()) {
            consume(1);
            recordSkipped(frameOffset, 1);
            beginSearching();
            enqueue(new Http2FrameCandidateRejected(frameOffset, observation));
            drainEvents();
            return true;
        }

        consume(checkedLength);
        enqueue(new Http2FrameExtracted(frameOffset, observation, boundaryProvenance));
        drainEvents();
        return true;
    }

    private void beginSearching() {
        state = Http2ExtractionState.SEARCHING;
        boundaryProvenance = null;
        candidates.clear();
        nextSearchOffset = bufferOffset;
    }

    private void trimSearchBuffer() {
        long retainFrom = nextSearchOffset;
        for (SearchCandidate candidate : candidates) {
            retainFrom = Math.min(retainFrom, candidate.startOffset);
        }
        if (retainFrom > bufferOffset) {
            long count = retainFrom - bufferOffset;
            recordSkipped(bufferOffset, count);
            consume(Math.toIntExact(count));
        }
    }

    private Http2FrameObservation observe(long offset, int length) {
        return parser.observe(buffer, indexOf(offset), length);
    }

    private Http2FrameObservation observeOwned(long offset, int length) {
        return parser.observeOwned(buffer, indexOf(offset), length);
    }

    private void recordSkipped(long offset, long count) {
        if (count == 0) {
            return;
        }
        if (skippedOffset < 0) {
            skippedOffset = offset;
            skippedByteCount = count;
            return;
        }
        if (skippedOffset + skippedByteCount != offset) {
            flushSkipped();
            skippedOffset = offset;
            skippedByteCount = count;
            return;
        }
        skippedByteCount = Math.addExact(skippedByteCount, count);
    }

    private void flushSkipped() {
        if (skippedOffset < 0) {
            return;
        }
        enqueue(new Http2ExtractionDiagnostic(
                Http2ExtractionDiagnosticReason.BYTES_SKIPPED,
                skippedOffset, skippedByteCount,
                "Payload bytes were skipped while searching for frame boundaries"));
        skippedOffset = -1;
        skippedByteCount = 0;
    }

    private void append(byte[] source, int offset, int length) {
        if (length == 0) {
            return;
        }
        compactOrGrow(length);
        System.arraycopy(source, offset, buffer, end, length);
        end += length;
    }

    private void compactOrGrow(int additionalLength) {
        int retained = bufferedByteCount();
        long required = (long) retained + additionalLength;
        if (required > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Payload chunk exceeds buffer capacity");
        }
        if (buffer.length - end >= additionalLength) {
            return;
        }
        if (start > 0 && buffer.length - retained >= additionalLength) {
            System.arraycopy(buffer, start, buffer, 0, retained);
            start = 0;
            end = retained;
            return;
        }
        int newLength = buffer.length;
        while (newLength < required) {
            int grown = newLength + (newLength >>> 1) + 1;
            newLength = grown < 0 || grown > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : grown;
        }
        byte[] grown = new byte[newLength];
        System.arraycopy(buffer, start, grown, 0, retained);
        buffer = grown;
        start = 0;
        end = retained;
    }

    private void consume(int length) {
        if (length < 0 || length > bufferedByteCount()) {
            throw new IllegalArgumentException("Invalid buffer consumption length");
        }
        start += length;
        bufferOffset = Math.addExact(bufferOffset, length);
        if (start == end) {
            start = 0;
            end = 0;
        } else if (start > buffer.length / 2) {
            int retained = bufferedByteCount();
            System.arraycopy(buffer, start, buffer, 0, retained);
            start = 0;
            end = retained;
        }
    }

    private int indexOf(long offset) {
        long relative = offset - bufferOffset;
        if (relative < 0 || relative > bufferedByteCount()) {
            throw new IllegalStateException("Extraction offset is outside the retained buffer");
        }
        return start + Math.toIntExact(relative);
    }

    private long endOffset() {
        return bufferOffset + bufferedByteCount();
    }

    private int unsigned24(int index) {
        return (unsignedByte(index) << 16)
                | (unsignedByte(index + 1) << 8)
                | unsignedByte(index + 2);
    }

    private int unsignedByte(int index) {
        return Byte.toUnsignedInt(buffer[index]);
    }

    private static boolean isStandardType(int type) {
        return type >= Http2FrameTypes.DATA && type <= Http2FrameTypes.CONTINUATION;
    }

    private void enqueue(Http2ExtractionEvent event) {
        pendingEvents.add(Objects.requireNonNull(event, "event"));
    }

    private void drainEvents() {
        while (!pendingEvents.isEmpty()) {
            Http2ExtractionEvent event = pendingEvents.removeFirst();
            delivering = true;
            try {
                eventSink.accept(event);
            } finally {
                delivering = false;
            }
        }
    }

    private void requireAccepting() {
        if (state == Http2ExtractionState.FINISHED) {
            throw new IllegalStateException("Extractor has already been finished");
        }
    }

    private void requireNotReentrant() {
        if (delivering) {
            throw new IllegalStateException("Extractor callbacks must not be reentrant");
        }
    }

    private enum SearchStage {
        FIRST_FRAME,
        SECOND_HEADER,
        SECOND_FRAME
    }

    private static final class SearchCandidate {
        private final long startOffset;
        private long requiredOffset;
        private SearchStage stage;
        private long firstEndOffset;
        private long secondEndOffset;

        private SearchCandidate(long startOffset, long requiredOffset,
                                SearchStage stage) {
            this.startOffset = startOffset;
            this.requiredOffset = requiredOffset;
            this.stage = stage;
        }
    }
}
