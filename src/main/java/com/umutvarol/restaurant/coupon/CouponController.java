package com.umutvarol.restaurant.coupon;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Coupons", description = "Promotional codes")
@RestController
@RequestMapping("/api/v1/coupons")
class CouponController {

    private final CouponService couponService;

    CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @Operation(summary = "List coupons that can currently be redeemed")
    @GetMapping
    List<CouponResponse> list() {
        return couponService.listRedeemable();
    }
}
