package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.Business.Plan;
import com.umutvarol.restaurant.business.Feature;
import java.util.EnumSet;
import java.util.Set;

/**
 * What each plan costs and includes (docs/SOFRA_CLOUD_PLAN.md). Prices are per open branch and month, in US cents;
 * a year costs ten months. The billing provider holds the prices that are actually charged; these are what the app
 * shows and must match them.
 */
public final class PlanCatalog {

    /** No limit. */
    public static final int UNLIMITED = -1;

    public record Limits(int branches, int activeStaff, int tables) {
    }

    public record Offer(Plan plan, int monthlyCentsPerBranch, int minimumBranches, Limits limits, Set<Feature> features) {

        public int yearlyCentsPerBranch() {
            return monthlyCentsPerBranch * 10;
        }

        public boolean includes(Feature feature) {
            return features.contains(feature);
        }
    }

    private static final Offer STARTER = new Offer(Plan.STARTER, 1900, 1, new Limits(1, 5, 15), features(Plan.STARTER));
    private static final Offer BUSINESS = new Offer(Plan.BUSINESS, 3900, 1,
            new Limits(UNLIMITED, UNLIMITED, UNLIMITED), features(Plan.BUSINESS));
    private static final Offer CHAIN = new Offer(Plan.CHAIN, 2900, 3,
            new Limits(UNLIMITED, UNLIMITED, UNLIMITED), features(Plan.CHAIN));

    private PlanCatalog() {
    }

    private static Set<Feature> features(Plan plan) {
        Set<Feature> included = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            if (plan.includes(feature)) {
                included.add(feature);
            }
        }
        return included;
    }

    public static Offer of(Plan plan) {
        return switch (plan) {
            case STARTER -> STARTER;
            case BUSINESS -> BUSINESS;
            case CHAIN -> CHAIN;
        };
    }

    /** How many branches are billed: the open ones, but at least the plan's minimum. */
    public static int billedBranches(Plan plan, int openBranches) {
        return Math.max(of(plan).minimumBranches(), Math.max(1, openBranches));
    }
}
