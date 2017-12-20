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

package io.grpc;

import com.google.common.annotations.VisibleForTesting;
import java.util.List;

@Internal
public final class InternalServiceProviders {
  public static <T extends ServiceProvider> T load(
      Class<T> klass, List<String> hardCodedClasses, ClassLoader classLoader) {
    return ServiceProviders.load(klass, hardCodedClasses, classLoader);
  }

  public static <T extends ServiceProvider> List<T> loadAll(
      Class<T> klass, List<String> hardCodedClasses, ClassLoader classLoader) {
    return ServiceProviders.loadAll(klass, hardCodedClasses, classLoader);
  }

  public static boolean isAndroid(ClassLoader cl) {
    return ServiceProviders.isAndroid(cl);
  }

  @VisibleForTesting
  public static <T> Iterable<T> getCandidatesViaServiceLoader(
      Class<T> klass, ClassLoader classLoader) {
    return ServiceProviders.getCandidatesViaServiceLoader(klass, classLoader);
  }

  @VisibleForTesting
  public static <T> Iterable<T> getCandidatesViaHardCoded(
      Class<T> klass, List<String> classNames, ClassLoader classLoader) {
    return ServiceProviders.getCandidatesViaHardCoded(klass, classNames, classLoader);
  }
}
