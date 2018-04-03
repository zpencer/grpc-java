/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.services;

import com.google.protobuf.MessageLite;
import io.grpc.InternalServiceProviders;
import java.io.Closeable;
import java.util.Collections;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Subclasses must be thread safe, and are responsible for writing the binary log message to
 * the appropriate destination.
 */
@ThreadSafe
public abstract class BinaryLogSinkProvider implements Closeable {
  private static final BinaryLogSinkProvider INSTANCE = InternalServiceProviders.load(
      BinaryLogSinkProvider.class,
      Collections.<Class<?>>emptyList(),
      BinaryLogSinkProvider.class.getClassLoader(),
      new InternalServiceProviders.PriorityAccessor<BinaryLogSinkProvider>() {
        @Override
        public boolean isAvailable(BinaryLogSinkProvider provider) {
          return provider.isAvailable();
        }

        @Override
        public int getPriority(BinaryLogSinkProvider provider) {
          return provider.priority();
        }
      });

  /**
   * Returns the {@code BinaryLogSink} that should be used.
   */
  @Nullable
  public static BinaryLogSinkProvider provider() {
    return INSTANCE;
  }

  /**
   * Writes the {@code message} to the destination.
   */
  public abstract void write(MessageLite message);

  /**
   * Whether this provider is available for use, taking the current environment into consideration.
   * If {@code false}, no other methods are safe to be called.
   */
  protected abstract boolean isAvailable();

  /**
   * A priority, from 0 to 10 that this provider should be used, taking the current environment into
   * consideration. 5 should be considered the default, and then tweaked based on environment
   * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
   * be last in line.
   */
  protected abstract int priority();
}
