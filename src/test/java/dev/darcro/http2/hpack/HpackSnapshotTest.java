package dev.darcro.http2.hpack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.darcro.http2.frame.ByteSequence;
import dev.darcro.http2.frame.ContinuationFrame;
import dev.darcro.http2.frame.HeadersFrame;
import dev.darcro.http2.frame.Http2Flags;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HpackSnapshotTest {
    @Test
    void decoderSnapshotRoundTripPreservesStateAndCompleteness() throws Exception {
        HpackDecoder decoder = HpackDecoder.atConnectionStart();
        decoder.analyze(hex("4001610162"));

        HpackDecoderSnapshot snapshot = HpackDecoderSnapshot.fromByteArray(
                decoder.snapshot().toByteArray());
        HpackDecoder restored = HpackDecoder.restore(snapshot,
                HpackDecoderConfig.defaults());

        assertEquals(1, HpackDecoderSnapshot.FORMAT_VERSION);
        assertEquals(1, Byte.toUnsignedInt(decoder.snapshot().toByteArray()[4]));
        assertEquals(HpackContextCompleteness.OBSERVED_COMPLETE,
                snapshot.contextCompleteness());
        assertTrue(snapshot.tableLimitKnown());
        assertEquals("a", restored.analyze(hex("be")).fields().get(0).nameUtf8());
    }

    @Test
    void snapshotPreservesPendingSettingsReduction() throws Exception {
        HpackDecoder decoder = HpackDecoder.atConnectionStart();
        decoder.updateMaxDynamicTableSize(128);

        HpackDecoder restored = HpackDecoder.restore(
                HpackDecoderSnapshot.fromByteArray(decoder.snapshot().toByteArray()),
                HpackDecoderConfig.defaults());

        assertEquals(128, restored.snapshot().pendingMinimum().orElseThrow());
        assertTrue(restored.analyze(hex("3f61")).complete());
    }

    @Test
    void restoresIncompleteAssemblerBlock() throws Exception {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart();
        assembler.accept(headers(1, 0, "8286"));

        HpackFrameAssemblerSnapshot persisted = HpackFrameAssemblerSnapshot.fromByteArray(
                assembler.snapshot().toByteArray());
        HpackFrameAssembler restored = HpackFrameAssembler.restore(persisted,
                HpackDecoderConfig.defaults());
        HpackFrameAnalysis result = restored.accept(continuation(1,
                Http2Flags.END_HEADERS, "84"));

        assertEquals(HpackFrameAnalysisStatus.BLOCK_ANALYZED, result.status());
        assertEquals(3, result.decodedBlock().orElseThrow().fields().size());
    }

    @Test
    void restoresDiscardingAssemblerState() throws Exception {
        HpackFrameAssembler assembler = new HpackFrameAssembler(
                new HpackDecoderConfig(4096, 1, 1024));
        assembler.accept(headers(1, 0, "8286"));
        assertTrue(assembler.snapshot().discarding());

        HpackFrameAssembler restored = HpackFrameAssembler.restore(
                HpackFrameAssemblerSnapshot.fromByteArray(
                        assembler.snapshot().toByteArray()),
                new HpackDecoderConfig(4096, 1, 1024));

        assertTrue(restored.snapshot().discarding());
        restored.accept(continuation(1, Http2Flags.END_HEADERS, ""));
        assertFalse(restored.snapshot().discarding());
    }

    @Test
    void restoreAppliesCurrentLocalLimits() throws Exception {
        HpackDecoder decoder = new HpackDecoder();
        HpackDecoderSnapshot snapshot = decoder.snapshot();

        assertThrows(HpackSnapshotException.class,
                () -> HpackDecoder.restore(snapshot,
                        new HpackDecoderConfig(4096, 1, 1)));
    }

    @Test
    void snapshotOwnsIndependentEntryAndFragmentBytes() throws Exception {
        HpackDecoder decoder = HpackDecoder.atConnectionStart();
        decoder.analyze(hex("4001610162"));
        HpackDecoderSnapshot snapshot = decoder.snapshot();
        byte[] nameCopy = snapshot.dynamicTableEntries().get(0).name().toByteArray();
        nameCopy[0] = 'z';
        assertEquals("a", snapshot.dynamicTableEntries().get(0).name()
                .decode(StandardCharsets.UTF_8));

        byte[] fragment = hex("82");
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        assembler.accept(new HeadersFrame(1, 0, 1, ByteSequence.wrap(fragment), null, 0));
        HpackFrameAssemblerSnapshot assemblerSnapshot = assembler.snapshot();
        fragment[0] = 0;
        assertArrayEquals(hex("82"), assemblerSnapshot.incompleteBlock().toByteArray());
    }

    @Test
    void rejectsCorruptSnapshotEnvelope() {
        byte[] valid = new HpackDecoder().snapshot().toByteArray();

        byte[] badMagic = valid.clone();
        badMagic[0] = 0;
        assertReason(HpackSnapshotErrorReason.INVALID_MAGIC, badMagic);

        byte[] badVersion = valid.clone();
        badVersion[4] = 99;
        assertReason(HpackSnapshotErrorReason.UNSUPPORTED_VERSION, badVersion);

        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        assertReason(HpackSnapshotErrorReason.TRUNCATED_INPUT, truncated);

        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertReason(HpackSnapshotErrorReason.TRAILING_DATA, trailing);
    }

    private static void assertReason(HpackSnapshotErrorReason reason, byte[] bytes) {
        HpackSnapshotException exception = assertThrows(HpackSnapshotException.class,
                () -> HpackDecoderSnapshot.fromByteArray(bytes));
        assertEquals(reason, exception.reason());
    }

    private static HeadersFrame headers(int streamId, int flags, String value) {
        byte[] bytes = hex(value);
        return new HeadersFrame(bytes.length, flags, streamId,
                ByteSequence.wrap(bytes), null, 0);
    }

    private static ContinuationFrame continuation(int streamId, int flags,
                                                   String value) {
        byte[] bytes = hex(value);
        return new ContinuationFrame(bytes.length, flags, streamId,
                ByteSequence.wrap(bytes));
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
