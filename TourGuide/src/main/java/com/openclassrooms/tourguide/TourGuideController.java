package com.openclassrooms.tourguide;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gpsUtil.location.Attraction;
import gpsUtil.location.VisitedLocation;

import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import tripPricer.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class TourGuideController {

    private static final Logger logger = LoggerFactory.getLogger(TourGuideController.class);

	@Autowired
	TourGuideService tourGuideService;
	
    @RequestMapping("/")
    public String index() {
        return "Greetings from TourGuide!";
    }
    
    @RequestMapping("/getLocation") 
    public VisitedLocation getLocation(@RequestParam String userName) {
    	return tourGuideService.getUserLocation(getUser(userName));
    }

    @RequestMapping("/getNearbyAttractions") 
    public List<NearbyAttraction> getNearbyAttractions(@RequestParam String userName) {
        VisitedLocation visitedLocation = tourGuideService.getUserLocation(getUser(userName));
        List<Attraction> attractions = tourGuideService.getNearByAttractions(visitedLocation);
        List<NearbyAttraction> result = attractions.stream().map(a -> {
            double distance = tourGuideService.getDistance(a, visitedLocation.location);
            int reward = tourGuideService.getAttractionRewardPoints(a, getUser(userName));
            return new NearbyAttraction(a.attractionName, a.latitude, a.longitude,
                    visitedLocation.location.latitude, visitedLocation.location.longitude,
                    distance, reward);
        }).collect(Collectors.toList());
        logger.info("Attractions trouvées: {}", result.size());
        if (!result.isEmpty()) {
            logger.info("Premier élément: {}", result.get(0));
        }
        return result;
    }
    
    @RequestMapping("/getRewards") 
    public List<UserReward> getRewards(@RequestParam String userName) {
    	return tourGuideService.getUserRewards(getUser(userName));
    }
       
    @RequestMapping("/getTripDeals")
    public List<Provider> getTripDeals(@RequestParam String userName) {
    	return tourGuideService.getTripDeals(getUser(userName));
    }
    
    private User getUser(String userName) {
    	return tourGuideService.getUser(userName);
    }
   

}