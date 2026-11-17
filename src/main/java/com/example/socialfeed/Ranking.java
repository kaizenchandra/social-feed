package com.example.socialfeed;

import java.time.*;

public final class Ranking {
    private Ranking() {
    }

    public static double score(Instant created, Instant snapshot, long likes, long comments, boolean self) {
        return score(created, snapshot, likes, comments, self, .75, .20, .05);
    }

    public static double score(Instant created, Instant snapshot, long likes, long comments, boolean self, double recencyWeight, double engagementWeight, double selfWeight) {
        double hours = Math.max(0, Duration.between(created, snapshot).toSeconds() / 3600.0);
        double recency = Math.exp(-hours / 24.0);
        double engagement = Math.min(1, Math.log1p(Math.max(0, likes) + 2.0 * Math.max(0, comments)) / Math.log(1001));
        return recencyWeight * recency + engagementWeight * engagement + selfWeight * (self ? 1 : 0);
    }
}
