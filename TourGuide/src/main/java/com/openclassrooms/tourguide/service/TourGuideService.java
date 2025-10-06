package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import tripPricer.Provider;
import tripPricer.TripPricer;

/**
 * Service responsible for managing tour guide operations including user tracking,
 * location management, and trip planning functionality.
 *
 * This service coordinates between GPS utilities, rewards calculation, and trip pricing
 * to provide a comprehensive tour guide experience.
 */
@Service
public class TourGuideService {
	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;

	/**
	 * Constructs a new TourGuideService with the specified GPS utility and rewards service.
	 *
	 * @param gpsUtil the GPS utility for location tracking
	 * @param rewardsService the service for calculating user rewards
	 */
	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		
		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

	/**
	 * Retrieves all rewards earned by the specified user.
	 *
	 * @param user the user whose rewards to retrieve
	 * @return a list of user rewards
	 */
	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	/**
	 * Gets the current or last known location of the specified user.
	 * If the user has no visited locations, tracks the user's current location.
	 *
	 * @param user the user whose location to retrieve
	 * @return the user's current or last visited location
	 */
	public VisitedLocation getUserLocation(User user) {
		VisitedLocation visitedLocation = (!user.getVisitedLocations().isEmpty()) ? user.getLastVisitedLocation()
				: trackUserLocation(user);
		return visitedLocation;
	}

	/**
	 * Retrieves a user by their username from the internal user map.
	 *
	 * @param userName the username to search for
	 * @return the user with the specified username, or null if not found
	 */
	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	/**
	 * Retrieves all users currently managed by the system.
	 *
	 * @return a list of all users
	 */
	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}

	/**
	 * Adds a new user to the system if they don't already exist.
	 *
	 * @param user the user to add to the system
	 */
	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	/**
	 * Generates and retrieves trip deals for the specified user based on their preferences
	 * and accumulated reward points.
	 *
	 * @param user the user for whom to generate trip deals
	 * @return a list of available trip providers matching the user's preferences
	 */
	public List<Provider> getTripDeals(User user) {
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(UserReward::getRewardPoints).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints);

		int desiredQuantity = user.getUserPreferences().getTicketQuantity();
		if (providers.size() < desiredQuantity) {
			List<Provider> extended = new java.util.ArrayList<>(providers);
			while (extended.size() < desiredQuantity) {
				extended.addAll(providers);
			}
			providers = extended.subList(0, desiredQuantity);
		} else if (providers.size() > desiredQuantity) {
			providers = providers.subList(0, desiredQuantity);
		}

		user.setTripDeals(providers);
		return providers;
	}

	/**
	 * Track user location for a single user.
	 *
	 * @param user the user to track
	 * @return the visited location
	 */
	public VisitedLocation trackUserLocation(User user) {
		return trackUsersLocations(List.of(user)).get(0);
	}

	/**
	 * Track locations for a list of users with automatic optimization.
	 * Uses parallel processing for multiple users, synchronous for single user.
	 *
	 * @param users the list of users to track
	 * @return list of visited locations in the same order as input users
	 */
	public List<VisitedLocation> trackUsersLocations(List<User> users) {
		if (users == null || users.isEmpty()) {
			return new ArrayList<>();
		}

		// For single user, process synchronously to avoid thread overhead
		if (users.size() == 1) {
			return List.of(trackSingleUserLocation(users.get(0)));
		}

		// For multiple users, use parallel processing with optimized thread pool
		int poolSize = Math.max(Runtime.getRuntime().availableProcessors() * 8, 100);
		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(poolSize);

		try {
			List<java.util.concurrent.CompletableFuture<VisitedLocation>> futures = users.stream()
				.map(user -> java.util.concurrent.CompletableFuture.supplyAsync(() -> trackSingleUserLocation(user), executor))
				.toList();

			return futures.stream()
				.map(java.util.concurrent.CompletableFuture::join)
				.collect(Collectors.toList());
		} finally {
			executor.shutdown();
		}
	}

	/**
	 * Internal method to track a single user's location.
	 *
	 * @param user the user to track
	 * @return the visited location
	 */
	private VisitedLocation trackSingleUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);
		rewardsService.calculateRewards(user);
		return visitedLocation;
	}

	/**
	 * Finds the 5 closest attractions to a given visited location.
	 * Returns attractions sorted by distance regardless of actual proximity.
	 *
	 * @param visitedLocation the location from which to find nearby attractions
	 * @return a list of the 5 closest attractions sorted by distance
	 */
	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		return gpsUtil.getAttractions().stream()
				.sorted(Comparator.comparingDouble(a -> rewardsService.getDistance(a, visitedLocation.location)))
				.limit(5)
				.collect(Collectors.toList());
	}

	/**
	 * Calculates the distance between two locations.
	 * This method delegates to the RewardsService for distance calculation.
	 *
	 * @param loc1 the first location
	 * @param loc2 the second location
	 * @return the distance between the two locations in statute miles
	 */
	public double getDistance(Location loc1, Location loc2) {
		return rewardsService.getDistance(loc1, loc2);
	}

	/**
	 * Retrieves the reward points for a specific attraction and user.
	 * This method delegates to the RewardsService for reward calculation.
	 *
	 * @param attraction the attraction for which to get reward points
	 * @param user the user for whom to calculate reward points
	 * @return the number of reward points awarded
	 */
	public int getAttractionRewardPoints(Attraction attraction, User user) {
		return rewardsService.getAttractionRewardPoints(attraction, user);
	}

	/**
	 * Registers a shutdown hook to properly stop the tracker when the application terminates.
	 */
	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
			}
		});
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 *
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	/**
	 * Initializes internal test users for development and testing purposes.
	 * Creates users with random location history based on the configured test user count.
	 */
	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
        logger.debug("Created {} internal test users.", InternalTestHelper.getInternalUserNumber());
	}

	/**
	 * Generates random location history for a test user.
	 * Creates 3 random visited locations with random coordinates and timestamps.
	 *
	 * @param user the user for whom to generate location history
	 */
	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	/**
	 * Generates a random longitude coordinate within valid range (-180 to 180 degrees).
	 *
	 * @return a random longitude value
	 */
	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	/**
	 * Generates a random latitude coordinate within valid range (-85.05 to 85.05 degrees).
	 *
	 * @return a random latitude value
	 */
	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	/**
	 * Generates a random date within the past 30 days.
	 *
	 * @return a random date for location history
	 */
	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
