/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.NameResolverProvider.HardcodedClasses;
import io.grpc.internal.DnsNameResolverProvider;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NameResolverProvider}. */
@RunWith(JUnit4.class)
public class NameResolverProviderTest {
  private final URI uri = URI.create("dns:///localhost");
  private final Attributes attributes = Attributes.EMPTY;

  @Test
  public void getDefaultScheme_noProvider() {
    List<NameResolverProvider> providers = Collections.<NameResolverProvider>emptyList();
    NameResolver.Factory factory = NameResolverProvider.asFactory(providers);
    try {
      factory.getDefaultScheme();
      fail("Expected exception");
    } catch (IllegalStateException ex) {
      assertTrue(ex.toString(), ex.getMessage().contains("No NameResolverProviders found"));
    }
  }

  @Test
  public void newNameResolver_providerReturnsNull() {
    List<NameResolverProvider> providers = Collections.<NameResolverProvider>singletonList(
        new BaseProvider(true, 5) {
          @Override
          public NameResolver newNameResolver(URI passedUri, Attributes passedAttributes) {
            assertSame(uri, passedUri);
            assertSame(attributes, passedAttributes);
            return null;
          }
        });
    assertNull(NameResolverProvider.asFactory(providers).newNameResolver(uri, attributes));
  }

  @Test
  public void newNameResolver_noProvider() {
    List<NameResolverProvider> providers = Collections.<NameResolverProvider>emptyList();
    NameResolver.Factory factory = NameResolverProvider.asFactory(providers);
    try {
      factory.newNameResolver(uri, attributes);
      fail("Expected exception");
    } catch (IllegalStateException ex) {
      assertTrue(ex.toString(), ex.getMessage().contains("No NameResolverProviders found"));
    }
  }

  @Test
  public void baseProviders() {
    List<NameResolverProvider> providers = NameResolverProvider.providers();
    assertEquals(1, providers.size());
    assertSame(DnsNameResolverProvider.class, providers.get(0).getClass());
    assertEquals("dns", NameResolverProvider.asFactory().getDefaultScheme());
  }

  @Test
  public void getClassesViaHardcoded_triesToLoadClasses() throws Exception {
    ClassLoader cl = getClass().getClassLoader();
    Set<String> classLoaderHistory = new HashSet<String>();
    Iterator<?> classIter = ServiceProvidersTestUtil
        .invokeCallable(
            HardcodedClassesCallable.class.getName(), cl, classLoaderHistory);
    assertTrue(classIter.hasNext());
    Class<?> klass = (Class) classIter.next();
    assertEquals(DnsNameResolverProvider.class.getName(), klass.getName());
    assertFalse(classIter.hasNext());
    assertTrue(classLoaderHistory.contains(DnsNameResolverProvider.class.getName()));
  }

  @Test
  public void getClassesViaHardCoded_ignoresMissingClasses() throws Exception {
    ClassLoader cl = getClass().getClassLoader();
    Set<String> classLoaderHistory = new HashSet<String>();
    cl = new ClassLoader(cl) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(DnsNameResolverProvider.class.getName())) {
          throw new ClassNotFoundException();
        } else {
          return super.loadClass(name, resolve);
        }
      }
    };
    Iterator<?> classIter = ServiceProvidersTestUtil
        .invokeCallable(
            HardcodedClassesCallable.class.getName(), cl, classLoaderHistory);
    assertFalse("Iterator should be empty", classIter.hasNext());
    assertTrue(classLoaderHistory.contains(DnsNameResolverProvider.class.getName()));
  }

  public static final class HardcodedClassesCallable implements Callable<Iterator<Class<?>>> {
    @Override
    public Iterator<Class<?>> call() throws Exception {
      return new HardcodedClasses().iterator();
    }
  }

  private static class BaseProvider extends NameResolverProvider {
    private final boolean isAvailable;
    private final int priority;

    public BaseProvider(boolean isAvailable, int priority) {
      this.isAvailable = isAvailable;
      this.priority = priority;
    }

    @Override
    protected boolean isAvailable() {
      return isAvailable;
    }

    @Override
    protected int priority() {
      return priority;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, Attributes params) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getDefaultScheme() {
      return "scheme" + getClass().getSimpleName();
    }
  }
}
