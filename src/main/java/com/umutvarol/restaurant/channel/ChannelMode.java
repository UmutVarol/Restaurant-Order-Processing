package com.umutvarol.restaurant.channel;

/** How a platform is connected. */
public enum ChannelMode {
    /** Not set up: its webhook is refused. */
    OFF,
    /** Demo: orders are generated here and updates are only recorded. */
    SIMULATED,
    /** Signed webhooks are accepted; updates go to the platform's API when one is configured. */
    CONNECTED
}
