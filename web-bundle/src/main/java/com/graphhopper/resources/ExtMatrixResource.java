package com.graphhopper.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopperAPI;
import com.graphhopper.http.GHPointParam;
import com.graphhopper.routing.ProfileResolver;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;
import com.graphhopper.util.shapes.GHPoint;
import io.dropwizard.jersey.params.AbstractParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.graphhopper.util.Parameters.Routing.ALGORITHM;
import static java.util.stream.Collectors.toList;

@Path("extmatrix")
public class ExtMatrixResource {

    private static final Logger logger = LoggerFactory.getLogger(MatrixResource.class);

    private final GraphHopperAPI graphHopper;
    private final ProfileResolver profileResolver;
    private final Boolean hasElevation;

    @Inject
    public ExtMatrixResource(GraphHopperAPI graphHopper, ProfileResolver profileResolver, @Named("hasElevation") Boolean hasElevation) {
        this.graphHopper = graphHopper;
        this.profileResolver = profileResolver;
        this.hasElevation = hasElevation;
    }


    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(@Context UriInfo uriInfo, @Context HttpServletRequest httpReq, MultivaluedMap<String, String> data) throws JsonProcessingException {

        String driverPoints = data.get("driverPoints").get(0);
        String pointString = data.get("point").get(0);
        List<GHPointParam> pointParams= new ArrayList<>();
        pointParams.add(new GHPointParam(pointString));
        return doGet(uriInfo, driverPoints, pointParams,"", "en", "car");


    }

    @GET
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doGet(@Context UriInfo uriInfo,
                          @QueryParam("driverPoints") String driverPoints,
                          @QueryParam("point") @NotNull List<GHPointParam> pointParams,
                          @QueryParam(ALGORITHM) @DefaultValue("") String algoStr,
                          @QueryParam("locale") @DefaultValue("en") String localeStr,
                          @QueryParam("profile") @DefaultValue("car") String profileName
    ) throws JsonProcessingException {

        List<GHPoint> infoPoints = pointParams.stream().map(AbstractParam::get).collect(toList());
        GHPoint destinationPoint = infoPoints.get(0);

        List<GHPoint> points = null;

        Map<String, ObjectNode> distancesJson = new HashMap<String, ObjectNode>();

        ObjectNode json = JsonNodeFactory.instance.objectNode();
        ObjectMapper mapper = new ObjectMapper();
        Map jsonObjectMap = mapper.readValue(driverPoints, Map.class);
        for(Object driverId : jsonObjectMap.keySet()) {
            points = new ArrayList<GHPoint>();
            Map jsonObject = (Map) jsonObjectMap.get((String) driverId);
            double driverLatitude = (double) jsonObject.get("lat");
            double driverLongitude = (double) jsonObject.get("lng");
            GHPoint driverPoint = new GHPoint(driverLatitude, driverLongitude);
            logger.info("added point " + driverLatitude + "," + driverLongitude);
            points.add(driverPoint);

            if(jsonObject.containsKey("over-lat")) {
                double driverFinishLatitude = (double) jsonObject.get("over-lat");
                double driverFinishLongitude = (double) jsonObject.get("over-lng");
                GHPoint driverFinishPoint = new GHPoint(driverFinishLatitude, driverFinishLongitude);
                logger.info("added over-lat point " + driverFinishLatitude + "," + driverFinishLongitude);
                points.add(driverFinishPoint);
            }

            points.add(destinationPoint);

            GHRequest request = new GHRequest(points);
            initHints(request.getHints(), uriInfo.getQueryParameters());
            request.setAlgorithm(algoStr).
                    setLocale(localeStr).
                    setProfile(profileName).
                    getHints().
                    putObject("calcPoints", false);

            GHResponse ghResponse = graphHopper.route(request);

            ObjectMapper responseMapper = new ObjectMapper();
            ObjectNode response = responseMapper.createObjectNode();

            if (ghResponse.hasErrors()) {
                logger.error(" errors:" + ghResponse.getErrors());
                response.put("distance", 0);
                response.put("time",  0);
                System.out.println("ERROR, set 0");
                distancesJson.put((String) driverId, response);
            } else {
                logger.info("distance: " + ghResponse.getBest().getDistance()
                        + ", time:" + Math.round(ghResponse.getBest().getTime() / 60000f)
                        + "min, points:" + ghResponse.getBest().getPoints().getSize() + ", debug - " + ghResponse.getDebugInfo());

                response.put("distance", ghResponse.getBest().getDistance());
                response.put("time",  ghResponse.getBest().getTime());
                System.out.println("CALCULATED DISTANCE " + response);
                distancesJson.put((String) driverId, response);
            }
        }

        return Response.ok(distancesJson).
                type(MediaType.APPLICATION_JSON).
                build();
    }

    private List<GHPoint> getPoints(String pointsAsStr)
    {
        String[] points = pointsAsStr.split(",");
        final List<GHPoint> infoPoints = new ArrayList<GHPoint>(points.length);
        for (String str : points)
        {
            String[] fromStrs = str.split(",");
            if (fromStrs.length == 2)
            {
                GHPoint place = GHPoint.fromString(str);
                if (place != null)
                    infoPoints.add(place);
            }
        }

        return infoPoints;
    }

    static void initHints(PMap m, MultivaluedMap<String, String> parameterMap) {
        for (Map.Entry<String, List<String>> e : parameterMap.entrySet()) {
            if (e.getValue().size() == 1) {
                m.putObject(Helper.camelCaseToUnderScore(e.getKey()), Helper.toObject(e.getValue().get(0)));
            } else {
                // TODO e.g. 'point' parameter occurs multiple times and we cannot throw an exception here
                //  unknown parameters (hints) should be allowed to be multiparameters, too, or we shouldn't use them for
                //  known parameters either, _or_ known parameters must be filtered before they come to this code point,
                //  _or_ we stop passing unknown parameters altogether.
                // throw new WebApplicationException(String.format("This query parameter (hint) is not allowed to occur multiple times: %s", e.getKey()));
                // see also #1976
            }
        }
    }



}
