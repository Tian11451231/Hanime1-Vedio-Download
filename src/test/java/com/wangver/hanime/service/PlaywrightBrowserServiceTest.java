package com.wangver.hanime.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlaywrightBrowserServiceTest {

    @Test
    void serializesConcurrentBrowserWork() throws Exception {
        PlaywrightBrowserService service = new PlaywrightBrowserService();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        AtomicBoolean secondEnteredWhileFirstRunning = new AtomicBoolean(false);
        AtomicInteger order = new AtomicInteger(0);
        AtomicInteger firstOrder = new AtomicInteger(0);
        AtomicInteger secondOrder = new AtomicInteger(0);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> service.runSerialized(() -> {
                firstOrder.set(order.incrementAndGet());
                firstEntered.countDown();
                allowFirstToFinish.await(3, TimeUnit.SECONDS);
                return 1;
            }));

            Future<Integer> second = executor.submit(() -> {
                firstEntered.await(3, TimeUnit.SECONDS);
                return service.runSerialized(() -> {
                    if (allowFirstToFinish.getCount() > 0) {
                        secondEnteredWhileFirstRunning.set(true);
                    }
                    secondOrder.set(order.incrementAndGet());
                    return 2;
                });
            });

            assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertFalse(secondEnteredWhileFirstRunning.get());

            allowFirstToFinish.countDown();

            assertEquals(1, first.get(3, TimeUnit.SECONDS));
            assertEquals(2, second.get(3, TimeUnit.SECONDS));
        }

        assertEquals(1, firstOrder.get());
        assertEquals(2, secondOrder.get());
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildLaunchArgsContainsDisableAutomationAndWindowPosition() throws Exception {
        PlaywrightBrowserService service = new PlaywrightBrowserService();

        Method method = PlaywrightBrowserService.class.getDeclaredMethod("buildLaunchArgs");
        method.setAccessible(true);

        List<String> args = (List<String>) method.invoke(service);

        assertTrue(args.contains("--disable-blink-features=AutomationControlled"));
        assertEquals(1, args.size());
    }
}
