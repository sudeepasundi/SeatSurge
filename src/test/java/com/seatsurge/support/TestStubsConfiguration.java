package com.seatsurge.support;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

import com.seatsurge.common.exception.ApiException;
import com.seatsurge.notification.TicketMailer;
import com.seatsurge.payment.PaymentGateway;

/** Replaces the external systems (Stripe, SMTP) with in-memory fakes that record every call. */
@TestConfiguration(proxyBeanMethods = false)
public class TestStubsConfiguration {

    @Bean
    @Primary
    FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }

    @Bean
    @Primary
    RecordingMailer recordingMailer() {
        return new RecordingMailer();
    }

    public static class FakePaymentGateway implements PaymentGateway {

        public final List<CheckoutSessionRequest> sessionRequests = new CopyOnWriteArrayList<>();
        public final List<String> refundKeys = new CopyOnWriteArrayList<>();
        public final List<String> refundedPaymentIntents = new CopyOnWriteArrayList<>();
        private final Set<Long> failNextCheckoutForOrder = ConcurrentHashMap.newKeySet();

        @Override
        public CheckoutSession createCheckoutSession(CheckoutSessionRequest request) {
            sessionRequests.add(request);
            if (failNextCheckoutForOrder.remove(request.orderId())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Simulated Stripe outage");
            }
            String id = "cs_test_order_" + request.orderId();
            return new CheckoutSession(id, "https://checkout.stripe.test/pay/" + id);
        }

        @Override
        public void refund(String paymentIntentId, String idempotencyKey) {
            refundKeys.add(idempotencyKey);
            refundedPaymentIntents.add(paymentIntentId);
        }

        /** The next Stripe call for this order fails (order ids are sequential, so tests can predict them). */
        public void failNextCheckoutFor(long orderId) {
            failNextCheckoutForOrder.add(orderId);
        }

        public long sessionsCreatedFor(long orderId) {
            return sessionRequests.stream().filter(r -> r.orderId() == orderId).count();
        }
    }

    public static class RecordingMailer implements TicketMailer {

        public record Mail(String to, String subject, String body) {
        }

        public final List<Mail> sent = new CopyOnWriteArrayList<>();
        private final Set<String> failNextFor = ConcurrentHashMap.newKeySet();

        @Override
        public void send(String to, String subject, String body) {
            if (failNextFor.remove(to)) {
                throw new IllegalStateException("Simulated SMTP failure");
            }
            sent.add(new Mail(to, subject, body));
        }

        public void failNextSendTo(String to) {
            failNextFor.add(to);
        }

        public List<Mail> sentTo(String to) {
            return sent.stream().filter(m -> m.to().equals(to)).toList();
        }
    }
}
