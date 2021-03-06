/*
 * Copyright (c) 2013 Plexxi, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.affinity.analytics.northbound;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;

import org.codehaus.enunciate.jaxrs.ResponseCode;
import org.codehaus.enunciate.jaxrs.StatusCodes;
import org.codehaus.enunciate.jaxrs.TypeHint;

import org.opendaylight.affinity.affinity.AffinityLink;
import org.opendaylight.affinity.affinity.IAffinityManager;
import org.opendaylight.affinity.analytics.IAnalyticsManager;
import org.opendaylight.controller.containermanager.IContainerManager;
import org.opendaylight.controller.hosttracker.IfIptoHost;
import org.opendaylight.controller.hosttracker.hostAware.HostNodeConnector;
import org.opendaylight.controller.northbound.commons.RestMessages;
import org.opendaylight.controller.northbound.commons.exception.*;
import org.opendaylight.controller.northbound.commons.utils.NorthboundUtils;
import org.opendaylight.controller.sal.authorization.Privilege;
import org.opendaylight.controller.sal.core.Host;
import org.opendaylight.controller.sal.utils.GlobalConstants;
import org.opendaylight.controller.sal.utils.IPProtocols;
import org.opendaylight.controller.sal.utils.ServiceHelper;
import org.opendaylight.controller.switchmanager.ISwitchManager;

/**
 * Northbound APIs that returns various Analytics exposed by the Southbound
 * plugins such as Openflow.
 *
 * <br>
 * <br>
 * Authentication scheme : <b>HTTP Basic</b><br>
 * Authentication realm : <b>opendaylight</b><br>
 * Transport : <b>HTTP and HTTPS</b><br>
 * <br>
 * HTTPS Authentication is disabled by default. Administrator can enable it in
 * tomcat-server.xml after adding a proper keystore / SSL certificate from a
 * trusted authority.<br>
 * More info :
 * http://tomcat.apache.org/tomcat-7.0-doc/ssl-howto.html#Configuration
 *
 */
@Path("/")
public class AnalyticsNorthbound {

    private String username;

    @Context
    public void setSecurityContext(SecurityContext context) {
        username = context.getUserPrincipal().getName();
    }

    protected String getUserName() {
        return username;
    }

    private IAnalyticsManager getAnalyticsService(String containerName) {
        IContainerManager containerManager = (IContainerManager) ServiceHelper.getGlobalInstance(IContainerManager.class, this);
        if (containerManager == null)
            throw new ServiceUnavailableException("Container " + RestMessages.SERVICEUNAVAILABLE.toString());

        boolean found = false;
        List<String> containerNames = containerManager.getContainerNames();
        for (String cName : containerNames)
            if (cName.trim().equalsIgnoreCase(containerName.trim()))
                found = true;
        if (found == false)
            throw new ResourceNotFoundException(containerName + " " + RestMessages.NOCONTAINER.toString());

        IAnalyticsManager analyticsManager = (IAnalyticsManager) ServiceHelper.getInstance(IAnalyticsManager.class, containerName, this);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());
        return analyticsManager;
    }

    /**
     * @param containerName: Name of the Container
     * @param dataLayerAddr: DataLayerAddress for the host
     * @param networkAddr: NetworkAddress for the host
     * @return Statistics for a (src, dst) pair.
     */
    @Path("/{containerName}/hoststats/{srcNetworkAddr}/{dstNetworkAddr}")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(Statistics.class)
    @StatusCodes({
            @ResponseCode(code = 200, condition = "Operation successful"),
            @ResponseCode(code = 404, condition = "The containerName is not found"),
            @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public Statistics getHostStatistics(
           @PathParam("containerName") String containerName,
           @PathParam("srcNetworkAddr") String srcNetworkAddr,
           @PathParam("dstNetworkAddr") String dstNetworkAddr) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        Host srcHost = handleHostAvailability(containerName, srcNetworkAddr);
        Host dstHost = handleHostAvailability(containerName, dstNetworkAddr);
        long byteCount = analyticsManager.getByteCount(srcHost, dstHost);
        long packetCount = analyticsManager.getPacketCount(srcHost, dstHost);
        double duration = analyticsManager.getDuration(srcHost, dstHost);
        double bitRate = analyticsManager.getBitRate(srcHost, dstHost);

        return new Statistics(byteCount, packetCount, duration, bitRate);
    }

    /**
     * @param containerName: Name of the Container
     * @param srcIP: Source IP
     * @param dstIP: Destination IP
     * @param protocol: IP protocol
     * @return Statistics for a (src, dst) pair and a particular protocol
     */
    @Path("/{containerName}/hoststats/{srcIP}/{dstIP}/{protocol}")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(Statistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public Statistics getHostStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("srcIP") String srcIP,
        @PathParam("dstIP") String dstIP,
        @PathParam("protocol") String protocol) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        Host srcHost = handleHostAvailability(containerName, srcIP);
        Host dstHost = handleHostAvailability(containerName, dstIP);
        long byteCount = analyticsManager.getByteCount(srcHost, dstHost, IPProtocols.getProtocolNumberByte(protocol));
        long packetCount = analyticsManager.getPacketCount(srcHost, dstHost, IPProtocols.getProtocolNumberByte(protocol));
        double duration = analyticsManager.getDuration(srcHost, dstHost, IPProtocols.getProtocolNumberByte(protocol));
        double bitRate = analyticsManager.getBitRate(srcHost, dstHost, IPProtocols.getProtocolNumberByte(protocol));

        return new Statistics(byteCount, packetCount, duration, bitRate);
    }

    /**
     * @param containerName: Name of the Container
     * @param srcIP: Source IP
     * @param dstIP: Destination IP
     * @return All statistics for a (src, dst) pair
     */
    @Path("/{containerName}/hoststats/{srcIP}/{dstIP}/all")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(AllStatistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public AllStatistics getAllHostStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("srcIP") String srcIP,
        @PathParam("dstIP") String dstIP) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        Host srcHost = handleHostAvailability(containerName, srcIP);
        Host dstHost = handleHostAvailability(containerName, dstIP);
        Map<Byte, Long> byteCounts = analyticsManager.getAllByteCounts(srcHost, dstHost);
        Map<Byte, Long> packetCounts = analyticsManager.getAllPacketCounts(srcHost, dstHost);
        Map<Byte, Double> durations = analyticsManager.getAllDurations(srcHost, dstHost);
        Map<Byte, Double> bitRates = analyticsManager.getAllBitRates(srcHost, dstHost);
        return new AllStatistics(byteCounts, packetCounts, durations, bitRates);
    }

    /**
     * @param containerName: Name of the Container
     * @param linkName: AffinityLink name
     * @return Statistics for an affinity link
     */
    @Path("/{containerName}/affinitylinkstats/{linkName}")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(Statistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
   public Statistics getAffinityLinkStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("linkName") String affinityLinkName) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        AffinityLink al = handleAffinityLinkAvailability(containerName, affinityLinkName);
        long byteCount = analyticsManager.getByteCount(al);
        long packetCount = analyticsManager.getPacketCount(al);
        double duration = analyticsManager.getDuration(al);
        double bitRate = analyticsManager.getBitRate(al);

        return new Statistics(byteCount, packetCount, duration, bitRate);
    }

    /**
     * @param containerName: Name of the Container
     * @param linkName: AffinityLink name
     * @param protocol: IP Protocol
     * @return Statistics for an affinity link and a particular protocol
     */
    @Path("/{containerName}/affinitylinkstats/{linkName}/{protocol}")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(Statistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public Statistics getAffinityLinkStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("linkName") String affinityLinkName,
        @PathParam("protocol") String protocol) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        AffinityLink al = handleAffinityLinkAvailability(containerName, affinityLinkName);
        long byteCount = analyticsManager.getByteCount(al, IPProtocols.getProtocolNumberByte(protocol));
        long packetCount = analyticsManager.getPacketCount(al, IPProtocols.getProtocolNumberByte(protocol));
        double duration = analyticsManager.getDuration(al, IPProtocols.getProtocolNumberByte(protocol));
        double bitRate = analyticsManager.getBitRate(al, IPProtocols.getProtocolNumberByte(protocol));

        return new Statistics(byteCount, packetCount, duration, bitRate);
    }

    /**
     * @param containerName: Name of the Container
     * @param linkName: AffinityLink name
     * @return All statistics for an affinity link
     */
    @Path("/{containerName}/affinitylinkstats/{linkName}/all")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(AllStatistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public AllStatistics getAllAffinityLinkStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("linkName") String affinityLinkName) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        AffinityLink al = handleAffinityLinkAvailability(containerName, affinityLinkName);
        Map<Byte, Long> byteCounts = analyticsManager.getAllByteCounts(al);
        Map<Byte, Long> packetCounts = analyticsManager.getAllPacketCounts(al);
        Map<Byte, Double> durations = analyticsManager.getAllDurations(al);
        Map<Byte, Double> bitRates = analyticsManager.getAllBitRates(al);
        return new AllStatistics(byteCounts, packetCounts, durations, bitRates);
    }

    /**
     * @param containerName: Name of the Container
     * @param srcIP: Source IP prefix
     * @param srcMask: Source mask
     * @param dstIP: Destination IP prefix
     * @param dstMask: Destination mask
     * @return Statistics between subnets
     */
    @Path("/{containerName}/subnetstats/{srcIP}/{srcMask}/{dstIP}/{dstMask}")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(Statistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public Statistics getSubnetStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("srcIP") String srcIP,
        @PathParam("srcMask") String srcMask,
        @PathParam("dstIP") String dstIP,
        @PathParam("dstMask") String dstMask) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        String srcString = srcIP + "/" + srcMask;
        String dstString = dstIP + "/" + dstMask;
        // TODO: This is hardly the most elegant way to handle null prefixes
        if (srcString.equals("null/null"))
            srcString = null;
        if (dstString.equals("null/null"))
            dstString = null;
        long byteCount = analyticsManager.getByteCount(srcString, dstString);
        long packetCount = analyticsManager.getPacketCount(srcString, dstString);
        double duration = analyticsManager.getDuration(srcString, dstString);
        double bitRate = analyticsManager.getBitRate(srcString, dstString);

        return new Statistics(byteCount, packetCount, duration, bitRate);
    }

    /**
     * @param containerName: Name of the Container
     * @param srcIP: Source IP prefix
     * @param srcMask: Source mask
     * @param dstIP: Destination IP prefix
     * @param dstMask: Destination mask
     * @param protocol: IP protocol
     * @return Statistics between subnets for a particular protocol
     */
    @Path("/{containerName}/subnetstats/{srcIP}/{srcMask}/{dstIP}/{dstMask}/{protocol}")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(Statistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public Statistics getSubnetStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("srcIP") String srcIP,
        @PathParam("srcMask") String srcMask,
        @PathParam("dstIP") String dstIP,
        @PathParam("dstMask") String dstMask,
        @PathParam("protocol") String protocol) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        String srcString = srcIP + "/" + srcMask;
        String dstString = dstIP + "/" + dstMask;
        // TODO: This is hardly the most elegant way to handle null prefixes
        if (srcString.equals("null/null"))
            srcString = null;
        if (dstString.equals("null/null"))
            dstString = null;
        long byteCount = analyticsManager.getByteCount(srcString, dstString, IPProtocols.getProtocolNumberByte(protocol));
        long packetCount = analyticsManager.getByteCount(srcString, dstString, IPProtocols.getProtocolNumberByte(protocol));
        double duration = analyticsManager.getDuration(srcString, dstString, IPProtocols.getProtocolNumberByte(protocol));
        double bitRate = analyticsManager.getBitRate(srcString, dstString, IPProtocols.getProtocolNumberByte(protocol));

        return new Statistics(byteCount, packetCount, duration, bitRate);
    }

    /**
     * @param containerName: Name of the Container
     * @param srcIP: Source IP prefix
     * @param srcMask: Source mask
     * @param dstIP: Destination IP prefix
     * @param dstMask: Destination mask
     * @return Statistics between subnets for a particular protocol
     */
    @Path("/{containerName}/subnetstats/{srcIP}/{srcMask}/{dstIP}/{dstMask}/all")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(AllStatistics.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public AllStatistics getAllSubnetStatistics(
        @PathParam("containerName") String containerName,
        @PathParam("srcIP") String srcIP,
        @PathParam("srcMask") String srcMask,
        @PathParam("dstIP") String dstIP,
        @PathParam("dstMask") String dstMask) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        String srcString = srcIP + "/" + srcMask;
        String dstString = dstIP + "/" + dstMask;
        // TODO: This is hardly the most elegant way to handle null prefixes
        if (srcString.equals("null/null"))
            srcString = null;
        if (dstString.equals("null/null"))
            dstString = null;

        Map<Byte, Long> byteCounts = analyticsManager.getAllByteCounts(srcString, dstString);
        Map<Byte, Long> packetCounts = analyticsManager.getAllPacketCounts(srcString, dstString);
        Map<Byte, Double> durations = analyticsManager.getAllDurations(srcString, dstString);
        Map<Byte, Double> bitRates = analyticsManager.getAllBitRates(srcString, dstString);
        return new AllStatistics(byteCounts, packetCounts, durations, bitRates);
    }

    /**
     * @param containerName: Name of the Container
     * @param ip: IP prefix
     * @param mask: Mask
     * @return Hosts that sent data into this subnet
     */
    @Path("/{containerName}/subnetstats/incoming/{ip}/{mask}/")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(IncomingHostData.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public IncomingHostData getIncomingHostByteCounts(
        @PathParam("containerName") String containerName,
        @PathParam("ip") String ip,
        @PathParam("mask") String mask) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        Map<Host, Long> hosts = analyticsManager.getIncomingHostByteCounts(ip + "/" + mask);
        return new IncomingHostData(hosts);
    }

    /**
     * @param containerName: Name of the Container
     * @param ip: IP prefix
     * @param mask: Mask
     * @param protocol: IP protocol
     * @return Hosts that sent data into this subnet using this protocol
     */
    @Path("/{containerName}/subnetstats/incoming/{ip}/{mask}/{protocol}")
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @TypeHint(IncomingHostData.class)
    @StatusCodes({
        @ResponseCode(code = 200, condition = "Operation successful"),
        @ResponseCode(code = 404, condition = "The containerName is not found"),
        @ResponseCode(code = 503, condition = "One or more of Controller Services are unavailable") })
    public IncomingHostData getIncomingHostByteCounts(
        @PathParam("containerName") String containerName,
        @PathParam("ip") String ip,
        @PathParam("mask") String mask,
        @PathParam("protocol") String protocol) {
        if (!NorthboundUtils.isAuthorized(getUserName(), containerName, Privilege.READ, this))
            throw new UnauthorizedException("User is not authorized to perform this operation on container " + containerName);
        handleDefaultDisabled(containerName);

        IAnalyticsManager analyticsManager = getAnalyticsService(containerName);
        if (analyticsManager == null)
            throw new ServiceUnavailableException("Analytics " + RestMessages.SERVICEUNAVAILABLE.toString());

        Map<Host, Long> hosts = analyticsManager.getIncomingHostByteCounts(ip + "/" + mask, IPProtocols.getProtocolNumberByte(protocol));
        return new IncomingHostData(hosts);
    }

    private void handleDefaultDisabled(String containerName) {
        IContainerManager containerManager = (IContainerManager) ServiceHelper.getGlobalInstance(IContainerManager.class, this);
        if (containerManager == null)
            throw new InternalServerErrorException(RestMessages.INTERNALERROR.toString());
        if (containerName.equals(GlobalConstants.DEFAULT.toString()) && containerManager.hasNonDefaultContainer())
            throw new ResourceConflictException(RestMessages.DEFAULTDISABLED.toString());
    }

    private AffinityLink handleAffinityLinkAvailability(String containerName, String linkName) {
        IAffinityManager affinityManager = (IAffinityManager) ServiceHelper.getInstance(IAffinityManager.class, containerName, this);
        if (affinityManager == null)
            throw new ServiceUnavailableException("Affinity manager " + RestMessages.SERVICEUNAVAILABLE.toString());
        AffinityLink al = affinityManager.getAffinityLink(linkName);
        if (al == null)
            throw new ResourceNotFoundException(linkName + " : AffinityLink does not exist");
        return al;
    }


    private Host handleHostAvailability(String containerName, String networkAddr) {
        IfIptoHost hostTracker = (IfIptoHost) ServiceHelper.getInstance(IfIptoHost.class, containerName, this);
        if (hostTracker == null)
            throw new ServiceUnavailableException("Host tracker " + RestMessages.SERVICEUNAVAILABLE.toString());

        Set<HostNodeConnector> allHosts = hostTracker.getAllHosts();
        if (allHosts == null)
            throw new ResourceNotFoundException(networkAddr + " : " + RestMessages.NOHOST.toString());

        Host host = null;
        try {
            InetAddress networkAddress = InetAddress.getByName(networkAddr);
            for (Host h : allHosts) {
                if (h.getNetworkAddress().equals(networkAddress)) {
                    host = h;
                    break;
                }
            }
        } catch (UnknownHostException e) {
        }

        if (host == null)
            throw new ResourceNotFoundException(networkAddr + " : " + RestMessages.NOHOST.toString());
        return host;
    }
}
