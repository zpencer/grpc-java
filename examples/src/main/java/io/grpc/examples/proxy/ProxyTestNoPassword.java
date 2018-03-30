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

package io.grpc.examples.proxy;

import com.google.protobuf.EmptyProtos.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.testing.integration.TestServiceGrpc;
import io.grpc.testing.integration.TestServiceGrpc.TestServiceBlockingStub;
import java.util.concurrent.TimeUnit;

/**
 * From examples directory:
 * $ ./gradlew clean -PmainClass=io.grpc.examples.proxy.ProxyTestNoPassword execute
 *
 * Note that -Dhttps.proxyHost and -Dhttps.proxyPort are hardcoded for the
 * execute task in examples/build.gradle.
 */
public class ProxyTestNoPassword {

  public static void main(String[] args) throws Exception {
    String proxyHost = System.getProperty("https.proxyHost");
    String proxyPort = System.getProperty("https.proxyPort");
    System.out.println(String.format("proxyHost=%s proxyPort=%s", proxyHost, proxyPort));
    ManagedChannel channel
        = ManagedChannelBuilder.forTarget("grpc-test.sandbox.googleapis.com:443").build();
    try {
      TestServiceBlockingStub blockingStub = TestServiceGrpc.newBlockingStub(channel);
      Empty response = blockingStub.emptyCall(Empty.getDefaultInstance());
      System.out.println("received the response");
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
