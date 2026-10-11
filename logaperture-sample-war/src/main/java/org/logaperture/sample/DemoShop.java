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
package org.logaperture.sample;

import java.net.ConnectException;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small online shop that logs like a real application, for the demo recordings
 * (doc/specs/demo-recordings.md). While running, a daemon thread places an order every
 * {@value #ORDER_PERIOD_MS} ms through {@code com.example.shop.checkout} and
 * {@code com.example.shop.payment}, at every level from TRACE to WARN, and
 * {@code com.example.shop.inventory.StockSync} reports a sync every
 * {@value #STOCK_PERIOD_MS} ms. {@link #startFlood()} makes the stock sync fail with a
 * {@link ConnectException} every {@value #FLOOD_PERIOD_MS} ms instead, for the
 * "something is flooding the log" story.
 *
 * <p>Order numbers, amounts and cards come from a fixed seed, so two recordings show the
 * same orders. Logs through SLF4J, which WildFly routes into JBoss LogManager.
 */
enum DemoShop {

    INSTANCE;

    static final String CHECKOUT_LOGGER = "com.example.shop.checkout.CheckoutService";
    static final String PAYMENT_LOGGER = "com.example.shop.payment.PaymentGateway";
    static final String STOCK_LOGGER = "com.example.shop.inventory.StockSync";

    static final long ORDER_PERIOD_MS = 1_500L;
    static final long STOCK_PERIOD_MS = 6_000L;
    static final long FLOOD_PERIOD_MS = 50L;

    private static final long SEED = 196L;
    private static final int FIRST_ORDER = 10_421;
    private static final String[] CARDS = {"4242", "1881", "0005", "7310"};

    private final Logger checkout = LoggerFactory.getLogger(CHECKOUT_LOGGER);
    private final Logger payment = LoggerFactory.getLogger(PAYMENT_LOGGER);
    private final Logger stock = LoggerFactory.getLogger(STOCK_LOGGER);

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> orders;
    private ScheduledFuture<?> stockSync;
    private ScheduledFuture<?> flood;
    private Random random;
    private int nextOrder;
    private long floodAttempts;

    /** @return whether this call started the shop (false: it was already running) */
    synchronized boolean start() {
        if (orders != null) {
            return false;
        }
        random = new Random(SEED);
        nextOrder = FIRST_ORDER;
        ensureExecutor();
        orders = executor.scheduleAtFixedRate(this::placeOrder, 0, ORDER_PERIOD_MS, TimeUnit.MILLISECONDS);
        stockSync = executor.scheduleAtFixedRate(this::syncStock, STOCK_PERIOD_MS / 2, STOCK_PERIOD_MS,
                TimeUnit.MILLISECONDS);
        return true;
    }

    /** @return whether this call started the flood (false: it was already flooding) */
    synchronized boolean startFlood() {
        if (flood != null) {
            return false;
        }
        floodAttempts = 0;
        ensureExecutor();
        flood = executor.scheduleAtFixedRate(this::failStockSync, 0, FLOOD_PERIOD_MS, TimeUnit.MILLISECONDS);
        return true;
    }

    /** @return whether a flood was running (and has now been stopped) */
    synchronized boolean stopFlood() {
        boolean wasFlooding = flood != null;
        flood = cancel(flood);
        return wasFlooding;
    }

    /** Stops the orders, the stock sync and any flood. @return whether anything was running */
    synchronized boolean stop() {
        boolean wasRunning = orders != null || flood != null;
        orders = cancel(orders);
        stockSync = cancel(stockSync);
        flood = cancel(flood);
        return wasRunning;
    }

    /** Called on undeploy, so a redeploy doesn't leak the thread. */
    synchronized void shutdown() {
        stop();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    synchronized boolean running() {
        return orders != null;
    }

    synchronized boolean flooding() {
        return flood != null;
    }

    private void placeOrder() {
        int order;
        int items;
        double total;
        String card;
        boolean declined;
        int latencyMs;
        synchronized (this) {
            order = nextOrder++;
            items = 1 + random.nextInt(4);
            total = (1_200 + random.nextInt(14_000)) / 100.0;
            card = CARDS[random.nextInt(CARDS.length)];
            declined = random.nextInt(9) == 0;
            latencyMs = 140 + random.nextInt(260);
        }
        String amount = String.format(Locale.ROOT, "%.2f", total);

        checkout.debug("Cart {}: {} line{}, subtotal {} EUR", order, items, items == 1 ? "" : "s", amount);
        payment.debug("POST /v2/auth order={} {} EUR", order, amount);
        payment.trace("req order={} card=****{} 3ds=ok", order, card);
        if (declined) {
            payment.trace("resp order={} code=05 in {}ms", order, latencyMs);
            payment.warn("Order {} declined: do not honour", order);
            return;
        }
        payment.trace("resp order={} code=00 in {}ms", order, latencyMs);
        payment.info("Authorised order {}: {} EUR", order, amount);
        checkout.info("Order {} placed: {} item{}, {} EUR", order, items, items == 1 ? "" : "s", amount);
    }

    private void syncStock() {
        int skus;
        synchronized (this) {
            if (flood != null) {
                return;
            }
            skus = 200 + random.nextInt(30);
        }
        stock.info("Stock synced: {} SKUs updated", skus);
    }

    private void failStockSync() {
        long attempt;
        synchronized (this) {
            attempt = ++floodAttempts;
        }
        try {
            connectToWarehouse();
        } catch (ConnectException e) {
            stock.warn("Stock sync failed (attempt {}), retrying", attempt, e);
        }
    }

    private static void connectToWarehouse() throws ConnectException {
        throw new ConnectException("Connection refused: warehouse:7443");
    }

    private void ensureExecutor() {
        if (executor == null) {
            ThreadFactory daemonThreads = runnable -> {
                Thread thread = new Thread(runnable, "shop-worker");
                thread.setDaemon(true);
                return thread;
            };
            executor = Executors.newScheduledThreadPool(2, daemonThreads);
        }
    }

    private static ScheduledFuture<?> cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
        return null;
    }
}
