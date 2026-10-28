package com.umutvarol.restaurant.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A manager standing at the till approves with their own PIN. Not needed when a manager does it themselves. */
public record ManagerApproval(
        @NotBlank @Size(max = 50) String manager,
        @NotBlank @Pattern(regexp = "\\d{4,6}") String pin) {

    @Override
    public String toString() {
        return "ManagerApproval[manager=" + manager + ", pin=***]";
    }
}
