package com.seatsurge.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Periodically puts seats of timed-out holds back on sale. */
@Component
@RequiredArgsConstructor
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldService holdService;

    @Scheduled(fixedDelayString = "${seatsurge.hold.sweep-interval}", initialDelayString = "${seatsurge.hold.sweep-interval}")
    public void sweep() {
        int expired = holdService.expireDueHolds();
        if (expired > 0) {
            log.info("Expired {} hold(s) and released their seats", expired);
        }
    }
}
