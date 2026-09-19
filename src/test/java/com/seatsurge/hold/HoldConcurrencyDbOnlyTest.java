package com.seatsurge.hold;

import org.springframework.test.context.TestPropertySource;

/** Same races as {@link HoldConcurrencyTest}, with Redis disabled: Postgres optimistic locking alone must hold. */
@TestPropertySource(properties = "seatsurge.hold.redis-fast-path=false")
class HoldConcurrencyDbOnlyTest extends HoldConcurrencyTest {
}
