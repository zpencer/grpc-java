/*
 * Copyright 2018, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.services.internal;

import com.google.protobuf.MessageLite;
import io.grpc.services.BinaryLogSinkProvider;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;

/**
 * Not a public class. Do not use.
 */
public final class BinaryLogSinkImpl extends BinaryLogSinkProvider {
  private static final Logger log = Logger.getLogger(BinaryLogSinkImpl.class.getName());

  private final Object lock = new Object();
  @GuardedBy("lock")
  private DataOutputStream out;
  @GuardedBy("lock")
  private boolean closed;

  /**
   * Creates an instance. The output goes to the JVM's temp dir with a prefix of BINARY_INFO.
   */
  public BinaryLogSinkImpl() {
  }

  private void maybeInit() {
    synchronized (lock) {
      if (closed || out != null) {
        return;
      }
      try {
        File outFile = File.createTempFile("BINARY_INFO.", "");
        log.log(Level.INFO, "Writing to {}", outFile.getAbsolutePath());
        out = new DataOutputStream(new FileOutputStream(outFile));
      } catch (IOException e) {
        closed = true;
        throw new RuntimeException(e);
      }
      Runtime.getRuntime().addShutdownHook(new Thread() {
        @Override
        public void run() {
          BinaryLogSinkImpl.this.closeQuietly();
        }
      });
    }
  }

  @Override
  public void write(MessageLite message) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      maybeInit();
      try {
        byte[] bytes = message.toByteArray();
        out.writeInt(bytes.length);
        out.write(bytes);
      } catch (IOException e) {
        log.log(Level.SEVERE, "Caught exception while writing", e);
        closeQuietly();
      }
    }
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 5;
  }

  @Override
  public void close() throws IOException {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      out.flush();
      out.close();
    }
  }

  private void closeQuietly() {
    try {
      close();
    } catch (IOException e) {
      log.log(Level.SEVERE, "Caught exception while closing", e);
    }
  }
}
