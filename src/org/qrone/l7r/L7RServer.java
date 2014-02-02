package org.qrone.l7r;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.spdy.*;
import org.eclipse.jetty.spdy.api.*;
import org.eclipse.jetty.spdy.api.server.*;
import org.eclipse.jetty.spdy.server.*;
import org.eclipse.jetty.spdy.server.http.HTTPSPDYServerConnectionFactory;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class L7RServer {
	private static Logger log = LoggerFactory.getLogger(L7RServer.class);

	private Map<Stream, Session> streamMap = new Hashtable<Stream, Session>();
	private Map<String, Session> sessionMap = new Hashtable<String, Session>();
	
	private void startServer() throws Exception{
		
		// Handles incoming SYN_STREAMS from Browser/L7R Client
		ServerSessionFrameListener application = new ServerSessionFrameListener.Adapter()
		{
		    public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
		    {
		        log.debug("onSyn" + synInfo.getHeaders().get("url").getValue());
		        
		        // Reply upon receiving a SYN_STREAM
		        try {
					stream.reply(new ReplyInfo(false));
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
		        
		        // Register when connected from L7R client.
		        Fields headers = synInfo.getHeaders();
		        if ("/l7r-system/regist".equals(headers.get("url").getValue()))
		        {
		        	// make MDN->Session map (currently all "1")
		        	// TODO add authentication here.
		            Session session = streamMap.get(stream);
		            sessionMap.put("1", session);
		            streamMap.remove(session);
		        }
		        return null;
		    }

			@Override
			public void onConnect(final Session session) {
				// make Stream->Session map used by registration.
				log.debug("onConnect");
				
				session.addListener(new Session.StreamListener(){
					@Override
					public void onStreamCreated(Stream arg0) {
						log.debug("onStreamCreated: " + arg0.toString());
						streamMap.put(arg0, session);
					}
					
					@Override
					public void onStreamClosed(Stream arg0) {
						log.debug("onStreamClosed: " + arg0.toString());
						streamMap.remove(arg0);
						
					}
				});
			}
		};
		

		// Wire up and start the connector
		Server server = new Server();

		HttpConfiguration tlsHttpConfiguration = new HttpConfiguration();
	    tlsHttpConfiguration.setSecureScheme("https");
	    tlsHttpConfiguration.setSecurePort(8443);
	    tlsHttpConfiguration.setSendServerVersion(true);
		
	    HttpConnectionFactory http = new HttpConnectionFactory(tlsHttpConfiguration);
	    ServerConnector connector = new ServerConnector(server, http);
	    connector.setSoLingerTime(-1);
	    connector.setPort(8080);
	    server.addConnector(connector);

	    Resource keystore = Resource.newClassPathResource("/keystore");

	    if (keystore != null && keystore.exists()) {
	    	// If Keystore exist, use NPN. 
			log.debug("keystore found");

			SPDYServerConnectionFactory.checkNPNAvailable();
			SPDYServerConnectionFactory con = new SPDYServerConnectionFactory(3, application);
			
			SslContextFactory factory = new SslContextFactory();
			factory.setKeyStoreResource(keystore);
			factory.setKeyStorePassword("wicket");
			factory.setTrustStoreResource(keystore);
			factory.setKeyManagerPassword("wicket");
			factory.setProtocol("TLSv1");
			factory.setIncludeProtocols("TLSv1");
			
			NextProtoNego.debug = true;
			
			SslConnectionFactory sslConnectionFactory = 
					  new SslConnectionFactory(factory, "npn");
			NPNServerConnectionFactory npnConnectionFactory = 
					  new NPNServerConnectionFactory("spdy/3", "spdy/2", "http/1.1");
			npnConnectionFactory.setDefaultProtocol("http/1.1");
			HTTPSPDYServerConnectionFactory spdy2ConnectionFactory = 
					  new HTTPSPDYServerConnectionFactory(2, tlsHttpConfiguration);
			HttpConnectionFactory httpConnectionFactory = 
					  new HttpConnectionFactory(tlsHttpConfiguration);
			
			ServerConnector spdyConnector = new ServerConnector(server, sslConnectionFactory, 
					  npnConnectionFactory, con, spdy2ConnectionFactory, httpConnectionFactory);
			spdyConnector.setPort(8443);
			server.addConnector(spdyConnector);
	    }else{
	    	// If Keystore does not exist, use pure spdy. 
			log.debug("keystore NOT found");
			
			SPDYServerConnector con = new SPDYServerConnector(server, application);
			con.setPort(8443);
			server.addConnector(con);
	    }


		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.addServlet(new ServletHolder(new HttpServlet() {

			// proxy Browser HTTP Request to L7R client.
			protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
				Session session = sessionMap.get("1");
				if(session == null){
					log.debug("No client sessions:");
					return;
				}
				
				final AsyncContext async = request.startAsync();
				final OutputStream out = response.getOutputStream();
				
				Fields headers = new Fields();
				headers.add("method", request.getMethod());
				headers.add("url", request.getPathInfo());
				
				Enumeration<String> e1 = request.getHeaderNames();
				while (e1.hasMoreElements()) {
					String name = e1.nextElement();
					Enumeration<String> e2 = request.getHeaders(name);
					while(e2.hasMoreElements()){
						headers.add(name, e2.nextElement());
					}
				}
				
				try {
					
					// SYN_STREAM to L7R client.
					log.debug("start SYN from server:");
					session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter() {
						
						@Override
						public void onData(Stream arg0, DataInfo di) {
							// proxy HTTP Response body from L7RClient->Browser
							
							try {
								byte[] bytes = di.asBytes(true);
								log.debug("onData:" + new String(bytes,"utf8"));
								
								out.write(bytes);
								if(di.isClose()){
									out.flush();
									out.close();
									async.complete();
								}
								
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}

						@Override
						public void onReply(Stream arg0, ReplyInfo ri) {
							// proxy HTTP Response header from L7RClient->Browser
							log.debug("onReply:");
							
							Collection<String> e1 = response.getHeaderNames();
							for (Iterator iterator = e1.iterator(); iterator
									.hasNext();) {
								String name = (String) iterator.next();
								Collection<String> e2 = response.getHeaders(name);
								for (Iterator i2 = e1.iterator(); iterator
										.hasNext();) {
									response.addHeader(name, (String) i2.next());
								}
							
							}
						}
					});
					
				} catch (ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (TimeoutException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
		}), "/*");
		
	    server.setHandler(context);
	    server.setDumpAfterStart(false);
		server.start();
		log.debug("SPDY server start");
	}
	
	public static void main(String[] args){
		try {
			new L7RServer().startServer();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
