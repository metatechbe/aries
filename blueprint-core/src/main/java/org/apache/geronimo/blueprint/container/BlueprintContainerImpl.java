/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.geronimo.blueprint.container;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.geronimo.blueprint.BeanProcessor;
import org.apache.geronimo.blueprint.BlueprintConstants;
import org.apache.geronimo.blueprint.ComponentDefinitionRegistryProcessor;
import org.apache.geronimo.blueprint.ExtendedBeanMetadata;
import org.apache.geronimo.blueprint.ExtendedBlueprintContainer;
import org.apache.geronimo.blueprint.NamespaceHandler;
import org.apache.geronimo.blueprint.Processor;
import org.apache.geronimo.blueprint.di.DefaultExecutionContext;
import org.apache.geronimo.blueprint.di.ExecutionContext;
import org.apache.geronimo.blueprint.di.Recipe;
import org.apache.geronimo.blueprint.di.Repository;
import org.apache.geronimo.blueprint.namespace.ComponentDefinitionRegistryImpl;
import org.apache.geronimo.blueprint.namespace.NamespaceHandlerRegistryImpl;
import org.apache.geronimo.blueprint.utils.HeaderParser;
import org.apache.geronimo.blueprint.utils.HeaderParser.PathElement;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.blueprint.container.BlueprintContainer;
import org.osgi.service.blueprint.container.BlueprintEvent;
import org.osgi.service.blueprint.container.BlueprintListener;
import org.osgi.service.blueprint.container.ComponentDefinitionException;
import org.osgi.service.blueprint.container.Converter;
import org.osgi.service.blueprint.container.NoSuchComponentException;
import org.osgi.service.blueprint.reflect.BeanArgument;
import org.osgi.service.blueprint.reflect.BeanMetadata;
import org.osgi.service.blueprint.reflect.BeanProperty;
import org.osgi.service.blueprint.reflect.CollectionMetadata;
import org.osgi.service.blueprint.reflect.ComponentMetadata;
import org.osgi.service.blueprint.reflect.Listener;
import org.osgi.service.blueprint.reflect.MapEntry;
import org.osgi.service.blueprint.reflect.MapMetadata;
import org.osgi.service.blueprint.reflect.Metadata;
import org.osgi.service.blueprint.reflect.PropsMetadata;
import org.osgi.service.blueprint.reflect.RefCollectionMetadata;
import org.osgi.service.blueprint.reflect.RefMetadata;
import org.osgi.service.blueprint.reflect.RegistrationListener;
import org.osgi.service.blueprint.reflect.ServiceMetadata;
import org.osgi.service.blueprint.reflect.ServiceReferenceMetadata;
import org.osgi.service.blueprint.reflect.Target;
import org.osgi.service.blueprint.reflect.ReferenceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: javadoc
 *
 * @author <a href="mailto:dev@geronimo.apache.org">Apache Geronimo Project</a>
 * @version $Rev: 760378 $, $Date: 2009-03-31 11:31:38 +0200 (Tue, 31 Mar 2009) $
 */
public class BlueprintContainerImpl implements ExtendedBlueprintContainer, NamespaceHandlerRegistry.Listener, Runnable, SatisfiableRecipe.SatisfactionListener {

    public static final boolean BEHAVIOR_TCK_INJECTION = true;

    private static final Logger LOGGER = LoggerFactory.getLogger(BlueprintContainerImpl.class);

    private enum State {
        Unknown,
        WaitForNamespaceHandlers,
        Populated,
        WaitForInitialReferences,
        InitialReferencesSatisfied,
        WaitForInitialReferences2,
        Create,
        Created,
        Failed,
    }

    private final BundleContext bundleContext;
    private final Bundle extenderBundle;
    private final BlueprintListener eventDispatcher;
    private final NamespaceHandlerRegistry handlers;
    private final List<URL> urls;
    private final ComponentDefinitionRegistryImpl componentDefinitionRegistry;
    private final AggregateConverter converter;
    private final ScheduledExecutorService executors;
    private Set<URI> namespaces;
    private State state = State.Unknown;
    private boolean destroyed;
    private Parser parser;
    private BlueprintObjectInstantiator instantiator;
    private ServiceRegistration registration;
    private List<Processor> processors;
    private Map<String, List<SatisfiableRecipe>> satisfiables;
    private long timeout = 5 * 60 * 1000;
    private boolean waitForDependencies = true;
    private boolean xmlValidation = true;
    private ScheduledFuture timeoutFuture;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    public BlueprintContainerImpl(BundleContext bundleContext, Bundle extenderBundle, BlueprintListener eventDispatcher, NamespaceHandlerRegistry handlers, ScheduledExecutorService executors, List<URL> urls) {
        this.bundleContext = bundleContext;
        this.extenderBundle = extenderBundle;
        this.eventDispatcher = eventDispatcher;
        this.handlers = handlers;
        this.urls = urls;
        this.converter = new AggregateConverter(this);
        this.componentDefinitionRegistry = new ComponentDefinitionRegistryImpl();
        this.executors = executors;
        this.processors = new ArrayList<Processor>();
    }

    public Bundle getExtenderBundle() {
        return extenderBundle;
    }

    public Class loadClass(String name) throws ClassNotFoundException {
        return bundleContext.getBundle().loadClass(name);
    }

    public <T extends Processor> List<T> getProcessors(Class<T> clazz) {
        List<T> p = new ArrayList<T>();
        for (Processor processor : processors) {
            if (clazz.isInstance(processor)) {
                p.add(clazz.cast(processor));
            }
        }
        return p;
    }

    public BlueprintListener getEventDispatcher() {
        return eventDispatcher;
    }

    private void checkDirectives() {
        Bundle bundle = bundleContext.getBundle();
        Dictionary headers = bundle.getHeaders();
        String symbolicName = (String)headers.get(Constants.BUNDLE_SYMBOLICNAME);
        List<PathElement> paths = HeaderParser.parseHeader(symbolicName);

        String timeoutDirective = paths.get(0).getDirective(BlueprintConstants.TIMEOUT_DIRECTIVE);
        if (timeoutDirective != null) {
            LOGGER.debug("Timeout directive: " + timeoutDirective);
            timeout = Integer.parseInt(timeoutDirective);
        }

        String waitForDependenciesDirective = paths.get(0).getDirective(BlueprintConstants.WAIT_FOR_DEPENDENCIES_DIRECTIVE);
        if (waitForDependenciesDirective != null) {
            LOGGER.debug("Wait-for-dependencies directive: " + waitForDependenciesDirective);
            waitForDependencies = Boolean.parseBoolean(waitForDependenciesDirective);
        }

        String xmlValidationDirective = paths.get(0).getDirective(BlueprintConstants.XML_VALIDATION);
        if (xmlValidationDirective != null) {
            LOGGER.debug("Xml-validation directive: " + xmlValidationDirective);
            xmlValidation = Boolean.parseBoolean(xmlValidationDirective);
        }
    }
    
    public void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            executors.submit(this);
        }
    }
    
    public void run() {
        scheduled.set(false);
        synchronized (scheduled) {
            synchronized (running) {
                running.set(true);
                try {
                    doRun();
                } finally {
                    running.set(false);
                    running.notifyAll();
                }
            }
        }
    }

    /**
     * This method must be called inside a synchronized block to ensure this method is not run concurrently
     */
    private void doRun() {
        try {
            for (;;) {
                if (destroyed) {
                    return;
                }
                LOGGER.debug("Running blueprint container for bundle {} in state {}", bundleContext.getBundle().getSymbolicName(), state);
                switch (state) {
                    case Unknown:
                        checkDirectives();
                        eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.CREATING, getBundleContext().getBundle(), getExtenderBundle()));
                        parser = new Parser();
                        parser.setValidation(xmlValidation);
                        parser.parse(urls);
                        namespaces = parser.getNamespaces();
                        if (namespaces.size() > 0) {
                            handlers.addListener(this);
                        }
                        state = State.WaitForNamespaceHandlers;
                        break;
                    case WaitForNamespaceHandlers:
                    {
                        List<String> missing = new ArrayList<String>();
                        for (URI ns : namespaces) {
                            if (handlers.getNamespaceHandler(ns) == null) {
                                missing.add("(&(" + Constants.OBJECTCLASS + "=" + NamespaceHandler.class.getName() + ")(" + NamespaceHandlerRegistryImpl.NAMESPACE + "=" + ns + "))");
                            }
                        }
                        if (missing.size() > 0) {
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.GRACE_PERIOD, getBundleContext().getBundle(), getExtenderBundle(), missing.toArray(new String[missing.size()])));
                            return;
                        }
                        parser.populate(handlers, componentDefinitionRegistry);
                        state = State.Populated;
                        break;
                    }
                    case Populated:
                        instantiator = new BlueprintObjectInstantiator(this, new RecipeBuilder(this).createRepository());
                        trackServiceReferences();
                        Runnable r = new Runnable() {
                            public void run() {
                                synchronized (scheduled) {
                                    Throwable t = new TimeoutException();
                                    state = State.Failed;
                                    unregisterServices();
                                    untrackServiceReferences();
                                    destroyComponents();
                                    LOGGER.error("Unable to start blueprint container for bundle " + bundleContext.getBundle().getSymbolicName(), t);
                                    eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.FAILURE, getBundleContext().getBundle(), getExtenderBundle(), getMissingDependencies(), t));
                                }
                            }
                        };
                        timeoutFuture = executors.schedule(r, timeout, TimeUnit.MILLISECONDS);
                        state = State.WaitForInitialReferences;
                        break;
                    case WaitForInitialReferences:
                        if (!waitForDependencies || checkAllSatisfiables()) {
                            state = State.InitialReferencesSatisfied;
                            break;
                        } else {
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.GRACE_PERIOD, getBundleContext().getBundle(), getExtenderBundle(), getMissingDependencies()));
                            return;
                        }
                    case InitialReferencesSatisfied:
                        processTypeConverters();
                        processProcessors();
                        state = State.WaitForInitialReferences2;
                        break;
                    case WaitForInitialReferences2:
                        if (!waitForDependencies || checkAllSatisfiables()) {
                            state = State.Create;
                            break;
                        } else {
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.GRACE_PERIOD, getBundleContext().getBundle(), getExtenderBundle(), getMissingDependencies()));
                            return;
                        }
                    case Create:
                        timeoutFuture.cancel(false);
                        registerServices();
                        instantiateEagerSingletonBeans();

                        // Register the BlueprintContainer in the OSGi registry
                        if (registration == null) {
                            Properties props = new Properties();
                            props.put(BlueprintConstants.CONTAINER_SYMBOLIC_NAME_PROPERTY,
                                      bundleContext.getBundle().getSymbolicName());
                            props.put(BlueprintConstants.CONTAINER_VERSION_PROPERTY,
                                      bundleContext.getBundle().getHeaders().get(Constants.BUNDLE_VERSION));
                            registration = bundleContext.registerService(BlueprintContainer.class.getName(), this, props);
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.CREATED, getBundleContext().getBundle(), getExtenderBundle()));
                            state = State.Created;
                        }
                        break;
                    case Created:
                    case Failed:
                        return;
                }
            }
        } catch (Throwable t) {
            state = State.Failed;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            unregisterServices();
            untrackServiceReferences();
            destroyComponents();
            LOGGER.error("Unable to start blueprint container for bundle " + bundleContext.getBundle().getSymbolicName(), t);
            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.FAILURE, getBundleContext().getBundle(), getExtenderBundle(), t));
        }
    }

    private void processTypeConverters() throws Exception {
        List<String> typeConverters = new ArrayList<String>();
        for (Target target : componentDefinitionRegistry.getTypeConverters()) {
            if (target instanceof ComponentMetadata) {
                typeConverters.add(((ComponentMetadata) target).getId());
            } else if (target instanceof RefMetadata) {
                typeConverters.add(((RefMetadata) target).getComponentId());
            } else {
                throw new ComponentDefinitionException("Unexpected metadata for type converter: " + target);
            }
        }

        Map<String, Object> objects = instantiator.createAll(typeConverters.toArray(new String[typeConverters.size()]));
        for (Object obj : objects.values()) {
            if (obj instanceof Converter) {
                converter.registerConverter((Converter) obj);
            } else {
                throw new ComponentDefinitionException("Type converter " + obj + " does not implement the " + Converter.class.getName() + " interface");
            }
        }
    }

    private void processProcessors() throws Exception {
        // Instanciate ComponentDefinitionRegistryProcessor and BeanProcessor
        for (BeanMetadata bean : getMetadata(BeanMetadata.class)) {
            if (bean instanceof ExtendedBeanMetadata && !((ExtendedBeanMetadata) bean).isProcessor()) {
                continue;
            }
            Class clazz = bean.getRuntimeClass();
            if (clazz == null && bean.getClassName() != null) {
                clazz = loadClass(bean.getClassName());
            }
            if (clazz == null) {
                continue;
            }
            if (ComponentDefinitionRegistryProcessor.class.isAssignableFrom(clazz)) {
                Object obj = instantiator.create(bean.getId());
                ((ComponentDefinitionRegistryProcessor) obj).process(componentDefinitionRegistry);
                // Update repository with recipes processed by the processors
                untrackServiceReferences();
                Repository repository = instantiator.getRepository();
                Repository tmpRepo = new RecipeBuilder(this).createRepository();
                for (String name : tmpRepo.getNames()) {
                    if (repository.getInstance(name) == null) {
                        repository.putRecipe(name, tmpRepo.getRecipe(name));
                    }
                }
                satisfiables = null;
                trackServiceReferences();
            } else if (Processor.class.isAssignableFrom(clazz)) {
                Object obj = instantiator.create(bean.getId());
                this.processors.add((Processor) obj);
            }
        }
    }

    private Map<String, List<SatisfiableRecipe>> getSatisfiableDependenciesMap() {
        if (satisfiables == null && instantiator != null) {
            ExecutionContext oldContext = ExecutionContext.setContext(new DefaultExecutionContext(this, instantiator.getRepository()));
            try {
                satisfiables = new HashMap<String, List<SatisfiableRecipe>>();
                for (Recipe r : instantiator.getAllRecipes()) {
                    List<SatisfiableRecipe> recipes = instantiator.getAllRecipes(SatisfiableRecipe.class, r.getName());
                    if (!recipes.isEmpty()) {
                        satisfiables.put(r.getName(), recipes);
                    }
                }
                return satisfiables;
            } finally {
                ExecutionContext.setContext(oldContext);
            }
        }
        return satisfiables;
    }

    private void trackServiceReferences() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        Set<String> satisfiables = new HashSet<String>();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe satisfiable : dependencies.get(name)) {
                if (satisfiables.add(satisfiable.getName())) {
                    satisfiable.start(this);
                }
            }
        }
        LOGGER.debug("Tracking service references: {}", satisfiables);
    }
    
    private void untrackServiceReferences() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        if (dependencies != null) {
            Set<String> satisfiables = new HashSet<String>();
            for (String name : dependencies.keySet()) {
                for (SatisfiableRecipe satisfiable : dependencies.get(name)) {
                    if (satisfiables.add(satisfiable.getName())) {
                        satisfiable.stop();
                    }
                }
            }
        }
    }

    private boolean checkAllSatisfiables() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe recipe : dependencies.get(name)) {
                if (!recipe.isSatisfied()) {
                    return false;
                }
            }
        }
        return true;
    }

    public void notifySatisfaction(SatisfiableRecipe satisfiable) {
        LOGGER.debug("Notified satisfaction {} in bundle {}: {}",
                new Object[] { satisfiable.getName(), bundleContext.getBundle().getSymbolicName(), satisfiable.isSatisfied() });
        if (state == State.WaitForInitialReferences) {
            schedule();
        } else if (state == State.Created) {
            Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
            for (String name : dependencies.keySet()) {
                ComponentMetadata metadata = componentDefinitionRegistry.getComponentDefinition(name);
                if (metadata instanceof ServiceMetadata) {
                    boolean satisfied = true;
                    for (SatisfiableRecipe recipe : dependencies.get(name)) {
                        if (!recipe.isSatisfied()) {
                            satisfied = false;
                            break;
                        }
                    }
                    ServiceRecipe reg = (ServiceRecipe) instantiator.getRepository().getRecipe(name);
                    if (satisfied && !reg.isRegistered()) {
                        LOGGER.debug("Registering service {} due to satisfied references", name);
                        reg.register();
                    } else if (!satisfied && reg.isRegistered()) {
                        LOGGER.debug("Unregistering service {} due to unsatisfied references", name);
                        reg.unregister();
                    }
                }
            }
        }
    }

    private void instantiateEagerSingletonBeans() {
        List<String> components = new ArrayList<String>();
        for (String name : componentDefinitionRegistry.getComponentDefinitionNames()) {
            ComponentMetadata component = componentDefinitionRegistry.getComponentDefinition(name);
            boolean eager = component.getInitialization() == ComponentMetadata.INITIALIZATION_EAGER;
            if (component instanceof BeanMetadata) {
                BeanMetadata local = (BeanMetadata) component;
                String scope = local.getScope();
                eager &= BeanMetadata.SCOPE_SINGLETON.equals(scope);
            }
            if (eager) {
                components.add(name);
            }
        }
        LOGGER.debug("Instantiating components: {}", components);
        try {
            instantiator.createAll(components);
        } catch (ComponentDefinitionException e) {
            throw e;
        } catch (Throwable t) {
            throw new ComponentDefinitionException("Unable to instantiate components", t);
        }
    }

    private void registerServices() {
        List<ServiceRecipe> recipes = instantiator.getAllRecipes(ServiceRecipe.class);
        for (ServiceRecipe r : recipes) {
            List<SatisfiableRecipe> dependencies = getSatisfiableDependenciesMap().get(r.getName());
            boolean satisfied = true;
            if (dependencies != null) {
                for (SatisfiableRecipe recipe : dependencies) {
                    if (!recipe.isSatisfied()) {
                        satisfied = false;
                        break;
                    }
                }
            }
            if (satisfied) {
                r.register();
            }
        }
    }

    private void unregisterServices() {
        if (instantiator != null) {
            List<ServiceRecipe> recipes = instantiator.getAllRecipes(ServiceRecipe.class);
            for (ServiceRecipe r : recipes) {
                r.unregister();
            }
        }
    }

    private void destroyComponents() {
        if (instantiator != null) {
            instantiator.getRepository().destroy();
        }
    }

    private String[] getMissingDependencies() {
        List<String> missing = new ArrayList<String>();
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        Set<SatisfiableRecipe> recipes = new HashSet<SatisfiableRecipe>();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe recipe : dependencies.get(name)) {
                if (!recipe.isSatisfied()) {
                    recipes.add(recipe);
                }
            }
        }
        for (SatisfiableRecipe recipe : recipes) {
            missing.add(recipe.getOsgiFilter());
        }
        return missing.toArray(new String[missing.size()]);
    }
    
    public Set<String> getComponentIds() {
        return componentDefinitionRegistry.getComponentDefinitionNames();
    }
    
    public Object getComponentInstance(String id) throws NoSuchComponentException {
        if (instantiator == null) {
            throw new NoSuchComponentException(id);
        }
        try {
            LOGGER.debug("Instantiating component {}", id);
            return instantiator.create(id);
        } catch (ComponentDefinitionException e) {
            throw e;
        } catch (Throwable t) {
            throw new ComponentDefinitionException("Cound not create component instance for " + id, t);
        }
    }

    public ComponentMetadata getComponentMetadata(String id) {
        ComponentMetadata metadata = componentDefinitionRegistry.getComponentDefinition(id);
        if (metadata == null) {
            throw new NoSuchComponentException(id);
        }
        return metadata;
    }

    public <T extends ComponentMetadata> Collection<T> getMetadata(Class<T> clazz) {
        Collection<T> metadatas = new ArrayList<T>();
        for (String name : componentDefinitionRegistry.getComponentDefinitionNames()) {
            ComponentMetadata component = componentDefinitionRegistry.getComponentDefinition(name);
            getMetadata(clazz, component, metadatas);
        }
        metadatas = Collections.unmodifiableCollection(metadatas);
        return metadatas;
    }

    private <T extends ComponentMetadata> void getMetadata(Class<T> clazz, Metadata component, Collection<T> metadatas) {
        if (component == null) {
            return;
        }
        if (clazz.isInstance(component)) {
            metadatas.add(clazz.cast(component));
        }
        if (component instanceof BeanMetadata) {
            getMetadata(clazz, ((BeanMetadata) component).getFactoryComponent(), metadatas);
            for (BeanArgument arg : ((BeanMetadata) component).getArguments()) {
                getMetadata(clazz, arg.getValue(), metadatas);
            }
            for (BeanProperty prop : ((BeanMetadata) component).getProperties()) {
                getMetadata(clazz, prop.getValue(), metadatas);
            }
        }
        if (component instanceof CollectionMetadata) {
            for (Metadata m : ((CollectionMetadata) component).getValues()) {
                getMetadata(clazz, m, metadatas);
            }
        }
        if (component instanceof MapMetadata) {
            for (MapEntry m : ((MapMetadata) component).getEntries()) {
                getMetadata(clazz, m.getKey(), metadatas);
                getMetadata(clazz, m.getValue(), metadatas);
            }
        }
        if (component instanceof PropsMetadata) {
            for (MapEntry m : ((PropsMetadata) component).getEntries()) {
                getMetadata(clazz, m.getKey(), metadatas);
                getMetadata(clazz, m.getValue(), metadatas);
            }
        }
        if (component instanceof ServiceReferenceMetadata) {
            for (Listener l : ((ServiceReferenceMetadata) component).getServiceListeners()) {
                getMetadata(clazz, l.getListenerComponent(), metadatas);
            }
        }
        if (component instanceof RefCollectionMetadata) {
            getMetadata(clazz, ((RefCollectionMetadata) component).getComparator(), metadatas);
        }
        if (component instanceof ServiceMetadata) {
            getMetadata(clazz, ((ServiceMetadata) component).getServiceComponent(), metadatas);
            for (MapEntry m : ((ServiceMetadata) component).getServiceProperties()) {
                getMetadata(clazz, m.getKey(), metadatas);
                getMetadata(clazz, m.getValue(), metadatas);
            }
            for (RegistrationListener l : ((ServiceMetadata) component).getRegistrationListeners()) {
                getMetadata(clazz, l.getListenerComponent(), metadatas);
            }
        }
    }

    public Repository getRepository() {
        return instantiator.getRepository();
    }
    
    public Converter getConverter() {
        return converter;
    }
    
    public ComponentDefinitionRegistryImpl getComponentDefinitionRegistry() {
        return componentDefinitionRegistry;
    }
        
    public BundleContext getBundleContext() {
        return bundleContext;
    }
    
    public void destroy() {
        destroyed = true;
        eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.DESTROYING, getBundleContext().getBundle(), getExtenderBundle()));

        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        if (registration != null) {
            registration.unregister();
        }
        handlers.removeListener(this);
        unregisterServices();
        untrackServiceReferences();

        synchronized (running) {
            while (running.get()) {
                try {
                    running.wait();
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }

        destroyComponents();
        
        eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.DESTROYED, getBundleContext().getBundle(), getExtenderBundle()));
        LOGGER.debug("Module container destroyed: " + this.bundleContext);
    }

    public void namespaceHandlerRegistered(URI uri) {
        if (namespaces != null && namespaces.contains(uri)) {
            schedule();
        }
    }

    public void namespaceHandlerUnregistered(URI uri) {
        if (namespaces != null && namespaces.contains(uri)) {
            unregisterServices();
            untrackServiceReferences();
            destroyComponents();
            state = State.WaitForNamespaceHandlers;
            schedule();
        }
    }

}

