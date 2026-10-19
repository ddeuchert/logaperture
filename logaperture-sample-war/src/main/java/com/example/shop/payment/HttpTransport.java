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
 * The bottom of the {@link ProviderClient}: makes no connection, and refuses one when the
 * request says the endpoint is unreachable, so the demo's trace never depends on the network.
 */
final class HttpTransport {

    void post(ProviderClient.Request request) throws ConnectException {
        send(request);
    }

    private void send(ProviderClient.Request request) throws ConnectException {
        connect(request);
    }

    private void connect(ProviderClient.Request request) throws ConnectException {
        if (request.unreachable()) {
            throw new ConnectException("Connection refused");
        }
    }
}
