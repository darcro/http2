package dev.darcro.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.darcro.http2.frame.Http2FrameParser;
import dev.darcro.http2.frame.Http2FrameTypes;
import dev.darcro.http2.frame.SettingsFrame;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class Http2FrameExtractorTest {
    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SETTINGS_ACK = hex("000000040100000000");
    private static final byte[] CAPTURED_HEADERS = hex(
            "0000c4010400000001886196dd6d5f4a044a436cca08017940bb71905c682a62d1"
                    + "bf5f87497ca58ae819aa6c96df697e9403ca681fa50400bca059b8db3704253168"
                    + "df0f138bfe5b1ca11c7209664bfcff52848fd24a8f0f0d023632408ff2b463275"
                    + "2d522d3947216c5ac4a7f8602e0009c69bf7686aa69d29afcff7c8712954d3a535"
                    + "f9f408bf2b4b60e92ac7ad263d48f89dd0e8c1ab6e4c5934f408cf2b794216aec"
                    + "3a4a4498f57f8a0fda949e42c11d07275f4090f2b10f524b52564faacab1eb498"
                    + "f523f85a8e8a8d2cb");

    @Test
    void detectsSplitPrefaceAndExtractsImmediately() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        byte[] input = concat(PREFACE, SETTINGS_ACK);

        for (byte value : input) {
            extractor.accept(new byte[]{value});
        }

        assertEquals(Http2ExtractionState.SYNCHRONIZED, extractor.state());
        assertEquals(input.length, extractor.acceptedByteCount());
        assertEquals(0, extractor.bufferedByteCount());
        assertEquals(2, events.size());
        assertEquals(0, assertInstanceOf(Http2ConnectionPrefaceExtracted.class,
                events.get(0)).streamOffset());
        Http2FrameExtracted frame = assertInstanceOf(Http2FrameExtracted.class,
                events.get(1));
        assertEquals(PREFACE.length, frame.streamOffset());
        assertEquals(FrameBoundaryProvenance.CONNECTION_PREFACE,
                frame.boundaryProvenance());
        assertInstanceOf(SettingsFrame.class, frame.observation().frame().orElseThrow());
    }

    @Test
    void confirmsTwoCapturedFramesWhenStartingMidConnection() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);

        extractor.accept(SETTINGS_ACK);
        assertTrue(events.isEmpty());
        extractor.accept(CAPTURED_HEADERS);

        List<Http2FrameExtracted> frames = frames(events);
        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).streamOffset());
        assertEquals(SETTINGS_ACK.length, frames.get(1).streamOffset());
        assertEquals(FrameBoundaryProvenance.MIDSTREAM_STANDARD_FRAME_SEQUENCE,
                frames.get(0).boundaryProvenance());
    }

    @Test
    void confirmsMidConnectionFramesAcrossEveryChunkBoundary() {
        byte[] input = concat(SETTINGS_ACK, CAPTURED_HEADERS);
        for (int split = 0; split <= input.length; split++) {
            List<Http2ExtractionEvent> events = new ArrayList<>();
            Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);

            extractor.accept(input, 0, split);
            extractor.accept(input, split, input.length - split);

            assertEquals(2, frames(events).size(), "split=" + split);
        }
    }

    @Test
    void reportsGarbagePrefixBeforeConfirmedFrames() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        byte[] garbage = {(byte) 0xff, (byte) 0xee, (byte) 0xdd};

        extractor.accept(concat(garbage, SETTINGS_ACK, CAPTURED_HEADERS));

        Http2ExtractionDiagnostic skipped = events.stream()
                .filter(Http2ExtractionDiagnostic.class::isInstance)
                .map(Http2ExtractionDiagnostic.class::cast)
                .filter(event -> event.reason()
                        == Http2ExtractionDiagnosticReason.BYTES_SKIPPED)
                .findFirst().orElseThrow();
        assertEquals(0, skipped.streamOffset());
        assertEquals(garbage.length, skipped.byteCount());
        assertEquals(3, frames(events).get(0).streamOffset());
    }

    @Test
    void extensionFrameCannotConfirmButIsExtractedAfterSynchronization() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        byte[] extension = frame(0xfe, 0, 0, new byte[]{1, 2});

        extractor.accept(concat(extension, SETTINGS_ACK, CAPTURED_HEADERS, extension));

        List<Http2FrameExtracted> frames = frames(events);
        assertEquals(3, frames.size());
        assertEquals(Http2FrameTypes.SETTINGS,
                frames.get(0).observation().frame().orElseThrow().type());
        assertEquals(0xfe, frames.get(2).observation().frame().orElseThrow().type());
    }

    @Test
    void malformedCandidateIsRejectedAndSynchronizationIsReacquired() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        byte[] malformedSettings = frame(Http2FrameTypes.SETTINGS, 0, 1, new byte[0]);

        extractor.accept(concat(PREFACE, SETTINGS_ACK, malformedSettings,
                SETTINGS_ACK, CAPTURED_HEADERS));

        Http2FrameCandidateRejected rejected = events.stream()
                .filter(Http2FrameCandidateRejected.class::isInstance)
                .map(Http2FrameCandidateRejected.class::cast)
                .findFirst().orElseThrow();
        assertEquals(PREFACE.length + SETTINGS_ACK.length, rejected.streamOffset());
        assertTrue(!rejected.observation().valid());
        List<Http2FrameExtracted> frames = frames(events);
        assertEquals(3, frames.size());
        assertEquals(FrameBoundaryProvenance.MIDSTREAM_STANDARD_FRAME_SEQUENCE,
                frames.get(1).boundaryProvenance());
    }

    @Test
    void oversizedHeaderLosesSynchronizationWithoutBufferingPayload() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(
                new Http2FrameParser(Http2FrameParser.INITIAL_MAX_FRAME_SIZE),
                events::add);
        byte[] oversizedHeader = hex("004001000000000001");

        extractor.accept(concat(PREFACE, oversizedHeader,
                SETTINGS_ACK, CAPTURED_HEADERS));

        assertTrue(events.stream().filter(Http2ExtractionDiagnostic.class::isInstance)
                .map(Http2ExtractionDiagnostic.class::cast)
                .anyMatch(event -> event.reason()
                        == Http2ExtractionDiagnosticReason.FRAME_SIZE_LIMIT));
        assertEquals(2, frames(events).size());
    }

    @Test
    void finishReportsTrailingInputAndBecomesTerminal() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        extractor.accept(Arrays.copyOf(PREFACE, 4));

        extractor.finish();
        extractor.finish();

        assertEquals(Http2ExtractionState.FINISHED, extractor.state());
        Http2ExtractionDiagnostic trailing = assertInstanceOf(
                Http2ExtractionDiagnostic.class, events.get(0));
        assertEquals(Http2ExtractionDiagnosticReason.TRAILING_INCOMPLETE_DATA,
                trailing.reason());
        assertEquals(4, trailing.byteCount());
        assertThrows(IllegalStateException.class,
                () -> extractor.accept(new byte[0]));
    }

    @Test
    void emittedFrameOwnsItsBytes() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        byte[] input = concat(PREFACE, SETTINGS_ACK);
        extractor.accept(input);
        Http2FrameExtracted extracted = frames(events).get(0);

        Arrays.fill(input, (byte) 0);

        assertArrayEquals(SETTINGS_ACK, extracted.observation().rawBytes().toByteArray());
    }

    @Test
    void bufferedInputDoesNotDependOnCallerArrayLifetime() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        byte[] first = SETTINGS_ACK.clone();
        extractor.accept(first);
        Arrays.fill(first, (byte) 0xff);

        extractor.accept(CAPTURED_HEADERS);

        assertArrayEquals(SETTINGS_ACK,
                frames(events).get(0).observation().rawBytes().toByteArray());
    }

    @Test
    void callbackExceptionDoesNotDuplicateEventOrLoseBufferedData() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        Http2FrameExtractor extractor = new Http2FrameExtractor(event -> {
            events.add(event);
            if (fail.getAndSet(false)) {
                throw new IllegalStateException("listener failed");
            }
        });

        assertThrows(IllegalStateException.class,
                () -> extractor.accept(concat(PREFACE, SETTINGS_ACK)));
        extractor.accept(new byte[0]);

        assertEquals(1, events.stream()
                .filter(Http2ConnectionPrefaceExtracted.class::isInstance).count());
        assertEquals(1, frames(events).size());
    }

    @Test
    void callbackFailureLeavesRemainingConfirmedEventQueued() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        Http2FrameExtractor extractor = new Http2FrameExtractor(event -> {
            events.add(event);
            if (event instanceof Http2FrameExtracted && fail.getAndSet(false)) {
                throw new IllegalStateException("listener failed");
            }
        });

        assertThrows(IllegalStateException.class,
                () -> extractor.accept(concat(SETTINGS_ACK, CAPTURED_HEADERS)));
        extractor.accept(new byte[0]);

        assertEquals(2, frames(events).size());
        assertEquals(2, frames(events).stream().map(Http2FrameExtracted::streamOffset)
                .distinct().count());
    }

    @Test
    void callbackReentrancyIsRejected() {
        Http2FrameExtractor[] holder = new Http2FrameExtractor[1];
        holder[0] = new Http2FrameExtractor(event -> holder[0].accept(new byte[0]));

        assertThrows(IllegalStateException.class, () -> holder[0].accept(PREFACE));
        assertEquals(Http2ExtractionState.SYNCHRONIZED, holder[0].state());
    }

    @Test
    void longUnsynchronizedInputRetainsOnlyPossibleHeaderSuffix() {
        Http2FrameExtractor extractor = new Http2FrameExtractor(event -> { });
        byte[] garbage = new byte[200_000];
        Arrays.fill(garbage, (byte) 0xff);

        extractor.accept(garbage);

        assertTrue(extractor.bufferedByteCount() < 9);
    }

    @Test
    void rangeOverloadUsesOnlySelectedBytes() {
        List<Http2ExtractionEvent> events = new ArrayList<>();
        Http2FrameExtractor extractor = new Http2FrameExtractor(events::add);
        byte[] selected = concat(PREFACE, SETTINGS_ACK);
        byte[] surrounding = new byte[selected.length + 4];
        System.arraycopy(selected, 0, surrounding, 2, selected.length);

        extractor.accept(surrounding, 2, selected.length);

        assertEquals(1, frames(events).size());
        assertEquals(selected.length, extractor.acceptedByteCount());
    }

    private static List<Http2FrameExtracted> frames(List<Http2ExtractionEvent> events) {
        return events.stream().filter(Http2FrameExtracted.class::isInstance)
                .map(Http2FrameExtracted.class::cast).toList();
    }

    private static byte[] frame(int type, int flags, int streamId, byte[] payload) {
        byte[] result = new byte[9 + payload.length];
        result[0] = (byte) (payload.length >>> 16);
        result[1] = (byte) (payload.length >>> 8);
        result[2] = (byte) payload.length;
        result[3] = (byte) type;
        result[4] = (byte) flags;
        result[5] = (byte) (streamId >>> 24);
        result[6] = (byte) (streamId >>> 16);
        result[7] = (byte) (streamId >>> 8);
        result[8] = (byte) streamId;
        System.arraycopy(payload, 0, result, 9, payload.length);
        return result;
    }

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
