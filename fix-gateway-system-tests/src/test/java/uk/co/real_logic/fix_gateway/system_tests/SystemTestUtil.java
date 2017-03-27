/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.system_tests;

import org.agrona.IoUtil;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.hamcrest.Matcher;
import uk.co.real_logic.fix_gateway.CommonConfiguration;
import uk.co.real_logic.fix_gateway.Reply;
import uk.co.real_logic.fix_gateway.builder.TestRequestEncoder;
import uk.co.real_logic.fix_gateway.decoder.Constants;
import uk.co.real_logic.fix_gateway.engine.EngineConfiguration;
import uk.co.real_logic.fix_gateway.engine.FixEngine;
import uk.co.real_logic.fix_gateway.engine.LowResourceEngineScheduler;
import uk.co.real_logic.fix_gateway.engine.framer.LibraryInfo;
import uk.co.real_logic.fix_gateway.library.FixLibrary;
import uk.co.real_logic.fix_gateway.library.LibraryConfiguration;
import uk.co.real_logic.fix_gateway.library.SessionConfiguration;
import uk.co.real_logic.fix_gateway.messages.SessionReplyStatus;
import uk.co.real_logic.fix_gateway.session.Session;
import uk.co.real_logic.fix_gateway.validation.AuthenticationStrategy;
import uk.co.real_logic.fix_gateway.validation.MessageValidationStrategy;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.*;
import static uk.co.real_logic.fix_gateway.CommonConfiguration.DEFAULT_REPLY_TIMEOUT_IN_MS;
import static uk.co.real_logic.fix_gateway.CommonConfiguration.optimalTmpDirName;
import static uk.co.real_logic.fix_gateway.FixMatchers.isConnected;
import static uk.co.real_logic.fix_gateway.Reply.State.COMPLETED;
import static uk.co.real_logic.fix_gateway.Timing.DEFAULT_TIMEOUT_IN_MS;
import static uk.co.real_logic.fix_gateway.Timing.assertEventuallyTrue;
import static uk.co.real_logic.fix_gateway.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.fix_gateway.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.fix_gateway.messages.SessionState.ACTIVE;

public final class SystemTestUtil
{
    public static final IdleStrategy ADMIN_IDLE_STRATEGY = new YieldingIdleStrategy();
    public static final String ACCEPTOR_ID = "acceptor";
    public static final String INITIATOR_ID = "initiator";
    public static final String INITIATOR_ID2 = "initiator2";
    public static final String CLIENT_LOGS = "client-logs";
    public static final String ACCEPTOR_LOGS = "acceptor-logs";
    public static final long TIMEOUT_IN_MS = 100;
    public static final long AWAIT_TIMEOUT = 50 * TIMEOUT_IN_MS;
    public static final String HI_ID = "hi";
    public static final int LIBRARY_LIMIT = 2;
    public static final String USERNAME = "bob";
    public static final String PASSWORD = "Uv1aegoh";

    public static final int SESSION_BUFFER_SIZE_IN_BYTES = 15000;

    static
    {
        final File parentDirectory = new File(optimalTmpDirName());
        for (final File directory : parentDirectory.listFiles((file) -> file.getName().startsWith("fix-library-")))
        {
            IoUtil.delete(directory, true);
        }
    }

    private static final AtomicLong TEST_REQ_COUNTER = new AtomicLong();

    static String testReqId()
    {
        return HI_ID + TEST_REQ_COUNTER.incrementAndGet();
    }

    public static long assertTestRequestSentAndReceived(
        final Session sendingSession,
        final TestSystem testSystem,
        final FakeOtfAcceptor receivingHandler)
    {
        final String testReqID = testReqId();
        final long position = sendTestRequest(sendingSession, testReqID);

        assertReceivedTestRequest(testSystem, receivingHandler, testReqID);

        return position;
    }

    public static long sendTestRequest(final Session session, final String testReqID)
    {
        assertEventuallyTrue("Session not connected", session::isConnected);

        final TestRequestEncoder testRequest = new TestRequestEncoder();
        testRequest.testReqID(testReqID);

        final long position = session.send(testRequest);
        assertThat(position, greaterThan(0L));
        return position;
    }

    public static void assertReceivedTestRequest(
        final TestSystem testSystem, final FakeOtfAcceptor acceptor, final String testReqId)
    {
        assertEventuallyTrue("Failed to receive a test request message",
            () ->
            {
                testSystem.poll();
                return acceptor
                    .hasReceivedMessage("1")
                    .filter(msg -> testReqId.equals(msg.getTestReqId()))
                    .count() > 0;
            });
    }

    public static void poll(final FixLibrary library1, final FixLibrary library2)
    {
        library1.poll(LIBRARY_LIMIT);
        if (library2 != null)
        {
            library2.poll(LIBRARY_LIMIT);
        }
    }

    public static void assertConnected(final FixLibrary library1, final FixLibrary library2)
    {
        assertThat(library1, isConnected());
        if (library2 != null)
        {
            assertThat(library2, isConnected());
        }
    }

    public static Reply<Session> initiate(
        final FixLibrary library,
        final int port,
        final String initiatorId,
        final String acceptorId)
    {
        final SessionConfiguration config = SessionConfiguration.builder()
            .address("localhost", port)
            .credentials(USERNAME, PASSWORD)
            .senderCompId(initiatorId)
            .targetCompId(acceptorId)
            .build();

        return library.initiate(config);
    }

    public static void awaitLibraryReply(final FixLibrary library, final Reply<?> reply)
    {
        awaitLibraryReply(library, null, reply);
    }

    static void awaitLibraryReply(final FixLibrary library, final FixLibrary library2, final Reply<?> reply)
    {
        assertEventuallyTrue(
            "No reply from: " + reply,
            () ->
            {
                poll(library, library2);

                return !reply.isExecuting();
            });
    }

    static void awaitLibraryReply(final TestSystem testSystem, final Reply<?> reply)
    {
        assertEventuallyTrue(
            "No reply from: " + reply,
            () ->
            {
                testSystem.poll();

                return !reply.isExecuting();
            });
    }

    public static SessionReplyStatus releaseToGateway(final FixLibrary library, final Session session)
    {
        final Reply<SessionReplyStatus> reply = library.releaseToGateway(session, DEFAULT_REPLY_TIMEOUT_IN_MS);
        awaitLibraryReply(library, reply);

        return reply.resultIfPresent();
    }

    public static FixEngine launchInitiatingEngine(final int libraryAeronPort)
    {
        delete(CLIENT_LOGS);
        return launchInitiatingEngineWithSameLogs(libraryAeronPort);
    }

    public static FixEngine launchInitiatingEngineWithSameLogs(final int libraryAeronPort)
    {
        final EngineConfiguration initiatingConfig = initiatingConfig(libraryAeronPort, "engineCounters");
        return FixEngine.launch(initiatingConfig);
    }

    public static EngineConfiguration initiatingConfig(final int libraryAeronPort, final String countersSuffix)
    {
        final EngineConfiguration configuration = new EngineConfiguration()
            .libraryAeronChannel("aeron:udp?endpoint=localhost:" + libraryAeronPort)
            .monitoringFile(optimalTmpDirName() + File.separator + "fix-client" + File.separator + countersSuffix)
            .logFileDir(CLIENT_LOGS)
            .scheduler(new LowResourceEngineScheduler());
        configuration.agentNamePrefix("init-");
        return configuration;
    }

    public static void delete(final String dirPath)
    {
        final File dir = new File(dirPath);
        if (dir.exists())
        {
            IoUtil.delete(dir, false);
        }
    }

    public static EngineConfiguration acceptingConfig(
        final int port,
        final String countersSuffix,
        final String acceptorId,
        final String initiatorId)
    {
        return acceptingConfig(port, countersSuffix, acceptorId, initiatorId, ACCEPTOR_LOGS);
    }

    public static EngineConfiguration acceptingConfig(
        final int port,
        final String countersSuffix,
        final String acceptorId,
        final String initiatorId,
        final String acceptorLogs)
    {
        final EngineConfiguration configuration = new EngineConfiguration();
        setupCommonConfig(acceptorId, initiatorId, configuration);

        return configuration
            .bindTo("localhost", port)
            .libraryAeronChannel("aeron:ipc")
            .monitoringFile(acceptorMonitoringFile(countersSuffix))
            .logFileDir(acceptorLogs)
            .scheduler(new LowResourceEngineScheduler());
    }

    public static String acceptorMonitoringFile(final String countersSuffix)
    {
        return optimalTmpDirName() + File.separator + "fix-acceptor" + File.separator + countersSuffix;
    }

    public static LibraryConfiguration acceptingLibraryConfig(
        final FakeHandler sessionHandler)
    {
        final LibraryConfiguration libraryConfiguration = new LibraryConfiguration();
        setupCommonConfig(ACCEPTOR_ID, INITIATOR_ID, libraryConfiguration);

        libraryConfiguration
            .sessionExistsHandler(sessionHandler)
            .sessionAcquireHandler(sessionHandler)
            .sentPositionHandler(sessionHandler)
            .libraryAeronChannels(singletonList(IPC_CHANNEL));

        return libraryConfiguration;
    }

    public static void setupCommonConfig(
        final String acceptorId, final String initiatorId, final CommonConfiguration configuration)
    {
        final MessageValidationStrategy validationStrategy = MessageValidationStrategy.targetCompId(acceptorId)
            .and(MessageValidationStrategy.senderCompId(Arrays.asList(initiatorId, INITIATOR_ID2)));

        final AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.of(validationStrategy);

        configuration
            .authenticationStrategy(authenticationStrategy)
            .messageValidationStrategy(validationStrategy)
            .sessionBufferSize(SESSION_BUFFER_SIZE_IN_BYTES);
    }

    public static Session acquireSession(final FakeHandler sessionHandler, final FixLibrary library)
    {
        final long sessionId = sessionHandler.awaitSessionId(() -> library.poll(LIBRARY_LIMIT));

        return acquireSession(sessionHandler, library, sessionId);
    }

    public static Session acquireSession(
        final FakeHandler sessionHandler, final FixLibrary library, final long sessionId)
    {
        return acquireSession(sessionHandler, library, sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY);
    }

    public static Session acquireSession(
        final FakeHandler sessionHandler,
        final FixLibrary library,
        final long sessionId,
        final int lastReceivedMsgSeqNum,
        final int sequenceIndex)
    {
        final SessionReplyStatus reply = requestSession(library, sessionId, lastReceivedMsgSeqNum, sequenceIndex);
        assertEquals(SessionReplyStatus.OK, reply);
        final Session session = sessionHandler.lastSession();
        sessionHandler.resetSession();
        return session;
    }

    public static SessionReplyStatus requestSession(
        final FixLibrary library,
        final long sessionId,
        final int lastReceivedMsgSeqNum,
        final int sequenceIndex)
    {
        final Reply<SessionReplyStatus> reply = library.requestSession(
            sessionId, lastReceivedMsgSeqNum, sequenceIndex, DEFAULT_REPLY_TIMEOUT_IN_MS);
        awaitLibraryReply(library, reply);
        assertEquals(COMPLETED, reply.state());

        return reply.resultIfPresent();
    }

    static void sessionLogsOn(
        final TestSystem testSystem,
        final Session session,
        final long timeoutInMs)
    {
        assertEventuallyTrue("Session has failed to logon",
            () ->
            {
                testSystem.poll();
                testSystem.assertConnected();

                assertEquals(ACTIVE, session.state());
            },
            timeoutInMs);
    }

    public static FixLibrary newInitiatingLibrary(final int libraryAeronPort, final FakeHandler sessionHandler)
    {
        return connect(initiatingLibraryConfig(libraryAeronPort, sessionHandler));
    }

    public static LibraryConfiguration initiatingLibraryConfig(
        final int libraryAeronPort, final FakeHandler sessionHandler)
    {
        return new LibraryConfiguration()
                .sessionAcquireHandler(sessionHandler)
                .sentPositionHandler(sessionHandler)
                .sessionExistsHandler(sessionHandler)
                .libraryAeronChannels(singletonList("aeron:udp?endpoint=localhost:" + libraryAeronPort));
    }

    public static FixLibrary connect(final LibraryConfiguration configuration)
    {
        final FixLibrary library = FixLibrary.connect(configuration);
        assertEventuallyTrue(
            () -> "Unable to connect to engine",
            () ->
            {
                library.poll(LIBRARY_LIMIT);

                return library.isConnected();
            },
            DEFAULT_TIMEOUT_IN_MS,
            library::close);

        return library;
    }

    public static FixLibrary newAcceptingLibrary(final FakeHandler sessionHandler)
    {
        return connect(acceptingLibraryConfig(sessionHandler));
    }

    public static void assertConnected(final Session session)
    {
        assertNotNull("Session is null", session);
        assertTrue("Session has failed to connect", session.isConnected());
    }

    public static List<LibraryInfo> libraries(final FixEngine engine)
    {
        final Reply<List<LibraryInfo>> reply = engine.libraries();
        assertEventuallyTrue(
            "No reply from: " + reply,
            () -> !reply.isExecuting());

        assertEquals(COMPLETED, reply.state());

        return reply.resultIfPresent();
    }

    public static Optional<LibraryInfo> libraryInfoById(final List<LibraryInfo> libraries, final int libraryId)
    {
        return libraries
            .stream()
            .filter(libraryInfo -> libraryInfo.libraryId() == libraryId)
            .findFirst();
    }

    public static LibraryInfo engineLibrary(final List<LibraryInfo> libraries)
    {
        return libraryInfoById(libraries, ENGINE_LIBRARY_ID).get(); // Error if not present
    }

    public static void awaitLibraryConnect(final FixLibrary library)
    {
        assertEventuallyTrue(
            () -> "Library hasn't seen Engine",
            () ->
            {
                library.poll(5);
                return library.isConnected();
            },
            AWAIT_TIMEOUT,
            () ->
            {
            }
        );
    }

    static void assertReceivedSingleHeartbeat(
        final TestSystem testSystem, final FakeOtfAcceptor acceptor, final String testReqId)
    {
        assertEventuallyTrue("Failed to received heartbeat",
            () ->
            {
                testSystem.poll();

                return acceptor
                    .hasReceivedMessage("0")
                    .filter((message) -> testReqId.equals(message.get(Constants.TEST_REQ_ID)))
                    .count() > 0;
            });
    }

    public static LibraryInfo gatewayLibraryInfo(final FixEngine engine)
    {
        return libraries(engine)
            .stream()
            .filter((libraryInfo) -> libraryInfo.libraryId() == ENGINE_LIBRARY_ID)
            .findAny()
            .orElseThrow(IllegalStateException::new);
    }

    @SafeVarargs
    public static void assertEventuallyHasLibraries(
        final TestSystem testSystem,
        final FixEngine engine,
        final Matcher<LibraryInfo>... libraryMatchers)
    {
        assertEventuallyTrue("Could not find libraries: " + Arrays.toString(libraryMatchers),
            () ->
            {
                testSystem.poll();
                final List<LibraryInfo> libraries = libraries(engine);
                assertThat(libraries, containsInAnyOrder(libraryMatchers));
            });
    }
}
