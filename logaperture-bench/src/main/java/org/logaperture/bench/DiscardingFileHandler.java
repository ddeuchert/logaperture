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
package org.logaperture.bench;

import org.jboss.logmanager.handlers.OutputStreamHandler;

import java.io.File;
import java.io.OutputStream;
import java.util.logging.Formatter;

/**
 * A JBoss LogManager handler that formats every record for real and then
 * throws the bytes away — doc/specs/overhead-benchmarks.md Decision #3:
 * formatting is counted, disk I/O is not.
 *
 * <p>It reports a {@link #getFile() file} so the JUL adapter classifies it
 * as persistent, the same as WildFly's {@code FILE} handler. That's what makes
 * {@code top}'s byte counting wrap it; a handler without one would silently
 * skip the {@code top} layer and measure less than production does.
 */
public final class DiscardingFileHandler extends OutputStreamHandler {

    private final CountingOutputStream sink;

    public DiscardingFileHandler(Formatter formatter) {
        this(formatter, new CountingOutputStream());
    }

    private DiscardingFileHandler(Formatter formatter, CountingOutputStream sink) {
        super(sink, formatter);
        this.sink = sink;
    }

    /** Never opened — only read reflectively by the adapter's persistent-handler check. */
    public File getFile() {
        return new File("/dev/null");
    }

    /** Bytes written since construction, so a benchmark can assert it measured real output. */
    public long bytesWritten() {
        return sink.count;
    }

    private static final class CountingOutputStream extends OutputStream {

        /** Written under the handler's own lock, which already serializes every publish. */
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
