package com.seatsurge.payment;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks")
@SecurityRequirements
public class StripeWebhookController {

    private final StripeWebhookService webhookService;

    /** Takes the raw body as a String: the signature is computed over the exact bytes Stripe sent. */
    @PostMapping("/stripe")
    @Operation(summary = "Stripe webhook endpoint (signature-verified, deduplicated by event id)")
    public Map<String, String> stripe(@RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        return Map.of("status", webhookService.handle(payload, signature).name());
    }
}
