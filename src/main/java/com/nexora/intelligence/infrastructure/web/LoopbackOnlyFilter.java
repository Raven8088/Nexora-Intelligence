package com.nexora.intelligence.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Refuses (403) every request to the question API that does not come from the loopback interface. The address is the
 * TCP peer: forwarding headers ({@code X-Forwarded-For}…) are ignored, so they cannot be used to bypass the check.
 */
class LoopbackOnlyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoopback(request.getRemoteAddr())) {
            ApiProblems.write(response, ApiProblems.Problem.LOCAL_ONLY, null);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank() || !address.matches("[0-9a-fA-F:.%]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
