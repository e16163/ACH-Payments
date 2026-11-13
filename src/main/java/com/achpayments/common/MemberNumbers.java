package com.achpayments.common;

import java.security.Principal;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.ldap.userdetails.InetOrgPerson;
import org.springframework.stereotype.Component;

/**
 * The signed-in member's number (the holderId in Ba's APIs): employeeNumber on an LDAP login, or
 * app.member-number for the fallback member login. Staff logins have none.
 */
@Component
public class MemberNumbers {

    private final String memberUsername;
    private final String memberNumber;

    public MemberNumbers(@Value("${app.member-username}") String memberUsername, @Value("${app.member-number}") String memberNumber) {
        this.memberUsername = memberUsername;
        this.memberNumber = memberNumber;
    }

    public Optional<String> of(Principal user) {
        if (user instanceof Authentication auth && auth.getPrincipal() instanceof InetOrgPerson person) {
            return Optional.ofNullable(person.getEmployeeNumber());
        }
        return user.getName().equals(memberUsername) ? Optional.of(memberNumber) : Optional.empty();
    }
}
