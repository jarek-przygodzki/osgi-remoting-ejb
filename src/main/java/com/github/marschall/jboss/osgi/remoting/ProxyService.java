package com.github.marschall.jboss.osgi.remoting;

import static javax.xml.stream.XMLInputFactory.IS_NAMESPACE_AWARE;
import static javax.xml.stream.XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES;
import static javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES;
import static javax.xml.stream.XMLInputFactory.IS_VALIDATING;
import static javax.xml.stream.XMLInputFactory.SUPPORT_DTD;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.ServiceRegistration;

final class ProxyService implements BundleListener {
  
  /**
   * The symbolic names of the bundles that have to be added to the
   * class loader of each client bundle. This contains the classes need
   * by jboss-remoting, not the classes need by the client bundle. Those
   * should be dealt with by the manifest of the client bundle. 
   */
  private static final String[] PARENT_BUNDLE_IDS = {};

  private final ConcurrentMap<Bundle, BundleProxyContext> contexts;

  private final XMLInputFactory inputFactory;

  private final BundleContext bundleContext;

  private final Logger logger;
  
  private final List<Bundle> parentBundles;


  ProxyService(BundleContext bundleContext, Logger logger) {
    this.bundleContext = bundleContext;
    this.logger = logger;
    this.contexts = new ConcurrentHashMap<Bundle, BundleProxyContext>();
    this.inputFactory = this.createInputFactory();
  }

  private XMLInputFactory createInputFactory() {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    //disable various features that we don't need and just cost performance
    factory.setProperty(IS_VALIDATING, Boolean.FALSE);
    factory.setProperty(IS_NAMESPACE_AWARE, Boolean.FALSE);
    factory.setProperty(IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
    factory.setProperty(IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    factory.setProperty(SUPPORT_DTD, Boolean.FALSE);
    return factory;
  }

  void initialBundles(Bundle[] bundles) {
    for (Bundle bundle : bundles) {
      int bundleState = bundle.getState();
      if (bundleState == BundleEvent.STARTING || bundleState == BundleEvent.STARTED) {
        this.addPotentialBundle(bundle);
      }
    }
  }

  private String getResourceLocation(Bundle bundle) {
    // http://cxf.apache.org/distributed-osgi-reference.html
    Dictionary<String,String> headers = bundle.getHeaders();
    String remoteServiceHeader = headers.get("Remote-Service");
    if (remoteServiceHeader != null) {
      return remoteServiceHeader;
    } else {
      return "OSGI-INF/remote-service";
    }
  }

  private List<URL> getServiceUrls(Bundle bundle) {
    String resourceLocation = this.getResourceLocation(bundle);
    Enumeration<URL> resources;
    try {
      resources = bundle.getResources(resourceLocation);
    } catch (IOException e) {
      this.logger.warning("failed to access location '" + resourceLocation + "' in bundle: " + bundle);
      return Collections.emptyList();
    }
    if (resources != null && resources.hasMoreElements()) {
      List<URL> serviceXmls = new ArrayList<URL>(1);
      while (resources.hasMoreElements()) {
        URL nextElement = resources.nextElement();
        if (nextElement.getFile().endsWith(".xml")) {
          serviceXmls.add(nextElement);
        }
      }
      return serviceXmls;
    } else {
      return Collections.emptyList();
    }

  }

  void addPotentialBundle(Bundle bundle) {
    // TODO check
    List<URL> serviceUrls = this.getServiceUrls(bundle);
    if (!serviceUrls.isEmpty()) {
      ClassLoader classLoader = createClassLoader(bundle);
      ArrayList<ServiceCaller> callers = new ArrayList<ServiceCaller>();
      ArrayList<ServiceRegistration<?>> registrations = new ArrayList<ServiceRegistration<?>>();
      for (URL serviceXml : serviceUrls) {
        ParseResult result = this.parseServiceXml(serviceXml);
        if (!result.isEmpty()) {
          for (ServiceInfo info : result.services) {
            Class<?> interfaceClazz;
            try {
              interfaceClazz = classLoader.loadClass(info.interfaceName);
            } catch (ClassNotFoundException e) {
              this.logger.warning("failed to load interface class: " + info.interfaceName + " remote service will not be available", e);
              continue;
            }
            Object jBossProxy = this.lookUpJBossProxy(interfaceClazz, info.jndiName);
            ServiceCaller serviceCaller = new ServiceCaller(jBossProxy, classLoader, this.logger);
            Object service = Proxy.newProxyInstance(classLoader, new Class[]{interfaceClazz}, serviceCaller);
            callers.add(serviceCaller);
            // TODO properties
            Dictionary<String, Object> properties = new Hashtable<String, Object>();
            properties.put("service.imported", true);
            ServiceRegistration<?> serviceRegistration = this.bundleContext.registerService(info.interfaceName, service, properties);
            registrations.add(serviceRegistration);
          }
        }
      }

      if (!callers.isEmpty() && !registrations.isEmpty()) {
        callers.trimToSize();
        registrations.trimToSize();
        BundleProxyContext bundleProxyContext = new BundleProxyContext(callers, registrations);
        // prevent double registration is case of concurrent call by listener and initial list
        BundleProxyContext previous = this.contexts.putIfAbsent(bundle, bundleProxyContext);
        if (previous != null) {
          // undo registration
          bundleProxyContext.unregisterServices(this.bundleContext);
        }
      }
    }
  }

  ClassLoader createClassLoader(Bundle bundle) {
    SuffixList<Bundle> bundles = new SuffixList<Bundle>(this.parentBundles, bundle);
    ClassLoader classLoader = new BundlesProxyClassLoader(bundles);
    return classLoader;
  }
  
  private Object lookUpJBossProxy(Class<?> interfaceClazz, String jndiName) {
    Object proxy = null;
    return interfaceClazz.cast(proxy);
  }

  private ParseResult parseServiceXml(URL serviceXml) {
    InputStream stream = serviceXml.openStream();
    try {
      XMLStreamReader reader = this.inputFactory.createXMLStreamReader(stream);
      try {

      } finally {
        // TODO CR
        reader.close();
      }
    } catch (IOException e) {

    } finally {
      stream.close();
    }

  }
  
  private InitialContext createInitialContext() {
    Properties jndiProps = new Properties();
    jndiProps.put(Context.INITIAL_CONTEXT_FACTORY, "org.jboss.naming.remote.client.InitialContextFactory");
    // TODO configure
    jndiProps.put(Context.PROVIDER_URL,"remote://localhost:4447");
    // create a context passing these properties
    Context ctx = new InitialContext(jndiProps);
  }

  void removePotentialBundle(Bundle bundle) {
    BundleProxyContext context = this.contexts.remove(bundle);
    if (context != null) {
      context.invalidateCallers();
      context.unregisterServices(bundleContext);
    }
  }

  @Override
  public void bundleChanged(BundleEvent event) {
    int eventType = event.getType();
    switch (eventType) {
    // TODO installed? uninstalled? started? resolved?
    case BundleEvent.STARTING:
      this.addPotentialBundle(event.getBundle());
      break;
    case BundleEvent.STOPPING: 
      this.removePotentialBundle(event.getBundle());
      break;

    }

  }

  void stop() {
    for (BundleProxyContext context : this.contexts.values()) {
      context.invalidateCallers();
      context.unregisterServices(bundleContext);
    }
  }

  static final class ParseResult {

    final List<ServiceInfo> services;

    ParseResult(List<ServiceInfo> services) {
      this.services = services;
    }

    boolean isEmpty() {
      return this.services.isEmpty();
    }

    int size() {
      return this.services.size();
    }

  }

  static final class ServiceInfo {

    final String interfaceName;
    final String jndiName;

    ServiceInfo(String interfaceName, String jndiName) {
      this.interfaceName = interfaceName;
      this.jndiName = jndiName;
    }

  }

  static final class BundleProxyContext {

    private final Collection<ServiceCaller> callers;

    private final Collection<ServiceRegistration<?>> registrations;

    BundleProxyContext(Collection<ServiceCaller> callers, Collection<ServiceRegistration<?>> registrations) {
      this.callers = callers;
      this.registrations = registrations;
    }

    void unregisterServices(BundleContext bundleContext) {
      for (ServiceRegistration<?> registration : this.registrations) {
        bundleContext.ungetService(registration.getReference());
      }
    }

    void invalidateCallers() {
      for (ServiceCaller caller : callers) {
        caller.invalidate();
      }
    }

  }


}
