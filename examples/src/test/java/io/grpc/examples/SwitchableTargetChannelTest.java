package io.grpc.examples;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterBlockingStub;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class SwitchableTargetChannelTest {
  static final class GreeterImpl extends GreeterGrpc.GreeterImplBase {
    AtomicLong rpcCount = new AtomicLong();
    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      rpcCount.getAndIncrement();
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }

  // A server interceptor that adds the switch target key on all trailers
  private final class RedirectTarget implements ServerInterceptor {
    final String nextTarget;

    RedirectTarget(String nextTarget) {
      this.nextTarget = nextTarget;
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
        Metadata metadata, ServerCallHandler<ReqT, RespT> next) {
      return next.startCall(new SimpleForwardingServerCall<ReqT, RespT>(call) {
      @Override
      public void close(Status status, Metadata trailers) {
        trailers.put(SwitchableTargetChannel.SWITCH_TARGET, nextTarget);
        super.close(status, trailers);
      }
    }, metadata);
    }
  }

  @Test
  public void switchTargetTest() throws Exception {
    GreeterImpl g1 = new GreeterImpl();
    GreeterImpl g2 = new GreeterImpl();
    GreeterImpl g3 = new GreeterImpl();
    Server s1 = ServerBuilder.forPort(50051).addService(g1)
        .intercept(new RedirectTarget("localhost:50052")).build().start();
    Server s2 = ServerBuilder.forPort(50052).addService(g2)
        .intercept(new RedirectTarget("localhost:50053")).build().start();
    Server s3 = ServerBuilder.forPort(50053).addService(g3)
        .intercept(new RedirectTarget("localhost:50051")).build().start();
    SwitchableTargetChannel channel = new SwitchableTargetChannel("localhost:50051",
          target -> ManagedChannelBuilder.forTarget(target).usePlaintext());

    try {
      GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
      assertEquals(g1.rpcCount.get(), 0);
      assertEquals(g2.rpcCount.get(), 0);
      assertEquals(g3.rpcCount.get(), 0);

      HelloReply reply1 = stub.sayHello(HelloRequest.newBuilder().setName("name1").build());
      assertEquals(g1.rpcCount.get(), 1);
      assertEquals(g2.rpcCount.get(), 0);
      assertEquals(g3.rpcCount.get(), 0);

      HelloReply reply2 = stub.sayHello(HelloRequest.newBuilder().setName("name2").build());
      assertEquals(g1.rpcCount.get(), 1);
      assertEquals(g2.rpcCount.get(), 1);
      assertEquals(g3.rpcCount.get(), 0);

      HelloReply reply3 = stub.sayHello(HelloRequest.newBuilder().setName("name3").build());
      assertEquals(g1.rpcCount.get(), 1);
      assertEquals(g2.rpcCount.get(), 1);
      assertEquals(g3.rpcCount.get(), 1);

      HelloReply reply4 = stub.sayHello(HelloRequest.newBuilder().setName("name4").build());
      assertEquals(g1.rpcCount.get(), 2);
      assertEquals(g2.rpcCount.get(), 1);
      assertEquals(g3.rpcCount.get(), 1);
    } finally {
      channel.shutdownNow();
      s1.shutdownNow();
      s2.shutdownNow();
      s3.shutdownNow();
    }
  }
}
