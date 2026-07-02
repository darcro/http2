package dev.darcro.http2.frame;

import java.util.List;

public record SettingsFrame(int length, int flags, int streamId,
                            List<Setting> settings) implements Http2Frame {
    public SettingsFrame {
        settings = List.copyOf(settings);
    }

    @Override
    public int type() {
        return Http2FrameTypes.SETTINGS;
    }

    public boolean ack() {
        return (flags & Http2Flags.ACK) != 0;
    }
}
