package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable state for an HPACK decoder and its optional incomplete field block. */
public final class HpackFrameAssemblerSnapshot {
    private final HpackDecoderSnapshot decoderSnapshot;
    private final boolean active;
    private final HeaderBlockOrigin origin;
    private final int streamId;
    private final boolean endStream;
    private final int promisedStreamId;
    private final byte[] incompleteBlockBytes;
    private final ByteSequence incompleteBlock;

    HpackFrameAssemblerSnapshot(HpackDecoderSnapshot decoderSnapshot, boolean active,
                                HeaderBlockOrigin origin, int streamId,
                                boolean endStream, int promisedStreamId,
                                byte[] incompleteBlock) {
        this.decoderSnapshot = Objects.requireNonNull(decoderSnapshot, "decoderSnapshot");
        this.active = active;
        this.origin = origin;
        this.streamId = streamId;
        this.endStream = endStream;
        this.promisedStreamId = promisedStreamId;
        this.incompleteBlockBytes = incompleteBlock.clone();
        this.incompleteBlock = ByteSequence.wrap(incompleteBlockBytes);
    }

    public HpackDecoderSnapshot decoderSnapshot() {
        return decoderSnapshot;
    }

    public boolean active() {
        return active;
    }

    public Optional<HeaderBlockOrigin> origin() {
        return Optional.ofNullable(origin);
    }

    public int streamId() {
        return streamId;
    }

    public boolean endStream() {
        return endStream;
    }

    public OptionalInt promisedStreamId() {
        return promisedStreamId == 0
                ? OptionalInt.empty() : OptionalInt.of(promisedStreamId);
    }

    public ByteSequence incompleteBlock() {
        return incompleteBlock;
    }

    public byte[] toByteArray() {
        return HpackSnapshotCodec.encode(this);
    }

    public static HpackFrameAssemblerSnapshot fromByteArray(byte[] encoded)
            throws HpackSnapshotException {
        return HpackSnapshotCodec.decodeAssembler(encoded);
    }

    byte[] incompleteBlockBytes() {
        return incompleteBlockBytes.clone();
    }
}
