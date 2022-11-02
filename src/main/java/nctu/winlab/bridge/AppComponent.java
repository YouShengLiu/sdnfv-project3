/*
 * Copyright 2022-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.bridge;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;

import org.onosproject.core.CoreService;
import org.onosproject.core.ApplicationId;

import org.onosproject.cfg.ComponentConfigService;

import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger("LearningBridge");

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    /* For registering the application */
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    /* For handling the packet */
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    /* For installing the flow rule */
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    /* Variables */
    private ApplicationId appId;
    private MyPacketProcessor processor = new MyPacketProcessor();
    private Map<DeviceId, Map<MacAddress, PortNumber>> forwardTable = new HashMap<>();
    private int flowPriority = 30; // Default value
    private int flowTimeout = 30;  // Default value

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.bridge");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestPacket();

        log.info("Started {}", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        cancelRequestPacket();

        log.info("Stopped");
    }

    /* Request packet */
    private void requestPacket() {
        // Request for IPv4 packets
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();

        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.LOWEST, appId);
    }

    /* Cancel request packet */
    private void cancelRequestPacket() {
        // Cancel the request for IPv4 packets
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();

        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.LOWEST, appId);
    }

    /* Add entry to table */
    private void addTable(ConnectPoint cp, MacAddress macAddr) {
        DeviceId switchID = cp.deviceId();
        Map<MacAddress, PortNumber> switchTable = forwardTable.getOrDefault(switchID, new HashMap<>());

        if (switchTable.get(macAddr) == null) {
            // Update Table
            switchTable.put(macAddr, cp.port());
            forwardTable.put(switchID, switchTable);

            log.info("Add an entry to the port table of `{}`. MAC address: `{}` => Port: `{}`.",
                    switchID, macAddr, cp.port());
            // log.info("Table: {}", forwardTable);
        }
    }

    /* Lookup the forwarding table */
    private PortNumber lookupTable(DeviceId switchID, MacAddress macAddr) {
        return forwardTable.get(switchID).get(macAddr);
    }


    /* Send out the packet from the specified port */
    private void packetout(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    /* Broadcast the packet */
    private void flood(PacketContext context) {
        packetout(context, PortNumber.FLOOD);
    }

    /* Install Flow Rule */
    private void installRule(PacketContext context, PortNumber dstPortNumber) {
        InboundPacket packet = context.inPacket();
        Ethernet ethPacket = packet.parsed();
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        TrafficTreatment treatment = context.treatmentBuilder().setOutput(dstPortNumber).build();

        // Match Src and Dst MAC Address
        selectorBuilder.matchEthDst(ethPacket.getDestinationMAC());
        selectorBuilder.matchEthSrc(ethPacket.getSourceMAC());

        // Create Flow Rule
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())          // Build the selector
                .withTreatment(treatment)                       // Setup the treatment
                .withPriority(flowPriority)                     // Setup the priority of flow
                .withFlag(ForwardingObjective.Flag.VERSATILE)   // Matches two or more header fields.
                .fromApp(appId)                                 // Specify from which application
                .makeTemporary(flowTimeout)                     // Set timeout
                .add();                                         // Build the flow rule
        // log.info("Flow Rule {}", forwardingObjective);

        // Install the flow rule on the specified switch
        flowObjectiveService.forward(packet.receivedFrom().deviceId(), forwardingObjective);

        // After install the flow rule, use packet-out message to send packet
        packetout(context, dstPortNumber);
    }

    /* Handle the packets coming from switchs */
    private class MyPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            // log.info("MyPacketProcessor Handle Packet.");

            if (context.isHandled()) {
                // log.info("Packet has been handled, skip it...");
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPacket = pkt.parsed();

            if (ethPacket == null) {
                log.error("Packet type is not ethernet");
                return;
            }

            if (ethPacket.getEtherType() == Ethernet.TYPE_LLDP || ethPacket.getEtherType() == Ethernet.TYPE_BSN) {
                log.info("Ignore LLDP or BDDP packet");
                return;
            }

            MacAddress srcMacAddr = ethPacket.getSourceMAC();
            MacAddress dstMacAddr = ethPacket.getDestinationMAC();
            ConnectPoint cp = pkt.receivedFrom();
            DeviceId switchID = cp.deviceId();

            addTable(cp, srcMacAddr);
            PortNumber dstPort = lookupTable(switchID, dstMacAddr);

            if (dstPort == null) {
                // Table Miss, Flood it
                log.info("MAC address `{}` is missed on `{}`. Flood the packet.", dstMacAddr, switchID);
                flood(context);
            } else {
                // Table Hit, Install rule
                log.info("MAC address `{}` is matched on `{}`. Install a flow rule.", dstMacAddr, switchID);
                installRule(context, dstPort);
            }
        }
    }
}
