/**
 * Copyright Pravega Authors.
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
package io.pravega.test.integration;

import io.pravega.client.ClientConfig;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.admin.impl.ReaderGroupManagerImpl;
import io.pravega.client.admin.impl.StreamManagerImpl;
import io.pravega.client.connection.impl.ConnectionPoolImpl;
import io.pravega.client.connection.impl.SocketConnectionFactoryImpl;
import io.pravega.client.segment.impl.NoSuchEventException;
import io.pravega.client.segment.impl.SegmentSealedException;
import io.pravega.client.stream.*;
import io.pravega.client.stream.impl.*;
import io.pravega.client.stream.mock.*;
import io.pravega.segmentstore.contracts.StreamSegmentStore;
import io.pravega.segmentstore.contracts.tables.TableStore;
import io.pravega.segmentstore.server.host.handler.PravegaConnectionListener;
import io.pravega.segmentstore.server.store.ServiceBuilder;
import io.pravega.segmentstore.server.store.ServiceBuilderConfig;
import io.pravega.segmentstore.server.store.ServiceConfig;
import io.pravega.segmentstore.server.writer.WriterConfig;
import io.pravega.test.common.AssertExtensions;
import io.pravega.test.common.TestUtils;
import io.pravega.test.integration.utils.SetupUtils;
import lombok.Cleanup;
import lombok.val;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * This runs a basic end to end test with a single thread in the thread pool to make sure we don't
 * block anything on it.
 */
public class SingleThreadEndToEndTest {
    private static final ServiceBuilder SERVICE_BUILDER = ServiceBuilder.newInMemoryBuilder(ServiceBuilderConfig.builder()
            .include(ServiceConfig.builder().with(ServiceConfig.CONTAINER_COUNT, 1))
            .include(WriterConfig.builder().with(WriterConfig.MAX_ROLLOVER_SIZE, 10485760L))
            .build());
    @BeforeClass
    public static void setup() throws Exception {
        SERVICE_BUILDER.initialize();
    }

    @Test(timeout = 60000)
    public void testReadWrite() throws Exception {
        @Cleanup("stopAllServices")
        SetupUtils setupUtils = new SetupUtils();
        setupUtils.startAllServices(1);
        setupUtils.createTestStream("stream", 1);
        @Cleanup
        EventStreamWriter<Integer> writer = setupUtils.getIntegerWriter("stream");
        writer.writeEvent(1);
        writer.flush();
        @Cleanup
        val rgm = setupUtils.createReaderGroupManager("stream");
        @Cleanup
        EventStreamReader<Integer> reader = setupUtils.getIntegerReader("stream", rgm);
        EventRead<Integer> event = reader.readNextEvent(10000);
        Assert.assertEquals(1, (int) event.getEvent());
    }
    
    @Test(timeout = 60000)
    public void testSealedStream() throws Exception {
        @Cleanup("stopAllServices")
        SetupUtils setupUtils = new SetupUtils();
        setupUtils.startAllServices(1);
        @Cleanup
        StreamManager streamManager = StreamManager.create(setupUtils.getClientConfig());
        streamManager.createScope("scope");
        streamManager.createStream("scope",
                                   "stream",
                                   StreamConfiguration.builder().scalingPolicy(ScalingPolicy.fixed(1)).build());

        @Cleanup
        EventStreamWriter<byte[]> writer = setupUtils.getClientFactory()
            .createEventWriter("stream",
                               new ByteArraySerializer(),
                               EventWriterConfig.builder().retryAttempts(2).enableLargeEvents(true).build());
        writer.writeEvent(new byte[Serializer.MAX_EVENT_SIZE + 1]).join();
        writer.flush();
        assertTrue(streamManager.sealStream("scope", "stream"));
        AssertExtensions.assertThrows(SegmentSealedException.class,
                                      () -> writer.writeEvent(new byte[Serializer.MAX_EVENT_SIZE + 1]).join());
        AssertExtensions.assertThrows(IllegalStateException.class, () -> writer.writeEvent(new byte[1]).join());
        writer.flush();
    }

    @Test(timeout = 10000)
    public void testFetchEvent() throws ReinitializationRequiredException {
        String endpoint = "localhost";
        String streamName = "testEventPointer";
        String readerName = "reader";
        String readerGroup = "testEventPointer-group";
        int port = TestUtils.getAvailableListenPort();
        String testString = "fetchEvent-teststring ";
        String scope = "Scope1";
        StreamSegmentStore store = SERVICE_BUILDER.createStreamSegmentService();
        TableStore tableStore = SERVICE_BUILDER.createTableStoreService();
        @Cleanup
        PravegaConnectionListener server = new PravegaConnectionListener(false, port, store, tableStore,SERVICE_BUILDER.getLowPriorityExecutor() );
        server.startListening();

        @Cleanup
        SocketConnectionFactoryImpl clientCF = new SocketConnectionFactoryImpl(ClientConfig.builder().build());
        @Cleanup
        ConnectionPoolImpl pool = new ConnectionPoolImpl(ClientConfig.builder().build(), clientCF);
        MockController controller = new MockController(endpoint, port, pool, true);

        @Cleanup
        StreamManager streamManager = new StreamManagerImpl(controller, pool);
        @Cleanup
        ClientFactoryImpl clientFactory = new ClientFactoryImpl(scope, controller, ClientConfig.builder().build());
        ReaderGroupManager readerGroupManager = new ReaderGroupManagerImpl(scope, controller, clientFactory);

        streamManager.createScope(scope);
        streamManager.createStream(scope, streamName, StreamConfiguration.builder()
                .scalingPolicy(ScalingPolicy.fixed(1))
                .build());
        readerGroupManager.createReaderGroup(readerGroup,ReaderGroupConfig
                .builder()
                .stream(Stream.of(scope, streamName))
                .automaticCheckpointIntervalMillis(2000)
                .build());
        UTF8StringSerializer serializer = new UTF8StringSerializer();

        @Cleanup
        EventStreamWriter<String> producer = clientFactory.createEventWriter(streamName, serializer, EventWriterConfig.builder().build());

        int writeCount = 0 ;
        for(int eventNumber = 1 ; eventNumber <= 100 ; eventNumber++ ) {
            producer.writeEvent(testString);
            writeCount++;
        }
        producer.flush();
        @Cleanup
        EventStreamReader<String> reader = clientFactory.createReader(readerName, readerGroup, serializer, ReaderConfig.builder().build());
        EventPointer pointer = reader.readNextEvent(100).getEventPointer();
        AtomicInteger counter = new AtomicInteger(0);
        while(pointer != null) {
            counter.incrementAndGet();
            CompletableFuture<String> cf = streamManager.fetchEvent(pointer, serializer);
            cf.thenAccept(s -> {
                assertEquals(s, testString);
            });
            pointer = reader.readNextEvent(10).getEventPointer();
        }
        assertEquals(writeCount , counter.get());
    }
}
