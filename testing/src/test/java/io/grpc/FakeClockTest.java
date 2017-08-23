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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link FrozenClock}. */
@RunWith(JUnit4.class)
public class FakeClockTest {
  private FrozenClock fakeClock;

  @Before
  public void setUp() {
    fakeClock = new FrozenClock();
  }

  @Test
  public void testScheduledExecutorService_sameInstance() {
    ScheduledExecutorService scheduledExecutorService1 = fakeClock.getScheduledExecutorService();
    ScheduledExecutorService scheduledExecutorService2 = fakeClock.getScheduledExecutorService();
    assertTrue(scheduledExecutorService1 == scheduledExecutorService2);
  }

  @Test
  public void testScheduledExecutorService_isDone() {
    ScheduledFuture<?> future = fakeClock.getScheduledExecutorService()
        .schedule(newRunnable(), 100L, TimeUnit.NANOSECONDS);

    fakeClock.forwardNanos(99L);
    assertFalse(future.isDone());

    fakeClock.forwardNanos(2L);
    assertTrue(future.isDone());
  }

  @Test
  public void testScheduledExecutorService_cancel() {
    ScheduledFuture<?> future = fakeClock.getScheduledExecutorService()
        .schedule(newRunnable(), 100L, TimeUnit.NANOSECONDS);

    fakeClock.forwardNanos(99L);
    future.cancel(false);

    fakeClock.forwardNanos(2);
    assertTrue(future.isCancelled());
  }

  @Test
  public void testScheduledExecutorService_getDelay() {
    ScheduledFuture<?> future = fakeClock.getScheduledExecutorService()
        .schedule(newRunnable(), 100L, TimeUnit.NANOSECONDS);

    fakeClock.forwardNanos(90L);
    assertEquals(10L, future.getDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  public void testScheduledExecutorService_result() {
    FrozenClock fakeClock = new FrozenClock();
    final boolean[] result = new boolean[]{false};
    ScheduledFuture<?> unused = fakeClock.getScheduledExecutorService().schedule(
        new Runnable() {
          @Override
          public void run() {
            result[0] = true;
          }
        },
        100L,
        TimeUnit.NANOSECONDS);

    fakeClock.forwardNanos(100L);
    assertTrue(result[0]);
  }

  @Test
  public void testStopWatch() {
    Stopwatch stopwatch = fakeClock.getStopwatchSupplier().get();
    long expectedElapsedNanos = 0L;

    stopwatch.start();

    fakeClock.forwardNanos(100L);
    expectedElapsedNanos += 100L;
    assertEquals(expectedElapsedNanos, stopwatch.elapsed(TimeUnit.NANOSECONDS));

    fakeClock.forwardTime(10L, TimeUnit.MINUTES);
    expectedElapsedNanos += TimeUnit.MINUTES.toNanos(10L);
    assertEquals(expectedElapsedNanos, stopwatch.elapsed(TimeUnit.NANOSECONDS));

    stopwatch.stop();

    fakeClock.forwardNanos(1000L);
    assertEquals(expectedElapsedNanos, stopwatch.elapsed(TimeUnit.NANOSECONDS));

    stopwatch.reset();

    expectedElapsedNanos = 0L;
    assertEquals(expectedElapsedNanos, stopwatch.elapsed(TimeUnit.NANOSECONDS));
  }

  @Test
  @SuppressWarnings("FutureReturnValueIgnored")
  public void testPendingAndDueTasks() {
    ScheduledExecutorService scheduledExecutorService = fakeClock.getScheduledExecutorService();

    scheduledExecutorService.schedule(newRunnable(), 200L, TimeUnit.NANOSECONDS);
    scheduledExecutorService.execute(newRunnable());
    scheduledExecutorService.schedule(newRunnable(), 0L, TimeUnit.NANOSECONDS);
    scheduledExecutorService.schedule(newRunnable(), 80L, TimeUnit.NANOSECONDS);
    scheduledExecutorService.schedule(newRunnable(), 90L, TimeUnit.NANOSECONDS);
    scheduledExecutorService.schedule(newRunnable(), 100L, TimeUnit.NANOSECONDS);
    scheduledExecutorService.schedule(newRunnable(), 110L, TimeUnit.NANOSECONDS);
    scheduledExecutorService.schedule(newRunnable(), 120L, TimeUnit.NANOSECONDS);


    assertEquals(8, fakeClock.numPendingTasks());
    assertEquals(2, fakeClock.getDueTasks().size());

    fakeClock.runDueTasks();

    assertEquals(6, fakeClock.numPendingTasks());
    assertEquals(0, fakeClock.getDueTasks().size());

    fakeClock.forwardNanos(90L);

    assertEquals(4, fakeClock.numPendingTasks());
    assertEquals(0, fakeClock.getDueTasks().size());

    fakeClock.forwardNanos(20L);

    assertEquals(2, fakeClock.numPendingTasks());
    assertEquals(0, fakeClock.getDueTasks().size());
  }

  @Test
  public void testTaskFilter() {
    ScheduledExecutorService scheduledExecutorService = fakeClock.getScheduledExecutorService();
    final AtomicBoolean selectedDone = new AtomicBoolean();
    final AtomicBoolean ignoredDone = new AtomicBoolean();
    final Runnable selectedRunnable = new Runnable() {
      @Override
      public void run() {
        selectedDone.set(true);
      }
    };
    Runnable ignoredRunnable = new Runnable() {
      @Override
      public void run() {
        ignoredDone.set(true);
      }
    };
    scheduledExecutorService.execute(selectedRunnable);
    scheduledExecutorService.execute(ignoredRunnable);
    assertEquals(2, fakeClock.numPendingTasks());
    assertEquals(1, fakeClock.runDueTasks(new FrozenClock.TaskFilter() {
      @Override
      public boolean shouldRun(Runnable runnable) {
        return runnable == selectedRunnable;
      }
    }));
    assertTrue(selectedDone.get());
    assertFalse(ignoredDone.get());
  }

  @Test
  public void testDeadlineExpires() {
    Deadline deadline = fakeClock.createDeadlineAfter(10, TimeUnit.MILLISECONDS);
    assertFalse(deadline.isExpired());
    fakeClock.forwardTime(9, TimeUnit.MILLISECONDS);
    assertFalse(deadline.isExpired());
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    assertTrue(deadline.isExpired());
  }

  @Test
  public void testDeadlineExpirationListeners() {
    Deadline deadline = fakeClock.createDeadlineAfter(10, TimeUnit.MILLISECONDS);
    Context withDeadline = Context.current().withDeadline(
        deadline, fakeClock.getScheduledExecutorService());
    final AtomicReference<Context> cancelledContext = new AtomicReference<Context>();
    withDeadline.addListener(new Context.CancellationListener() {
      @Override
      public void cancelled(Context context) {
        cancelledContext.set(context);
      }
    }, MoreExecutors.directExecutor());
    assertEquals(1, fakeClock.forwardTime(10, TimeUnit.MILLISECONDS));
    assertSame(withDeadline, cancelledContext.get());
  }

  private Runnable newRunnable() {
    return new Runnable() {
      @Override
      public void run() {
      }
    };
  }
}
