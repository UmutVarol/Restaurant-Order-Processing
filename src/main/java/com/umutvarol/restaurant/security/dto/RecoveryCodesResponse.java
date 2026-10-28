package com.umutvarol.restaurant.security.dto;

import java.util.List;

/** Shown once; only their hashes are kept. */
public record RecoveryCodesResponse(List<String> codes) {

    @Override
    public String toString() {
        return "RecoveryCodesResponse[" + codes.size() + " codes]";
    }
}
