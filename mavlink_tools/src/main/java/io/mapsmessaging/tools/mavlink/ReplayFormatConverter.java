package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.replay.ReplayFormat;
import picocli.CommandLine.ITypeConverter;

public class ReplayFormatConverter implements ITypeConverter<ReplayFormat> {

  @Override
  public ReplayFormat convert(String value) {
    return ReplayFormat.parse(value);
  }
}
