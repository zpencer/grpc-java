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

package io.grpc;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * An {@link java.io.InputStream} or alike whose available data can be exposed as a list of
 * read only {@link ByteBuffer}s.
 *
 * <p>Usually it's a {@link java.io.InputStream} that also implements this interface, in which case
 * reading from the ByteBuffers does not change the state of the {@link java.io.InputStream}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4783")
public interface ReadableBufferList {

  /**
   * Returns true if the buffer list is available.
   */
  boolean bufferListAvailable();

  /**
   * Returns a list of {@link ByteBuffer}s representing the readable data. Callers should not
   * mutate the buffers' contents. This method can only be called if {@link #bufferListAvailable()}
   * returned true.
   */
  List<ByteBuffer> getBufferList();
}
