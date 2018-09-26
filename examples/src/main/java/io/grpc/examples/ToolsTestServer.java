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

package io.grpc.examples;

import com.google.protobuf.Any;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.util.Timestamps;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.StatusProto;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.stub.StreamObserver;
import io.grpc.toolstest.ComplexMessage;
import io.grpc.toolstest.GetComplexMessageRequest;
import io.grpc.toolstest.OtherMessage;
import io.grpc.toolstest.SimpleMessage;
import io.grpc.toolstest.ToolsTestGrpc;
import io.grpc.toolstest_external.ExternalMessage;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Server that implements toolstest.proto
 */
public class ToolsTestServer {
  private static final Logger logger = Logger.getLogger(
      ToolsTestServer.class.getName());

  private Server server;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50051;
    server = ServerBuilder.forPort(port)
        // .addService(new ToolsTestServer())
        .addService(ProtoReflectionService.newInstance())
        .addService(new ToolsTestServerImpl())
        .intercept(new MetadataInterceptor())
        .build()
        .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        ToolsTestServer.this.stop();
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
    final ToolsTestServer server = new ToolsTestServer();
    server.start();
    server.blockUntilShutdown();
  }

  static final class MetadataInterceptor implements ServerInterceptor {
    static final Metadata.Key<String> ASCII_KEY =
        Metadata.Key.of("tools-ascii", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<SimpleMessage> STUB_KEY =
        Metadata.Key.of("tools-stub-proto-bin",
            ProtoUtils.metadataMarshaller(SimpleMessage.getDefaultInstance()));
    static final Metadata.Key<OtherMessage> NON_STUB_KEY =
        Metadata.Key.of("tools-nonstub-proto-bin",
            ProtoUtils.metadataMarshaller(OtherMessage.getDefaultInstance()));
    static final Metadata.Key<ExternalMessage> EXTERNAL_KEY =
        Metadata.Key.of("tools-external-proto-bin",
            ProtoUtils.metadataMarshaller(ExternalMessage.getDefaultInstance()));


    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        final Metadata requestHeaders,
        ServerCallHandler<ReqT, RespT> next) {
      return next.startCall(new SimpleForwardingServerCall<ReqT, RespT>(call) {
        @Override
        public void sendHeaders(Metadata meta) {
          meta.put(ASCII_KEY, "ascii header value");
          meta.put(
              STUB_KEY, SimpleMessage.newBuilder().setName("reflectable message").build());
          meta.put(
              NON_STUB_KEY, OtherMessage.newBuilder().setD1(1.11).setD2(1.11).build());
          meta.put(EXTERNAL_KEY, ExternalMessage.newBuilder().setD(1.11).build());
          super.sendHeaders(meta);
        }

        @Override
        public void close(Status status, Metadata meta) {
          meta.put(ASCII_KEY, "ascii header value");
          meta.put(
              STUB_KEY, SimpleMessage.newBuilder().setName("reflectable message").build());
          meta.put(
              NON_STUB_KEY, OtherMessage.newBuilder().setD1(1.11).setD2(1.11).build());
          meta.put(EXTERNAL_KEY, ExternalMessage.newBuilder().setD(1.11).build());
          super.close(status, meta);
        }
      }, requestHeaders);
    }
  }


  static final class ToolsTestServerImpl extends ToolsTestGrpc.ToolsTestImplBase {
    @Override
    public void echo(ComplexMessage request, StreamObserver<ComplexMessage> responseObserver) {
      responseObserver.onNext(request);
      responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<ComplexMessage> echoStream(
        StreamObserver<ComplexMessage> responseObserver) {
      return new StreamObserver<ComplexMessage>() {
        @Override
        public void onNext(ComplexMessage complexMessage) {
          responseObserver.onNext(complexMessage);
        }

        @Override
        public void onError(Throwable throwable) {
          responseObserver.onError(throwable);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public void getComplex(
        GetComplexMessageRequest request, StreamObserver<ComplexMessage> responseObserver) {
      ComplexMessage.Builder builder = ComplexMessage.newBuilder()
          .setDoubleValue(DoubleValue.newBuilder().setValue(1.2345).build())
          .setName("the response")
          .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));
      if (request.getKnownAny()) {
        builder.setPayload(
            Any.pack(SimpleMessage.newBuilder().setName("simple-message-name").build()));
      } else {
        builder.setPayload(
            Any.pack(
                ExternalMessage.newBuilder()
                    .setD(1.0)
                    .build()));
      }
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    }

    @Override
    public void failWithStatusProto(SimpleMessage request,
        StreamObserver<com.google.protobuf.Empty> responseObserver) {
      responseObserver.onError(
          StatusProto.toStatusException(
              com.google.rpc.Status.newBuilder()
                  .setMessage("this is a status-proto message")
                  .addDetails(
                      Any.pack(SimpleMessage.newBuilder().setName("simple-message-name").build()))
                  .build()));
    }


    @Override
    public void failWithStatus(io.grpc.toolstest.FailureTypeRequest request,
        StreamObserver<com.google.protobuf.Empty> responseObserver) {
      int code = request.getCode() == 0 ? 1 : request.getCode();
      throw new StatusRuntimeException(
          Status.fromCodeValue(code).withDescription(request.getMessage()));
    }
  }
}
