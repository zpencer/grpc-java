package io.grpc.examples;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;

/**
 * A ManagedChannel that is capable of switching targets. The target is switched when an RPC
 * finishes with a trailer that contains the key "switch-target" and whose value is the new target.
 */
public final class SwitchableTargetChannel extends ManagedChannel {
  public static final Metadata.Key<String> SWITCH_TARGET
      = Metadata.Key.of("switch-target", Metadata.ASCII_STRING_MARSHALLER);

  public interface ChannelFactory {
    ManagedChannelBuilder builderFor(String target);
  }

  private final Object lock = new Object();
  private final ChannelFactory factory;
  private volatile ManagedChannel curChannel;
  private volatile String curTarget;

  // if shutdown has been called, do not bother switching targets
  @GuardedBy("lock")
  private boolean shutdownCalled = false;

  private final ClientInterceptor endpointSwitch = new ClientInterceptor() {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions,
        Channel next) {
      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(
          methodDescriptor, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
            public void onClose(Status status, Metadata trailers) {
              String newTarget = trailers.get(SWITCH_TARGET);
              if (newTarget != null && !newTarget.equals(curTarget)) {
                synchronized (lock) {
                  // May have raced with another invocation that already switched targets
                  if (!shutdownCalled && !newTarget.equals(curTarget)) {
                    ManagedChannel old = curChannel;
                    curChannel = createChannel(newTarget);
                    curTarget = newTarget;
                    // nit: we should also awaitTermination()
                    old.shutdown();
                  }
                }
              }
              super.onClose(status, trailers);
            }
          }, headers);
        }
      };
    }
  };

  public SwitchableTargetChannel(String initialEndpoint, ChannelFactory factory) {
    curChannel = factory.builderFor(initialEndpoint).intercept(endpointSwitch).build();
    curTarget = initialEndpoint;
    this.factory = factory;
  }

  @Override
  public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
      MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
    return curChannel.newCall(methodDescriptor, callOptions);
  }

  @Override
  public String authority() {
    return curChannel.authority();
  }

  @Override
  public ManagedChannel shutdown() {
    synchronized (lock) {
      shutdownCalled = true;
      return curChannel.shutdown();
    }
  }

  @Override
  public boolean isShutdown() {
    return curChannel.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return curChannel.isTerminated();
  }

  @Override
  public ManagedChannel shutdownNow() {
    return curChannel.shutdown();
  }

  @Override
  public boolean awaitTermination(long l, TimeUnit timeUnit) throws InterruptedException {
    return curChannel.awaitTermination(l, timeUnit);
  }

  @GuardedBy("lock")
  private ManagedChannel createChannel(String target) {
    ManagedChannelBuilder builder = factory.builderFor(target);
    builder.intercept(endpointSwitch);
    return builder.build();
  }
}
