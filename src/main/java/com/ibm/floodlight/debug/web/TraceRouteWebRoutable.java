package com.ibm.floodlight.debug.web;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class TraceRouteWebRoutable implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
        router.attach("/trace/json", TraceRouteTraceResource.class);
        return router;
	}

	@Override
	public String basePath() {
		return "/wm/traceroute";
	}

}
