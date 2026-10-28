package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.GuestRequestKind;
import java.util.List;

/** What a guest's phone learns from the table's QR code: where they are, and which calls are already waiting. */
public record PublicTableResponse(String restaurant, String tableCode, List<GuestRequestKind> openCalls) {
}
