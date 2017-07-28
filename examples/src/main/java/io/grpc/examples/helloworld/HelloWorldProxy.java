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

package io.grpc.examples.helloworld;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class HelloWorldProxy {
  private static final Logger logger = Logger.getLogger(HelloWorldProxy.class.getName());

  private static ManagedChannel channel;
  private static GreeterGrpc.GreeterBlockingStub blockingStub;
  private Server server;
  private static final Context.Key<Metadata> L5D_KEY = Context.key("l5d");

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50052;
    server = ServerBuilder.forPort(port)
        .addService(new GreeterImpl())
        .intercept(
            new ServerInterceptor() {
              @Override
              public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                logger.info("got these headers: " + headers);
                final Metadata l5d = new Metadata();
                for (String name : headers.keys()) {
                  if (name.startsWith("l5d-")) {
                    Metadata.Key<String> key = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
                    Iterable<String> values = headers.getAll(key);
                    if (values != null) {
                      for (String val : values) {
                        l5d.put(key, val);
                      }
                    }
                  }
                }
                ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
                return new ServerCall.Listener<ReqT>() {
                  @Override
                  public void onMessage(ReqT message) {
                    Context.current().withValue(L5D_KEY, l5d).run(
                        new Runnable() {
                          @Override
                          public void run() {
                            listener.onMessage(message);
                          }
                        }
                    );
                  }

                  public void onHalfClose() {
                    Context.current().withValue(L5D_KEY, l5d).run(
                        new Runnable() {
                          @Override
                          public void run() {
                            listener.onHalfClose();
                          }
                        }
                    );
                  }

                  public void onCancel() {
                    Context.current().withValue(L5D_KEY, l5d).run(
                        new Runnable() {
                          @Override
                          public void run() {
                            listener.onCancel();
                          }
                        }
                    );
                  }

                  public void onComplete() {
                    Context.current().withValue(L5D_KEY, l5d).run(
                        new Runnable() {
                          @Override
                          public void run() {
                            listener.onComplete();
                          }
                        }
                    );
                  }

                  public void onReady() {
                    Context.current().withValue(L5D_KEY, l5d).run(
                        new Runnable() {
                          @Override
                          public void run() {
                            listener.onReady();
                          }
                        }
                    );
                  }
                };
              }
            }
        )
        .build()
        .start();
    logger.info("Server started, listening on " + port);

    channel = ManagedChannelBuilder.forAddress("localhost", 50051)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext(true)
        .intercept(new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> clientCall = next.newCall(method, callOptions);
            return new ForwardingClientCall<ReqT, RespT>() {
              @Override
              public void start(Listener<RespT> responseListener, Metadata headers) {
                Metadata l5d = L5D_KEY.get(Context.current());
                logger.info("l5d=" + l5d);
                if (l5d != null) {
                  headers.merge(l5d);
                }
                super.start(responseListener, headers);
              }
              @Override
              protected ClientCall<ReqT, RespT> delegate() {
                return clientCall;
              }
            };
          }
        })
        .build();
    blockingStub = GreeterGrpc.newBlockingStub(channel);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        HelloWorldProxy.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    final HelloWorldProxy server = new HelloWorldProxy();
    server.start();
    server.blockUntilShutdown();
  }

  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      responseObserver.onNext(blockingStub.sayHello(req));
      logger.info("got proxied response");
      responseObserver.onCompleted();
    }
  }
}
