package com.ibm.floodlight.debug.web;

import java.io.IOException;
import java.util.List;

import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.topology.NodePortTuple;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.openflow.util.HexString;
//import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.floodlight.debug.ITraceRouteService;


public class TraceRouteTraceResource extends ServerResource {

	protected static Logger log = LoggerFactory.getLogger(TraceRouteTraceResource.class);
	Ethernet debugPacket;
	

	//@Get("json")
	@Post
	public List<NodePortTuple> trace (String packetJson) {
		List<NodePortTuple> trace = null;
		try {
			parse(packetJson);
			log.debug("Starting trace route");
			log.debug("Ethernet: src {}, dst {}", HexString.toHexString(debugPacket.getSourceMACAddress()),
					HexString.toHexString(debugPacket.getDestinationMACAddress()));
			log.debug("Ethernet: VLAN-ID {}, VLAN-PCP {}", debugPacket.getVlanID(),
					debugPacket.getPriorityCode());
			log.debug("IPv4: src {}, dst {}", 
					IPv4.fromIPv4Address(((IPv4)debugPacket.getPayload()).getSourceAddress()),
					IPv4.fromIPv4Address(((IPv4)debugPacket.getPayload()).getDestinationAddress()));
							
			ITraceRouteService tr = (ITraceRouteService)getContext().getAttributes().get(ITraceRouteService.class.getCanonicalName());
			trace = tr.traceRoute(debugPacket);					
		} catch (IOException e) {
			log.error("Input  JSON error in route install: " + packetJson, e);
		}	
        if (trace!=null) {
            return trace;
        }
        else {
            log.debug("ERROR! no trace route found");
            return null;
        }
	}
		
	
	public void parse (String packetJson) throws IOException {
	    /* Parse JSON formatted string with format 
	       { 
	       		"ethSrc"  	  : "AA:BB:CC:DD:EE:FF",
	        	"ethDst"  	  : "AA:BB:CC:DD:EE:FF",
	     		"vlanID"  	  : "short",
	     		"ipSrc"	  	  : "10.10.10.10",
	     		"ipDst"   	  : "10.10.10.10",
	     		"tcpSrcPort"  : "short",
	     		"tcpDstPort"  : "short"	     	 		 
		   }
	    */

		byte[] ethSrc = {0,0,0,0,0,0}, ethDst = {0,0,0,0,0,0};
		short vlanID = 0;
		int ipSrc = 0, ipDst = 0;
		short tcpSrcPort = 0, tcpDstPort = 0;
			
		MappingJsonFactory f = new MappingJsonFactory();
        JsonParser jp;
        
        try {
        	jp = f.createJsonParser(packetJson);
        } catch (JsonParseException e) {
        	throw new IOException(e);
        }	
        
        jp.nextToken();
        if (jp.getCurrentToken() != JsonToken.START_OBJECT) {
        	throw new IOException("Expected START_OBJECT in JSON");
		}	        	    		
        while (jp.nextToken() != JsonToken.END_OBJECT) {
        	if (jp.getCurrentToken() != JsonToken.FIELD_NAME) {
        		throw new IOException("Expected FIELD_NAME in JSON");
			}        
			String n = jp.getCurrentName();
			jp.nextToken();      
			
			if ((n == "ethSrc") && (Ethernet.isMACAddress(jp.getText()))) {
				ethSrc = Ethernet.toMACAddress(jp.getText());
			}
			if ((n == "ethDst") && (Ethernet.isMACAddress(jp.getText()))) {
				ethDst = Ethernet.toMACAddress(jp.getText());
			}
						
			if ((n == "vlanID") && (jp.getCurrentToken() == JsonToken.VALUE_NUMBER_INT)) {
				vlanID = jp.getShortValue();
			}
			
			if (n == "ipSrc") {
				ipSrc = IPv4.toIPv4Address(jp.getText());
			}
			
			if (n == "ipDst") {
				ipDst = IPv4.toIPv4Address(jp.getText());
			}			
			
			if ((n == "tcpSrcPort") && (jp.getCurrentToken() == JsonToken.VALUE_NUMBER_INT)) {
				tcpSrcPort = jp.getShortValue();
			}
			
			if ((n == "tcpDstPort") && (jp.getCurrentToken() == JsonToken.VALUE_NUMBER_INT)) {
				tcpDstPort = jp.getShortValue();
			}
        }
		
        //Construct a debug (Ethernet-IPv4-TCP) packet (Default VLAN tagging; Note VLAN_UNTAGGED is 0xffff)
        debugPacket = new Ethernet();
        
        debugPacket.setDestinationMACAddress(ethDst)
        .setSourceMACAddress(ethSrc)
        .setVlanID(vlanID)
        .setPriorityCode((byte)0)
        .setEtherType(Ethernet.TYPE_IPv4)
        .setPayload(
        		new IPv4()
        		.setTtl((byte) 128)
        		.setSourceAddress(ipSrc)
        		.setDestinationAddress(ipDst)
        		.setProtocol(IPv4.PROTOCOL_TCP)
        		.setPayload(
        				new TCP()
        				.setSourcePort(tcpSrcPort)
        				.setDestinationPort(tcpDstPort)
        				.setPayload(new Data(new byte[] {0x01}))));				        
	}	
 }
