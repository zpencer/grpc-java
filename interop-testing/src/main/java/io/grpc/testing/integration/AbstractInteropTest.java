/*
 * Copyright 2014, gRPC Authors All rights reserved.
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

package io.grpc.testing.integration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.instrumentation.stats.ContextUtils.STATS_CONTEXT_KEY;
import static io.grpc.testing.integration.Messages.PayloadType.COMPRESSABLE;
import static io.opencensus.trace.unsafe.ContextUtils.CONTEXT_SPAN_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.SettableFuture;
import com.google.instrumentation.stats.RpcConstants;
import com.google.instrumentation.stats.StatsContextFactory;
import com.google.instrumentation.stats.TagKey;
import com.google.instrumentation.stats.TagValue;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.EmptyProtos.Empty;
import com.google.protobuf.MessageLite;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.AbstractServerImplBuilder;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.testing.StatsTestUtils.FakeStatsContext;
import io.grpc.internal.testing.StatsTestUtils.FakeStatsContextFactory;
import io.grpc.internal.testing.StatsTestUtils.MetricsRecord;
import io.grpc.internal.testing.StatsTestUtils.MockableSpan;
import io.grpc.internal.testing.StatsTestUtils;
import io.grpc.internal.testing.TestClientStreamTracer;
import io.grpc.internal.testing.TestServerStreamTracer;
import io.grpc.internal.testing.TestStreamTracer;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.StreamRecorder;
import io.grpc.testing.TestUtils;
import io.grpc.testing.integration.Messages.EchoStatus;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.PayloadType;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.testing.integration.Messages.StreamingInputCallRequest;
import io.grpc.testing.integration.Messages.StreamingInputCallResponse;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import io.opencensus.trace.Span;
import io.opencensus.trace.unsafe.ContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;

/**
 * Abstract base class for all GRPC transport tests.
 *
 * <p> New tests should avoid using Mockito to support running on AppEngine.</p>
 */
public abstract class AbstractInteropTest {

  @Rule public final Timeout globalTimeout = Timeout.seconds(30);

  /** Must be at least {@link #unaryPayloadLength()}, plus some to account for encoding overhead. */
  public static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;
  public static final Metadata.Key<Messages.SimpleContext> METADATA_KEY =
      ProtoUtils.keyForProto(Messages.SimpleContext.getDefaultInstance());
  private static final AtomicReference<ServerCall<?, ?>> serverCallCapture =
      new AtomicReference<ServerCall<?, ?>>();
  private static final AtomicReference<Metadata> requestHeadersCapture =
      new AtomicReference<Metadata>();
  private static final AtomicReference<Context> contextCapture =
      new AtomicReference<Context>();
  private static ScheduledExecutorService testServiceExecutor;
  private static Server server;
  private static final FakeStatsContextFactory clientStatsCtxFactory =
      new FakeStatsContextFactory();
  private static final FakeStatsContextFactory serverStatsCtxFactory =
      new FakeStatsContextFactory();

  private static final LinkedBlockingQueue<ServerStreamTracerInfo> serverStreamTracers =
      new LinkedBlockingQueue<ServerStreamTracerInfo>();

  private static final class ServerStreamTracerInfo {
    final String fullMethodName;
    final InteropServerStreamTracer tracer;

    ServerStreamTracerInfo(String fullMethodName, InteropServerStreamTracer tracer) {
      this.fullMethodName = fullMethodName;
      this.tracer = tracer;
    }

    private static final class InteropServerStreamTracer extends TestServerStreamTracer {
      private volatile Context contextCapture;

      @Override
      public <ReqT, RespT> Context filterContext(Context context) {
        contextCapture = context;
        return super.filterContext(context);
      }
    }
  }

  private static final ServerStreamTracer.Factory serverStreamTracerFactory =
      new ServerStreamTracer.Factory() {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
          ServerStreamTracerInfo.InteropServerStreamTracer tracer
              = new ServerStreamTracerInfo.InteropServerStreamTracer();
          serverStreamTracers.add(new ServerStreamTracerInfo(fullMethodName, tracer));
          return tracer;
        }
      };

  protected static final Empty EMPTY = Empty.getDefaultInstance();

  protected static void startStaticServer(
      AbstractServerImplBuilder<?> builder, ServerInterceptor ... interceptors) {
    testServiceExecutor = Executors.newScheduledThreadPool(2);

    List<ServerInterceptor> allInterceptors = ImmutableList.<ServerInterceptor>builder()
        .add(TestUtils.recordServerCallInterceptor(serverCallCapture))
        .add(TestUtils.recordRequestHeadersInterceptor(requestHeadersCapture))
        .add(recordContextInterceptor(contextCapture))
        .addAll(TestServiceImpl.interceptors())
        .add(interceptors)
        .build();

    builder
        .addService(
            ServerInterceptors.intercept(
                new TestServiceImpl(testServiceExecutor),
                allInterceptors))
        .addStreamTracerFactory(serverStreamTracerFactory);
    io.grpc.internal.TestingAccessor.setStatsContextFactory(builder, serverStatsCtxFactory);
    try {
      server = builder.build().start();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  protected static void stopStaticServer() {
    server.shutdownNow();
    testServiceExecutor.shutdown();
  }

  @VisibleForTesting
  static int getPort() {
    return server.getPort();
  }

  protected ManagedChannel channel;
  protected TestServiceGrpc.TestServiceBlockingStub blockingStub;
  protected TestServiceGrpc.TestServiceStub asyncStub;

  private final LinkedBlockingQueue<TestClientStreamTracer> clientStreamTracers =
      new LinkedBlockingQueue<TestClientStreamTracer>();

  private final ClientStreamTracer.Factory clientStreamTracerFactory =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(CallOptions callOptions, Metadata headers) {
          TestClientStreamTracer tracer = new TestClientStreamTracer();
          clientStreamTracers.add(tracer);
          return tracer;
        }
      };
  private final ClientInterceptor tracerSetupInterceptor = new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
          return next.newCall(
              method, callOptions.withStreamTracerFactory(clientStreamTracerFactory));
        }
      };

  /**
   * Must be called by the subclass setup method if overridden.
   */
  @Before
  public void setUp() {
    channel = createChannel();

    blockingStub =
        TestServiceGrpc.newBlockingStub(channel).withInterceptors(tracerSetupInterceptor);
    asyncStub = TestServiceGrpc.newStub(channel).withInterceptors(tracerSetupInterceptor);
    requestHeadersCapture.set(null);
    clientStatsCtxFactory.rolloverRecords();
    serverStatsCtxFactory.rolloverRecords();
    serverStreamTracers.clear();
  }

  /** Clean up. */
  @After
  public void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdown();
      channel.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  protected abstract ManagedChannel createChannel();

  protected final StatsContextFactory getClientStatsFactory() {
    return clientStatsCtxFactory;
  }

  /**
   * Return true if exact metric values should be checked.
   */
  protected boolean metricsExpected() {
    return true;
  }

  /**
   * Return true if the server is in the same process as the test methods.
   */
  protected boolean serverInProcess() {
    return true;
  }

  @Test
  public void emptyUnary() throws Exception {
    assertEquals(EMPTY, blockingStub.emptyCall(EMPTY));
  }

  /** Sends a cacheable unary rpc using GET. Requires that the server is behind a caching proxy. */
  public void cacheableUnary() {
    // Set safe to true.
    MethodDescriptor<SimpleRequest, SimpleResponse> safeCacheableUnaryCallMethod =
        TestServiceGrpc.METHOD_CACHEABLE_UNARY_CALL.toBuilder().setSafe(true).build();
    // Set fake user IP since some proxies (GFE) won't cache requests from localhost.
    Metadata.Key<String> userIpKey = Metadata.Key.of("x-user-ip", Metadata.ASCII_STRING_MARSHALLER);
    Metadata metadata = new Metadata();
    metadata.put(userIpKey, "1.2.3.4");
    Channel channelWithUserIpKey =
        ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
    SimpleRequest requests1And2 =
        SimpleRequest.newBuilder()
            .setPayload(
                Payload.newBuilder()
                    .setBody(ByteString.copyFromUtf8(String.valueOf(System.nanoTime()))))
            .build();
    SimpleRequest request3 =
        SimpleRequest.newBuilder()
            .setPayload(
                Payload.newBuilder()
                    .setBody(ByteString.copyFromUtf8(String.valueOf(System.nanoTime()))))
            .build();

    SimpleResponse response1 =
        ClientCalls.blockingUnaryCall(
            channelWithUserIpKey, safeCacheableUnaryCallMethod, CallOptions.DEFAULT, requests1And2);
    SimpleResponse response2 =
        ClientCalls.blockingUnaryCall(
            channelWithUserIpKey, safeCacheableUnaryCallMethod, CallOptions.DEFAULT, requests1And2);
    SimpleResponse response3 =
        ClientCalls.blockingUnaryCall(
            channelWithUserIpKey, safeCacheableUnaryCallMethod, CallOptions.DEFAULT, request3);

    assertEquals(response1, response2);
    assertNotEquals(response1, response3);
  }

  @Test
  public void largeUnary() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(314159)
        .setResponseType(PayloadType.COMPRESSABLE)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[314159])))
        .build();

    assertEquals(goldenResponse, blockingStub.unaryCall(request));

    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.OK,
        Collections.singleton(request), Collections.singleton(goldenResponse));
  }

  /**
   * Tests client per-message compression for unary calls. The Java API does not support inspecting
   * a message's compression level, so this is primarily intended to run against a gRPC C++ server.
   */
  public void clientCompressedUnary() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest expectCompressedRequest =
        SimpleRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(true))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleRequest expectUncompressedRequest =
        SimpleRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(false))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleResponse goldenResponse =
        SimpleResponse.newBuilder()
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[314159])))
            .build();

    // Send a non-compressed message with expectCompress=true. Servers supporting this test case
    // should return INVALID_ARGUMENT.
    try {
      blockingStub.unaryCall(expectCompressedRequest);
      fail("expected INVALID_ARGUMENT");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.INVALID_ARGUMENT.getCode(), e.getStatus().getCode());
    }
    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.INVALID_ARGUMENT);

    assertEquals(
        goldenResponse, blockingStub.withCompression("gzip").unaryCall(expectCompressedRequest));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(expectCompressedRequest),
        Collections.singleton(goldenResponse));

    assertEquals(goldenResponse, blockingStub.unaryCall(expectUncompressedRequest));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(expectUncompressedRequest),
        Collections.singleton(goldenResponse));
  }

  /**
   * Tests if the server can send a compressed unary response. Ideally we would assert that the
   * responses have the requested compression, but this is not supported by the API. Given a
   * compliant server, this test will exercise the code path for receiving a compressed response but
   * cannot itself verify that the response was compressed.
   */
  @Test
  public void serverCompressedUnary() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest responseShouldBeCompressed =
        SimpleRequest.newBuilder()
            .setResponseCompressed(BoolValue.newBuilder().setValue(true))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleRequest responseShouldBeUncompressed =
        SimpleRequest.newBuilder()
            .setResponseCompressed(BoolValue.newBuilder().setValue(false))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleResponse goldenResponse =
        SimpleResponse.newBuilder()
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[314159])))
            .build();

    assertEquals(goldenResponse, blockingStub.unaryCall(responseShouldBeCompressed));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(responseShouldBeCompressed),
        Collections.singleton(goldenResponse));

    assertEquals(goldenResponse, blockingStub.unaryCall(responseShouldBeUncompressed));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(responseShouldBeUncompressed),
        Collections.singleton(goldenResponse));
  }

  @Test
  public void serverStreaming() throws Exception {
    final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .setResponseType(PayloadType.COMPRESSABLE)
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(31415))
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(9))
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(2653))
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(58979))
        .build();
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[31415])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[9])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[2653])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[58979])))
            .build());

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub.streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertEquals(goldenResponses, recorder.getValues());
  }

  @Test
  public void clientStreaming() throws Exception {
    final List<StreamingInputCallRequest> requests = Arrays.asList(
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[27182])))
            .build(),
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[8])))
            .build(),
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[1828])))
            .build(),
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[45904])))
            .build());
    final StreamingInputCallResponse goldenResponse = StreamingInputCallResponse.newBuilder()
        .setAggregatedPayloadSize(74922)
        .build();

    StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingInputCallRequest> requestObserver =
        asyncStub.streamingInputCall(responseObserver);
    for (StreamingInputCallRequest request : requests) {
      requestObserver.onNext(request);
    }
    requestObserver.onCompleted();
    assertEquals(goldenResponse, responseObserver.firstValue().get());
    responseObserver.awaitCompletion();
  }

  /**
   * Tests client per-message compression for streaming calls. The Java API does not support
   * inspecting a message's compression level, so this is primarily intended to run against a gRPC
   * C++ server.
   */
  public void clientCompressedStreaming() throws Exception {
    final StreamingInputCallRequest expectCompressedRequest =
        StreamingInputCallRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(true))
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[27182])))
            .build();
    final StreamingInputCallRequest expectUncompressedRequest =
        StreamingInputCallRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(false))
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[45904])))
            .build();
    final StreamingInputCallResponse goldenResponse =
        StreamingInputCallResponse.newBuilder().setAggregatedPayloadSize(73086).build();

    StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingInputCallRequest> requestObserver =
        asyncStub.streamingInputCall(responseObserver);

    // Send a non-compressed message with expectCompress=true. Servers supporting this test case
    // should return INVALID_ARGUMENT.
    requestObserver.onNext(expectCompressedRequest);
    responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    Throwable e = responseObserver.getError();
    assertNotNull("expected INVALID_ARGUMENT", e);
    assertEquals(Status.INVALID_ARGUMENT.getCode(), Status.fromThrowable(e).getCode());

    // Start a new stream
    responseObserver = StreamRecorder.create();
    @SuppressWarnings("unchecked")
    ClientCallStreamObserver<StreamingInputCallRequest> clientCallStreamObserver =
        (ClientCallStreamObserver)
            asyncStub.withCompression("gzip").streamingInputCall(responseObserver);
    clientCallStreamObserver.setMessageCompression(true);
    clientCallStreamObserver.onNext(expectCompressedRequest);
    clientCallStreamObserver.setMessageCompression(false);
    clientCallStreamObserver.onNext(expectUncompressedRequest);
    clientCallStreamObserver.onCompleted();
    responseObserver.awaitCompletion();
    assertSuccess(responseObserver);
    assertEquals(goldenResponse, responseObserver.firstValue().get());
  }

  /**
   * Tests server per-message compression in a streaming response. Ideally we would assert that the
   * responses have the requested compression, but this is not supported by the API. Given a
   * compliant server, this test will exercise the code path for receiving a compressed response but
   * cannot itself verify that the response was compressed.
   */
  public void serverCompressedStreaming() throws Exception {
    final StreamingOutputCallRequest request =
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(
                ResponseParameters.newBuilder()
                    .setCompressed(BoolValue.newBuilder().setValue(true))
                    .setSize(31415))
            .addResponseParameters(
                ResponseParameters.newBuilder()
                    .setCompressed(BoolValue.newBuilder().setValue(false))
                    .setSize(92653))
            .build();
    final List<StreamingOutputCallResponse> goldenResponses =
        Arrays.asList(
            StreamingOutputCallResponse.newBuilder()
                .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[31415])))
                .build(),
            StreamingOutputCallResponse.newBuilder()
                .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[92653])))
                .build());

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub.streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertEquals(goldenResponses, recorder.getValues());
  }

  @Test
  public void pingPong() throws Exception {
    final List<StreamingOutputCallRequest> requests = Arrays.asList(
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(31415))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[27182])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(9))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[8])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(2653))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[1828])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(58979))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[45904])))
            .build());
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[31415])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[9])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[2653])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[58979])))
            .build());

    final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<Object>(5);
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
          @Override
          public void onNext(StreamingOutputCallResponse response) {
            queue.add(response);
          }

          @Override
          public void onError(Throwable t) {
            queue.add(t);
          }

          @Override
          public void onCompleted() {
            queue.add("Completed");
          }
        });
    for (int i = 0; i < requests.size(); i++) {
      assertNull(queue.peek());
      requestObserver.onNext(requests.get(i));
      assertEquals(goldenResponses.get(i),
                   queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    }
    requestObserver.onCompleted();
    assertEquals("Completed", queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
  }

  @Test
  public void emptyStream() throws Exception {
    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onCompleted();
    responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
  }

  @Test
  public void cancelAfterBegin() throws Exception {
    StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingInputCallRequest> requestObserver =
        asyncStub.streamingInputCall(responseObserver);
    requestObserver.onError(new RuntimeException());
    responseObserver.awaitCompletion();
    assertEquals(Arrays.<StreamingInputCallResponse>asList(), responseObserver.getValues());
    assertEquals(Status.Code.CANCELLED,
        Status.fromThrowable(responseObserver.getError()).getCode());

    if (metricsExpected()) {
      // CensusStreamTracerModule record final status in the interceptor, thus is guaranteed to be
      // recorded.  The tracer stats rely on the stream being created, which is not always the case
      // in this test.  Therefore we don't check the tracer stats.
      MetricsRecord clientRecord = clientStatsCtxFactory.pollRecord(5, TimeUnit.SECONDS);
      checkTags(
          clientRecord, false, "grpc.testing.TestService/StreamingInputCall",
          Status.CANCELLED.getCode());
      // Do not check server-side metrics, because the status on the server side is undetermined.
    }
  }

  @Test
  public void cancelAfterFirstResponse() throws Exception {
    final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(31415))
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[27182])))
        .build();
    final StreamingOutputCallResponse goldenResponse = StreamingOutputCallResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[31415])))
        .build();

    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onNext(request);
    assertEquals(goldenResponse, responseObserver.firstValue().get());
    requestObserver.onError(new RuntimeException());
    responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertEquals(1, responseObserver.getValues().size());
    assertEquals(Status.Code.CANCELLED,
                 Status.fromThrowable(responseObserver.getError()).getCode());

    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.CANCELLED);
  }

  @Test
  public void fullDuplexCallShouldSucceed() throws Exception {
    // Build the request.
    List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
    StreamingOutputCallRequest.Builder streamingOutputBuilder =
        StreamingOutputCallRequest.newBuilder();
    streamingOutputBuilder.setResponseType(COMPRESSABLE);
    for (Integer size : responseSizes) {
      streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
    }
    final StreamingOutputCallRequest request = streamingOutputBuilder.build();

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestStream =
        asyncStub.fullDuplexCall(recorder);

    final int numRequests = 10;
    List<StreamingOutputCallRequest> requests =
        new ArrayList<StreamingOutputCallRequest>(numRequests);
    for (int ix = numRequests; ix > 0; --ix) {
      requests.add(request);
      requestStream.onNext(request);
    }
    requestStream.onCompleted();
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertEquals(responseSizes.size() * numRequests, recorder.getValues().size());
    for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
      StreamingOutputCallResponse response = recorder.getValues().get(ix);
      assertEquals(COMPRESSABLE, response.getPayload().getType());
      int length = response.getPayload().getBody().size();
      int expectedSize = responseSizes.get(ix % responseSizes.size());
      assertEquals("comparison failed at index " + ix, expectedSize, length);
    }

    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.OK, requests,
        recorder.getValues());
  }

  @Test
  public void halfDuplexCallShouldSucceed() throws Exception {
    // Build the request.
    List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
    StreamingOutputCallRequest.Builder streamingOutputBuilder =
        StreamingOutputCallRequest.newBuilder();
    streamingOutputBuilder.setResponseType(COMPRESSABLE);
    for (Integer size : responseSizes) {
      streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
    }
    final StreamingOutputCallRequest request = streamingOutputBuilder.build();

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestStream = asyncStub.halfDuplexCall(recorder);

    final int numRequests = 10;
    List<StreamingOutputCallRequest> requests =
        new ArrayList<StreamingOutputCallRequest>(numRequests);
    for (int ix = numRequests; ix > 0; --ix) {
      requests.add(request);
      requestStream.onNext(request);
    }
    requestStream.onCompleted();
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertEquals(responseSizes.size() * numRequests, recorder.getValues().size());
    for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
      StreamingOutputCallResponse response = recorder.getValues().get(ix);
      assertEquals(COMPRESSABLE, response.getPayload().getType());
      int length = response.getPayload().getBody().size();
      int expectedSize = responseSizes.get(ix % responseSizes.size());
      assertEquals("comparison failed at index " + ix, expectedSize, length);
    }
  }

  @Test
  public void serverStreamingShouldBeFlowControlled() throws Exception {
    final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .setResponseType(COMPRESSABLE)
        .addResponseParameters(ResponseParameters.newBuilder().setSize(100000))
        .addResponseParameters(ResponseParameters.newBuilder().setSize(100001))
        .build();
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[100000]))).build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[100001]))).build());

    long start = System.nanoTime();

    final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<Object>(10);
    ClientCall<StreamingOutputCallRequest, StreamingOutputCallResponse> call =
        channel.newCall(TestServiceGrpc.METHOD_STREAMING_OUTPUT_CALL, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<StreamingOutputCallResponse>() {
      @Override
      public void onHeaders(Metadata headers) {}

      @Override
      public void onMessage(final StreamingOutputCallResponse message) {
        queue.add(message);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        queue.add(status);
      }
    }, new Metadata());
    call.sendMessage(request);
    call.halfClose();

    // Time how long it takes to get the first response.
    call.request(1);
    assertEquals(goldenResponses.get(0),
        queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    long firstCallDuration = System.nanoTime() - start;

    // Without giving additional flow control, make sure that we don't get another response. We wait
    // until we are comfortable the next message isn't coming. We may have very low nanoTime
    // resolution (like on Windows) or be using a testing, in-process transport where message
    // handling is instantaneous. In both cases, firstCallDuration may be 0, so round up sleep time
    // to at least 1ms.
    assertNull(queue.poll(Math.max(firstCallDuration * 4, 1 * 1000 * 1000), TimeUnit.NANOSECONDS));

    // Make sure that everything still completes.
    call.request(1);
    assertEquals(goldenResponses.get(1),
        queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    assertEquals(Status.OK, queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
  }

  @Test
  public void veryLargeRequest() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
        .setResponseSize(10)
        .setResponseType(PayloadType.COMPRESSABLE)
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[10])))
        .build();

    assertEquals(goldenResponse, blockingStub.unaryCall(request));
  }

  @Test
  public void veryLargeResponse() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(unaryPayloadLength())
        .setResponseType(PayloadType.COMPRESSABLE)
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
        .build();

    assertEquals(goldenResponse, blockingStub.unaryCall(request));
  }

  @Test
  public void exchangeMetadataUnaryCall() throws Exception {
    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub;

    // Capture the metadata exchange
    Metadata fixedHeaders = new Metadata();
    // Send a context proto (as it's in the default extension registry)
    Messages.SimpleContext contextValue =
        Messages.SimpleContext.newBuilder().setValue("dog").build();
    fixedHeaders.put(METADATA_KEY, contextValue);
    stub = MetadataUtils.attachHeaders(stub, fixedHeaders);
    // .. and expect it to be echoed back in trailers
    AtomicReference<Metadata> trailersCapture = new AtomicReference<Metadata>();
    AtomicReference<Metadata> headersCapture = new AtomicReference<Metadata>();
    stub = MetadataUtils.captureMetadata(stub, headersCapture, trailersCapture);

    assertNotNull(stub.emptyCall(EMPTY));

    // Assert that our side channel object is echoed back in both headers and trailers
    Assert.assertEquals(contextValue, headersCapture.get().get(METADATA_KEY));
    Assert.assertEquals(contextValue, trailersCapture.get().get(METADATA_KEY));
  }

  @Test
  public void exchangeMetadataStreamingCall() throws Exception {
    TestServiceGrpc.TestServiceStub stub = asyncStub;

    // Capture the metadata exchange
    Metadata fixedHeaders = new Metadata();
    // Send a context proto (as it's in the default extension registry)
    Messages.SimpleContext contextValue =
        Messages.SimpleContext.newBuilder().setValue("dog").build();
    fixedHeaders.put(METADATA_KEY, contextValue);
    stub = MetadataUtils.attachHeaders(stub, fixedHeaders);
    // .. and expect it to be echoed back in trailers
    AtomicReference<Metadata> trailersCapture = new AtomicReference<Metadata>();
    AtomicReference<Metadata> headersCapture = new AtomicReference<Metadata>();
    stub = MetadataUtils.captureMetadata(stub, headersCapture, trailersCapture);

    List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
    Messages.StreamingOutputCallRequest.Builder streamingOutputBuilder =
        Messages.StreamingOutputCallRequest.newBuilder();
    streamingOutputBuilder.setResponseType(COMPRESSABLE);
    for (Integer size : responseSizes) {
      streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
    }
    final Messages.StreamingOutputCallRequest request = streamingOutputBuilder.build();

    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<Messages.StreamingOutputCallRequest> requestStream =
        stub.fullDuplexCall(recorder);

    final int numRequests = 10;
    List<StreamingOutputCallRequest> requests =
        new ArrayList<StreamingOutputCallRequest>(numRequests);

    for (int ix = numRequests; ix > 0; --ix) {
      requests.add(request);
      requestStream.onNext(request);
    }
    requestStream.onCompleted();
    recorder.awaitCompletion();
    assertSuccess(recorder);
    org.junit.Assert.assertEquals(responseSizes.size() * numRequests, recorder.getValues().size());

    // Assert that our side channel object is echoed back in both headers and trailers
    Assert.assertEquals(contextValue, headersCapture.get().get(METADATA_KEY));
    Assert.assertEquals(contextValue, trailersCapture.get().get(METADATA_KEY));
  }

  @Test
  public void sendsTimeoutHeader() {
    Assume.assumeTrue("can not capture request headers on server side", server != null);
    long configuredTimeoutMinutes = 100;
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(configuredTimeoutMinutes, TimeUnit.MINUTES);
    stub.emptyCall(EMPTY);
    long transferredTimeoutMinutes = TimeUnit.NANOSECONDS.toMinutes(
        requestHeadersCapture.get().get(GrpcUtil.TIMEOUT_KEY));
    Assert.assertTrue(
        "configuredTimeoutMinutes=" + configuredTimeoutMinutes
            + ", transferredTimeoutMinutes=" + transferredTimeoutMinutes,
        configuredTimeoutMinutes - transferredTimeoutMinutes >= 0
            && configuredTimeoutMinutes - transferredTimeoutMinutes <= 1);
  }

  @Test
  public void deadlineNotExceeded() {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());
    blockingStub
        .withDeadlineAfter(10, TimeUnit.SECONDS)
        .streamingOutputCall(StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setIntervalUs(0))
                .build()).next();
  }

  @Test
  public void deadlineExceeded() throws Exception {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(10, TimeUnit.MILLISECONDS);
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder()
            .setIntervalUs((int) TimeUnit.SECONDS.toMicros(20)))
        .build();
    try {
      stub.streamingOutputCall(request).next();
      fail("Expected deadline to be exceeded");
    } catch (StatusRuntimeException ex) {
      assertEquals(Status.DEADLINE_EXCEEDED.getCode(), ex.getStatus().getCode());
    }

    assertStatsTrace("grpc.testing.TestService/EmptyCall", Status.Code.OK);
    if (metricsExpected()) {
      // Stream may not have been created before deadline is exceeded, thus we don't test the tracer
      // stats.
      MetricsRecord clientRecord = clientStatsCtxFactory.pollRecord(5, TimeUnit.SECONDS);
      checkTags(
          clientRecord, false, "grpc.testing.TestService/StreamingOutputCall",
          Status.Code.DEADLINE_EXCEEDED);
      // Do not check server-side metrics, because the status on the server side is undetermined.
    }
  }

  @Test
  public void deadlineExceededServerStreaming() throws Exception {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());
    ResponseParameters.Builder responseParameters = ResponseParameters.newBuilder()
        .setSize(1)
        .setIntervalUs(10000);
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .setResponseType(PayloadType.COMPRESSABLE)
        .addResponseParameters(responseParameters)
        .addResponseParameters(responseParameters)
        .addResponseParameters(responseParameters)
        .addResponseParameters(responseParameters)
        .build();
    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub
        .withDeadlineAfter(30, TimeUnit.MILLISECONDS)
        .streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertEquals(Status.DEADLINE_EXCEEDED.getCode(),
        Status.fromThrowable(recorder.getError()).getCode());
    assertStatsTrace("grpc.testing.TestService/EmptyCall", Status.Code.OK);
    if (metricsExpected()) {
      // Stream may not have been created when deadline is exceeded, thus we don't check tracer
      // stats.
      MetricsRecord clientRecord = clientStatsCtxFactory.pollRecord(5, TimeUnit.SECONDS);
      checkTags(
          clientRecord, false, "grpc.testing.TestService/StreamingOutputCall",
          Status.Code.DEADLINE_EXCEEDED);
      // Do not check server-side metrics, because the status on the server side is undetermined.
    }
  }

  @Test
  public void deadlineInPast() throws Exception {
    // Test once with idle channel and once with active channel
    try {
      blockingStub
          .withDeadlineAfter(-10, TimeUnit.SECONDS)
          .emptyCall(Empty.getDefaultInstance());
      fail("Should have thrown");
    } catch (StatusRuntimeException ex) {
      assertEquals(Status.Code.DEADLINE_EXCEEDED, ex.getStatus().getCode());
    }

    // CensusStreamTracerModule record final status in the interceptor, thus is guaranteed to be
    // recorded.  The tracer stats rely on the stream being created, which is not the case if
    // deadline is exceeded before the call is created. Therefore we don't check the tracer stats.
    if (metricsExpected()) {
      MetricsRecord clientRecord = clientStatsCtxFactory.pollRecord(5, TimeUnit.SECONDS);
      checkTags(
          clientRecord, false, "grpc.testing.TestService/EmptyCall",
          Status.DEADLINE_EXCEEDED.getCode());
    }

    // warm up the channel
    blockingStub.emptyCall(Empty.getDefaultInstance());
    try {
      blockingStub
          .withDeadlineAfter(-10, TimeUnit.SECONDS)
          .emptyCall(Empty.getDefaultInstance());
      fail("Should have thrown");
    } catch (StatusRuntimeException ex) {
      assertEquals(Status.Code.DEADLINE_EXCEEDED, ex.getStatus().getCode());
    }
    assertStatsTrace("grpc.testing.TestService/EmptyCall", Status.Code.OK);
    if (metricsExpected()) {
      MetricsRecord clientRecord = clientStatsCtxFactory.pollRecord(5, TimeUnit.SECONDS);
      checkTags(
          clientRecord, false, "grpc.testing.TestService/EmptyCall",
          Status.DEADLINE_EXCEEDED.getCode());
    }
  }

  @Test
  public void maxInboundSize_exact() {
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();
    int size = blockingStub.streamingOutputCall(request).next().getSerializedSize();

    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxInboundMessageSize(size);

    stub.streamingOutputCall(request).next();
  }

  @Test
  public void maxInboundSize_tooBig() {
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();
    int size = blockingStub.streamingOutputCall(request).next().getSerializedSize();

    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxInboundMessageSize(size - 1);

    try {
      stub.streamingOutputCall(request).next();
      fail();
    } catch (StatusRuntimeException ex) {
      Status s = ex.getStatus();
      assertThat(s.getCode()).named(s.toString()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(Throwables.getStackTraceAsString(ex)).contains("exceeds maximum");
    }
  }

  @Test
  public void maxOutboundSize_exact() {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());

    // set at least one field to ensure the size is non-zero.
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxOutboundMessageSize(request.getSerializedSize());

    stub.streamingOutputCall(request).next();
  }

  @Test
  public void maxOutboundSize_tooBig() {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());
    // set at least one field to ensure the size is non-zero.
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxOutboundMessageSize(request.getSerializedSize() - 1);
    try {
      stub.streamingOutputCall(request).next();
      fail();
    } catch (StatusRuntimeException ex) {
      Status s = ex.getStatus();
      assertThat(s.getCode()).named(s.toString()).isEqualTo(Status.Code.CANCELLED);
      assertThat(Throwables.getStackTraceAsString(ex)).contains("message too large");
    }
  }

  protected int unaryPayloadLength() {
    // 10MiB.
    return 10485760;
  }

  @Test
  public void gracefulShutdown() throws Exception {
    final List<StreamingOutputCallRequest> requests = Arrays.asList(
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(3))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[2])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(1))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[7])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(4))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[1])))
            .build());
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[3])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[1])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setType(PayloadType.COMPRESSABLE)
                .setBody(ByteString.copyFrom(new byte[4])))
            .build());

    final ArrayBlockingQueue<StreamingOutputCallResponse> responses =
        new ArrayBlockingQueue<StreamingOutputCallResponse>(3);
    final SettableFuture<Void> completed = SettableFuture.create();
    final SettableFuture<Void> errorSeen = SettableFuture.create();
    StreamObserver<StreamingOutputCallResponse> responseObserver =
        new StreamObserver<StreamingOutputCallResponse>() {

          @Override
          public void onNext(StreamingOutputCallResponse value) {
            responses.add(value);
          }

          @Override
          public void onError(Throwable t) {
            errorSeen.set(null);
          }

          @Override
          public void onCompleted() {
            completed.set(null);
          }
        };
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onNext(requests.get(0));
    assertEquals(
        goldenResponses.get(0), responses.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    // Initiate graceful shutdown.
    channel.shutdown();
    requestObserver.onNext(requests.get(1));
    assertEquals(
        goldenResponses.get(1), responses.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    // The previous ping-pong could have raced with the shutdown, but this one certainly shouldn't.
    requestObserver.onNext(requests.get(2));
    assertEquals(
        goldenResponses.get(2), responses.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    assertFalse(completed.isDone());
    requestObserver.onCompleted();
    completed.get(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertFalse(errorSeen.isDone());
  }

  @Test
  public void customMetadata() throws Exception {
    final int responseSize = 314159;
    final int requestSize = 271828;
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(responseSize)
        .setResponseType(PayloadType.COMPRESSABLE)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[requestSize])))
        .build();
    final StreamingOutputCallRequest streamingRequest = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(responseSize))
        .setResponseType(PayloadType.COMPRESSABLE)
        .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[requestSize])))
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[responseSize])))
        .build();
    final StreamingOutputCallResponse goldenStreamingResponse =
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[responseSize])))
        .build();
    final byte[] trailingBytes =
        {(byte) 0xa, (byte) 0xb, (byte) 0xa, (byte) 0xb, (byte) 0xa, (byte) 0xb};

    // Test UnaryCall
    Metadata metadata = new Metadata();
    metadata.put(Util.ECHO_INITIAL_METADATA_KEY, "test_initial_metadata_value");
    metadata.put(Util.ECHO_TRAILING_METADATA_KEY, trailingBytes);
    TestServiceGrpc.TestServiceBlockingStub blockingStub = this.blockingStub;
    blockingStub = MetadataUtils.attachHeaders(blockingStub, metadata);
    AtomicReference<Metadata> headersCapture = new AtomicReference<Metadata>();
    AtomicReference<Metadata> trailersCapture = new AtomicReference<Metadata>();
    blockingStub = MetadataUtils.captureMetadata(blockingStub, headersCapture, trailersCapture);
    SimpleResponse response = blockingStub.unaryCall(request);

    assertEquals(goldenResponse, response);
    assertEquals("test_initial_metadata_value",
        headersCapture.get().get(Util.ECHO_INITIAL_METADATA_KEY));
    assertTrue(
        Arrays.equals(trailingBytes, trailersCapture.get().get(Util.ECHO_TRAILING_METADATA_KEY)));
    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.OK,
        Collections.singleton(request), Collections.singleton(goldenResponse));

    // Test FullDuplexCall
    metadata = new Metadata();
    metadata.put(Util.ECHO_INITIAL_METADATA_KEY, "test_initial_metadata_value");
    metadata.put(Util.ECHO_TRAILING_METADATA_KEY, trailingBytes);
    TestServiceGrpc.TestServiceStub stub = asyncStub;
    stub = MetadataUtils.attachHeaders(stub, metadata);
    headersCapture = new AtomicReference<Metadata>();
    trailersCapture = new AtomicReference<Metadata>();
    stub = MetadataUtils.captureMetadata(stub, headersCapture, trailersCapture);

    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<Messages.StreamingOutputCallRequest> requestStream =
        stub.fullDuplexCall(recorder);
    requestStream.onNext(streamingRequest);
    requestStream.onCompleted();
    recorder.awaitCompletion();

    assertSuccess(recorder);
    assertEquals(goldenStreamingResponse, recorder.firstValue().get());
    assertEquals("test_initial_metadata_value",
        headersCapture.get().get(Util.ECHO_INITIAL_METADATA_KEY));
    assertTrue(
        Arrays.equals(trailingBytes, trailersCapture.get().get(Util.ECHO_TRAILING_METADATA_KEY)));
    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.OK,
        Collections.singleton(streamingRequest), Collections.singleton(goldenStreamingResponse));
  }

  @Test(timeout = 10000)
  public void censusContextsPropagated() {
    if (!serverInProcess()) {
      return;
    }
    Span clientParentSpan = MockableSpan.generateRandomSpan(new Random());
    Context ctx =
        Context.ROOT.withValues(
            STATS_CONTEXT_KEY,
            clientStatsCtxFactory.getDefault().with(
                StatsTestUtils.EXTRA_TAG, TagValue.create("extra value")),
            ContextUtils.CONTEXT_SPAN_KEY,
            clientParentSpan);
    Context origCtx = ctx.attach();
    try {
      blockingStub.unaryCall(SimpleRequest.getDefaultInstance());
      Context serverCtx = contextCapture.get();
      assertNotNull(serverCtx);

      FakeStatsContext statsCtx = (FakeStatsContext) STATS_CONTEXT_KEY.get(serverCtx);
      assertNotNull(statsCtx);
      Map<TagKey, TagValue> tags = statsCtx.getTags();
      boolean tagFound = false;
      for (Map.Entry<TagKey, TagValue> tag : tags.entrySet()) {
        if (tag.getKey().equals(StatsTestUtils.EXTRA_TAG)) {
          assertEquals(TagValue.create("extra value"), tag.getValue());
          tagFound = true;
        }
      }
      assertTrue("tag not found", tagFound);

      Span span = CONTEXT_SPAN_KEY.get(serverCtx);
      assertNotNull(span);
      assertEquals(clientParentSpan.getContext().getTraceId(), span.getContext().getTraceId());
    } finally {
      ctx.detach(origCtx);
    }
  }

  @Test
  public void statusCodeAndMessage() throws Exception {
    int errorCode = 2;
    String errorMessage = "test status message";
    EchoStatus responseStatus = EchoStatus.newBuilder()
        .setCode(errorCode)
        .setMessage(errorMessage)
        .build();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder()
        .setResponseStatus(responseStatus)
        .build();
    StreamingOutputCallRequest streamingRequest = StreamingOutputCallRequest.newBuilder()
        .setResponseStatus(responseStatus)
        .build();

    // Test UnaryCall
    try {
      blockingStub.unaryCall(simpleRequest);
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.UNKNOWN.getCode(), e.getStatus().getCode());
      assertEquals(errorMessage, e.getStatus().getDescription());
    }
    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.UNKNOWN);

    // Test FullDuplexCall
    @SuppressWarnings("unchecked")
    StreamObserver<StreamingOutputCallResponse> responseObserver =
        mock(StreamObserver.class);
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onNext(streamingRequest);
    requestObserver.onCompleted();

    ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver, timeout(operationTimeoutMillis())).onError(captor.capture());
    assertEquals(Status.UNKNOWN.getCode(), Status.fromThrowable(captor.getValue()).getCode());
    assertEquals(errorMessage, Status.fromThrowable(captor.getValue()).getDescription());
    verifyNoMoreInteractions(responseObserver);
    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.UNKNOWN);
  }

  /** Sends an rpc to an unimplemented method within TestService. */
  @Test
  public void unimplementedMethod() {
    try {
      blockingStub.unimplementedCall(Empty.getDefaultInstance());
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.UNIMPLEMENTED.getCode(), e.getStatus().getCode());
    }

    assertClientStatsTrace("grpc.testing.TestService/UnimplementedCall",
        Status.Code.UNIMPLEMENTED);
  }

  /** Sends an rpc to an unimplemented service on the server. */
  @Test
  public void unimplementedService() {
    UnimplementedServiceGrpc.UnimplementedServiceBlockingStub stub =
        UnimplementedServiceGrpc.newBlockingStub(channel).withInterceptors(tracerSetupInterceptor);
    try {
      stub.unimplementedCall(Empty.getDefaultInstance());
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.UNIMPLEMENTED.getCode(), e.getStatus().getCode());
    }

    assertStatsTrace("grpc.testing.UnimplementedService/UnimplementedCall",
        Status.Code.UNIMPLEMENTED);
  }

  /** Start a fullDuplexCall which the server will not respond, and verify the deadline expires. */
  @SuppressWarnings("MissingFail")
  @Test
  public void timeoutOnSleepingServer() throws Exception {
    TestServiceGrpc.TestServiceStub stub =
        asyncStub.withDeadlineAfter(1, TimeUnit.MILLISECONDS);

    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = stub.fullDuplexCall(responseObserver);

    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[27182])))
        .build();
    try {
      requestObserver.onNext(request);
    } catch (IllegalStateException expected) {
      // This can happen if the stream has already been terminated due to deadline exceeded.
    }

    responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertEquals(0, responseObserver.getValues().size());
    assertEquals(Status.DEADLINE_EXCEEDED.getCode(),
                 Status.fromThrowable(responseObserver.getError()).getCode());

    if (metricsExpected()) {
      // CensusStreamTracerModule record final status in the interceptor, thus is guaranteed to be
      // recorded.  The tracer stats rely on the stream being created, which is not always the case
      // in this test, thus we will not check that.
      MetricsRecord clientRecord = clientStatsCtxFactory.pollRecord(5, TimeUnit.SECONDS);
      checkTags(
          clientRecord, false, "grpc.testing.TestService/FullDuplexCall",
          Status.DEADLINE_EXCEEDED.getCode());
    }
  }

  /** Sends a large unary rpc with service account credentials. */
  public void serviceAccountCreds(String jsonKey, InputStream credentialsStream, String authScope)
      throws Exception {
    // cast to ServiceAccountCredentials to double-check the right type of object was created.
    GoogleCredentials credentials =
        ServiceAccountCredentials.class.cast(GoogleCredentials.fromStream(credentialsStream));
    credentials = credentials.createScoped(Arrays.<String>asList(authScope));
    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
        .withCallCredentials(MoreCallCredentials.from(credentials));
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setFillUsername(true)
        .setFillOauthScope(true)
        .setResponseSize(314159)
        .setResponseType(PayloadType.COMPRESSABLE)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();

    final SimpleResponse response = stub.unaryCall(request);
    assertFalse(response.getUsername().isEmpty());
    assertTrue("Received username: " + response.getUsername(),
        jsonKey.contains(response.getUsername()));
    assertFalse(response.getOauthScope().isEmpty());
    assertTrue("Received oauth scope: " + response.getOauthScope(),
        authScope.contains(response.getOauthScope()));

    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setOauthScope(response.getOauthScope())
        .setUsername(response.getUsername())
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[314159])))
        .build();
    assertEquals(goldenResponse, response);
  }

  /** Sends a large unary rpc with compute engine credentials. */
  public void computeEngineCreds(String serviceAccount, String oauthScope) throws Exception {
    ComputeEngineCredentials credentials = new ComputeEngineCredentials();
    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
        .withCallCredentials(MoreCallCredentials.from(credentials));
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setFillUsername(true)
        .setFillOauthScope(true)
        .setResponseSize(314159)
        .setResponseType(PayloadType.COMPRESSABLE)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();

    final SimpleResponse response = stub.unaryCall(request);
    assertEquals(serviceAccount, response.getUsername());
    assertFalse(response.getOauthScope().isEmpty());
    assertTrue("Received oauth scope: " + response.getOauthScope(),
        oauthScope.contains(response.getOauthScope()));

    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setOauthScope(response.getOauthScope())
        .setUsername(response.getUsername())
        .setPayload(Payload.newBuilder()
            .setType(PayloadType.COMPRESSABLE)
            .setBody(ByteString.copyFrom(new byte[314159])))
        .build();
    assertEquals(goldenResponse, response);
  }

  /** Test JWT-based auth. */
  public void jwtTokenCreds(InputStream serviceAccountJson) throws Exception {
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseType(PayloadType.COMPRESSABLE)
        .setResponseSize(314159)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .setFillUsername(true)
        .build();

    ServiceAccountCredentials credentials = (ServiceAccountCredentials)
        GoogleCredentials.fromStream(serviceAccountJson);
    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
        .withCallCredentials(MoreCallCredentials.from(credentials));
    SimpleResponse response = stub.unaryCall(request);
    assertEquals(credentials.getClientEmail(), response.getUsername());
    assertEquals(314159, response.getPayload().getBody().size());
  }

  /** Sends a unary rpc with raw oauth2 access token credentials. */
  public void oauth2AuthToken(String jsonKey, InputStream credentialsStream, String authScope)
      throws Exception {
    GoogleCredentials utilCredentials =
        GoogleCredentials.fromStream(credentialsStream);
    utilCredentials = utilCredentials.createScoped(Arrays.<String>asList(authScope));
    AccessToken accessToken = utilCredentials.refreshAccessToken();

    // TODO(madongfly): The Auth library may have something like AccessTokenCredentials in the
    // future, change to the official implementation then.
    OAuth2Credentials credentials = new OAuth2Credentials(accessToken) {
      private static final long serialVersionUID = 0;

      @Override
      public AccessToken refreshAccessToken() throws IOException {
        throw new IOException("This credential is based on a certain AccessToken, "
            + "so you can not refresh AccessToken");
      }
    };

    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
        .withCallCredentials(MoreCallCredentials.from(credentials));
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setFillUsername(true)
        .setFillOauthScope(true)
        .build();

    final SimpleResponse response = stub.unaryCall(request);
    assertFalse(response.getUsername().isEmpty());
    assertTrue("Received username: " + response.getUsername(),
        jsonKey.contains(response.getUsername()));
    assertFalse(response.getOauthScope().isEmpty());
    assertTrue("Received oauth scope: " + response.getOauthScope(),
        authScope.contains(response.getOauthScope()));
  }

  /** Sends a unary rpc with "per rpc" raw oauth2 access token credentials. */
  public void perRpcCreds(String jsonKey, InputStream credentialsStream, String oauthScope)
      throws Exception {
    // In gRpc Java, we don't have per Rpc credentials, user can use an intercepted stub only once
    // for that purpose.
    // So, this test is identical to oauth2_auth_token test.
    oauth2AuthToken(jsonKey, credentialsStream, oauthScope);
  }

  protected static void assertSuccess(StreamRecorder<?> recorder) {
    if (recorder.getError() != null) {
      throw new AssertionError(recorder.getError());
    }
  }

  /** Helper for asserting remote address {@link io.grpc.ServerCall#getAttributes()} */
  protected void assertRemoteAddr(String expectedRemoteAddress) {
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(5, TimeUnit.SECONDS);

    stub.unaryCall(SimpleRequest.getDefaultInstance());

    String inetSocketString = serverCallCapture.get().getAttributes()
        .get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString();
    // The substring is simply host:port, even if host is IPv6 as it fails to use []. Can't use
    // standard parsing because the string isn't following any standard.
    String host = inetSocketString.substring(0, inetSocketString.lastIndexOf(':'));
    assertEquals(expectedRemoteAddress, host);
  }

  /** Helper for asserting TLS info in SSLSession {@link io.grpc.ServerCall#getAttributes()} */
  protected void assertX500SubjectDn(String tlsInfo) {
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(5, TimeUnit.SECONDS);

    stub.unaryCall(SimpleRequest.getDefaultInstance());

    List<Certificate> certificates = Lists.newArrayList();
    SSLSession sslSession =
        serverCallCapture.get().getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    try {
      certificates = Arrays.asList(sslSession.getPeerCertificates());
    } catch (SSLPeerUnverifiedException e) {
      fail("No cert");
    }

    X509Certificate x509cert = (X509Certificate) certificates.get(0);

    assertEquals(1, certificates.size());
    assertEquals(tlsInfo, x509cert.getSubjectDN().toString());
  }

  protected int operationTimeoutMillis() {
    return 5000;
  }

  /**
   * Some tests run on memory constrained environments.  Rather than OOM, just give up.  64 is
   * choosen as a maximum amount of memory a large test would need.
   */
  private static void assumeEnoughMemory() {
    Runtime r = Runtime.getRuntime();
    long usedMem = r.totalMemory() - r.freeMemory();
    long actuallyFreeMemory = r.maxMemory() - usedMem;
    Assume.assumeTrue(
        actuallyFreeMemory + " is not sufficient to run this test",
        actuallyFreeMemory >= 64 * 1024 * 1024);
  }

  /**
   * Wrapper around {@link Mockito#verify}, to keep log spam down on failure.
   */
  private static <T> T verify(T mock, VerificationMode mode) {
    try {
      return Mockito.verify(mock, mode);
    } catch (AssertionError e) {
      String msg = e.getMessage();
      if (msg.length() >= 256) {
        throw new AssertionError(msg.substring(0, 256), e);
      }
      throw e;
    }
  }

  /**
   * Wrapper around {@link Mockito#verify}, to keep log spam down on failure.
   */
  private static void verifyNoMoreInteractions(Object... mocks) {
    try {
      Mockito.verifyNoMoreInteractions(mocks);
    } catch (AssertionError e) {
      String msg = e.getMessage();
      if (msg.length() >= 256) {
        throw new AssertionError(msg.substring(0, 256), e);
      }
      throw e;
    }
  }

  /**
   * Poll the next metrics record and check it against the provided information, including the
   * message sizes.
   */
  private void assertStatsTrace(String method, Status.Code status,
      Collection<? extends MessageLite> requests,
      Collection<? extends MessageLite> responses) {
    assertClientStatsTrace(method, status, requests, responses);
    assertServerStatsTrace(method, status, requests, responses);
  }

  /**
   * Poll the next metrics record and check it against the provided information, without checking
   * the message sizes.
   */
  private void assertStatsTrace(String method, Status.Code status) {
    assertStatsTrace(method, status, null, null);
  }

  private void assertClientStatsTrace(String method, Status.Code code,
      Collection<? extends MessageLite> requests, Collection<? extends MessageLite> responses) {
    // Tracer-based stats
    TestClientStreamTracer tracer = clientStreamTracers.poll();
    assertNotNull(tracer);
    assertTrue(tracer.getOutboundHeaders());
    // assertClientStatsTrace() is called right after application receives status,
    // but streamClosed() may be called slightly later than that.  So we need a timeout.
    try {
      assertTrue(tracer.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    assertEquals(code, tracer.getStatus().getCode());

    if (requests != null && responses != null) {
      checkTracers(tracer, requests, responses);
    }
    if (metricsExpected()) {
      // CensusStreamTracerModule records final status in interceptor, which is guaranteed to be
      // done before application receives status.
      MetricsRecord clientRecord = clientStatsCtxFactory.pollRecord();
      checkTags(clientRecord, false, method, code);

      if (requests != null && responses != null) {
        checkCensus(clientRecord, false, requests, responses);
      }
    }
  }

  private void assertClientStatsTrace(String method, Status.Code status) {
    assertClientStatsTrace(method, status, null, null);
  }

  @SuppressWarnings("AssertionFailureIgnored") // Failure is checked in the end by the passed flag.
  private void assertServerStatsTrace(String method, Status.Code code,
      Collection<? extends MessageLite> requests, Collection<? extends MessageLite> responses) {
    if (!serverInProcess()) {
      return;
    }
    AssertionError checkFailure = null;
    boolean passed = false;

    if (metricsExpected()) {
      // Because the server doesn't restart between tests, it may still be processing the requests
      // from the previous tests when a new test starts, thus the test may see metrics from previous
      // tests.  The best we can do here is to exhaust all records and find one that matches the
      // given conditions.
      while (true) {
        MetricsRecord serverRecord;
        try {
          // On the server, the stats is finalized in ServerStreamListener.closed(), which can be
          // run after the client receives the final status.  So we use a timeout.
          serverRecord = serverStatsCtxFactory.pollRecord(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        if (serverRecord == null) {
          break;
        }
        try {
          checkTags(serverRecord, true, method, code);
          if (requests != null && responses != null) {
            checkCensus(serverRecord, true, requests, responses);
          }
          passed = true;
          break;
        } catch (AssertionError e) {
          // May be the fallout from a previous test, continue trying
          checkFailure = e;
        }
      }
      if (!passed) {
        if (checkFailure == null) {
          throw new AssertionError("No record found");
        }
        throw checkFailure;
      }
    }

    // Use the same trick to check ServerStreamTracer records
    passed = false;
    while (true) {
      ServerStreamTracerInfo tracerInfo;
      tracerInfo = serverStreamTracers.poll();
      if (tracerInfo == null) {
        break;
      }
      try {
        assertEquals(method, tracerInfo.fullMethodName);
        assertNotNull(tracerInfo.tracer.contextCapture);
        // On the server, streamClosed() may be called after the client receives the final status.
        // So we use a timeout.
        try {
          assertTrue(tracerInfo.tracer.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
        assertEquals(code, tracerInfo.tracer.getStatus().getCode());
        if (requests != null && responses != null) {
          checkTracers(tracerInfo.tracer, responses, requests);
        }
        passed = true;
        break;
      } catch (AssertionError e) {
        // May be the fallout from a previous test, continue trying
        checkFailure = e;
      }
    }
    if (!passed) {
      if (checkFailure == null) {
        throw new AssertionError("No ServerStreamTracer found");
      }
      throw checkFailure;
    }
  }

  private static void checkTags(
      MetricsRecord record, boolean server, String methodName, Status.Code status) {
    assertNotNull("record is not null", record);
    TagValue methodNameTag = record.tags.get(
        server ? RpcConstants.RPC_SERVER_METHOD : RpcConstants.RPC_CLIENT_METHOD);
    assertNotNull("method name tagged", methodNameTag);
    assertEquals("method names match", methodName, methodNameTag.toString());
    TagValue statusTag = record.tags.get(RpcConstants.RPC_STATUS);
    assertNotNull("status tagged", statusTag);
    assertEquals(status.toString(), statusTag.toString());
  }

  /**
   * Check information recorded by tracers.
   */
  private void checkTracers(
      TestStreamTracer tracer,
      Collection<? extends MessageLite> sentMessages,
      Collection<? extends MessageLite> receivedMessages) {
    long uncompressedSentSize = 0;
    int seqNo = 0;
    for (MessageLite msg : sentMessages) {
      assertThat(tracer.nextOutboundEvent()).isEqualTo(String.format("outboundMessage(%d)", seqNo));
      assertThat(tracer.nextOutboundEvent()).isEqualTo("outboundMessage()");
      assertThat(tracer.nextOutboundEvent()).matches(
          String.format("outboundMessageSent\\(%d, -?[0-9]+, -?[0-9]+\\)", seqNo));
      seqNo++;
      uncompressedSentSize += msg.getSerializedSize();
    }
    assertNull(tracer.nextOutboundEvent());
    long uncompressedReceivedSize = 0;
    seqNo = 0;
    for (MessageLite msg : receivedMessages) {
      assertThat(tracer.nextInboundEvent()).isEqualTo(String.format("inboundMessage(%d)", seqNo));
      assertThat(tracer.nextInboundEvent()).isEqualTo("inboundMessage()");
      assertThat(tracer.nextInboundEvent()).matches(
          String.format("inboundMessageRead\\(%d, -?[0-9]+, -?[0-9]+\\)", seqNo)); 
      uncompressedReceivedSize += msg.getSerializedSize();
      seqNo++;
    }
    assertNull(tracer.nextInboundEvent());
    if (metricsExpected()) {
      assertEquals(uncompressedSentSize, tracer.getOutboundUncompressedSize());
      assertEquals(uncompressedReceivedSize, tracer.getInboundUncompressedSize());
    }
  }

  /**
   * Check information recorded by Census.
   */
  private void checkCensus(MetricsRecord record, boolean server,
      Collection<? extends MessageLite> requests, Collection<? extends MessageLite> responses) {
    int uncompressedRequestsSize = 0;
    for (MessageLite request : requests) {
      uncompressedRequestsSize += request.getSerializedSize();
    }
    int uncompressedResponsesSize = 0;
    for (MessageLite response : responses) {
      uncompressedResponsesSize += response.getSerializedSize();
    }
    if (server && serverInProcess()) {
      assertEquals(
          requests.size(), record.getMetricAsLongOrFail(RpcConstants.RPC_SERVER_REQUEST_COUNT));
      assertEquals(
          responses.size(), record.getMetricAsLongOrFail(RpcConstants.RPC_SERVER_RESPONSE_COUNT));
      assertEquals(uncompressedRequestsSize,
          record.getMetricAsLongOrFail(RpcConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES));
      assertEquals(uncompressedResponsesSize,
          record.getMetricAsLongOrFail(RpcConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES));
      assertNotNull(record.getMetric(RpcConstants.RPC_SERVER_SERVER_LATENCY));
      // It's impossible to get the expected wire sizes because it may be compressed, so we just
      // check if they are recorded.
      assertNotNull(record.getMetric(RpcConstants.RPC_SERVER_REQUEST_BYTES));
      assertNotNull(record.getMetric(RpcConstants.RPC_SERVER_RESPONSE_BYTES));
    }
    if (!server) {
      assertEquals(
          requests.size(), record.getMetricAsLongOrFail(RpcConstants.RPC_CLIENT_REQUEST_COUNT));
      assertEquals(
          responses.size(), record.getMetricAsLongOrFail(RpcConstants.RPC_CLIENT_RESPONSE_COUNT));
      assertEquals(uncompressedRequestsSize,
          record.getMetricAsLongOrFail(RpcConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
      assertEquals(uncompressedResponsesSize,
          record.getMetricAsLongOrFail(RpcConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
      assertNotNull(record.getMetric(RpcConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));
      // It's impossible to get the expected wire sizes because it may be compressed, so we just
      // check if they are recorded.
      assertNotNull(record.getMetric(RpcConstants.RPC_CLIENT_REQUEST_BYTES));
      assertNotNull(record.getMetric(RpcConstants.RPC_CLIENT_RESPONSE_BYTES));
    }
  }

  private static ServerInterceptor recordContextInterceptor(
      final AtomicReference<Context> contextCapture) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call,
          Metadata requestHeaders,
          ServerCallHandler<ReqT, RespT> next) {
        contextCapture.set(Context.current());
        return next.startCall(call, requestHeaders);
      }
    };
  }
}
