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

import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.onlab.util.Tools.get;

import org.onlab.packet.Ethernet;
// import org.onlab.packet.ICMP;
// import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;

import org.onosproject.core.CoreService;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
// import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.ConnectPoint;
// import org.onosproject.net.DeviceId;
// import org.onosproject.net.Host;
// import org.onosproject.net.HostId;
// import org.onosproject.net.Link;
// import org.onosproject.net.Path;
// import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
// import org.onosproject.net.flow.DefaultTrafficTreatment;
// import org.onosproject.net.flow.FlowEntry;
// import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
// import org.onosproject.net.flow.criteria.Criterion;
// import org.onosproject.net.flow.criteria.EthCriterion;
// import org.onosproject.net.flow.instructions.Instruction;
// import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.InboundPacket;
// import org.onosproject.net.link.LinkEvent;
// import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
// import org.onosproject.net.topology.TopologyEvent;
// import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {SomeInterface.class},
           property = {
               "someProperty=Some Default String Value",
           })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */
    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    /* Variables */
    private ApplicationId appId;
    private MyPacketProcessor processor = new MyPacketProcessor();
    private Map<DeviceId, Map<MacAddress, PortNumber>> forwardTable = new HashMap<>();
    private int flowPriority = 30; // Default value
    private int flowTimeout = 30;  // Default value

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.winlab.bridge");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestPacket();

        log.info("Started {}", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
        cancelRequestPacket();

        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
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
                .withFlag(ForwardingObjective.Flag.VERSATILE)   // ??
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
