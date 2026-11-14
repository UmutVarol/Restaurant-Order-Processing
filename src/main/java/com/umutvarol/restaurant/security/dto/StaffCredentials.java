package com.umutvarol.restaurant.security.dto;

/** Shown once, to hand to the employee; it is never stored or shown again. */
public record StaffCredentials(StaffResponse staff, String temporaryPassword) {

    @Override
    public String toString() {
        return "StaffCredentials[staff=" + staff + ", temporaryPassword=***]";
    }
}
