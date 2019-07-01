package io.antmedia.rest;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.Nullable;
import org.red5.server.api.scope.IBroadcastScope;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.datastore.db.types.ConferenceRoom;
import io.antmedia.datastore.db.types.SocialEndpointChannel;
import io.antmedia.datastore.db.types.SocialEndpointCredentials;
import io.antmedia.datastore.db.types.TensorFlowObject;
import io.antmedia.datastore.db.types.Token;
import io.antmedia.ipcamera.OnvifCamera;
import io.antmedia.ipcamera.onvifdiscovery.OnvifDiscovery;
import io.antmedia.muxer.Mp4Muxer;
import io.antmedia.muxer.MuxAdaptor;
import io.antmedia.muxer.Muxer;
import io.antmedia.rest.BroadcastRestService.BroadcastStatistics;
import io.antmedia.rest.model.Interaction;
import io.antmedia.rest.model.Result;
import io.antmedia.security.ITokenService;
import io.antmedia.social.LiveComment;
import io.antmedia.social.endpoint.PeriscopeEndpoint;
import io.antmedia.social.endpoint.VideoServiceEndpoint;
import io.antmedia.social.endpoint.VideoServiceEndpoint.DeviceAuthParameters;
import io.antmedia.streamsource.StreamFetcher;
import io.antmedia.webrtc.api.IWebRTCAdaptor;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Contact;
import io.swagger.annotations.ExternalDocs;
import io.swagger.annotations.Info;
import io.swagger.annotations.License;
import io.swagger.annotations.SwaggerDefinition;

@Api(value = "BroadcastRestService")
@SwaggerDefinition(
		info = @Info(
				description = "Ant Media Server REST API Reference",
				version = "v2.0",
				title = "Ant Media Server REST API Reference",
				contact = @Contact(name = "Ant Media Info", email = "contact@antmedia.io", url = "https://antmedia.io"),
				license = @License(name = "Apache 2.0", url = "http://www.apache.org")),
		consumes = {"application/json"},
		produces = {"application/json"},
		schemes = {SwaggerDefinition.Scheme.HTTP, SwaggerDefinition.Scheme.HTTPS},
		externalDocs = @ExternalDocs(value = "External Docs", url = "https://antmedia.io"),
		basePath = "/v2"
		)
@Component
@Path("/v2/broadcasts")
public class BroadcastRestServiceV2 extends RestServiceBase{

	@ApiModel(value="SimpleStat", description="Simple generic statistics class to return single values")
	public static class SimpleStat {
		@ApiModelProperty(value = "the stat value")
		public long number;

		public SimpleStat(long number) {
			this.number = number;
		}

		public long getNumber() {
			return number;
		}

	}

	@ApiOperation(value = "Creates a Broadcast, IP Camera or Stream Source and returns the full broadcast object with rtmp address and "
			+ "other information. The different between Broadcast and IP Camera or Stream Source is that Broadcast is ingested by Ant Media Server"
			+ "IP Camera or Stream Source is pulled by Ant Media Server")
	@ApiResponses(value = { @ApiResponse(code = 400, message = "If stream id is already used in the data store, it returns error", response=Result.class),
			@ApiResponse(code = 200, message = "Returns the created stream", response = Broadcast.class)})
	@POST
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response createBroadcast(@ApiParam(value = "Broadcast object only related information should be set, it may be null as well.", required = false) Broadcast broadcast,
			@ApiParam(value = "Comma separated social network IDs, they must in comma separated and IDs must match with the defined IDs.", required = false) @QueryParam("socialNetworks") String socialEndpointIds,
			@ApiParam(value = "Only effective if stream is IP Camera or Stream Source. If it's true, it starts automatically pulling stream. Default value is false by default", required = false, defaultValue="false") @QueryParam("autoStart") boolean autoStart) {


		if (broadcast != null && broadcast.getStreamId() != null && !broadcast.getStreamId().isEmpty()) {
			// make sure stream id is not set on rest service
			Broadcast broadcastTmp = getDataStore().get(broadcast.getStreamId());
			if (broadcastTmp != null) {

				return Response.status(Status.BAD_REQUEST).entity(new Result(false, "Stream id is already being used. ")).build();
			}
		}

		Object returnObject = new Result(false, "unexptected parameters received");

		if (autoStart)  
		{
			//auto is only effective for IP Camera or Stream Source 
			//so if it's true, it should be IP Camera or Stream Soruce
			//otherwise wrong parameter
			if (broadcast != null) {
				returnObject = addStreamSource(broadcast, socialEndpointIds);
			}
		}
		else {
			Broadcast createdBroadcast = createBroadcastWithStreamID(broadcast);
			if (createdBroadcast.getStreamId() != null && socialEndpointIds != null) {
				String[] endpointIds = socialEndpointIds.split(",");
				for (String endpointId : endpointIds) {
					addSocialEndpoint(createdBroadcast.getStreamId(), endpointId);
				}
			}
			returnObject = createdBroadcast;
		}


		return Response.status(returnObject instanceof Result ? Status.BAD_REQUEST : Status.OK).entity(returnObject).build();
	}

	@ApiOperation(value = "Delete broadcast from data store and stop if it's broadcasting", response = Result.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "If it's deleted, success is true. If it's not deleted, success if false.") })
	@DELETE
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public Result deleteBroadcast(@ApiParam(value = " Id of the braodcast", required = true) @PathParam("id") String id) {
		return super.deleteBroadcast(id);		
	}


	@ApiOperation(value = "Get broadcast object")
	@ApiResponses(value = { @ApiResponse(code = 200, message = "Return the broadcast object"),
			@ApiResponse(code = 404, message = "Broadcast object not found")})
	@GET
	@Path("/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getBroadcast(@ApiParam(value = "id of the broadcast", required = true) @PathParam("id") String id) {
		Broadcast broadcast = null;
		if (id != null) {
			broadcast = lookupBroadcast(id);
		}
		if (broadcast != null) {
			return Response.status(Status.OK).entity(broadcast).build();
		}
		else {
			return Response.status(Status.NOT_FOUND).build();
		}
	}

	@ApiOperation(value = "Gets the broadcast list from database", notes = "",responseContainer = "List", response = Broadcast.class)
	@GET
	@Path("/list/{offset}/{size}")
	@Produces(MediaType.APPLICATION_JSON)
	public List<Broadcast> getBroadcastList(@ApiParam(value = "This is the offset of the list, it is useful for pagination", required = true) @PathParam("offset") int offset,
			@ApiParam(value = "Number of items that will be fetched. If there is not enough item in the datastore, returned list size may less then this value", required = true) @PathParam("size") int size) {
		return getDataStore().getBroadcastList(offset, size);
	}


	@ApiOperation(value = "Updates the Broadcast objects fields if it's not null." + 
			" The updated fields are as follows: name, description, userName, password, IP address, streamUrl of the broadcast. " + 
			"It also updates the social endpoints", notes = "", response = Result.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "If it's updated, success field is true. If it's not updated, success  field if false.")})
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public Result updateBroadcast(@ApiParam(value="Broadcast id", required = true) @PathParam("id") String id, 
			@ApiParam(value="Broadcast object with the updates") Broadcast broadcast,
			@ApiParam(value = "Comma separated social network IDs, they must in comma separated and IDs must match with the defined IDs", required = true) @QueryParam("socialNetworks") String socialNetworksToPublish) {
		Result result = new Result(false);
		if (id != null && broadcast != null) 
		{
			if (broadcast.getType() != null && 
					(broadcast.getType().equals(AntMediaApplicationAdapter.IP_CAMERA) || 
							broadcast.getType().equals(AntMediaApplicationAdapter.STREAM_SOURCE))) 
			{
				result = super.updateStreamSource(id, broadcast, socialNetworksToPublish);
			}
			else 
			{
				result = super.updateBroadcast(id, broadcast, socialNetworksToPublish);
			}
		}
		return result;
	}

	@ApiOperation(value = "Revoke authorization from a social network account that is authorized before", notes = "", response = Result.class)
	@DELETE
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/social-networks/{endpointId}")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public Result revokeSocialNetwork(@ApiParam(value = "Endpoint id", required = true) @PathParam("endpointId") String endpointId) {
		return super.revokeSocialNetwork(endpointId);
	}

	@ApiOperation(value = "Add social endpoint to a stream for the specified service id. ", notes = "", response = Result.class)
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}/social-endpoints/{endpointServiceId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Result addSocialEndpointJSON(@ApiParam(value = "Stream id", required = true) @PathParam("id") String id,
			@ApiParam(value = "the id of the service in order to have successfull operation. Social network must be authorized in advance", required = true) @PathParam("endpointServiceId") String endpointServiceId) {
		return addSocialEndpoint(id, endpointServiceId);
	}

	@ApiOperation(value = "Add a third pary rtmp end point to the stream. When broadcast is started,it will send rtmp stream to this rtmp url as well. ", notes = "", response = Result.class)
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}/endpoint")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public Result addEndpoint(@ApiParam(value = "Broadcast id", required = true) @PathParam("id") String id,
			@ApiParam(value = "RTMP url of the endpoint that stream will be republished", required = true) @QueryParam("rtmpUrl") String rtmpUrl) {
		return super.addEndpoint(id, rtmpUrl);
	}

	@ApiOperation(value = "Returns live comments from a specific endpoint like Facebook, Youtube, PSCP, etc. It works If interactivity is collected which can be enabled/disabled by properties file.", notes = "Notes here", responseContainer = "List", response = LiveComment.class)
	@GET
	@Path("/{id}/social-endpoints/{endpointServiceId}/live-comments/{offset}/{batch}")
	@Produces(MediaType.APPLICATION_JSON)
	public List<LiveComment> getLiveCommentsFromEndpoint(@ApiParam(value = "This is the id of the endpoint service", required = true)
	@PathParam("endpointServiceId") String endpointServiceId,
	@ApiParam(value = "Broadcast id", required = true)
	@PathParam("id") String streamId,
	@ApiParam(value = "this is the start offset where to start getting comment", required = true)
	@PathParam("offset") int offset,
	@ApiParam(value = "number of items to be returned", required = true)
	@PathParam("batch") int batch) 
	{

		VideoServiceEndpoint videoServiceEndPoint = getApplication().getVideoServiceEndPoint(endpointServiceId);
		List<LiveComment> liveComment = null;
		if (videoServiceEndPoint != null) {
			liveComment = videoServiceEndPoint.getComments(streamId, offset, batch);
		}
		return liveComment;
	}


	@ApiOperation(value = "Return the number of live views in specified video service endpoint. It works If interactivity is collected which can be enabled/disabled by properties file.", notes = "", response = Result.class)
	@GET
	@Path("/{id}/social-endpoints/{endpointServiceId}/live-views-count")
	@Produces(MediaType.APPLICATION_JSON)
	public Result getViewerCountFromEndpoint(@ApiParam(value = "the id of the endpoint", required = true)
	@PathParam("endpointServiceId") String endpointServiceId,
	@ApiParam(value = "the id of the stream", required = true)
	@PathParam("id") String streamId) 
	{
		VideoServiceEndpoint videoServiceEndPoint = getApplication().getVideoServiceEndPoint(endpointServiceId);
		long liveViews = 0;
		if (videoServiceEndPoint != null) {
			liveViews = videoServiceEndPoint.getLiveViews(streamId);
		}
		return new Result(true, String.valueOf(liveViews));
	}


	@ApiOperation(value = "Returns the number of live comment count from a specific video service endpoint. It works If interactivity is collected which can be enabled/disabled by properties file.", notes = "", response = Result.class)
	@GET
	@Path("/{id}/social-endpoints/{endpointServiceId}/live-comments-count")
	@Produces(MediaType.APPLICATION_JSON)
	public Result getLiveCommentsCount(@ApiParam(value = " the id of the endpoint", required = true) @PathParam("endpointServiceId") String endpointServiceId,
			@ApiParam(value = "the id of the stream", required = true)  @PathParam("streamId") String streamId) {
		VideoServiceEndpoint videoServiceEndPoint = getApplication().getVideoServiceEndPoint(endpointServiceId);
		int commentCount = 0;
		if (videoServiceEndPoint != null) {
			commentCount = videoServiceEndPoint.getTotalCommentsCount(streamId);
		}
		return new Result(true, String.valueOf(commentCount));
	}


	@ApiOperation(value = "Return the interaction from a specific endpoint like Facebook, Youtube, PSCP, etc. It works If interactivity is collected which can be enabled/disabled by properties file.", notes = "", response = Interaction.class)
	@GET
	@Path("/{id}/social-endpoints/{endpointServiceId}/interaction")
	@Produces(MediaType.APPLICATION_JSON)
	public Interaction getInteractionFromEndpoint(@ApiParam(value = "the id of the endpoint", required = true) @PathParam("endpointServiceId") String endpointServiceId,
			@ApiParam(value = "the id of the stream", required = true) @PathParam("id") String streamId) {
		Interaction interaction = null;
		VideoServiceEndpoint videoServiceEndPoint = getApplication().getVideoServiceEndPoint(endpointServiceId);
		if (videoServiceEndPoint != null) {
			interaction = videoServiceEndPoint.getInteraction(streamId);
		}
		return interaction;
	}



	@ApiOperation(value = "Get detected objects from the stream based on offset and size", notes = "",responseContainer = "List", response = TensorFlowObject.class)
	@GET
	@Path("/{id}/detections/{offset}/{size}")
	@Produces(MediaType.APPLICATION_JSON)
	public List<TensorFlowObject> getDetectionList(@ApiParam(value = "the id of the stream", required = true) @PathParam("id") String id,
			@ApiParam(value = "starting point of the list", required = true) @PathParam("offset") int offset,
			@ApiParam(value = "total size of the return list", required = true) @PathParam("size") int size) {
		List<TensorFlowObject> list = null;

		if (id != null) {
			list = getDataStore().getDetectionList(id, offset, size);	
		}

		if (list == null) {
			//do not return null in rest service
			list = new ArrayList<>();
		}
		return list;
	}

	@ApiOperation(value = "Get total number of detected objects", notes = "", response = Long.class)
	@GET
	@Path("/{id}/detections/count")
	@Produces(MediaType.APPLICATION_JSON)
	public SimpleStat getObjectDetectedTotal(@ApiParam(value = "id of the stream", required = true) @PathParam("id") String id){
		return new SimpleStat(getDataStore().getObjectDetectedTotal(id));
	}

	@ApiOperation(value = "Import Live Streams to Stalker Portal", notes = "", response = Result.class)
	@POST
	@Path("/import-to-stalker")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public Result importLiveStreams2Stalker() 
	{
		return super.importLiveStreams2Stalker();
	}


	@ApiOperation(value = "Get the total number of broadcasts", notes = "", response = SimpleStat.class)
	@GET
	@Path("/count")
	@Produces(MediaType.APPLICATION_JSON)
	public SimpleStat getTotalBroadcastNumber() {
		return new SimpleStat(getDataStore().getTotalBroadcastNumber());
	}

	@ApiOperation(value = "Return the active live streams", notes = "", response = SimpleStat.class)
	@GET
	@Path("/active-live-stream-count")
	@Produces(MediaType.APPLICATION_JSON)
	public SimpleStat getAppLiveStatistics() {
		return new SimpleStat(getDataStore().getActiveBroadcastCount());
	}


	@ApiOperation(value = "Generates random one-time token for specified stream")
	@ApiResponses(value = { @ApiResponse(code = 200, message = "Returns token", response=Token.class), 
			@ApiResponse(code = 400, message = "When there is an error in creating token", response=Result.class)})
	@GET
	@Path("/{id}/token")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getToken (@ApiParam(value = "the id of the stream", required = true) @PathParam("id")String streamId,
			@ApiParam(value = "the expire date of the token", required = true) @QueryParam("expireDate") long expireDate,
			@ApiParam(value = "type of the token. It may be \"play\" or \"publish\" ", required = true) @QueryParam("type") String type) 
	{
		Token token = null;
		String message = "Define stream Id and Expire Date (unix time)";
		if(streamId != null && expireDate > 0) {

			ApplicationContext appContext = getAppContext();

			if(appContext != null && appContext.containsBean(ITokenService.BeanName.TOKEN_SERVICE.toString())) 
			{
				ITokenService tokenService = (ITokenService)appContext.getBean(ITokenService.BeanName.TOKEN_SERVICE.toString());
				token = tokenService.createToken(streamId, expireDate, type);
				if(token != null) 
				{
					if (getDataStore().saveToken(token)) {
						//returns token only everything is OK
						return Response.status(Status.OK).entity(token).build();
					}
					else {
						message = "Cannot save token to the datastore";
					}
				}
				else {
					message = "Cannot create token. It can be a mock token service";
				}
			}
			else {
				message = "No token service in this app";
			}
		}

		return Response.status(Status.BAD_REQUEST).entity(new Result(false, message)).build();
	}

	@ApiOperation(value = "Perform validation of token for requested stream. If validated, success field is true, "
			+ "not validated success field false", response = Result.class)
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/validate-token")
	@Produces(MediaType.APPLICATION_JSON)
	public Result validateToken (@ApiParam(value = "token to be validated", required = true) Token token) 
	{
		boolean result =  false;
		
		if(token.getTokenId() != null && getDataStore().validateToken(token) != null) {
			result = true;
		}

		return new Result(result);
	}


	@ApiOperation(value = " Removes all tokens related with requested stream", notes = "", response = Result.class)
	@DELETE
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}/tokens")
	@Produces(MediaType.APPLICATION_JSON)
	public Result revokeTokens (@ApiParam(value = "the id of the stream", required = true) @PathParam("id") String streamId) {
		Result result = new Result(false);

		if(streamId != null) {
			result.setSuccess(getDataStore().revokeTokens(streamId));
		}

		return result;
	}


	@ApiOperation(value = "Get the all tokens of requested stream", notes = "",responseContainer = "List", response = Token.class)
	@GET
	@Path("/{id}/tokens/list/{offset}/{size}")
	@Produces(MediaType.APPLICATION_JSON)
	public List<Token> listTokens (@ApiParam(value = "the id of the stream", required = true) @PathParam("id") String streamId,
			@ApiParam(value = "the starting point of the list", required = true) @PathParam("offset") int offset,
			@ApiParam(value = "size of the return list (max:50 )", required = true) @PathParam("size") int size) {
		List<Token> tokens = null;
		if(streamId != null) {
			tokens = getDataStore().listAllTokens(streamId, offset, size);
		}
		return tokens;
	}

	@ApiOperation(value = "Get the broadcast live statistics total RTMP watcher count, total HLS watcher count, total WebRTC watcher count", notes = "", response = BroadcastStatistics.class)
	@GET
	@Path("/{id}/broadcast-statistics")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public BroadcastStatistics getBroadcastStatistics(@ApiParam(value = "the id of the stream", required = true) @PathParam("id") String id) {
		return super.getBroadcastStatistics(id);
	}

	@ApiOperation(value = "Get WebRTC Client Statistics such as : Audio bitrate, Video bitrate, Target bitrate, Video Sent Period etc.", notes = "", responseContainer = "List",response = WebRTCClientStats.class)
	@GET
	@Path("/{stream_id}/webrtc-client-stats/{offset}/{size}")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public List<WebRTCClientStats> getWebRTCClientStatsList(@ApiParam(value = "offset of the list", required = true) @PathParam("offset") int offset,
			@ApiParam(value = "Number of items that will be fetched", required = true) @PathParam("size") int size,
			@ApiParam(value = "the id of the stream", required = true) @PathParam("stream_id") String streamId) {

		return super.getWebRTCClientStatsList(offset, size, streamId);
	}

	@ApiOperation(value = "Returns filtered broadcast list according to type. It's useful for getting IP Camera and Stream Sources from the whole list", notes = "",responseContainer = "List",response = Broadcast.class)
	@GET
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/filter-list/{offset}/{size}/{type}")
	@Produces(MediaType.APPLICATION_JSON)
	public List<Broadcast> filterBroadcastList(@ApiParam(value = "starting point of the list", required = true) @PathParam("offset") int offset,
			@ApiParam(value = "size of the return list (max:50 )", required = true) @PathParam("size") int size,
			@ApiParam(value = "type of the stream. Possible values are \"liveStream\", \"ipCamera\", \"streamSource\", \"VoD\"", required = true) @PathParam("type") String type) {
		return getDataStore().filterBroadcastList(offset, size, type);
	}


	@ApiOperation(value = "Get device parameters for social network authorization.", notes = "", response = Object.class)
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/social-networks/{serviceName}")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public Object getDeviceAuthParameters(@ApiParam(value = "Name of the service, like Facebook, Youtube, Periscope", required = true) @PathParam("serviceName") String serviceName) {
		return super.getDeviceAuthParameters(serviceName);
	}

	@ApiOperation(value = "Check if device is authenticated in the social network. In authorization phase, " +
			"this function may be polled periodically until it returns success." +
			"Server checks social network service for about 1 minute so that if user" +
			"does not enter DeviceAuthParameters in a 1 minute, this function will" +
			"never return true", notes = "", response = Result.class)
	@GET
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/social-network-status/{userCode}")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public Result checkDeviceAuthStatus(@ApiParam(value = "Code of social media account", required = true) @PathParam("userCode") String userCode) {
		return super.checkDeviceAuthStatus(userCode);
	}

	@ApiOperation(value = "Get Credentials of Social Endpoints", notes = "", responseContainer = "List",response = SocialEndpointCredentials.class)
	@GET
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/social-endpoints/{offset}/{size}")
	@Produces(MediaType.APPLICATION_JSON)
	public List<SocialEndpointCredentials> getSocialEndpoints(@ApiParam(value = "the starting point of the list", required = true) @PathParam("offset") int offset,
			@ApiParam(value = "size of the return list (max:50 )", required = true) @PathParam("size") int size) {
		List<SocialEndpointCredentials> endPointCredentials = new ArrayList<>();
		Map<String, VideoServiceEndpoint> endPointMap = getEndpointList();
		if (endPointMap != null) {
			for (VideoServiceEndpoint videoServiceEndpoint : endPointMap.values()) {
				endPointCredentials.add(videoServiceEndpoint.getCredentials());
			}
		}
		return endPointCredentials;
	}

	@ApiOperation(value = "Some social networks have different channels especially for facebook," +
			"Live stream can be published on Facebook Page or Personal account, this" +
			"service returns the related information about that.", notes = "", response = SocialEndpointChannel.class)
	@GET
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/social-networks-channel/{endpointId}")
	@Produces(MediaType.APPLICATION_JSON)
	public SocialEndpointChannel getSocialNetworkChannel(@ApiParam(value = "endpointId", required = true) @PathParam("endpointId") String endpointId) {
		Map<String, VideoServiceEndpoint> endPointMap = getEndpointList();
		VideoServiceEndpoint endPoint = endPointMap.get(endpointId);
		SocialEndpointChannel channel = null;
		if (endPoint != null) {
			channel = endPoint.getChannel();
		}
		return channel;
	}


	@ApiOperation(value = "Returns available social network channels for the specific service", notes = "",responseContainer = "List",response = SocialEndpointChannel.class)
	@GET
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/social-networks-channel-lists/{endpointId}/{type}")
	@Produces(MediaType.APPLICATION_JSON)
	public List<SocialEndpointChannel> getSocialNetworkChannelList(@ApiParam(value = "endpointId", required = true) @PathParam("endpointId") String endpointId,
			@ApiParam(value = "This is very service specific, it may be page for Facebook", required = true) @PathParam("type") String type) {

		Map<String, VideoServiceEndpoint> endPointMap = getEndpointList();
		VideoServiceEndpoint endPoint = endPointMap.get(endpointId);
		List<SocialEndpointChannel>  channelList = null;
		if (endPoint != null) {
			channelList = endPoint.getChannelList();
		}
		return channelList;
	}


	@ApiOperation(value = "If there are multiple channels in a social network," +
			"this method sets specific channel for that endpoint" +
			"If a user has pages in Facebook, this method sets the specific page to publish live stream to", notes = "", response = Result.class)
	@POST
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/social-networks-channels/{endpointId}/{type}/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Result setSocialNetworkChannelList(@ApiParam(value = "endpointId", required = true) @PathParam("endpointId") String endpointId,
			@ApiParam(value = "type", required = true) @PathParam("type") String type,
			@ApiParam(value = "id", required = true) @PathParam("id") String channelId) {
		boolean result = false;
		Map<String, VideoServiceEndpoint> endPointMap = getEndpointList();

		VideoServiceEndpoint endPoint = endPointMap.get(endpointId);

		if (endPoint != null) {
			result = endPoint.setActiveChannel(type, channelId);
		}
		return new Result(result, null);
	}

	@ApiOperation(value = "Set stream specific recording setting, this setting overrides general Mp4 Muxing Setting", notes = "", response = Result.class)
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}/recording/{recording-status}")
	@Produces(MediaType.APPLICATION_JSON)
	public Result enableMp4Muxing(@ApiParam(value = "the id of the stream", required = true) @PathParam("id") String streamId,
			@ApiParam(value = "Change recording status. If true, starts recording. If false stop recording", required = true) @PathParam("recording-status") boolean enableRecording) {
		Result result = new Result(false);
		if (streamId != null) {

			if (getDataStore().setMp4Muxing(streamId, enableRecording ? MP4_ENABLE : MP4_DISABLE)) 
			{
				if (enableRecording) {
					startMp4Muxing(streamId);
				} else {
					stopMp4Muxing(streamId);
				}
				result.setSuccess(true);
				result.setMessage("streamId:" + streamId);
			} else {
				result.setMessage("no stream for this id: " + streamId + " or wrong setting parameter");
			}
		}

		return result;
	}


	@ApiOperation(value = "Get IP Camera Error after connection failure", notes = "Notes here", response = Result.class)
	@GET
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}/error")
	@Produces(MediaType.APPLICATION_JSON)
	public Result getCameraError(@ApiParam(value = "the id of the stream", required = true) @PathParam("id") String id) {
		Result result = new Result(true);

		for (StreamFetcher camScheduler : getApplication().getStreamFetcherManager().getStreamFetcherList()) {
			if (camScheduler.getStream().getIpAddr().equals(id)) {
				result = camScheduler.getCameraError();
			}
		}

		return result;
	}

	@ApiOperation(value = "Start external sources (IP Cameras and Stream Sources) again if it is added and stopped before", response = Result.class)
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}/start")
	@Produces(MediaType.APPLICATION_JSON)
	public Result startStreamSource(@ApiParam(value = "the id of the stream. The broadcast type should be IP Camera or Stream Source otherwise it does not work", required = true) @PathParam("id") String id) 
	{
		Result result = new Result(false);	
		Broadcast broadcast = getDataStore().get(id);

		if (broadcast != null) 
		{
			if(broadcast.getStreamUrl() == null && broadcast.getType().equals(AntMediaApplicationAdapter.IP_CAMERA)) 
			{
				//if streamURL is not defined before for IP Camera, connect to it again and define streamURL
				Result connResult = connectToCamera(broadcast);
				if (connResult.isSuccess()) 
				{
					String authparam = broadcast.getUsername() + ":" + broadcast.getPassword() + "@";
					String rtspURLWithAuth = RTSP + authparam + connResult.getMessage().substring(RTSP.length());
					logger.info("rtsp url with auth: {}", rtspURLWithAuth);
					broadcast.setStreamUrl(rtspURLWithAuth);
				}	

			}

			if(getApplication().startStreaming(broadcast) != null) {
				result.setSuccess(true);
			}

		}
		return result;
	}

	@ApiOperation(value = "Stop external sources (IP Cameras and Stream Sources)", response = Result.class)
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/{id}/stop")
	@Produces(MediaType.APPLICATION_JSON)
	public Result stopStreamSource(@ApiParam(value = "the id of the broadcast. The broadcast type should be IP Camera or Stream Source otherwise it does not work", required = true) @PathParam("id") String id) 
	{
		Result result = new Result(false);
		Broadcast broadcast = getDataStore().get(id);
		if(broadcast != null) {
			result = getApplication().stopStreaming(broadcast);
		}
		return result;
	}


	@ApiOperation(value = "Get Discovered ONVIF IP Cameras, this service perform a discovery inside of internal network and get automatically  ONVIF enabled camera information", notes = "Notes here", response = Result.class)
	@GET
	@Path("/onvif-devices")
	@Produces(MediaType.APPLICATION_JSON)
	public String[] searchOnvifDevices() {

		String localIP = null;
		String[] list = null;
		Enumeration<NetworkInterface> interfaces = null;
		try {
			interfaces = NetworkInterface.getNetworkInterfaces();
		} catch (SocketException e) {
			// handle error
		}

		if (interfaces != null) {
			while (interfaces.hasMoreElements()) {
				NetworkInterface i = interfaces.nextElement();
				Enumeration<InetAddress> addresses = i.getInetAddresses();
				while (addresses.hasMoreElements() && (localIP == null || localIP.isEmpty())) {
					InetAddress address = addresses.nextElement();
					if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
						localIP = address.getHostAddress();
					}
				}
			}
			logger.info("IP Address: {} " , localIP);
		}

		if (localIP != null) {

			String[] ipAddrParts = localIP.split("\\.");

			String ipAd = ipAddrParts[0] + "." + ipAddrParts[1] + "." + ipAddrParts[2] + ".";
			ArrayList<String> addressList = new ArrayList<>();

			for (int i = 2; i < 255; i++) {
				addressList.add(ipAd + i);

			}

			List<URL> onvifDevices = OnvifDiscovery.discoverOnvifDevices(true, addressList);

			list = new String[onvifDevices.size()];

			if (!onvifDevices.isEmpty()) {

				for (int i = 0; i < onvifDevices.size(); i++) {

					list[i] = StringUtils.substringBetween(onvifDevices.get(i).toString(), HTTP, "/");
				}
			}

		}

		return list;
	}

	@ApiOperation(value = "Move IP Camera Up", response = Result.class)
	@POST
	@Path("/{id}/ip-camera/move-up")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveUp(@ApiParam(value = "the id of the IP Camera", required = true) @PathParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getApplication().getOnvifCamera(id);
		if (camera != null) {
			camera.moveUp();
			result = true;
		}
		return new Result(result);
	}

	@ApiOperation(value = "Move IP Camera Down", response = Result.class)
	@POST
	@Path("/{id}/ip-camera/move-down")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveDown(@ApiParam(value = "the id of the IP Camera", required = true) @PathParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getApplication().getOnvifCamera(id);
		if (camera != null) {
			camera.moveDown();
			result = true;
		}
		return new Result(result);
	}



	@ApiOperation(value = "Move IP Camera Left", notes = "Notes here", response = Result.class)
	@POST
	@Path("/{id}/ip-camera/move-left")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveLeft(@ApiParam(value = "the id of the IP Camera", required = true) @PathParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getApplication().getOnvifCamera(id);
		if (camera != null) {
			camera.moveLeft();
			result = true;
		}
		return new Result(result);
	}


	@ApiOperation(value = "Move IP Camera Right", response = Result.class)
	@POST
	@Path("/{id}/ip-camera/move-right")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveRight(@ApiParam(value = "the id of the IP Camera", required = true) @PathParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getApplication().getOnvifCamera(id);
		if (camera != null) {
			camera.moveRight();
			result = true;
		}
		return new Result(result);
	}

	@ApiOperation(value="Zoom-In IP Camera")
	@POST
	@Path("/{id}/ip-camera/zoom-in")
	@Produces(MediaType.APPLICATION_JSON)
	public Result zoomInIPCamera(@ApiParam(value = "the id of the IP Camera", required = true) @PathParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getApplication().getOnvifCamera(id);
		if (camera != null) {
			camera.zoomIn();
			result = true;
		}
		return new Result(result);
	}


	@ApiOperation(value="Zoom-In IP Camera")
	@POST
	@Path("/{id}/ip-camera/zoom-out")
	@Produces(MediaType.APPLICATION_JSON)
	public Result zoomOutIPCamera(@ApiParam(value = "the id of the IP Camera", required = true) @PathParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getApplication().getOnvifCamera(id);
		if (camera != null) {
			camera.zoomOut();
			result = true;
		}
		return new Result(result);
	}

	@ApiOperation(value = "Creates a conference room with the parameters. The room name is key so if this is called with the same room name then new room is overwritten to old one", response = ConferenceRoom.class)
	@ApiResponses(value = { @ApiResponse(code = 400, message = "If operation is no completed for any reason", response=Result.class),
			@ApiResponse(code = 200, message = "Returns the created conference room", response = ConferenceRoom.class)})
	@POST
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/conference-rooms")
	@Produces(MediaType.APPLICATION_JSON)
	public Response createConferenceRoom(@ApiParam(value = "Conference Room object with start and end date", required = true) ConferenceRoom room) {

		if(room != null) {

			if(room.getStartDate() == 0) {
				room.setStartDate(Instant.now().getEpochSecond());
			}

			if(room.getEndDate() == 0) {
				room.setEndDate(Instant.now().getEpochSecond() + 3600 );
			}

			if (getDataStore().createConferenceRoom(room)) {
				return Response.status(Status.OK).entity(room).build();
			}
		}
		return Response.status(Status.BAD_REQUEST).entity(new Result(false, "Operation not completed")).build();
	}

	@ApiOperation(value = "Edits previously saved conference room", response = Response.class)
	@ApiResponses(value = { @ApiResponse(code = 400, message = "If operation is no completed for any reason", response=Result.class),
			@ApiResponse(code = 200, message = "Returns the updated Conference room", response = ConferenceRoom.class)})
	@PUT
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/conference-rooms/{room_id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response editConferenceRoom(@ApiParam(value="Room id") @PathParam("room_id") String roomId,  @ApiParam(value = "Conference Room object with start and end date", required = true) ConferenceRoom room) {

		if(room != null && getDataStore().editConferenceRoom(roomId, room)) {
			return Response.status(Status.OK).entity(room).build();
		}
		return Response.status(Status.BAD_REQUEST).entity(new Result(false, "Operation not completed")).build();
	}

	@ApiOperation(value = "Deletes a conference room. The room id is key so if this is called with the same room id then new room is overwritten to old one", response = Result.class)
	@DELETE
	@Consumes({ MediaType.APPLICATION_JSON })
	@Path("/conference-rooms/{room_id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Result deleteConferenceRoom(@ApiParam(value = "the id of the conference room", required = true) @PathParam("room_id") String roomId) {
		boolean result = false;
		if(roomId != null) {
			result = getDataStore().deleteConferenceRoom(roomId);
		}
		return new Result(result);
	}



}