package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

/**
 * Service responsible for calculating rewards for users based on visited locations and nearby attractions.
 * It uses an injected {@link GpsUtil} to obtain attractions and an injected {@link RewardCentral}
 * to fetch reward points for a user and an attraction.
 */
@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

    // proximity in miles
    private static final int DEFAULT_PROXIMITY_BUFFER = 10;
    private int proximityBuffer = DEFAULT_PROXIMITY_BUFFER;
    private static final int ATTRACTION_PROXIMITY_RANGE = 200;
    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;
    }

    /**
     * Set the proximity buffer (in statute miles) used to determine whether a visited location
     * is considered "near" an attraction.
     *
     * @param proximityBuffer proximity distance in miles
     */
    public void setProximityBuffer(int proximityBuffer) {
        this.proximityBuffer = proximityBuffer;
    }

    /**
     * Reset the proximity buffer to its default value.
     */
    public void setDefaultProximityBuffer() {
        proximityBuffer = DEFAULT_PROXIMITY_BUFFER;
    }

    /**
     * Calculate rewards for a single user.
     *
     * @param user the user for whom to calculate rewards
     */
    public void calculateRewards(User user) {
        calculateRewards(List.of(user));
    }

    /**
     * Calculate rewards for a list of users in parallel using CompletableFuture with a custom thread pool.
     * This is the main method that handles both single and multiple users efficiently.
     *
     * @param users the list of users to process
     */
    public void calculateRewards(List<User> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        List<Attraction> sharedAttractions = new ArrayList<>(gpsUtil.getAttractions());

        // For single user, process synchronously to avoid thread overhead
        if (users.size() == 1) {
            processUserRewards(users.get(0), sharedAttractions);
            return;
        }

        // For multiple users, use parallel processing
        int poolSize = Math.max(Runtime.getRuntime().availableProcessors() * 4, 16);
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        try {
            List<CompletableFuture<Void>> futures = users.stream()
                .map(user -> CompletableFuture.runAsync(() -> processUserRewards(user, sharedAttractions), executor))
                .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Process rewards for a single user using the provided list of attractions.
     * This method is thread-safe and optimized for both single and parallel execution.
     *
     * @param user the user for whom to compute rewards
     * @param attractions a list of attractions to consider
     */
    private void processUserRewards(User user, List<Attraction> attractions) {
        List<VisitedLocation> userLocations = new ArrayList<>(user.getVisitedLocations());
        List<UserReward> currentUserRewards = new ArrayList<>(user.getUserRewards());

        Set<String> existingRewardAttractions = currentUserRewards.stream()
            .map(r -> r.attraction.attractionName)
            .collect(Collectors.toSet());

        for (VisitedLocation visitedLocation : userLocations) {
            for (Attraction attraction : attractions) {
                if (!existingRewardAttractions.contains(attraction.attractionName)) {
                    if (nearAttraction(visitedLocation, attraction)) {
                        user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
                        existingRewardAttractions.add(attraction.attractionName);
                    }
                }
            }
        }
    }

    /**
     * Check whether a given location is within the configured proximity range of an attraction.
     *
     * @param attraction the attraction to compare
     * @param location the location to check
     * @return true if the location is within {@code ATTRACTION_PROXIMITY_RANGE} miles of the attraction
     */
    public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
        return getDistance(attraction, location) <= ATTRACTION_PROXIMITY_RANGE;
    }

    /**
     * Determine if a visited location is near an attraction using the configured proximity buffer.
     *
     * @param visitedLocation the visited location
     * @param attraction the attraction
     * @return true if the distance between the visited location and the attraction is less than or equal to the proximity buffer
     */
    private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
        return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
    }

    /**
     * Retrieve reward points for an attraction and a user by delegating to RewardCentral.
     *
     * @param attraction the attraction
     * @param user the user
     * @return reward points
     */
    private int getRewardPoints(Attraction attraction, User user) {
        return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
    }

    /**
     * Public accessor for reward points for an attraction and a user.
     *
     * @param attraction the attraction
     * @param user the user
     * @return the points awarded for this attraction to this user
     */
    public int getAttractionRewardPoints(Attraction attraction, User user) {
        return getRewardPoints(attraction, user);
    }

    /**
     * Compute the distance in statute miles between two locations using the spherical law of cosines.
     *
     * @param loc1 first location
     * @param loc2 second location
     * @return distance in statute miles
     */
    public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
    }

}
