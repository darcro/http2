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
import dev.darcro.http2.frame.PushPromiseFrame;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class HpackSnapshotTest {
    @Test
    void decoderRoundTripContinuesTheSameDynamicContext() throws Exception {
        HpackDecoder uninterrupted = populatedRequestDecoder();
        HpackDecoder persisted = populatedRequestDecoder();

        HpackDecoderSnapshot snapshot = persisted.snapshot();
        assertEquals(2, snapshot.dynamicTableEntries().size());
        assertEquals("cache-control", utf8(snapshot.dynamicTableEntries().get(0).name()));
        assertEquals("no-cache", utf8(snapshot.dynamicTableEntries().get(0).value()));
        assertEquals(persisted.dynamicTableSize(), snapshot.dynamicTableSize());

        byte[] encodedSnapshot = snapshot.toByteArray();
        HpackDecoderSnapshot decodedSnapshot =
                HpackDecoderSnapshot.fromByteArray(encodedSnapshot);
        assertArrayEquals(encodedSnapshot, decodedSnapshot.toByteArray());

        HpackDecoder restored = HpackDecoder.restore(decodedSnapshot,
                HpackDecoderConfig.defaults());
        byte[] thirdRequest = hex(
                "828785bf408825a849e95ba97d7f8925a849e95bb8e8b4bf");
        assertFieldsEqual(uninterrupted.decode(thirdRequest), restored.decode(thirdRequest));
        assertEquals(uninterrupted.dynamicTableSize(), restored.dynamicTableSize());
    }

    @Test
    void snapshotPreservesPendingSettingsReduction() throws Exception {
        HpackDecoder decoder = new HpackDecoder(
                new HpackDecoderConfig(8_192, 65_536, 65_536));
        decoder.decode(hex("4001610162"));
        decoder.updateMaxDynamicTableSize(128);

        HpackDecoderSnapshot snapshot = HpackDecoderSnapshot.fromByteArray(
                decoder.snapshot().toByteArray());
        assertEquals(4_096, snapshot.dynamicTableLimit());
        assertEquals(128, snapshot.maximumTableSize());
        assertEquals(128, snapshot.pendingMinimum().orElseThrow());

        HpackDecoder restored = HpackDecoder.restore(snapshot,
                new HpackDecoderConfig(8_192, 65_536, 65_536));
        assertTrue(restored.decode(hex("3f61")).isEmpty());
        HpackDecoderSnapshot afterUpdate = restored.snapshot();
        assertEquals(128, afterUpdate.dynamicTableLimit());
        assertTrue(afterUpdate.pendingMinimum().isEmpty());
    }

    @Test
    void restoreRejectsStateBeyondCallerConfiguration() throws Exception {
        HpackDecoder decoder = new HpackDecoder(
                new HpackDecoderConfig(8_192, 65_536, 65_536));
        decoder.updateMaxDynamicTableSize(8_192);
        decoder.decode(hex("3fe13f"));

        HpackSnapshotException error = assertThrows(HpackSnapshotException.class,
                () -> HpackDecoder.restore(decoder.snapshot(),
                        HpackDecoderConfig.strictDefaults()));
        assertEquals(HpackSnapshotErrorReason.CONFIGURATION_LIMIT, error.reason());
        assertEquals(-1, error.offset());
    }

    @Test
    void snapshotBytesAndEntriesAreImmutableCopies() throws Exception {
        byte[] block = hex("4001610162");
        HpackDecoder decoder = new HpackDecoder();
        decoder.decode(block);
        HpackDecoderSnapshot snapshot = decoder.snapshot();

        block[2] = 'z';
        byte[] exposedNameCopy = snapshot.dynamicTableEntries().get(0).name().toByteArray();
        exposedNameCopy[0] = 'z';
        assertEquals("a", utf8(snapshot.dynamicTableEntries().get(0).name()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.dynamicTableEntries().clear());
    }

    @Test
    void assemblerRoundTripResumesAnIncompleteHeadersBlock() throws Exception {
        byte[] initialFragment = hex("8286");
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        HeadersFrame headers = new HeadersFrame(initialFragment.length,
                Http2Flags.END_STREAM, 1, ByteSequence.wrap(initialFragment), null, 0);
        assertTrue(assembler.accept(headers).isEmpty());

        HpackFrameAssemblerSnapshot snapshot = assembler.snapshot();
        byte[] encodedSnapshot = snapshot.toByteArray();
        initialFragment[0] = 0;
        HpackFrameAssemblerSnapshot decoded =
                HpackFrameAssemblerSnapshot.fromByteArray(encodedSnapshot);
        assertTrue(decoded.active());
        assertEquals(HeaderBlockOrigin.HEADERS, decoded.origin().orElseThrow());
        assertEquals(1, decoded.streamId());
        assertTrue(decoded.endStream());
        assertArrayEquals(hex("8286"), decoded.incompleteBlock().toByteArray());

        HpackFrameAssembler restored = HpackFrameAssembler.restore(decoded,
                HpackDecoderConfig.defaults());
        byte[] finalFragment = hex("84410f7777772e6578616d706c652e636f6d");
        DecodedHeaderBlock block = restored.accept(new ContinuationFrame(
                finalFragment.length, Http2Flags.END_HEADERS, 1,
                ByteSequence.wrap(finalFragment))).orElseThrow();
        assertEquals(4, block.fields().size());
        assertEquals("www.example.com", block.fields().get(3).valueUtf8());
        assertTrue(block.endStream());
        assertFalse(restored.failed());
    }

    @Test
    void idleAssemblerRoundTripRemainsUsable() throws Exception {
        HpackFrameAssemblerSnapshot snapshot = new HpackFrameAssembler().snapshot();
        HpackFrameAssemblerSnapshot decoded = HpackFrameAssemblerSnapshot.fromByteArray(
                snapshot.toByteArray());
        assertFalse(decoded.active());
        assertTrue(decoded.origin().isEmpty());
        assertTrue(decoded.incompleteBlock().isEmpty());

        HpackFrameAssembler restored = HpackFrameAssembler.restore(decoded,
                HpackDecoderConfig.defaults());
        assertFalse(restored.failed());
        assertEquals(HpackDecoderConfig.defaults(), restored.config());
    }

    @Test
    void assemblerSnapshotDoesNotPersistSequenceRecoveryDiagnostics() throws Exception {
        HpackFrameAssembler assembler = new HpackFrameAssembler(
                HpackFrameSequenceRecoveryPolicy.RECOVER);
        assertTrue(assembler.accept(new ContinuationFrame(1, Http2Flags.END_HEADERS,
                1, ByteSequence.wrap(hex("82")))).isEmpty());
        assertTrue(assembler.recoveredSequenceErrors());
        HpackFrameAssemblerSnapshot snapshot = HpackFrameAssemblerSnapshot.fromByteArray(
                assembler.snapshot().toByteArray());

        HpackFrameAssembler legacyRestored = HpackFrameAssembler.restore(snapshot,
                HpackDecoderConfig.defaults());
        HpackFrameAssembler recoverRestored = HpackFrameAssembler.restore(snapshot,
                HpackDecoderConfig.defaults(), HpackFrameSequenceRecoveryPolicy.RECOVER);

        assertFalse(legacyRestored.failed());
        assertEquals(HpackFrameSequenceRecoveryPolicy.FAIL_FAST,
                legacyRestored.sequenceRecoveryPolicy());
        assertFalse(legacyRestored.recoveredSequenceErrors());

        assertFalse(recoverRestored.failed());
        assertEquals(HpackFrameSequenceRecoveryPolicy.RECOVER,
                recoverRestored.sequenceRecoveryPolicy());
        assertFalse(recoverRestored.recoveredSequenceErrors());
        assertTrue(recoverRestored.accept(new ContinuationFrame(1,
                Http2Flags.END_HEADERS, 1, ByteSequence.wrap(hex("82")))).isEmpty());
        assertTrue(recoverRestored.recoveredSequenceErrors());
    }

    @Test
    void assemblerRoundTripPreservesPushPromiseMetadata() throws Exception {
        byte[] initial = hex("8286");
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        assembler.accept(new PushPromiseFrame(initial.length, 0, 1, 2,
                ByteSequence.wrap(initial), 0));

        HpackFrameAssembler restored = HpackFrameAssembler.restore(
                HpackFrameAssemblerSnapshot.fromByteArray(
                        assembler.snapshot().toByteArray()),
                HpackDecoderConfig.defaults());
        byte[] last = hex("84");
        DecodedHeaderBlock block = restored.accept(new ContinuationFrame(last.length,
                Http2Flags.END_HEADERS, 1, ByteSequence.wrap(last))).orElseThrow();

        assertEquals(HeaderBlockOrigin.PUSH_PROMISE, block.origin());
        assertEquals(2, block.promisedStreamId().orElseThrow());
        assertFalse(block.endStream());
        assertEquals(3, block.fields().size());
    }

    @Test
    void assemblerRestoreEnforcesEncodedBlockLimit() throws Exception {
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        byte[] fragment = hex("828684");
        assembler.accept(new HeadersFrame(fragment.length, 0, 1,
                ByteSequence.wrap(fragment), null, 0));

        HpackSnapshotException error = assertThrows(HpackSnapshotException.class,
                () -> HpackFrameAssembler.restore(assembler.snapshot(),
                        new HpackDecoderConfig(4_096, 2, 65_536)));
        assertEquals(HpackSnapshotErrorReason.CONFIGURATION_LIMIT, error.reason());
    }

    @Test
    void failedDecoderAndAssemblerCannotBeSnapshotted() {
        HpackDecoder decoder = new HpackDecoder();
        assertThrows(HpackDecodingException.class, () -> decoder.decode(hex("80")));
        assertThrows(IllegalStateException.class, decoder::snapshot);

        HpackFrameAssembler assembler = new HpackFrameAssembler();
        assertThrows(HpackDecodingException.class, () -> assembler.accept(
                new HeadersFrame(1, Http2Flags.END_HEADERS, 1,
                        ByteSequence.wrap(hex("80")), null, 0)));
        assertThrows(IllegalStateException.class, assembler::snapshot);
    }

    @Test
    void decoderCodecRejectsHeaderAndLengthCorruption() throws Exception {
        byte[] valid = new HpackDecoder().snapshot().toByteArray();

        assertReason(HpackSnapshotErrorReason.INVALID_MAGIC, changed(valid, 0, 0));
        assertReason(HpackSnapshotErrorReason.UNSUPPORTED_VERSION, changed(valid, 4, 2));
        assertReason(HpackSnapshotErrorReason.INVALID_KIND, changed(valid, 5, 2));
        assertReason(HpackSnapshotErrorReason.INVALID_DECODER_STATE,
                changed(valid, 6, 1));
        assertReason(HpackSnapshotErrorReason.TRUNCATED_INPUT,
                Arrays.copyOf(valid, valid.length - 1));
        assertReason(HpackSnapshotErrorReason.TRAILING_DATA,
                Arrays.copyOf(valid, valid.length + 1));
        assertReason(HpackSnapshotErrorReason.INVALID_LENGTH,
                changed(valid, 24, 0xff));
    }

    @Test
    void decoderCodecRejectsInvalidTableInvariants() throws Exception {
        byte[] valid = new HpackDecoder().snapshot().toByteArray();
        byte[] negativeLimit = valid.clone();
        putInt(negativeLimit, 12, -1);
        assertReason(HpackSnapshotErrorReason.INVALID_DECODER_STATE, negativeLimit);

        byte[] limitAboveMaximum = valid.clone();
        putInt(limitAboveMaximum, 16, 0);
        assertReason(HpackSnapshotErrorReason.INVALID_DECODER_STATE, limitAboveMaximum);
    }

    @Test
    void assemblerCodecRejectsWrongKindAndInvalidMetadata() throws Exception {
        byte[] decoder = new HpackDecoder().snapshot().toByteArray();
        HpackSnapshotException wrongKind = assertThrows(HpackSnapshotException.class,
                () -> HpackFrameAssemblerSnapshot.fromByteArray(decoder));
        assertEquals(HpackSnapshotErrorReason.INVALID_KIND, wrongKind.reason());

        byte[] assembler = new HpackFrameAssembler()
                .snapshot().toByteArray();
        assembler[28] = 1; // active without origin or stream metadata
        HpackSnapshotException invalid = assertThrows(HpackSnapshotException.class,
                () -> HpackFrameAssemblerSnapshot.fromByteArray(assembler));
        assertEquals(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE, invalid.reason());
    }

    private static HpackDecoder populatedRequestDecoder() throws Exception {
        HpackDecoder decoder = new HpackDecoder();
        decoder.decode(hex("828684410f7777772e6578616d706c652e636f6d"));
        decoder.decode(hex("828684be58086e6f2d6361636865"));
        return decoder;
    }

    private static void assertFieldsEqual(List<HpackHeaderField> expected,
                                          List<HpackHeaderField> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).name(), actual.get(i).name());
            assertEquals(expected.get(i).value(), actual.get(i).value());
            assertEquals(expected.get(i).sensitive(), actual.get(i).sensitive());
        }
    }

    private static void assertReason(HpackSnapshotErrorReason reason, byte[] encoded) {
        HpackSnapshotException error = assertThrows(HpackSnapshotException.class,
                () -> HpackDecoderSnapshot.fromByteArray(encoded));
        assertEquals(reason, error.reason(), error.getMessage());
    }

    private static byte[] changed(byte[] source, int offset, int value) {
        byte[] result = source.clone();
        result[offset] = (byte) value;
        return result;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static String utf8(ByteSequence value) {
        return new String(value.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
