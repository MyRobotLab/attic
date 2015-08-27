package org.myrobotlab.net;

import java.io.Serializable;
import java.net.URI;
import java.util.HashMap;

import org.myrobotlab.codec.Encoder;
import org.myrobotlab.framework.MRLListener;
import org.myrobotlab.framework.Message;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.service.Runtime;
import org.myrobotlab.service.TestCatcher;
import org.myrobotlab.service.TestThrower;
import org.myrobotlab.service.interfaces.CommunicationInterface;
import org.myrobotlab.service.interfaces.Gateway;
import org.myrobotlab.service.interfaces.NameProvider;
import org.myrobotlab.service.interfaces.ServiceInterface;
import org.slf4j.Logger;

/**
 * goal of this class is to provide the interface for non-blocking communication (local &
 * remote) a good test of this goal is for this
 * class to be used outside of MRL process.
 * 
 * e.g. - the goal is the design of a very small lib - using only native
 * dependencies can do all of the necessary messaging MRL supports with very
 * little work
 * 
 * @author GroG
 *
 */
public class CommunicationManager implements Serializable, CommunicationInterface, NameProvider {

	private static final long serialVersionUID = 1L;
	public final static Logger log = LoggerFactory.getLogger(CommunicationManager.class);
	String name;

	/**
	 * mrlToProtocolKey -
	 */
	static HashMap<URI, URI> mrlToProtocolKey = new HashMap<URI, URI>();

	public CommunicationManager(String name) {
		this.name = name;
	}

	// FIXME - put in Runtime
	@Override
	public void addRemote(URI mrlHost, URI protocolKey) {
		mrlToProtocolKey.put(mrlHost, protocolKey);
	}

	/**
	 * mrl:/ get a gateway for remote communication
	 */
	public Gateway getComm(URI uri) {
		if (uri.getScheme().equals(Encoder.SCHEME_MRL)) {
			Gateway gateway = (Gateway) Runtime.getService(uri.getHost());
			return gateway;
		} else {
			log.error(String.format("%s not SCHEME_MRL", uri));
			return null;
		}
	}

	@Override
	final public void send(final Message msg) {

		ServiceInterface sw = Runtime.getService(msg.getName());
		if (sw == null) {
			log.error(String.format("could not find service %s to process %s from sender %s - tearing down route", msg.name, msg.method, msg.sender));
			ServiceInterface sender = Runtime.getService(msg.sender);
			if (sender != null) {
				sender.removeListener(msg.sendingMethod, msg.getName(), msg.method);
			}
			return;
		}

		URI host = sw.getInstanceId();
		if (host == null) {
			// local message
			// log.info(String.format("local %s.%s->%s/%s.%s(%s)", msg.sender,
			// msg.sendingMethod, sw.getHost(), msg.name, msg.method,
			// Encoder.getParameterSignature(msg.data)));
			sw.in(msg);
		} else {
			// remote message
			// log.info(String.format("remote %s.%s->%s/%s.%s(%s)", msg.sender,
			// msg.sendingMethod, sw.getHost(), msg.name, msg.method,
			// Encoder.getParameterSignature(msg.data)));

			URI protocolKey = mrlToProtocolKey.get(host);
			getComm(host).sendRemote(protocolKey, msg);
		}
	}

	/**
	 * get a gateway, send the message through the gateway with a protocol key
	 */
	@Override
	final public void send(final URI uri, final Message msg) {
		getComm(uri).sendRemote(uri, msg);
	}

	// FIXME - remove all others !!!
	public Message createMessage(String name, String method, Object... data) {
		Message msg = new Message();
		msg.name = name; // destination instance name
		msg.sender = getName();
		msg.data = data;
		msg.method = method;

		return msg;
	}

	// ----- Message - subscribe - begin -----------
	// ----------- candidate for Encoder begin ------------------------

	public static final String capitalize(final String line) {
		return Character.toUpperCase(line.charAt(0)) + line.substring(1);
	}

	public static final String getMethodCallbackConvention(String topicMethod) {
		// replacements
		if (topicMethod.startsWith("publish")) {
			return String.format("on%s", topicMethod.substring("publish".length()));
		} else if (topicMethod.startsWith("get")) {
			return String.format("on%s", topicMethod.substring("get".length()));
		}

		// no replacement - just pefix and capitalize
		// FIXME - subscribe to onMethod --- gets ---> onOnMethod :P
		return String.format("on%s", capitalize(topicMethod));
	}

	// ----------- candidate for Encoder begin ------------------------

	@Override
	public void subscribe(String topicKey) {
		
		if (topicKey == null){
			throw new IllegalArgumentException("subscribe - parameter cannot be null - try  \"topicName/topicMethod/callbackName/callbackMethod\"");
		}
		
		String[] topics = topicKey.split("/");

		String topicName = null;
		String topicMethod = null;
		String callbackName = getName();
		String callbackMethod = null;

		if (topics.length > 4){ // || ((topics.length == 5 && topics[0].length() == 0))){
			throw new IllegalArgumentException("subscribe - too many parameters - try  \"topicName/topicMethod/callbackName/callbackMethod\"");
		}

		if (topics.length == 4){
			topicName = topics[0];
			topicMethod = topics[1];
			callbackName = topics[2];
			callbackMethod = topics[3];
		} else if (topics.length == 3) {
			topicName = topics[0];
			topicMethod = topics[1];
			callbackMethod = topics[2];
		} else if (topics.length == 2) {
			topicName = topics[0];
			topicMethod = topics[1];
		} else if (topics.length == 1) {			
			topicMethod = topics[0];
		}
		
		if (topicName == null){
			topicName = getName();
		}
		
		if (callbackName == null){
			callbackName = getName();
		}
		
		if (topicMethod == null || topicMethod.length() == 0){
			throw new IllegalArgumentException("topicMethod can not be null or 0 length");
		}

		if (callbackMethod == null){
			callbackMethod = getMethodCallbackConvention(topicMethod);
		}
		subscribe(topicName, topicMethod, callbackName, callbackMethod);
	}

	public void subscribe(String topicName, String topicMethod, String callbackName, String callbackMethod) {
		log.warn(String.format("subscribe [%s/%s ---> %s/%s]", topicName, topicMethod, callbackName, callbackMethod));
		MRLListener listener = new MRLListener(topicMethod, callbackName, callbackMethod);
		send(createMessage(topicName, "addListener", listener));
	}

	@Override
	public String getName() {
		return name;
	}

	static public int count(String data, char toCount){
		int charCount = 0;

		for( int i = 0; i < data.length( ); i++ )
		{
			char tmp = data.charAt( i );

		    if( toCount == tmp )
		        ++charCount;
		}
		
		return charCount;
	}

	public static void main(String[] args) {
		LoggingFactory.getInstance().configure();
		LoggingFactory.getInstance().setLevel(Level.WARN);

		// TODO - send a verify for service & another verify for method ?

		TestThrower thrower = (TestThrower)Runtime.start("thrower", "TestThrower");
		TestCatcher catcher = (TestCatcher)Runtime.start("catcher", "TestCatcher");
		
		CommunicationManager cm = new CommunicationManager("catcher");
		
		cm.subscribe("opencv/publishOpenCVData");		// -- opencv/publishOpenCVData 	---> catcher/onOpenCVData
		cm.subscribe("/register/python/registerMe");	// -- runtime/register ---> python/onRegister
		cm.subscribe("/register/registerMePlease");
		cm.subscribe("opencv/publishOpenCVData");	// -- opencv/publishOpenCVData ---> me/onOpenCVData
		cm.subscribe("opencv/getState");			// -- opencv/getState ---> me/onState
		cm.subscribe("opencv/publishOpenCVData/python/onOpenCVDataHere");
		cm.subscribe("opencv/publishOpenCVData/gotData");
		cm.subscribe("publishMethod/onMethod");
		cm.subscribe("/register/python/onRegister");
		

		// prefix [http://host:port/api//subscribe/]
		// prefix [http://host:port/api/catcher/subscribe/]
		
		cm.subscribe("register"); 	// [catcher/register ---> catcher/onRegister]
		cm.subscribe("/register");	// [/register ---> catcher/onRegister]
		cm.subscribe("register/");	// [catcher/register ---> catcher/onRegister]
		cm.subscribe("/register/");	// [/register ---> catcher/onRegister]
		
		cm.subscribe("thrower/register");		// [thrower/register ---> catcher/onRegister]
		cm.subscribe("thrower/register/"); 		// [thrower/register ---> catcher/onRegister]
		cm.subscribe("/thrower/register"); 		// [/thrower ---> catcher/onThrower]

		cm.subscribe("/thrower/register/"); 	// [/thrower ---> catcher/onThrower]
		
		cm.subscribe("test/register/catcher"); 	// [test/register ---> catcher/onRegister]
		cm.subscribe("/test/register/catcher");	// [/test ---> register/onTest]
		cm.subscribe("test/register/catcher/");	// [test/register ---> catcher/onRegister]
		cm.subscribe("/test/register/catcher/");// [/test ---> register/onTest]
		
		cm.subscribe("test/register/catcher/doRegister");	// [test/register ---> catcher/onRegister]
		
		try {
			cm.subscribe("/test/register/catcher/doRegister"); 	// [/test ---> register/onTest] (INVALID)
		} catch(Exception e){
			log.info("valid exception");
		}
		
		cm.subscribe("test/register/catcher/doRegister/"); 	// [test/register ---> catcher/onRegister]
		
		try {
			cm.subscribe("/test/register/catcher/doRegister/"); // [/test ---> register/onTest] (INVALID)
		} catch(Exception e){
			log.info("valid exception");
		}

		// ALL INVALID
		try {
			cm.subscribe("/test/register/catcher/doRegister/bogus");
		} catch(Exception e){
			log.info("valid exception");
		}
		
		try {
			cm.subscribe("bogus/test/register/catcher/doRegister/");
		} catch(Exception e){
			log.info("valid exception");
		}
		
		
		try {
			cm.subscribe(""); 
		} catch(Exception e){
			log.info("valid exception");
		}
			
		try {
			cm.subscribe(null);
		} catch(Exception e){
			log.info("valid exception");
		}
		
		try {
			cm.subscribe("/");
		} catch(Exception e){
			log.info("valid exception");
		} 	
		
		try {
			cm.subscribe("bogus/test/register/catcher/doRegister/bogus");
		} catch(Exception e){
			log.info("valid exception");
		} 	
		
		try {
			cm.subscribe("/"); 		
		} catch(Exception e){
			log.info("valid exception");
		} 	
		
						
		cm.subscribe("//"); 						
		cm.subscribe("///"); 						
		cm.subscribe("////"); 						
		cm.subscribe("/////"); 						

	}

}
