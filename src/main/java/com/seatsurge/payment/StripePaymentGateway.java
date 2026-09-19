package com.seatsurge.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.common.exception.ApiException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

@Component
public class StripePaymentGateway implements PaymentGateway {

    private final StripeClient client;
    private final SeatSurgeProperties.Stripe config;

    public StripePaymentGateway(SeatSurgeProperties properties) {
        this.config = properties.stripe();
        this.client = hasText(config.secretKey()) ? new StripeClient(config.secretKey()) : null;
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutSessionRequest request) {
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(config.successUrl())
                .setCancelUrl(config.cancelUrl())
                .setClientReferenceId(request.orderId().toString())
                .setCustomerEmail(request.customerEmail())
                .setExpiresAt(request.expiresAt().getEpochSecond())
                .putMetadata("order_id", request.orderId().toString())
                .putMetadata("hold_id", request.holdId().toString())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("order_id", request.orderId().toString())
                        .build());
        for (LineItem item : request.lineItems()) {
            params.addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(request.currency())
                            .setUnitAmount(item.amountCents())
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(item.name())
                                    .build())
                            .build())
                    .build());
        }
        try {
            Session session = requireClient().v1().checkout().sessions()
                    .create(params.build(), idempotent(request.idempotencyKey()));
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR",
                    "Could not start checkout with the payment provider: " + e.getMessage());
        }
    }

    @Override
    public void refund(String paymentIntentId, String idempotencyKey) {
        try {
            requireClient().v1().refunds().create(
                    RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                    idempotent(idempotencyKey));
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe refund failed for " + paymentIntentId + ": " + e.getMessage(), e);
        }
    }

    private StripeClient requireClient() {
        if (client == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_NOT_CONFIGURED",
                    "Payments are not configured (set STRIPE_SECRET_KEY)");
        }
        return client;
    }

    /** Stripe deduplicates on this key, so a retried call never creates a second session or refund. */
    private static RequestOptions idempotent(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
