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
import java.util.List;

/**
 * The payment provider's client: a request passes through an interceptor chain, as in common
 * HTTP clients, then the {@link HttpTransport}. The interceptors only pass the request on;
 * they are there for the depth they give the {@link PaymentGateway}'s trace. Classes, not
 * lambdas, so each frame has a readable name.
 */
final class ProviderClient {

    private final List<Interceptor> interceptors = List.of(
            new TracingInterceptor(), new MetricsInterceptor(), new IdempotencyInterceptor(),
            new AuthInterceptor());
    private final HttpTransport transport;

    ProviderClient(HttpTransport transport) {
        this.transport = transport;
    }

    void authorise(int order, String amount, boolean unreachable) throws ConnectException {
        execute(new Request("/v2/auth", "order=" + order + " amount=" + amount, unreachable));
    }

    private void execute(Request request) throws ConnectException {
        new Chain(0).proceed(request);
    }

    record Request(String path, String body, boolean unreachable) {
    }

    interface Interceptor {
        void intercept(Request request, Chain chain) throws ConnectException;
    }

    final class Chain {

        private final int index;

        Chain(int index) {
            this.index = index;
        }

        void proceed(Request request) throws ConnectException {
            if (index == interceptors.size()) {
                transport.post(request);
            } else {
                interceptors.get(index).intercept(request, new Chain(index + 1));
            }
        }
    }

    static final class TracingInterceptor implements Interceptor {
        @Override
        public void intercept(Request request, Chain chain) throws ConnectException {
            chain.proceed(request);
        }
    }

    static final class MetricsInterceptor implements Interceptor {
        @Override
        public void intercept(Request request, Chain chain) throws ConnectException {
            chain.proceed(request);
        }
    }

    static final class IdempotencyInterceptor implements Interceptor {
        @Override
        public void intercept(Request request, Chain chain) throws ConnectException {
            chain.proceed(request);
        }
    }

    static final class AuthInterceptor implements Interceptor {
        @Override
        public void intercept(Request request, Chain chain) throws ConnectException {
            chain.proceed(request);
        }
    }
}
