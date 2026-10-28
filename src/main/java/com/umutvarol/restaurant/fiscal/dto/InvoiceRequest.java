package com.umutvarol.restaurant.fiscal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A guest asks for an e-Arşiv invoice in their (or their company's) name. */
public record InvoiceRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(min = 10, max = 11) String taxId,
        @Size(max = 255) String address) {
}
