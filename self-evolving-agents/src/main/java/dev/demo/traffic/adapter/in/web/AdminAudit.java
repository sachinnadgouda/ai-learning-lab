package dev.demo.traffic.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/** Audit trail for administrative actions (dedicated logger so it can be routed separately). */
public final class AdminAudit {
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private AdminAudit() {}

    public static String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    public static void log(Authentication auth, String action, String detail) {
        AUDIT.info("admin action actor={} action={} detail={}", actor(auth), action, detail);
    }
}
