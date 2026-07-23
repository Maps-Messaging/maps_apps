package io.mapsmessaging.tools.mavlink.replay;

import java.nio.file.Path;

public record TimedReplayFrame(Path sourcePath, long timelineNanos, ReplayFrame replayFrame) {
}
