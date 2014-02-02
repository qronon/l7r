package org.qrone.l7r;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.spdy.*;
import org.eclipse.jetty.spdy.api.*;
import org.eclipse.jetty.spdy.api.server.*;
import org.eclipse.jetty.spdy.client.*;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.spdy.server.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class L7RClient {
	private static Logger log = LoggerFactory.getLogger(L7RClient.class);


	private void startClient() throws Exception {
		
		// Start a SPDYClient factory shared among all SPDYClient instances
		SPDYClient.Factory clientFactory = new SPDYClient.Factory();
		clientFactory.start();
		 
		// Create one SPDYClient instance
		SPDYClient client = clientFactory.newSPDYClient(SPDY.V3);
		
		SessionFrameListener listener = new SessionFrameListener.Adapter() {
			
			@Override
			public StreamFrameListener onSyn(final Stream stream, SynInfo arg1) {
				// receive proxied Browser HTTP Request -> L7RServer -> L7RClient
		        
		        try {
			        log.debug("onSyn:" + arg1.getHeaders().get("url").toString());
					stream.reply(new ReplyInfo(false));
					
			        log.debug("onData:");
					stream.data(new StringDataInfo("Echo for L7R", true));
					
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (TimeoutException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		        
				return new StreamFrameListener.Adapter()
	            {
					// create HTTP Response.
					// TODO support webdav or proxy again.
	                public void onData(Stream stream, DataInfo dataInfo)
	                {
	    		        log.debug("onData:");
	    		        
	                    String clientData = dataInfo.asString("UTF-8", true);
	                    try {
							stream.data(new StringDataInfo("Echo2 for L7R" + clientData, true));
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (ExecutionException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (TimeoutException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
	                }
	            };
			}
		};
		
		 
		Session session = client.connect(new InetSocketAddress("localhost", 8443), listener);
		 
		// Sends SYN_STREAM and regist to L7R Server
		Fields headers = new Fields();
		headers.put("url", "/l7r-system/regist");
		Stream stream = session.syn(new SynInfo(headers, false), null);
		stream.data(new StringDataInfo("Hello, World", true));
		
        log.debug("startClient Request");
		
	}
	
	
	public static void main(String[] arg){
		try {
			new L7RClient().startClient();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
