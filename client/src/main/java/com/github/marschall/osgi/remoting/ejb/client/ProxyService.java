package com.github.marschall.osgi.remoting.ejb.client;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.xml.stream.XMLStreamException;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceRegistration;

import com.github.marschall.osgi.remoting.ejb.api.InitialContextService;
import com.github.marschall.osgi.remoting.ejb.api.ProxyFlusher;

final class ProxyService implements BundleListener, ProxyFlusher {

  private final ConcurrentMap<Bundle, BundleProxyContext> contexts;

  private final ServiceXmlParser parser;

  private final BundleContext bundleContext;

  private final LoggerBridge logger;

  private volatile ClassLoader parent;

  private volatile InitialContextService initialContextService;

  private final ExecutorService executorService;

  private volatile ServiceRegistration<ProxyFlusher> flusherRegisterService;
  
  ProxyService(BundleContext bundleContext, LoggerBridge logger, ExecutorService executorService) {
    this.bundleContext = bundleContext;
    this.logger = logger;
    this.executorService = executorService;
    this.contexts = new ConcurrentHashMap<Bundle, BundleProxyContext>();
    this.parser = new ServiceXmlParser();
  }

  void setInitialContextService(InitialContextService initialContextService) {
    this.initialContextService = initialContextService;
    this.parent = new BundlesProxyClassLoader(this.lookUpParentBundles());

    // first add the listener so we don't miss anything
    this.bundleContext.addBundleListener(this);

    // then query the bundles
    Bundle[] bundles = this.bundleContext.getBundles();
    this.initialBundles(bundles);

    this.flusherRegisterService = this.bundleContext.registerService(ProxyFlusher.class, this, new Hashtable<String, Object>());
  }

  private Bundle[] lookUpParentBundles() {
    Set<String> symbolicNames = this.initialContextService.getClientBundleSymbolicNames();
    Map<String, Bundle> found = new HashMap<String, Bundle>(symbolicNames.size());
    for (Bundle bundle : bundleContext.getBundles()) {
      String symbolicName = bundle.getSymbolicName();
      if (symbolicNames.contains(symbolicName)) {
        Bundle previous = found.put(symbolicName, bundle);
        if (previous != null) {
          this.logger.warning("non-unique bundle: " + symbolicName);
        }
      }
    }
    if (found.size() != symbolicNames.size()) {
      // TODO better message
      throw new ServiceException("not all client bundles found");
    }
    // TODO sort?
    Collection<Bundle> bundles = found.values();
    return bundles.toArray(new Bundle[bundles.size()]);
  }


  private void initialBundles(Bundle[] bundles) {
    for (Bundle bundle : bundles) {
      int bundleState = bundle.getState();
      if (bundleState == Bundle.ACTIVE) {
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
    Enumeration<URL> resources = bundle.findEntries(resourceLocation, "*.xml", false);
    if (resources != null && resources.hasMoreElements()) {
      List<URL> serviceXmls = new ArrayList<URL>(1);
      while (resources.hasMoreElements()) {
        URL nextElement = resources.nextElement();
        serviceXmls.add(nextElement);
      }
      return serviceXmls;
    } else {
      return Collections.emptyList();
    }

  }

  void addPotentialBundle(Bundle bundle) {
    List<URL> serviceUrls = this.getServiceUrls(bundle);
    if (!serviceUrls.isEmpty()) {
      List<ParseResult> results = new ArrayList<ParseResult>(serviceUrls.size());
      for (URL serviceXml : serviceUrls) {
        ParseResult result;
        try {
          result = this.parser.parseServiceXml(serviceXml);
        } catch (IOException e) {
          this.logger.warning("could not parse XML: " + serviceXml + " in bundle:" + bundle + ", ignoring",  e);
          continue;
        } catch (XMLStreamException e) {
          this.logger.warning("could not parse XML: " + serviceXml + " in bundle:" + bundle + ", ignoring",  e);
          continue;
        }
        if (!result.isEmpty()) {
          results.add(result);
        }
      }
      if (results.isEmpty()) {
        return;
      }

      ParseResult result = ParseResult.flatten(results);
      this.registerServices(bundle, result);
    }
  }

  void registerServices(Bundle bundle, ParseResult result) {
    ClassLoader classLoader = createClassLoader(bundle);
    Thread currentThread = Thread.currentThread();
    ClassLoader oldContextClassLoader = currentThread.getContextClassLoader();
    // switch TCCL only once for all the look ups
    currentThread.setContextClassLoader(classLoader);

    List<ServiceCaller> callers = new ArrayList<ServiceCaller>(result.size());
    List<ServiceRegistration<?>> registrations = new ArrayList<ServiceRegistration<?>>(result.size());
    Context namingContext;
    try {
      namingContext = this.createNamingContext();
    } catch (NamingException e) {
      // there isn't really anything anybody can do
      // but we shouldn't pump exception into the OSGi framework
      this.logger.warning("could not register bundle: " + bundle, e);
      return;
    }

    try {
      for (ServiceInfo info : result.services) {
        Class<?> interfaceClass;
        try {
          interfaceClass = classLoader.loadClass(info.interfaceName);
        } catch (ClassNotFoundException e) {
          this.logger.warning("failed to load interface class: " + info.interfaceName
              + ", remote service will not be available", e);
          continue;
        }
        Future<?> serviceProxy = this.lookUpServiceProxy(interfaceClass, info.jndiName, namingContext, classLoader);
        ServiceCaller serviceCaller = new ServiceCaller(serviceProxy, classLoader, this.logger, info.jndiName);
        Object service = Proxy.newProxyInstance(classLoader, new Class[]{interfaceClass}, serviceCaller);
        callers.add(serviceCaller);
        // TODO properties
        // TODO exported configs
        // org.osgi.service.remoteserviceadmin.RemoteConstants.ENDPOINT_ID
        Dictionary<String, Object> properties = new Hashtable<String, Object>();
        properties.put("service.imported", true);
        properties.put("com.github.marschall.osgi.remoting.ejb.jndiName", info.jndiName);
        ServiceRegistration<?> serviceRegistration = this.bundleContext.registerService((Class<Object>) interfaceClass, service, properties);
        registrations.add(serviceRegistration);
      }
    } finally {
      currentThread.setContextClassLoader(oldContextClassLoader);
    }

    BundleProxyContext bundleProxyContext = new BundleProxyContext(namingContext, callers, registrations, classLoader);
    registerBundleProxyContext(bundle, bundleProxyContext);
  }

  private void registerBundleProxyContext(Bundle bundle, BundleProxyContext bundleProxyContext) {
    // detect double registration is case of concurrent call by #bundleChanged and #initialBundles
    BundleProxyContext previous = this.contexts.putIfAbsent(bundle, bundleProxyContext);
    if (previous != null) {
      // undo registration
      bundleProxyContext.unregisterServices(this.bundleContext);
    }
  }

  ClassLoader createClassLoader(Bundle bundle) {
    return new BundleProxyClassLoader(bundle, this.parent);
  }

  private Future<?> lookUpServiceProxy(Class<?> interfaceClazz, String jndiName, Context namingContext, ClassLoader classLoader) {
    Callable<Object> lookUp = new ProxyLookUp(interfaceClazz, jndiName, namingContext, classLoader);
    return this.executorService.submit(lookUp);
  }

  private Context createNamingContext() throws NamingException {
    // create a namingContext passing these properties
    Hashtable<?, ?> environment = this.initialContextService.getEnvironment();
    if (environment != null) {
      return new InitialContext(environment);
    } else {
      return new InitialContext();
    }
  }

  void removePotentialBundle(Bundle bundle) {
    BundleProxyContext context = this.contexts.remove(bundle);
    if (context != null) {
      try {
        context.release(bundleContext);
      } catch (NamingException e) {
        // there isn't really anything anybody can do
        // but we shouldn't pump exception into the OSGi framework
        this.logger.warning("could not unregister bundle: " + bundle, e);
      }
    }
  }

  @Override
  public void bundleChanged(BundleEvent event) {
    int eventType = event.getType();
    switch (eventType) {
      case BundleEvent.STARTED:
        this.addPotentialBundle(event.getBundle());
        break;
      case BundleEvent.STOPPED:
        this.removePotentialBundle(event.getBundle());
        break;
    }
  }

  @Override
  public void flushProxies() {
    NamingException lastCause = null;
    for (BundleProxyContext proxyContext : contexts.values()) {
      try {
        proxyContext.flushProxies(this.initialContextService);
      } catch (NamingException e) {
        // TODO collect exceptions for SE 7
        this.logger.error("could not flush proxy", e);
        lastCause = e;
      }
    }
    if (lastCause != null) {
      throw new RuntimeException("could not flush all proxies", lastCause);
    }
  }
  
  void setProxies() {
    NamingException lastCause = null;
    for (BundleProxyContext proxyContext : contexts.values()) {
      try {
        proxyContext.flushProxies(this.initialContextService);
      } catch (NamingException e) {
        // TODO collect exceptions for SE 7
        this.logger.error("could not flush proxy", e);
        lastCause = e;
      }
    }
    if (lastCause != null) {
      throw new RuntimeException("could not flush all proxies", lastCause);
    }
  }

  void stop() {
    for (BundleProxyContext context : this.contexts.values()) {
      try {
        context.release(bundleContext);
      } catch (NamingException e) {
        // there isn't really anything anybody can do
        // but we shouldn't pump exception into the OSGi framework
        // and we should continue the loop
        this.logger.warning("could not unregister service", e);
      }
    }
    this.flusherRegisterService.unregister();
    this.flusherRegisterService = null;

    this.bundleContext.removeBundleListener(this);
  }

  static final class ProxyLookUp implements Callable<Object> {

    private final Class<?> interfaceClazz;
    private final String jndiName;
    private final Context namingContext;
    private final ClassLoader classLoader;

    ProxyLookUp(Class<?> interfaceClazz, String jndiName, Context namingContext, ClassLoader classLoader) {
      this.interfaceClazz = interfaceClazz;
      this.jndiName = jndiName;
      this.namingContext = namingContext;
      this.classLoader = classLoader;
    }

    @Override
    public Object call() throws Exception {
      Thread currentThread = Thread.currentThread();
      ClassLoader oldContextClassLoader = currentThread.getContextClassLoader();
      try {
        currentThread.setContextClassLoader(this.classLoader);
        Object proxy = namingContext.lookup(jndiName);
        return this.interfaceClazz.cast(proxy);
      } finally {
        currentThread.setContextClassLoader(oldContextClassLoader);
      }
    }

  }

}
