package com.ibm.floodlight.debug;

import java.util.List;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.topology.NodePortTuple;

public interface ITraceRouteService extends IFloodlightService {
	
	//trace route for an Ethernet packet: must carry IPv4 payload
	public List<NodePortTuple> traceRoute (Ethernet packet);

}
