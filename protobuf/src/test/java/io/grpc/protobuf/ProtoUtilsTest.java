/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.protobuf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.io.ByteStreams;
import com.google.protobuf.Type;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.ReadableBufferList;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ProtoUtils}. */
@RunWith(JUnit4.class)
public class ProtoUtilsTest {
  private Type proto = Type.newBuilder().setName("value").build();

  @Test
  public void testRoundtrip() throws Exception {
    Marshaller<Type> marshaller = ProtoUtils.marshaller(Type.getDefaultInstance());
    InputStream is = marshaller.stream(proto);
    is = new ByteArrayInputStream(ByteStreams.toByteArray(is));
    assertEquals(proto, marshaller.parse(is));
  }

  @Test
  public void keyForProto() {
    assertEquals("google.protobuf.Type-bin",
        ProtoUtils.keyForProto(Type.getDefaultInstance()).originalName());
  }

  @Test
  public void parseFromReadableBufferList_multipleBuffers() throws Exception {
    // protobuf (non lite) supports the multiple ByteBuffer constructor
    Marshaller<Type> marshaller = ProtoLiteUtils.marshaller(Type.getDefaultInstance());
    Type expect = Type.newBuilder().setName("expected name").build();
    byte[] bytes = expect.toByteArray();
    int half = bytes.length / 2;
    assertTrue(half > 2);

    InputStream bl = new CustomReadableBufferList(
        ByteBuffer.wrap(Arrays.copyOfRange(bytes, 0, half)),
        ByteBuffer.wrap(Arrays.copyOfRange(bytes, half, bytes.length)));
    Type result = marshaller.parse(bl);
    assertEquals(expect, result);
  }

  private static class CustomReadableBufferList extends InputStream implements ReadableBufferList {
    private final ByteBuffer[] bufs;

    CustomReadableBufferList(ByteBuffer ... bufs) {
      this.bufs = bufs;
    }

    @Override
    public boolean bufferListAvailable() {
      return true;
    }

    @Override
    public List<ByteBuffer> getBufferList() {
      return Arrays.asList(bufs);
    }

    @Override
    public int read() {
      throw new RuntimeException("should not be called");
    }
  }
}
