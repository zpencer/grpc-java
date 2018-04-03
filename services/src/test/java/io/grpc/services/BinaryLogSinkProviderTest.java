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

package io.grpc.services;

import static org.junit.Assert.fail;

import io.grpc.InternalServiceProviders;
import io.grpc.services.internal.BinaryLogSinkImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BinaryLogSinkProviderTest {

  @Test
  public void defaultSinkProvider() throws Exception {
    for (BinaryLogSinkProvider sink
        : InternalServiceProviders.getCandidatesViaServiceLoader(
            BinaryLogSinkProvider.class, BinaryLogSinkImpl.class.getClassLoader())) {
      if (sink instanceof BinaryLogSinkImpl) {
        return;
      }
    }
    fail("ServiceLoader unable to load BinaryLogSinkImpl");
  }
}
