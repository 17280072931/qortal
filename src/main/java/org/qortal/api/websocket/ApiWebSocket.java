package org.qortal.api.websocket;

import java.io.Writer;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;

interface ApiWebSocket {

	void onNotify(Session session, String address);

	default String getPathInfo(Session session) {
		ServletUpgradeRequest upgradeRequest = (ServletUpgradeRequest) session.getUpgradeRequest();
		return upgradeRequest.getHttpServletRequest().getPathInfo();
	}

	default Map<String, String> getPathParams(Session session, String pathSpec) {
		UriTemplatePathSpec uriTemplatePathSpec = new UriTemplatePathSpec(pathSpec);
		return uriTemplatePathSpec.getPathParams(this.getPathInfo(session));
	}

	default void marshall(Writer writer, Object object) {
		Marshaller marshaller = createMarshaller(object.getClass());

		try {
			marshaller.marshal(object, writer);
		} catch (JAXBException e) {
			throw new RuntimeException("Unable to create marshall object for websocket", e);
		}
	}

	private static Marshaller createMarshaller(Class<?> objectClass) {
		try {
			// Create JAXB context aware of object's class
			JAXBContext jc = JAXBContextFactory.createContext(new Class[] { objectClass }, null);

			// Create marshaller
			Marshaller marshaller = jc.createMarshaller();

			// Set the marshaller media type to JSON
			marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell marshaller not to include JSON root element in the output
			marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);

			return marshaller;
		} catch (JAXBException e) {
			throw new RuntimeException("Unable to create websocket marshaller", e);
		}
	}

}
