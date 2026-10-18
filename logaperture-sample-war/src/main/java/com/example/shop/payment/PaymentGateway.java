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
package com.example.shop.payment;

import java.net.ConnectException;

/**
 * The demo shop's payment provider call, for the "stack traces are burying the log" story
 * (doc/specs/demo-recordings.md, story 4), called by the shop's {@code CheckoutService}. It
 * authorises nothing: it exists so that a refused connection is thrown through real nested
 * calls -- this gateway, a {@link ProviderClient} and its interceptor chain, an
 * {@link HttpTransport} -- and the trace looks like an application's, the same in every
 * recording.
 *
 * <p>Lives in {@code com.example.shop}, not the project's {@code org.logaperture} package root,
 * because the recording shows these frames: the sample WAR is a test fixture that never ships.
 */
public final class PaymentGateway {

    private final ProviderClient client = new ProviderClient(new HttpTransport());

    /**
     * Sends the authorisation; with {@code unreachable}, the provider refuses the connection.
     *
     * @throws PaymentException wrapping the transport's {@link ConnectException}
     */
    public void authorise(int order, String amount, boolean unreachable) throws PaymentException {
        try {
            client.authorise(order, amount, unreachable);
        } catch (ConnectException e) {
            throw new PaymentException("Authorisation failed for order " + order, e);
        }
    }
}
