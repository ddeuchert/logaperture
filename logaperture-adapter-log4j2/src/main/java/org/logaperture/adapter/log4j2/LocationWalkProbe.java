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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.impl.MutableLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether handing {@link SourcelessEvent} to {@code Log4jLogEvent.Builder} pays on the Log4j this adapter is
 * bound to (issue #333). It pays only where the plain copy walks the stack, and it is only safe where the view
 * route does not cost something worse. Both depend on the Log4j version, and the second was found the hard way:
 * on 2.20 the copy of a reusable event reads a field and never walks, while the view takes the builder's
 * generic path, which builds a {@code ThrowableProxy} of the full, untrimmed throwable first (~14 µs, a
 * regression). So it is asked of the running Log4j once, with two probes, rather than decided by a version
 * number:
 * <ol>
 *   <li>copying a reusable event with a caller location to ask for, does the builder ask for it?</li>
 *   <li>copying a view, does the builder leave the thrown proxy alone?</li>
 * </ol>
 * Anything unexpected answers no, which keeps the plain copy as before.
 */
final class LocationWalkProbe {

    private static volatile Boolean viewPays;

    private LocationWalkProbe() {
    }

    /** True where the view saves a stack walk and costs nothing else; asked once per adapter class loader. */
    static boolean viewPays() {
        Boolean known = viewPays;
        if (known == null) {
            known = probe();
            viewPays = known;
        }
        return known;
    }

    static boolean probe() {
        try {
            AtomicInteger walks = new AtomicInteger();
            MutableLogEvent reusable = new MutableLogEvent() {
                @Override
                public StackTraceElement getSource() {
                    walks.incrementAndGet();
                    return super.getSource();
                }
            };
            reusable.setLoggerName("logaperture.probe");
            reusable.setLoggerFqcn("logaperture.probe.Fqcn");
            reusable.setLevel(Level.ERROR);
            reusable.setMessage(new SimpleMessage("probe"));
            reusable.setIncludeLocation(true);
            new Log4jLogEvent.Builder(reusable).build();
            if (walks.get() == 0) {
                return false; // this Log4j's copy does not ask: nothing to save
            }
            AtomicInteger proxies = new AtomicInteger();
            Log4jLogEvent plain = new Log4jLogEvent.Builder().setLoggerName("logaperture.probe")
                    .setLevel(Level.ERROR).setMessage(new SimpleMessage("probe")).build();
            SourcelessEvent view = new SourcelessEvent(plain) {
                @Override
                public org.apache.logging.log4j.core.impl.ThrowableProxy getThrownProxy() {
                    proxies.incrementAndGet();
                    return super.getThrownProxy();
                }
            };
            new Log4jLogEvent.Builder(view).build();
            return proxies.get() == 0;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
