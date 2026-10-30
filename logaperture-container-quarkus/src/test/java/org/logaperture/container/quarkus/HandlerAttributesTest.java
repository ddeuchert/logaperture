/*
 * Copyright 2026 David Deuchert
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
package org.logaperture.container.quarkus;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerAttributesTest {

    /** A syslog handler as JBoss LogManager's: the server it sends to, and the port. */
    public static class Syslog extends Handler {
        private final InetAddress server;
        private final int port;

        Syslog(InetAddress server, int port) {
            this.server = server;
            this.port = port;
        }

        public InetAddress getServerAddress() {
            return server;
        }

        public int getPort() {
            return port;
        }

        @Override public void publish(LogRecord record) { }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static HandlerAttributes.Result syslog(String endpoint, Handler handler) {
        return HandlerAttributes.check("S", "syslog", Map.of("endpoint", endpoint), handler);
    }

    @Test
    void anEndpointMatchesByHostNameAndPort() throws Exception {
        Syslog handler = new Syslog(InetAddress.getByAddress("logs.example", new byte[] {10, 0, 0, 1}), 514);

        assertTrue(syslog("logs.example:514", handler).confirmed());
        assertNotNull(syslog("logs.example:515", handler).mismatch());
        assertNotNull(syslog("other.example:514", handler).mismatch());
    }

    @Test
    void anAddressLiteralIsComparedByItsBytesWithoutALookup() throws Exception {
        Syslog v6 = new Syslog(InetAddress.getByName("0:0:0:0:0:0:0:1"), 514);
        Syslog v4 = new Syslog(InetAddress.getByName("127.0.0.1"), 514);

        assertTrue(syslog("[::1]:514", v6).confirmed());
        assertTrue(syslog("127.0.0.1:514", v4).confirmed());
        assertNotNull(syslog("127.0.0.2:514", v4).mismatch());
    }

    @Test
    void aHostThatCannotBeToldIsNotHeldAgainstThePair() throws Exception {
        Syslog fromLiteral = new Syslog(InetAddress.getByName("10.0.0.1"), 514); // no name to compare with

        HandlerAttributes.Result result = syslog("logs.example:514", fromLiteral);

        assertTrue(result.confirmed());
        assertNull(result.mismatch());
    }

    @Test
    void nothingConfiguredOrNothingReadableIsTypeOnly() {
        Syslog noAccessors = new Syslog(null, 514);

        assertFalse(HandlerAttributes.check("S", "syslog", Map.of(), noAccessors).confirmed());
        assertNull(syslog("not-an-endpoint", noAccessors).mismatch());
    }
}
