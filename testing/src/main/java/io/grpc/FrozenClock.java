/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.AbstractFuture;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A clock that can be manually manipulated and provides a {@link ScheduledExecutorService} and a
 * {@link io.grpc.Deadline} factory. The clock only moves forward when {@link #forwardNanos(long)}
 * or {@link #forwardTime(long, TimeUnit)} is called.
 *
 * <p>The {@link ScheduledExecutorService} only supports a limited set of methods. Due tasks will
 * not execute until {@link #runDueTasks} is called, or if time is moved forward beyond their
 * due time. If unit tests use {@link Context#withDeadline(Deadline, ScheduledExecutorService)} or
 * {@link Context#withDeadlineAfter(long, TimeUnit, ScheduledExecutorService)}, then this is the
 * executor that should be used.
 */
public final class FrozenClock {

  private final ScheduledExecutorService scheduledExecutorService = new ScheduledExecutorImpl();

  private final PriorityBlockingQueue<ScheduledTask> tasks =
      new PriorityBlockingQueue<ScheduledTask>();

  private final Ticker ticker =
      new Ticker() {
        @Override public long read() {
          return currentTimeNanos;
        }
      };

  private final Deadline.Ticker deadlineTicker = new Deadline.Ticker() {
    @Override
    public long read() {
      return currentTimeNanos;
    }
  };

  private final Supplier<Stopwatch> stopwatchSupplier =
      new Supplier<Stopwatch>() {
        @Override public Stopwatch get() {
          return Stopwatch.createUnstarted(ticker);
        }
      };

  private long currentTimeNanos;

  /**
   * A task that is scheduled to be run at some future time.
   */
  public class ScheduledTask extends AbstractFuture<Void> implements ScheduledFuture<Void> {
    public final Runnable command;
    public final long dueTimeNanos;

    ScheduledTask(long dueTimeNanos, Runnable command) {
      this.dueTimeNanos = dueTimeNanos;
      this.command = command;
    }

    @Override public boolean cancel(boolean mayInterruptIfRunning) {
      tasks.remove(this);
      return super.cancel(mayInterruptIfRunning);
    }

    @Override public long getDelay(TimeUnit unit) {
      return unit.convert(dueTimeNanos - currentTimeNanos, TimeUnit.NANOSECONDS);
    }

    @Override public int compareTo(Delayed other) {
      ScheduledTask otherTask = (ScheduledTask) other;
      if (dueTimeNanos > otherTask.dueTimeNanos) {
        return 1;
      } else if (dueTimeNanos < otherTask.dueTimeNanos) {
        return -1;
      } else {
        return 0;
      }
    }

    void complete() {
      set(null);
    }

    @Override
    public String toString() {
      return "[due=" + dueTimeNanos + ", task=" + command + "]";
    }
  }

  private class ScheduledExecutorImpl implements ScheduledExecutorService {
    @Override public <V> ScheduledFuture<V> schedule(
        Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override public ScheduledFuture<?> schedule(Runnable cmd, long delay, TimeUnit unit) {
      ScheduledTask task = new ScheduledTask(currentTimeNanos + unit.toNanos(delay), cmd);
      tasks.add(task);
      return task;
    }

    @Override public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override public <T> T invokeAny(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override public boolean isShutdown() {
      throw new UnsupportedOperationException();
    }

    @Override public boolean isTerminated() {
      throw new UnsupportedOperationException();
    }

    @Override public void shutdown() {
      throw new UnsupportedOperationException();
    }

    @Override public List<Runnable> shutdownNow() {
      throw new UnsupportedOperationException();
    }

    @Override public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override public Future<?> submit(Runnable task) {
      throw new UnsupportedOperationException();
    }

    @Override public <T> Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException();
    }

    @Override public void execute(Runnable command) {
      // Since it is being enqueued immediately, no point in tracing the future for cancellation.
      Future<?> unused = schedule(command, 0, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Returns a partially implemented instance of {@link ScheduledExecutorService} that uses this
   * clock ticker for testing.
   *
   * <p>Only {@link ScheduledExecutorService#schedule(Runnable, long, TimeUnit)} and
   * {@link ScheduledExecutorService#execute(Runnable)} are supported.
   */
  public ScheduledExecutorService getScheduledExecutorService() {
    return scheduledExecutorService;
  }

  /**
   * Provides a stopwatch instance that uses the clock ticker.
   */
  public Supplier<Stopwatch> getStopwatchSupplier() {
    return stopwatchSupplier;
  }

  /**
   * Ticker of the clock.
   */
  public Ticker getTicker() {
    return ticker;
  }

  /**
   * Returns a {@link Deadline} whose remaining time is controlled by this class.
   *
   * <p>If the deadline is to be passed into
   * {@link Context#withDeadline(Deadline, ScheduledExecutorService)}, then
   * {@link #getScheduledExecutorService()} should be used as the executor to ensure deadline
   * cancellations are not scheduled based on system time.
   */
  public Deadline createDeadlineAfter(int duration, TimeUnit unit) {
    return Deadline.after(duration, unit, deadlineTicker);
  }

  /**
   * Run all due tasks.
   *
   * @return the number of tasks run by this call
   */
  public int runDueTasks() {
    return runDueTasks(new TaskFilter() {
      @Override
      public boolean shouldRun(Runnable runnable) {
        return true;
      }
    });
  }

  /**
   * Run all due tasks that match the {@link TaskFilter}.
   *
   * @return the number of tasks run by this call
   */
  public int runDueTasks(TaskFilter filter) {
    int count = 0;
    List<ScheduledTask> putBack = new ArrayList<ScheduledTask>();
    while (true) {
      ScheduledTask task = tasks.peek();
      if (task == null || task.dueTimeNanos > currentTimeNanos) {
        break;
      }
      if (tasks.remove(task)) {
        if (filter.shouldRun(task.command)) {
          task.command.run();
          task.complete();
          count++;
        } else {
          putBack.add(task);
        }
      }
    }
    tasks.addAll(putBack);
    return count;
  }

  /**
   * Return all due tasks.
   */
  public Collection<ScheduledTask> getDueTasks() {
    ArrayList<ScheduledTask> result = new ArrayList<ScheduledTask>();
    for (ScheduledTask task : tasks) {
      if (task.dueTimeNanos > currentTimeNanos) {
        continue;
      }
      result.add(task);
    }
    return result;
  }

  /**
   * Return all unrun tasks.
   */
  public Collection<ScheduledTask> getPendingTasks() {
    return new ArrayList<ScheduledTask>(tasks);
  }

  /**
   * Forward the time by the given duration and run all due tasks.
   *
   * @return the number of tasks run by this call
   */
  public int forwardTime(long value, TimeUnit unit) {
    currentTimeNanos += unit.toNanos(value);
    return runDueTasks();
  }

  /**
   * Forward the time by the given nanoseconds and run all due tasks.
   *
   * @return the number of tasks run by this call
   */
  public int forwardNanos(long nanos) {
    return forwardTime(nanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Return the number of queued tasks.
   */
  public int numPendingTasks() {
    return tasks.size();
  }

  /**
   * Returns the clock's current time in millis.
   */
  public long currentTimeMillis() {
    // Normally millis and nanos are of different epochs. Add an offset to simulate that.
    return TimeUnit.NANOSECONDS.toMillis(currentTimeNanos + 123456789L);
  }

  /**
   * A filter that allows us to have fine grained control over which tasks are run.
   */
  public interface TaskFilter {
    /**
     * Inspect the Runnable and returns true if it should be run.
     */
    boolean shouldRun(Runnable runnable);
  }
}
