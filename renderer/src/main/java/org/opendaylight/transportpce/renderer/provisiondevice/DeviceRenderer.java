/*
 * Copyright © 2017 AT&T and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.transportpce.renderer.provisiondevice;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.MountPointService;
import org.opendaylight.transportpce.renderer.mapping.PortMapping;
import org.opendaylight.transportpce.renderer.openroadminterface.OpenRoadmInterfaces;
import org.opendaylight.transportpce.renderer.openroadminterface.OpenRoadmOchInterface;
import org.opendaylight.transportpce.renderer.openroadminterface.OpenRoadmXponderInterface;
import org.opendaylight.yang.gen.v1.http.org.openroadm.optical.channel.interfaces.rev161014.OchAttributes.ModulationFormat;
import org.opendaylight.yang.gen.v1.http.org.openroadm.optical.channel.interfaces.rev161014.R100G;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.renderer.rev170228.RendererService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.renderer.rev170228.ServicePathInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.renderer.rev170228.ServicePathOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.renderer.rev170228.ServicePathOutputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.renderer.rev170228.service.path.input.Nodes;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeviceRenderer implements RendererService {

    private final DataBroker db;
    private final MountPointService mps;
    private static final Logger LOG = LoggerFactory.getLogger(RendererService.class);
    private final Set<String> currentMountedDevice;
    private final Set<String> nodesProvisioned;

    public DeviceRenderer(DataBroker db, MountPointService mps, Set<String> currentMountedDevice) {
        this.db = db;
        this.mps = mps;
        this.currentMountedDevice = currentMountedDevice;
        this.nodesProvisioned = new HashSet<>();
    }

    /**
     * This method is the implementation of the 'service-path' RESTCONF service,
     * which is one of the external APIs into the renderer application. The service
     * provides two functions:
     *
     * <p>
     * 1. Create This operation results in provisioning the device for a given
     * wavelength and a list of nodes with each node listing its termination points.
     *
     * <p>
     * 2. Delete This operation results in de-provisioning the device for a given
     * wavelength and a list of nodes with each node listing its termination points.
     *
     * <p>
     * The signature for this method was generated by yang tools from the renderer
     * API model.
     *
     * @param input
     *            Input parameter from the service-path yang model
     *
     * @return Result of the request
     */
    @Override
    public Future<RpcResult<ServicePathOutput>> servicePath(ServicePathInput input) {

        if (input.getOperation().getIntValue() == 1) {
            LOG.info("Create operation request received");
            return RpcResultBuilder.success(setupServicePath(input)).buildFuture();
        } else if (input.getOperation().getIntValue() == 2) {
            LOG.info("Delete operation request received");
            return RpcResultBuilder.success(deleteServicePath(input)).buildFuture();
        }
        return RpcResultBuilder.success(new ServicePathOutputBuilder().setResult("Invalid operation")).buildFuture();
    }

    /**
     * This method set's wavelength path based on following steps: For each node:
     *
     * <p>
     * 1. Create Och interface on source termination point. 2. Create Och interface
     * on destination termination point. 3. Create cross connect between source and
     * destination tps created in step 1 and 2.
     *
     * <p>
     * Naming convention used for OCH interfaces name : tp-wavenumber Naming
     * convention used for cross connect name : src-dest-wavenumber
     *
     * @param input
     *            Input parameter from the service-path yang model
     *
     * @return Result list of all nodes if request successful otherwise specific
     *         reason of failure.
     */
    public ServicePathOutputBuilder setupServicePath(ServicePathInput input) {

        String serviceName = input.getServiceName();
        List<Nodes> nodes = input.getNodes();
        ServicePathOutputBuilder setServBldr = new ServicePathOutputBuilder();
        LOG.info(currentMountedDevice.toString());
        int crossConnectFlag;
        for (Nodes n : nodes) {
            LOG.info("Starting provisioning for node : " + n.getNodeId());
            crossConnectFlag = 0;
            String nodeId = n.getNodeId();
            // if the node is currently mounted then proceed
            if (currentMountedDevice.contains(n.getNodeId())) {
                String srcTp = n.getSrcTp();
                String destTp = n.getDestTp();

                Long waveNumber = input.getWaveNumber();
                String mf = input.getModulationFormat();
                if (destTp.contains("LINE")) {
                    crossConnectFlag++;

                    ModulationFormat modulationFormat = null;
                    for (int i = 0; i < ModulationFormat.values().length; i++) {
                        ModulationFormat smodulationFormat = ModulationFormat.forValue(i);
                        if (smodulationFormat.getName().equals(mf)) {
                            modulationFormat = smodulationFormat;
                        }
                    }
                    try {
                        LOG.info("Modulation Format {} configured exists.", modulationFormat.getName());
                    } catch (NullPointerException e) {
                        LOG.error(mf + " modulation format does not exist.");
                    }

                    if (!new OpenRoadmXponderInterface(db, mps, nodeId, destTp, serviceName)
                            .createLineInterfaces(waveNumber, R100G.class, modulationFormat)) {

                        return setServBldr.setResult("Unable to LINE interface on " + nodeId + " at " + destTp);
                    }
                    LOG.info("LINE interface created for node " + nodeId);
                }
                if (srcTp.contains("CLNT")) {
                    crossConnectFlag++;
                    if (!new OpenRoadmXponderInterface(db, mps, nodeId, srcTp, serviceName).createClientInterfaces()) {
                        return setServBldr.setResult("Unable to Client interface on " + nodeId + " at " + srcTp);
                    }
                }
                String srcIf;
                String dstIf;
                if (srcTp.contains("TTP") || srcTp.contains("PP")) {
                    srcIf = new OpenRoadmOchInterface(db, mps, nodeId, srcTp, serviceName).createInterface(waveNumber);
                    // if source interface creation was successful then proceed
                    // otherwise return.
                    if (srcIf == null) {
                        LOG.warn("Unable to create OCH interface on " + nodeId + " at " + srcTp);
                        return setServBldr.setResult("Unable to create OCH interface on " + nodeId + " at " + srcTp);
                    }
                }
                if (destTp.contains("TTP") || destTp.contains("PP")) {
                    dstIf = new OpenRoadmOchInterface(db, mps, nodeId, destTp, serviceName).createInterface(waveNumber);
                    // if destination interface creation was successful then proceed
                    // otherwise return.
                    if (dstIf == null) {
                        LOG.warn("Unable to create OCH interface on " + nodeId + " at " + destTp);
                        return setServBldr.setResult("Unable to create OCH interface on " + nodeId + " at " + destTp);
                    }
                }
                if (crossConnectFlag < 1) {
                    LOG.info("Creating cross connect between source :" + srcTp + " destination " + destTp + " for node "
                            + n.getNodeId());
                    DataBroker netconfNodeDataBroker = PortMapping.getDeviceDataBroker(nodeId, mps);
                    String crossConnectName = srcTp + "-" + destTp + "-" + waveNumber;
                    CrossConnect roadmConnections = new CrossConnect(netconfNodeDataBroker);
                    if (roadmConnections.postCrossConnect(waveNumber, srcTp, destTp)) {
                        nodesProvisioned.add(nodeId);
                        roadmConnections.getConnectionPortTrail(nodeId, mps, waveNumber, srcTp, destTp);
                    } else {
                        return setServBldr.setResult("Unable to post Roadm-connection for node " + nodeId);
                    }
                }
            } else {
                LOG.warn(nodeId + " is not mounted on the controller");
                return setServBldr.setResult(nodeId + " is not mounted on the controller");
            }
        }
        return setServBldr.setResult("Roadm-connection successfully created for nodes " + nodesProvisioned.toString());
    }

    /**
     * This method removes wavelength path based on following steps: For each node:
     *
     * <p>
     * 1. Delete Cross connect between source and destination tps. 2. Delete Och
     * interface on source termination point. 3. Delete Och interface on destination
     * termination point.
     *
     * <p>
     * Naming convention used for OCH interfaces name : tp-wavenumber Naming
     * convention used for cross connect name : src-dest-wavenumber
     *
     * @param input
     *            Input parameter from the service-path yang model
     *
     * @return Result result of the request.
     */
    public ServicePathOutputBuilder deleteServicePath(ServicePathInput input) {
        List<Nodes> nodes = input.getNodes();
        ServicePathOutputBuilder delServBldr = new ServicePathOutputBuilder();
        LOG.info(currentMountedDevice.toString());
        for (Nodes n : nodes) {

            String nodeId = n.getNodeId();
            LOG.info("Deleting service setup on node " + nodeId);
            String srcTp = n.getSrcTp();
            String destTp = n.getDestTp();
            Long waveNumber = input.getWaveNumber();

            // if the node is currently mounted then proceed.
            if (currentMountedDevice.contains(nodeId)) {

                if (destTp.contains("LINE")) {
                    if (new OpenRoadmInterfaces(db, mps, nodeId, destTp)
                            .deleteInterface(destTp + "-ODU") == false) {
                        LOG.error("Failed to delete interface " + destTp + "-ODU");
                    }
                    if (new OpenRoadmInterfaces(db, mps, nodeId, destTp)
                            .deleteInterface(destTp + "-OTU") == false) {
                        LOG.error("Failed to delete interface " + destTp + "-OTU");
                    }
                    if (new OpenRoadmInterfaces(db, mps, nodeId, destTp)
                            .deleteInterface(destTp + "-" + waveNumber) == false) {
                        LOG.error("Failed to delete interface " + destTp + "-" + waveNumber);
                    }
                }
                if (srcTp.contains("CLNT")) {
                    // Deleting interface on source termination point
                    if (new OpenRoadmInterfaces(db, mps, nodeId, srcTp)
                            .deleteInterface(srcTp + "-ETHERNET") == false) {
                        LOG.error("Failed to delete Ethernet interface  on " + srcTp);
                    }
                    continue;
                }
                String connectionNumber = srcTp + "-" + destTp + "-" + waveNumber;
                CrossConnect roadmConnection = new CrossConnect(PortMapping.getDeviceDataBroker(nodeId, mps),
                        connectionNumber);
                if (!roadmConnection.deleteCrossConnect()) {
                    LOG.error("Failed to delete {} ", srcTp + "-" + destTp + "-" + waveNumber);
                }
                // Deleting interface on source termination point
                if (!new OpenRoadmInterfaces(db, mps, nodeId, srcTp)
                        .deleteInterface(srcTp + "-" + waveNumber.toString())) {
                    LOG.error("Failed to delete interface " + srcTp + "-" + waveNumber.toString());
                }

                // Deleting interface on destination termination point
                if (!new OpenRoadmInterfaces(db, mps, nodeId, destTp)
                        .deleteInterface(destTp + "-" + waveNumber.toString())) {
                    LOG.error("Failed to delete interface " + destTp + "-" + waveNumber.toString());
                }
            } else {
                LOG.warn(nodeId + " is not mounted on the controller");
                return delServBldr.setResult(nodeId + " is not mounted on the controller");
            }
        }
        return delServBldr.setResult("Request processed");
    }
}
