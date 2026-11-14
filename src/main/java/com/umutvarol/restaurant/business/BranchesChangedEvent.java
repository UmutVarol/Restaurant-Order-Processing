package com.umutvarol.restaurant.business;

/** A branch was opened, closed or reopened: what a chain pays for may change. */
public record BranchesChangedEvent(long businessId) {
}
