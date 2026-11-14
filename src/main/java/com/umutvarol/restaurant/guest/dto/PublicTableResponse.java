package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.GuestRequestKind;
import com.umutvarol.restaurant.guest.ServiceMode;
import java.util.List;

/**
 * What a guest's phone learns from the table's QR code: where they are, which calls are already waiting, and how
 * the restaurant takes orders; {@code selfPay}: guests may pay the bill from their phone (the plan includes it).
 */
public record PublicTableResponse(String restaurant, String tableCode, List<GuestRequestKind> openCalls,
                                  ServiceMode mode, boolean selfPay) {
}
