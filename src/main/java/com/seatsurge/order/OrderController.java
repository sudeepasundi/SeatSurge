package com.seatsurge.order;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.idempotency.IdempotencyService;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.order.dto.OrderDtos.CheckoutResponse;
import com.seatsurge.order.dto.OrderDtos.OrderDetail;
import com.seatsurge.order.dto.OrderDtos.OrderSummary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('FAN')")
@Tag(name = "Orders & checkout")
public class OrderController {

    private final OrderService orderService;
    private final IdempotencyService idempotency;

    @PostMapping("/holds/{holdId}/checkout")
    @Operation(summary = "Start Stripe Checkout for a hold",
            description = "Requires an Idempotency-Key header: retrying with the same key returns the original "
                    + "response (header Idempotent-Replayed: true) instead of creating a second order.")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = CheckoutResponse.class)))
    public ResponseEntity<?> checkout(@AuthenticationPrincipal AuthUser user, @PathVariable Long holdId,
            @Parameter(description = "Unique per checkout attempt, e.g. a UUID")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return idempotency.execute(user.id(), idempotencyKey, "POST /holds/" + holdId + "/checkout",
                HttpStatus.CREATED, () -> orderService.checkout(holdId, user));
    }

    @GetMapping("/orders")
    @Operation(summary = "List my orders")
    public PageResponse<OrderSummary> listMine(@AuthenticationPrincipal AuthUser user,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.listMine(user, pageable);
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get an order with its tickets")
    public OrderDetail get(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId) {
        return orderService.get(orderId, user);
    }
}
