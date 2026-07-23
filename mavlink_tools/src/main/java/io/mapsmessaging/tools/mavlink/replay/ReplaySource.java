package io.mapsmessaging.tools.mavlink.replay;

import java.io.Closeable;
import java.io.IOException;

public interface ReplaySource extends Closeable {

  ReplayFrame nextFrame() throws IOException;

  default boolean usesSourceTimeline() {
    return false;
  }
}
