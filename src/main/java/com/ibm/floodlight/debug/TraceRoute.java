package com.ibm.floodlight.debug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.topology.NodePortTuple;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.factory.BasicFactory;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.floodlight.debug.web.TraceRouteWebRoutable;

public class TraceRoute implements IFloodlightModule, IOFMessageListener,
		IOFSwitchListener, ITraceRouteService {

	//Hardware switches don't seem to support TABLE action
	//If TESTBED == 0, use TABLE action for each hop 
	//Else only use TABLE action for the first switch (hopefully vSwitch)
	protected static final boolean TESTBED = true;

	protected static final short IDLE_TIMEOUT = 0;					//infinite
	protected static final short HARD_TIMEOUT = 0;					//infinite
	protected static final int MAX_PRIORITY = 32767;
	
	//Using VLAN PCP bits for tagging debug packets
	//All non-debug packets should set VLAN-PCP bits as zero
	
	protected static Logger log = LoggerFactory.getLogger(TraceRoute.class);

	//map of (switchId : switchColor)
	private Map<Long, Integer> topoColor;	
	private Boolean isConfigured;	
	private Long lastPacketInTime;
	
	//trace route output is stored here
	protected List<NodePortTuple> traceOut;
	
	//service dependencies
	protected IFloodlightProviderService floodlightProvider;	
	protected IDeviceService deviceManager;
	protected ILinkDiscoveryService LinkManager;
	protected IRestApiService restApi;
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
	    Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
	    l.add(ITraceRouteService.class);
	    return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
	    Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
	    m.put(ITraceRouteService.class, this);
	    return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
		l.add(IDeviceService.class);
		l.add(ILinkDiscoveryService.class);
		l.add(IRestApiService.class);
        return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		deviceManager = context.getServiceImpl(IDeviceService.class);	 
		LinkManager = context.getServiceImpl(ILinkDiscoveryService.class);	
		restApi = context.getServiceImpl(IRestApiService.class);
        topoColor = new HashMap<Long, Integer>();
        isConfigured = false;
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this); 
		floodlightProvider.addOFSwitchListener(this);
		//Should we implement ILinkDiscoveryListener? LinkManager.addListener(this);
		restApi.addRestletRoutable(new TraceRouteWebRoutable());
	}
	
	
	//IOFSwitchListener
	@Override
	public void addedSwitch(IOFSwitch sw) {
		isConfigured = false;
	}

	@Override
	public void removedSwitch(IOFSwitch sw) {
		isConfigured = false;
	}

	@Override
	public void switchPortChanged(Long switchId) {
		isConfigured = false;
	}
		
		
	//IOFMessageListener
	@Override
	public String getName() {
		return "TraceRoute";
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		//lets run it before everything (we were not seeing packet_in)
		return type.equals(OFType.PACKET_IN) && name.equals("linkdiscovery");
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {

		// Parse the received packet		
		OFPacketIn pi = (OFPacketIn) msg;		
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
        
        //We only care about Ethernet packets with VLAN PCP bits non-zero
        if (match.getDataLayerVirtualLanPriorityCodePoint() == 0) {
     			// Allow the next module to process this OpenFlow message
     		    return Command.CONTINUE;
     	} else {
     		//log last packet-in time
     		lastPacketInTime = System.currentTimeMillis();

     		//log (switch dpid, port) based on the packet-in
     		log.debug("Trace-route packet-in received: Updating path: {}, {}", sw.getId(), pi.getInPort());
     		traceOut.add(new NodePortTuple(sw.getId(), pi.getInPort()));
     		 		
     		//Send packet_out with input port, VLAN PCP bit for the switch, and action TABLE
     		Ethernet eth = new Ethernet();
            eth.deserialize(pi.getPacketData(), 0, pi.getPacketData().length);

     		int switchColor = topoColor.get(sw.getId());   
     		
     		if (!TESTBED) {
     			//send packet_out to the same switch with TABLE action
     			SendPacketOut (eth, switchColor, sw, pi.getInPort(), OFPort.OFPP_TABLE.getValue());    
     		} else {
     			//send packet_out to the previous switch with action set to known output port
     			NodePortTuple prev = traceOut.get(traceOut.size()-2);
     			IOFSwitch prevSw = floodlightProvider.getSwitches().get(prev.getNodeId());
     			short actionPort = 0;

     			for (Link lnk : LinkManager.getPortLinks().get(new NodePortTuple(sw.getId(), pi.getInPort()))) {
     				if (lnk.getSrc() == prev.getNodeId()) actionPort = lnk.getSrcPort();
     				if (lnk.getDst() == prev.getNodeId()) actionPort = lnk.getDstPort();    			
     				SendPacketOut (eth, switchColor, prevSw, prev.getPortId(), actionPort);    
     				break;
     			}
     		}     		
     		return Command.STOP;    		
     	}
	}
	
	
	//Send packet_out to switch with input port, VLAN PCP bits set for switchColor and action set to output port
	private void SendPacketOut (Ethernet eth, int switchColor, IOFSwitch sw, short port, short actionPort) {
 		OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory()
        		.getMessage(OFType.PACKET_OUT);        

 		// Update the inputPort 
 		po.setInPort(port);
        
 		// Set the actions to apply for this packet	
 		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(new OFActionOutput(actionPort));
 		po.setActions(actions);
 		po.setActionsLength((short)OFActionOutput.MINIMUM_LENGTH);
 		
 		//modify VLAN-PCP bits based on switch color
 		byte pcp = (byte)switchColor;
 		eth.setPriorityCode(pcp);
 		
 		byte[] PacketData = eth.serialize();   		
 		po.setLength(U16.t(OFPacketOut.MINIMUM_LENGTH
                + po.getActionsLength() + PacketData.length));
        po.setPacketData(PacketData);

		try {
			sw.write(po, null);
			sw.flush();
		} catch (IOException e) {
			log.error("Failure sending packet out to switch {}", sw.getId());
		}	
	}
	
	
	//assign a color to each switch: two adjacent switches should get different colors
	private void assignColor() {
		//track the max color (quit if more than 8)
		int max = 0;
		//greedy coloring. Initialize switch colors
		for (Long sw : floodlightProvider.getSwitches().keySet()) {
			topoColor.put(sw, -1);
		}

		for (Long sw : floodlightProvider.getSwitches().keySet()) {
			//Find adjacent switch colors
			Set<Integer> adjColors = new HashSet<Integer>();
			for (Link lnk : LinkManager.getSwitchLinks().get(sw)) {
				if ((lnk.getSrc() == sw) && (lnk.getDst() != sw) && (topoColor.get(lnk.getDst()) != -1)) 
					adjColors.add(topoColor.get(lnk.getDst()));
				if ((lnk.getDst() == sw) && (lnk.getSrc() != sw) && (topoColor.get(lnk.getSrc()) != -1)) 
					adjColors.add(topoColor.get(lnk.getSrc()));
			}
			int i = 1;
			while (adjColors.contains(i)) i++;
			topoColor.put(sw, i);
			if (i >= max) max = i;
		}
		if (max > 8) {
			log.debug("Coloring is using more than 8 colors. Exiting....");
			System.exit(0);
		}
	}
	
	
	//Install highest priority rules in each switch based on the graph coloring
	//Currently using VLAN PCP (3 bits) for tagging
	private void installTraceRouteRules() {
		for (Long sw : floodlightProvider.getSwitches().keySet()) {
			IOFSwitch ofswitch = floodlightProvider.getSwitches().get(sw);
			
			//Find switch's own color
			int switchColor = topoColor.get(sw);

			//Find adjacent switch colors
			Set<Integer> adjColors = new HashSet<Integer>();
			for (Link lnk : LinkManager.getSwitchLinks().get(sw)) {
				if ((lnk.getSrc() == sw) && (lnk.getDst() != sw)) 
					adjColors.add(topoColor.get(lnk.getDst()));
				if ((lnk.getDst() == sw) && (lnk.getSrc() != sw)) 
					adjColors.add(topoColor.get(lnk.getSrc()));
			}

			//Make sure there is no rule in the switch for its own color: DELETE FLOW_MOD
			BasicFactory ofMessageFactory = new BasicFactory();
			OFFlowMod fm = (OFFlowMod) ofMessageFactory.getMessage(OFType.FLOW_MOD);
			OFMatch match= new OFMatch();
			match.setWildcards(OFMatch.OFPFW_ALL
					& (~OFMatch.OFPFW_DL_VLAN_PCP));

			//Configure delete packet and send it to the switch	        
			match.setDataLayerVirtualLanPriorityCodePoint((byte)switchColor);		        

			fm.setCommand(OFFlowMod.OFPFC_DELETE)
			.setOutPort(OFPort.OFPP_NONE.getValue())			//used for DELETE
			.setMatch(match)
			.setLengthU(OFFlowMod.MINIMUM_LENGTH);

			try {
				ofswitch.write(fm, null);
				ofswitch.flush();
			} catch (IOException e) {
				log.error("Failure sending flow mod remove message to switch {}", ofswitch.getId());
			}
			
			//Install rules for adjacent colors	
			List<OFAction> actions = new ArrayList<OFAction>();
			actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue()));

			for (int i : adjColors) {
				match.setDataLayerVirtualLanPriorityCodePoint((byte)i);	

				fm.setIdleTimeout(IDLE_TIMEOUT)
				.setHardTimeout(HARD_TIMEOUT)
				.setBufferId(OFPacketOut.BUFFER_ID_NONE)
				.setCommand(OFFlowMod.OFPFC_ADD)
				.setPriority((short)MAX_PRIORITY)
				.setMatch(match)
				.setActions(actions)
				.setLengthU(OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH);

				try {
					ofswitch.write(fm, null);
					ofswitch.flush();
				} catch (IOException e) {
					log.error("Failure sending flow mod add message to switch {}", ofswitch.getId());
				}			

			}

		}
	}
	
	
	@Override	
	public synchronized List<NodePortTuple> traceRoute(Ethernet eth) {
		//Only handle Ethernet packet with VLAN tag		
		if (eth.getVlanID() == Ethernet.VLAN_UNTAGGED) {
			throw new IllegalArgumentException("Debug packet must be tagged with VLAN");
		}		
										
		//Check if configuration is up-to-date
		if (!isConfigured) {
			assignColor();
			installTraceRouteRules();
			isConfigured = true;			
		}
				
		traceOut = new ArrayList<NodePortTuple>();
		
		//Configure Ethernet packet and send it out as packet out to the first switch
		Long srcMAC = Ethernet.toLong(eth.getSourceMACAddress());			
		Iterator<? extends IDevice> dvcs = deviceManager.queryDevices(srcMAC, null, null, null, null);
    	if (dvcs == null) {
    		log.debug("Controller has not learnt the device (source MAC). Exiting.....");
    		System.exit(0);
    	}
		
    	lastPacketInTime = System.currentTimeMillis();
    	
		for (IDevice dvc; dvcs.hasNext();) {
    		dvc = dvcs.next();
            SwitchPort[] ps = dvc.getAttachmentPoints();
            for (SwitchPort sp : ps) {
            	IOFSwitch sw = floodlightProvider.getSwitches().get(sp.getSwitchDPID());
            	short port = (short) sp.getPort();
            	traceOut.add(new NodePortTuple(sp.getSwitchDPID(), port));            	
           		int switchColor = topoColor.get(sw.getId()); 
           		lastPacketInTime = System.currentTimeMillis();
         		SendPacketOut(eth, switchColor, sw, port, OFPort.OFPP_TABLE.getValue());    	         	
            }
    	}
    	
    	//if no packet-in activity in the last 1s, then call it quits
    	long deltaTime;
    	/*
    	try {
    		Thread.sleep(100000);
    	} catch (InterruptedException e) {};
    	*/
    	do {
    		try {
    			Thread.sleep(1000);
    		} catch (InterruptedException e) {};
    		deltaTime = System.currentTimeMillis() - lastPacketInTime;
    	} while (deltaTime < 1000);
		
    	//if configuration changed during trace route then repeat
		if (!isConfigured) {
			traceRoute(eth);
		}
		return traceOut;
	}
}
