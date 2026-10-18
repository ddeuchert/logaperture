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
package com.example.shop.checkout;

import com.example.shop.payment.PaymentException;
import com.example.shop.payment.PaymentGateway;

/**
 * The checkout step in front of the {@link PaymentGateway}, for the "stack traces are burying
 * the log" story (doc/specs/demo-recordings.md, story 4): the top frames of the payment trace
 * are then all the shop's own code.
 */
public final class CheckoutService {

    private final PaymentGateway gateway = new PaymentGateway();

    /** @throws PaymentException when the provider refuses the connection ({@code unreachable}) */
    public void checkout(int order, String amount, boolean unreachable) throws PaymentException {
        pay(order, amount, unreachable);
    }

    private void pay(int order, String amount, boolean unreachable) throws PaymentException {
        gateway.authorise(order, amount, unreachable);
    }
}
