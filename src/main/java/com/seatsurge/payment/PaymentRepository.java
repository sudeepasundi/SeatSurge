package com.seatsurge.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    @Query("""
            select new com.seatsurge.payment.PaidOrderRef(p.order.id, p.stripePaymentIntentId)
            from Payment p
            where p.order.event.id = :eventId and p.order.status = com.seatsurge.order.OrderStatus.PAID
            """)
    List<PaidOrderRef> findPaidOrders(@Param("eventId") Long eventId);
}
