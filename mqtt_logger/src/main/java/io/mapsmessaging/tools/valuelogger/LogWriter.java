package io.mapsmessaging.tools.valuelogger;

import com.google.gson.JsonObject;
import java.io.Closeable;

public interface LogWriter extends Closeable {

  void open() throws Exception;

  void write(JsonObject logRecord) throws Exception;
}