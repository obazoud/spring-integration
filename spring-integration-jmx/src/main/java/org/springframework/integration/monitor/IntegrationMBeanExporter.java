/*
 * Copyright 2002-2010 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.integration.monitor;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.DynamicMBean;
import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.modelmbean.ModelMBean;

import org.aopalliance.aop.Advice;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.Lifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.MessageChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.MessageHandler;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.core.PollableChannel;
import org.springframework.integration.endpoint.AbstractEndpoint;
import org.springframework.jmx.export.MBeanExporter;
import org.springframework.jmx.export.UnableToRegisterMBeanException;
import org.springframework.jmx.export.annotation.AnnotationJmxAttributeSource;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedMetric;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.jmx.export.assembler.MetadataMBeanInfoAssembler;
import org.springframework.jmx.export.naming.MetadataNamingStrategy;
import org.springframework.jmx.support.MetricType;
import org.springframework.util.Assert;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.ReflectionUtils;

/**
 * <p>
 * MBean exporter for Spring Integration components in an existing application. Add an instance of this as a bean
 * definition in the same context as the components you need to monitor and all message channels and message handlers
 * will be exposed.
 * </p>
 * <p>
 * Channels will report metrics on send and receive (counts, rates, errors) and handlers will report metrics on
 * execution duration. Channels will be registered under their name (bean id), if explicit, or the last part of their
 * internal name (e.g. "nullChannel") if registered by the framework. A handler that is attached to an endpoint will be
 * registered with the endpoint name (bean id) if there is one, otherwise under the name of the input channel. Handler
 * object names contain a <code>bean</code> key that reports the source of the name: "endpoint" if the name is the
 * endpoint id; "anonymous" if it is the input channel; and "handler" as a fallback, where the object name is just the
 * <code>toString()</code> of the handler.
 * </p>
 * <p>
 * This component is itself an MBean, reporting attributes concerning the names and object names of the channels and
 * handlers. It doesn't register itself to avoid conflicts with the standard <code>&lt;context:mbean-export/&gt;</code>
 * from Spring (which should therefore be used any time you need to expose those features).
 * </p>
 * 
 * @author Dave Syer
 * @author Helena Edelson
 * @author Oleg Zhurakousky
 */
@ManagedResource
public class IntegrationMBeanExporter extends MBeanExporter implements BeanPostProcessor, BeanFactoryAware,
		BeanClassLoaderAware, SmartLifecycle {

	private static final Log logger = LogFactory.getLog(IntegrationMBeanExporter.class);

	public static final String DEFAULT_DOMAIN = "org.springframework.integration";

	private final AnnotationJmxAttributeSource attributeSource = new AnnotationJmxAttributeSource();

	private ListableBeanFactory beanFactory;

	private Map<Object, AtomicLong> anonymousHandlerCounters = new HashMap<Object, AtomicLong>();

	private Map<Object, AtomicLong> anonymousSourceCounters = new HashMap<Object, AtomicLong>();

	private Set<SimpleMessageHandlerMetrics> handlers = new HashSet<SimpleMessageHandlerMetrics>();

	private Set<SimpleMessageSourceMetrics> sources = new HashSet<SimpleMessageSourceMetrics>();

	private Set<DirectChannelMetrics> channels = new HashSet<DirectChannelMetrics>();

	private Map<String, Object> exposedBeans = new HashMap<String, Object>();

	private Map<String, DirectChannelMetrics> channelsByName = new HashMap<String, DirectChannelMetrics>();

	private Map<String, MessageHandlerMetrics> handlersByName = new HashMap<String, MessageHandlerMetrics>();

	private Map<String, MessageSourceMetrics> sourcesByName = new HashMap<String, MessageSourceMetrics>();

	private Map<String, String> beansByEndpointName = new HashMap<String, String>();

	private ClassLoader beanClassLoader;

	private volatile boolean autoStartup = true;

	private volatile int phase = 0;

	private volatile boolean running;

	private final ReentrantLock lifecycleLock = new ReentrantLock();

	private String domain = DEFAULT_DOMAIN;

	private boolean initialized = false;

	private final Properties objectNameStaticProperties = new Properties();

	private final MetadataMBeanInfoAssembler assembler = new MetadataMBeanInfoAssembler(attributeSource);

	private final MetadataNamingStrategy namingStrategy = new MetadataNamingStrategy(attributeSource);

	private String[] componentNamePatterns = { "*" };

	public IntegrationMBeanExporter() {
		super();
		// Shouldn't be necessary, but to be on the safe side...
		setAutodetect(false);
		setNamingStrategy(namingStrategy);
		setAssembler(assembler);
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
		super.setBeanClassLoader(classLoader);
	}

	/**
	 * Static properties that will be added to all object names.
	 * 
	 * @param objectNameStaticProperties the objectNameStaticProperties to set
	 */
	public void setObjectNameStaticProperties(Map<String, String> objectNameStaticProperties) {
		this.objectNameStaticProperties.putAll(objectNameStaticProperties);
	}

	/**
	 * The JMX domain to use for MBeans registered. Defaults to <code>spring.application</code> (which is useful in
	 * SpringSource HQ).
	 * 
	 * @param domain the domain name to set
	 */
	public void setDefaultDomain(String domain) {
		this.domain = domain;
		this.namingStrategy.setDefaultDomain(domain);
	}

	public void setComponentNamePatterns(String[] componentNamePatterns) {
		Assert.notEmpty(componentNamePatterns, "componentNamePatterns must not be empty");
		this.componentNamePatterns = componentNamePatterns;
	}

	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		super.setBeanFactory(beanFactory);
		Assert.isTrue(beanFactory instanceof ListableBeanFactory, "A ListableBeanFactory is required.");
		this.beanFactory = (ListableBeanFactory) beanFactory;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {

		if (bean instanceof Advised) {
			for (Advisor advisor : ((Advised) bean).getAdvisors()) {
				Advice advice = advisor.getAdvice();
				if (advice instanceof MessageHandlerMetrics || advice instanceof MessageSourceMetrics
						|| advice instanceof MessageChannelMetrics) {
					// Already advised - so probably a factory bean product
					return bean;
				}
			}
		}

		boolean foundMetrics = false;

		if (bean instanceof MessageHandler) {
			SimpleMessageHandlerMetrics monitor = new SimpleMessageHandlerMetrics((MessageHandler) bean);
			Object advised = applyHandlerInterceptor(bean, monitor, beanClassLoader);
			handlers.add(monitor);
			foundMetrics = true;
			bean = advised;
		}

		if (bean instanceof MessageSource<?>) {
			SimpleMessageSourceMetrics monitor = new SimpleMessageSourceMetrics((MessageSource<?>) bean);
			Object advised = applySourceInterceptor(bean, monitor, beanClassLoader);
			sources.add(monitor);
			foundMetrics = true;
			bean = advised;
		}

		if (bean instanceof MessageChannel) {
			DirectChannelMetrics monitor;
			MessageChannel target = (MessageChannel) extractTarget(bean);
			if (bean instanceof PollableChannel) {
				if (target instanceof QueueChannel) {
					monitor = new QueueChannelMetrics((QueueChannel) target, beanName);
				}
				else {
					monitor = new PollableChannelMetrics(target, beanName);
				}
			}
			else {
				monitor = new DirectChannelMetrics(target, beanName);
			}
			Object advised = applyChannelInterceptor(bean, monitor, beanClassLoader);
			channels.add(monitor);
			foundMetrics = true;
			bean = advised;
		}

		if (foundMetrics) {
			// Only force the other exporters to initialize if we are sure we need to...
			if (!initialized) {
				try {
					collectMBeanExporters();
					initialized = true;
				}
				catch (BeanCreationException e) {
					// Ignore
					if (logger.isDebugEnabled()) {
						logger.debug("Ignoring BeanCreationException while instantiating MBeanExporter during creation of metrics for beanName=["
								+ beanName + "]");
					}
				}
			}
		}

		return bean;

	}

	/**
	 * Copy of private method in super class. Needed so we can avoid using the bean factory to extract the bean again,
	 * and risk it being a proxy (which it almost certainly is by now).
	 * 
	 * @param bean the bean instance to register
	 * @param beanKey the bean name or human readable version if autogenerated
	 * @return the JMX object name of the MBean that was registered
	 */
	private ObjectName registerBeanInstance(Object bean, String beanKey) {
		try {
			ObjectName objectName = getObjectName(bean, beanKey);
			Object mbeanToExpose = null;
			if (isMBean(bean.getClass())) {
				mbeanToExpose = bean;
			}
			else {
				DynamicMBean adaptedBean = adaptMBeanIfPossible(bean);
				if (adaptedBean != null) {
					mbeanToExpose = adaptedBean;
				}
			}
			if (mbeanToExpose != null) {
				if (logger.isInfoEnabled()) {
					logger.info("Located MBean '" + beanKey + "': registering with JMX server as MBean [" + objectName
							+ "]");
				}
				doRegister(mbeanToExpose, objectName);
			}
			else {
				if (logger.isInfoEnabled()) {
					logger.info("Located managed bean '" + beanKey + "': registering with JMX server as MBean ["
							+ objectName + "]");
				}
				ModelMBean mbean = createAndConfigureMBean(bean, beanKey);
				doRegister(mbean, objectName);
				// injectNotificationPublisherIfNecessary(bean, mbean, objectName);
			}
			return objectName;
		}
		catch (JMException e) {
			throw new UnableToRegisterMBeanException("Unable to register MBean [" + bean + "] with key '" + beanKey
					+ "'", e);
		}
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public final boolean isAutoStartup() {
		return this.autoStartup;
	}

	public final int getPhase() {
		return this.phase;
	}

	public final boolean isRunning() {
		this.lifecycleLock.lock();
		try {
			return this.running;
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	public final void start() {
		this.lifecycleLock.lock();
		try {
			if (!this.running) {
				this.doStart();
				this.running = true;
				if (logger.isInfoEnabled()) {
					logger.info("started " + this);
				}
			}
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	public final void stop() {
		this.lifecycleLock.lock();
		try {
			if (this.running) {
				this.doStop();
				this.running = false;
				if (logger.isInfoEnabled()) {
					logger.info("stopped " + this);
				}
			}
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	public final void stop(Runnable callback) {
		this.lifecycleLock.lock();
		try {
			this.stop();
			callback.run();
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	protected void doStop() {
		unregisterBeans();
		channelsByName.clear();
		handlersByName.clear();
		sourcesByName.clear();
	}

	protected void doStart() {
		registerChannels();
		registerHandlers();
		registerSources();
		registerEndpoints();
	}

	/**
	 * Force early initialization of other MBean exporters to ensure that they don't try to register the same beans that
	 * this exporter is covering.
	 */
	private void collectMBeanExporters() {
		String[] beanNames = beanFactory.getBeanNamesForType(MBeanExporter.class, false, false);
		for (String beanName : beanNames) {
			beanFactory.getBean(beanName, MBeanExporter.class);
		}
	}

	@Override
	public void destroy() {
		super.destroy();
		for (MessageChannelMetrics monitor : channels) {
			logger.info("Summary on shutdown: " + monitor);
		}
		for (MessageHandlerMetrics monitor : handlers) {
			logger.info("Summary on shutdown: " + monitor);
		}
	}

	@ManagedMetric(metricType = MetricType.COUNTER, displayName = "MessageChannel Channel Count")
	public int getChannelCount() {
		return channelsByName.size();
	}

	@ManagedMetric(metricType = MetricType.COUNTER, displayName = "MessageHandler Handler Count")
	public int getHandlerCount() {
		return handlersByName.size();
	}

	@ManagedAttribute
	public String[] getHandlerNames() {
		return handlersByName.keySet().toArray(new String[0]);
	}

	@ManagedMetric(metricType = MetricType.GAUGE, displayName = "Active Handler Count")
	public int getActiveHandlerCount() {
		int count = 0;
		for (MessageHandlerMetrics monitor : handlers) {
			count += monitor.getActiveCount();
		}
		return count;
	}

	@ManagedMetric(metricType = MetricType.GAUGE, displayName = "Queued Message Count")
	public int getQueuedMessageCount() {
		int count = 0;
		for (MessageChannelMetrics monitor : channels) {
			if (monitor instanceof QueueChannelMetrics) {
				count += ((QueueChannelMetrics) monitor).getQueueSize();
			}
		}
		return count;
	}

	@ManagedAttribute
	public String[] getChannelNames() {
		return channelsByName.keySet().toArray(new String[0]);
	}

	public Statistics getHandlerDuration(String name) {
		if (handlersByName.containsKey(name)) {
			return handlersByName.get(name).getDuration();
		}
		logger.debug("No handler found for (" + name + ")");
		return null;
	}

	public int getSourceMessageCount(String name) {
		if (sourcesByName.containsKey(name)) {
			return sourcesByName.get(name).getMessageCount();
		}
		logger.debug("No source found for (" + name + ")");
		return -1;
	}

	public int getChannelReceiveCount(String name) {
		if (channelsByName.containsKey(name)) {
			if (channelsByName.get(name) instanceof PollableChannelMetrics) {
				return ((PollableChannelMetrics) channelsByName.get(name)).getReceiveCount();
			}
		}
		logger.debug("No channel found for (" + name + ")");
		return -1;
	}

	@ManagedOperation
	public Statistics getChannelSendRate(String name) {
		if (channelsByName.containsKey(name)) {
			return channelsByName.get(name).getSendRate();
		}
		logger.debug("No channel found for (" + name + ")");
		return null;
	}

	public Statistics getChannelErrorRate(String name) {
		if (channelsByName.containsKey(name)) {
			return channelsByName.get(name).getErrorRate();
		}
		logger.debug("No channel found for (" + name + ")");
		return null;
	}

	@Override
	protected void registerBeans() {
		if (!exposedBeans.isEmpty()) {
			super.setBeans(exposedBeans);
			super.registerBeans();
		}
	}

	private void registerChannels() {
		for (DirectChannelMetrics monitor : channels) {
			String name = monitor.getName();
			if (!PatternMatchUtils.simpleMatch(this.componentNamePatterns, name)) {
				continue;
			}
			// Only register once...
			if (!channelsByName.containsKey(name)) {
				String beanKey = getChannelBeanKey(name);
				logger.info("Registering MessageChannel " + name);
				if (name != null) {
					channelsByName.put(name, monitor);
				}
				registerBeanNameOrInstance(monitor, beanKey);
				// Expose the raw bean if it is managed
				MessageChannel bean = monitor.getMessageChannel();
				if (assembler.includeBean(bean.getClass(), monitor.getName())) {
					registerBeanInstance(bean, monitor.getName());
				}
			}
		}
	}

	private void registerHandlers() {
		for (SimpleMessageHandlerMetrics source : handlers) {
			MessageHandlerMetrics monitor = enhanceHandlerMonitor(source);
			String name = monitor.getName();
			if (!PatternMatchUtils.simpleMatch(this.componentNamePatterns, name)) {
				continue;
			}
			// Only register once...
			if (!handlersByName.containsKey(name)) {
				String beanKey = getHandlerBeanKey(monitor);
				if (name != null) {
					handlersByName.put(name, monitor);
				}
				registerBeanNameOrInstance(monitor, beanKey);
				// Expose the raw bean if it is managed
				MessageHandler bean = source.getMessageHandler();
				if (assembler.includeBean(bean.getClass(), source.getName())) {
					registerBeanInstance(bean, monitor.getName());
				}
			}
		}
	}

	private void registerSources() {
		for (SimpleMessageSourceMetrics source : sources) {
			MessageSourceMetrics monitor = enhanceSourceMonitor(source);
			String name = monitor.getName();
			if (!PatternMatchUtils.simpleMatch(this.componentNamePatterns, name)) {
				continue;
			}
			// Only register once...
			if (!sourcesByName.containsKey(name)) {
				String beanKey = getSourceBeanKey(monitor);
				if (name != null) {
					sourcesByName.put(name, monitor);
				}
				registerBeanNameOrInstance(monitor, beanKey);
				// Expose the raw bean if it is managed
				MessageSource<?> bean = source.getMessageSource();
				if (assembler.includeBean(bean.getClass(), source.getName())) {
					registerBeanInstance(bean, monitor.getName());
				}
			}
		}
	}

	private void registerEndpoints() {
		String[] names = beanFactory.getBeanNamesForType(AbstractEndpoint.class);
		Set<String> endpointNames = new HashSet<String>();
		for (String name : names) {
			if (!beansByEndpointName.values().contains(name)) {
				AbstractEndpoint endpoint = beanFactory.getBean(name, AbstractEndpoint.class);
				String beanKey;
				name = endpoint.getComponentName();
				String source;
				if (name.startsWith("_org.springframework.integration")) {
					name = getInternalComponentName(name);
					source = "internal";
				}
				else {
					name = endpoint.getComponentName();
					source = "endpoint";
				}
				if (!PatternMatchUtils.simpleMatch(this.componentNamePatterns, name)) {
					continue;
				}
				if (endpointNames.contains(name)) {
					int count = 0;
					String unique = name+"#"+count;
					while (endpointNames.contains(unique)) {
						unique = name + "#" + (++count);
					}
					name = unique;
				}
				endpointNames.add(name);
				beanKey = getEndpointBeanKey(endpoint, name, source);
				ObjectName objectName = registerBeanInstance(new ManagedEndpoint(endpoint), beanKey);
				logger.info("Registered endpoint without MessageSource: " + objectName);
			}
		}
	}

	private Object applyChannelInterceptor(Object bean, DirectChannelMetrics interceptor, ClassLoader beanClassLoader) {
		NameMatchMethodPointcutAdvisor channelsAdvice = new NameMatchMethodPointcutAdvisor(interceptor);
		channelsAdvice.addMethodName("send");
		channelsAdvice.addMethodName("receive");
		return applyAdvice(bean, channelsAdvice, beanClassLoader);
	}

	private Object applyHandlerInterceptor(Object bean, SimpleMessageHandlerMetrics interceptor,
			ClassLoader beanClassLoader) {
		NameMatchMethodPointcutAdvisor handlerAdvice = new NameMatchMethodPointcutAdvisor(interceptor);
		handlerAdvice.addMethodName("handleMessage");
		return applyAdvice(bean, handlerAdvice, beanClassLoader);
	}

	private Object applySourceInterceptor(Object bean, SimpleMessageSourceMetrics interceptor,
			ClassLoader beanClassLoader) {
		NameMatchMethodPointcutAdvisor sourceAdvice = new NameMatchMethodPointcutAdvisor(interceptor);
		sourceAdvice.addMethodName("receive");
		return applyAdvice(bean, sourceAdvice, beanClassLoader);
	}

	private Object extractTarget(Object bean) {
		if (!(bean instanceof Advised)) {
			return bean;
		}
		Advised advised = (Advised) bean;
		if (advised.getTargetSource() == null) {
			return null;
		}
		try {
			return extractTarget(advised.getTargetSource().getTarget());
		}
		catch (Exception e) {
			logger.error("Could not extract target", e);
			return null;
		}
	}

	private Object applyAdvice(Object bean, PointcutAdvisor advisor, ClassLoader beanClassLoader) {
		Class<?> targetClass = AopUtils.getTargetClass(bean);
		if (AopUtils.canApply(advisor.getPointcut(), targetClass)) {
			if (bean instanceof Advised) {
				((Advised) bean).addAdvisor(advisor);
				return bean;
			}
			else {
				ProxyFactory proxyFactory = new ProxyFactory(bean);
				proxyFactory.addAdvisor(advisor);
				/**
				 * N.B. it's not a good idea to use proxyFactory.setProxyTargetClass(true) here because it forces all
				 * the integration components to be cglib proxyable (i.e. have a default constructor etc.), which they
				 * are not in general (usually for good reason).
				 */
				return proxyFactory.getProxy(beanClassLoader);
			}
		}
		return bean;
	}

	private String getChannelBeanKey(String channel) {
		String name = "" + channel;
		if (name.startsWith("org.springframework.integration")) {
			name = name + ",source=anonymous";
		}
		return String.format(domain + ":type=MessageChannel,name=%s" + getStaticNames(), name);
	}

	private String getHandlerBeanKey(MessageHandlerMetrics handler) {
		// This ordering of keys seems to work with default settings of JConsole
		return String.format(domain + ":type=MessageHandler,name=%s,bean=%s" + getStaticNames(), handler.getName(),
				handler.getSource());
	}

	private String getSourceBeanKey(MessageSourceMetrics handler) {
		// This ordering of keys seems to work with default settings of JConsole
		return String.format(domain + ":type=MessageSource,name=%s,bean=%s" + getStaticNames(), handler.getName(),
				handler.getSource());
	}

	private String getEndpointBeanKey(AbstractEndpoint endpoint, String name, String source) {
		// This ordering of keys seems to work with default settings of JConsole
		return String.format(domain + ":type=ManagedEndpoint,name=%s,bean=%s" + getStaticNames(), name, source);
	}

	private String getStaticNames() {
		if (objectNameStaticProperties.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();

		for (Object key : objectNameStaticProperties.keySet()) {
			builder.append("," + key + "=" + objectNameStaticProperties.get(key));
		}
		return builder.toString();
	}

	private MessageHandlerMetrics enhanceHandlerMonitor(SimpleMessageHandlerMetrics monitor) {

		MessageHandlerMetrics result = monitor;

		if (monitor.getName() != null && monitor.getSource() != null) {
			return monitor;
		}

		// Assignment algorithm and bean id, with bean id pulled reflectively out of enclosing endpoint if possible
		String[] names = beanFactory.getBeanNamesForType(AbstractEndpoint.class);

		String name = null;
		String endpointName = null;
		String source = "endpoint";
		Object endpoint = null;

		for (String beanName : names) {
			endpoint = beanFactory.getBean(beanName);
			Object field = null;
			try {
				field = extractTarget(getField(endpoint, "handler"));
			}
			catch (Exception e) {
				logger.trace("Could not get handler from bean = " + beanName);
			}
			if (field == monitor.getMessageHandler()) {
				name = beanName;
				endpointName = beanName;
				break;
			}
		}
		if (name != null && endpoint != null && name.startsWith("_org.springframework.integration")) {
			name = getInternalComponentName(name);
			source = "internal";
		}
		if (name != null && endpoint != null && name.startsWith("org.springframework.integration")) {
			Object target = endpoint;
			if (endpoint instanceof Advised) {
				TargetSource targetSource = ((Advised) endpoint).getTargetSource();
				if (targetSource != null) {
					try {
						target = targetSource.getTarget();
					}
					catch (Exception e) {
						logger.debug("Could not get handler from bean = " + name);
					}
				}
			}
			Object field = getField(target, "inputChannel");
			if (field != null) {
				if (!anonymousHandlerCounters.containsKey(field)) {
					anonymousHandlerCounters.put(field, new AtomicLong());
				}
				AtomicLong count = anonymousHandlerCounters.get(field);
				long total = count.incrementAndGet();
				String suffix = "";
				/*
				 * Short hack to makes sure object names are unique if more than one endpoint has the same input channel
				 */
				if (total > 1) {
					suffix = "#" + total;
				}
				name = field + suffix;
				source = "anonymous";
			}
		}

		if (endpoint instanceof Lifecycle) {
			// Wrap the monitor in a lifecycle so it exposes the start/stop operations
			result = new LifecycleMessageHandlerMetrics((Lifecycle) endpoint, monitor);
		}

		if (name == null) {
			name = monitor.getMessageHandler().toString();
			source = "handler";
		}

		if (endpointName != null) {
			beansByEndpointName.put(name, endpointName);
		}

		monitor.setSource(source);
		monitor.setName(name);

		return result;

	}

	private String getInternalComponentName(String name) {
		return name.substring("_org.springframework.integration".length() + 1);
	}

	private MessageSourceMetrics enhanceSourceMonitor(SimpleMessageSourceMetrics monitor) {

		MessageSourceMetrics result = monitor;

		if (monitor.getName() != null && monitor.getSource() != null) {
			return monitor;
		}

		// Assignment algorithm and bean id, with bean id pulled reflectively out of enclosing endpoint if possible
		String[] names = beanFactory.getBeanNamesForType(AbstractEndpoint.class);

		String name = null;
		String endpointName = null;
		String source = "endpoint";
		Object endpoint = null;

		for (String beanName : names) {
			endpoint = beanFactory.getBean(beanName);
			Object field = null;
			try {
				field = extractTarget(getField(endpoint, "source"));
			}
			catch (Exception e) {
				logger.trace("Could not get source from bean = " + beanName);
			}
			if (field == monitor.getMessageSource()) {
				name = beanName;
				endpointName = beanName;
				break;
			}
		}
		if (name != null && endpoint != null && name.startsWith("_org.springframework.integration")) {
			name = getInternalComponentName(name);
			source = "internal";
		}
		if (name != null && endpoint != null && name.startsWith("org.springframework.integration")) {
			Object target = endpoint;
			if (endpoint instanceof Advised) {
				TargetSource targetSource = ((Advised) endpoint).getTargetSource();
				if (targetSource != null) {
					try {
						target = targetSource.getTarget();
					}
					catch (Exception e) {
						logger.debug("Could not get handler from bean = " + name);
					}
				}
			}
			Object field = getField(target, "outputChannel");
			if (field != null) {
				if (!anonymousSourceCounters.containsKey(field)) {
					anonymousSourceCounters.put(field, new AtomicLong());
				}
				AtomicLong count = anonymousSourceCounters.get(field);
				long total = count.incrementAndGet();
				String suffix = "";
				/*
				 * Short hack to makes sure object names are unique if more than one endpoint has the same input channel
				 */
				if (total > 1) {
					suffix = "#" + total;
				}
				name = field + suffix;
				source = "anonymous";
			}
		}

		if (endpoint instanceof Lifecycle) {
			// Wrap the monitor in a lifecycle so it exposes the start/stop operations
			result = new LifecycleMessageSourceMetrics((Lifecycle) endpoint, monitor);
		}

		if (name == null) {
			name = monitor.getMessageSource().toString();
			source = "handler";
		}

		if (endpointName != null) {
			beansByEndpointName.put(name, endpointName);
		}

		monitor.setSource(source);
		monitor.setName(name);

		return result;
	}

	private static Object getField(Object target, String name) {
		Assert.notNull(target, "Target object must not be null");
		Field field = ReflectionUtils.findField(target.getClass(), name);
		if (field == null) {
			throw new IllegalArgumentException("Could not find field [" + name + "] on target [" + target + "]");
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Getting field [" + name + "] from target [" + target + "]");
		}
		ReflectionUtils.makeAccessible(field);
		return ReflectionUtils.getField(field, target);
	}

}
