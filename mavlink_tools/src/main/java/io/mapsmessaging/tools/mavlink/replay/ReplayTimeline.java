package io.mapsmessaging.tools.mavlink.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ReplayTimeline implements AutoCloseable {

  private final List<SourceCursor> sourceCursors;

  public ReplayTimeline(List<Path> captureFiles, ReplayFormat replayFormat, long rawDelayNanos) throws IOException {
    ReplaySourceFactory replaySourceFactory = new ReplaySourceFactory();
    this.sourceCursors = new ArrayList<>();

    try {
      for (Path captureFile : captureFiles) {
        ReplaySource replaySource = replaySourceFactory.create(captureFile, replayFormat, rawDelayNanos);
        ReplayFrame replayFrame = replaySource.nextFrame();
        if (replayFrame == null) {
          replaySource.close();
          continue;
        }
        sourceCursors.add(new SourceCursor(captureFile, replaySource, replayFrame));
      }
      initialiseTimelines();
    } catch (IOException exception) {
      close();
      throw exception;
    }
  }

  public TimedReplayFrame nextFrame() throws IOException {
    SourceCursor sourceCursor = sourceCursors.stream()
        .filter(SourceCursor::hasFrame)
        .min(Comparator.comparingLong(SourceCursor::timelineNanos).thenComparing(cursor -> cursor.sourcePath().toString()))
        .orElse(null);

    if (sourceCursor == null) {
      return null;
    }

    TimedReplayFrame timedReplayFrame = new TimedReplayFrame(
        sourceCursor.sourcePath(),
        sourceCursor.timelineNanos(),
        sourceCursor.replayFrame()
    );
    sourceCursor.advance();
    return timedReplayFrame;
  }

  private void initialiseTimelines() {
    long sourceTimelineOrigin = sourceCursors.stream()
        .filter(SourceCursor::usesSourceTimeline)
        .mapToLong(cursor -> cursor.replayFrame().sourceTimestampNanos())
        .min()
        .orElse(0);

    for (SourceCursor sourceCursor : sourceCursors) {
      sourceCursor.initialiseTimeline(sourceTimelineOrigin);
    }
  }

  @Override
  public void close() throws IOException {
    IOException firstException = null;
    for (SourceCursor sourceCursor : sourceCursors) {
      try {
        sourceCursor.close();
      } catch (IOException exception) {
        if (firstException == null) {
          firstException = exception;
        } else {
          firstException.addSuppressed(exception);
        }
      }
    }
    if (firstException != null) {
      throw firstException;
    }
  }

  private static final class SourceCursor implements AutoCloseable {

    private final Path sourcePath;
    private final ReplaySource replaySource;
    private ReplayFrame replayFrame;
    private long timelineNanos;
    private long sourceTimelineOrigin;

    private SourceCursor(Path sourcePath, ReplaySource replaySource, ReplayFrame replayFrame) {
      this.sourcePath = sourcePath;
      this.replaySource = replaySource;
      this.replayFrame = replayFrame;
    }

    private void initialiseTimeline(long sourceTimelineOrigin) {
      this.sourceTimelineOrigin = sourceTimelineOrigin;
      if (usesSourceTimeline()) {
        timelineNanos = Math.max(0, replayFrame.sourceTimestampNanos() - sourceTimelineOrigin);
      } else {
        timelineNanos = replayFrame.delayNanos();
      }
    }

    private void advance() throws IOException {
      ReplayFrame nextFrame = replaySource.nextFrame();
      if (nextFrame == null) {
        replayFrame = null;
        return;
      }

      replayFrame = nextFrame;
      if (usesSourceTimeline()) {
        timelineNanos = Math.max(0, replayFrame.sourceTimestampNanos() - sourceTimelineOrigin);
      } else {
        timelineNanos = Math.addExact(timelineNanos, replayFrame.delayNanos());
      }
    }

    private Path sourcePath() {
      return sourcePath;
    }

    private ReplayFrame replayFrame() {
      return replayFrame;
    }

    private long timelineNanos() {
      return timelineNanos;
    }

    private boolean hasFrame() {
      return replayFrame != null;
    }

    private boolean usesSourceTimeline() {
      return replaySource.usesSourceTimeline();
    }

    @Override
    public void close() throws IOException {
      replaySource.close();
    }
  }
}
