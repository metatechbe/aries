/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aries.subsystem.itests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.equinox;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

import org.apache.aries.subsystem.core.ResourceHelper;
import org.apache.aries.subsystem.core.obr.felix.RepositoryAdminRepository;
import org.apache.aries.subsystem.itests.util.RepositoryGenerator;
import org.apache.aries.subsystem.itests.util.Utils;
import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.container.def.PaxRunnerOptions;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.framework.resource.Resource;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.repository.Repository;
import org.osgi.service.subsystem.Subsystem;
import org.osgi.service.subsystem.Subsystem.State;
import org.osgi.service.subsystem.SubsystemConstants;
import org.osgi.service.subsystem.SubsystemConstants.EVENT_TYPE;

public abstract class SubsystemTest extends IntegrationTest {
	protected static class SubsystemEventHandler implements EventHandler {
		private final Map<Long, List<Event>> subsystemIdToEvents = new HashMap<Long, List<Event>>();
		
		public void clear() {
			synchronized (subsystemIdToEvents) {
				subsystemIdToEvents.clear();
			}
		}
		
		public void handleEvent(Event event) {
			Long subsystemId = (Long)event.getProperty(SubsystemConstants.EVENT_SUBSYSTEM_ID);
			synchronized (subsystemIdToEvents) {
				List <Event> events = subsystemIdToEvents.get(subsystemId);
				if (events == null) {
					events = new ArrayList<Event>();
					subsystemIdToEvents.put(subsystemId, events);
				}
				synchronized (events) {
					events.add(event);
					events.notify();
				}
			}
		}
		
		public Event poll(long subsystemId) throws InterruptedException {
			return poll(subsystemId, 0);
		}
		
		public Event poll(long subsystemId, long timeout) throws InterruptedException {
			List<Event> events;
			synchronized (subsystemIdToEvents) {
				events = subsystemIdToEvents.get(subsystemId);
				if (events == null) {
					events = new ArrayList<Event>();
					subsystemIdToEvents.put(subsystemId, events);
				}
			}
			synchronized (events) {
				if (events.isEmpty()) {
					events.wait(timeout);
					if (events.isEmpty()) {
						return null;
					}
				}
				return events.remove(0);
			}
		}
		
		public int size() {
			synchronized (subsystemIdToEvents) {
				return subsystemIdToEvents.size();
			}
		}
	}
	
	@org.ops4j.pax.exam.junit.Configuration
	public static Option[] configuration() {
		Option[] options = options(
				// Log
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api"),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service"),
				// Felix Config Admin
				mavenBundle("org.apache.felix", "org.apache.felix.configadmin"),
				// Felix mvn url handler
				mavenBundle("org.ops4j.pax.url", "pax-url-mvn"),
				// this is how you set the default log level when using pax
				// logging (logProfile)
				systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("DEBUG"),
				systemProperty("org.osgi.framework.bsnversion").value("multiple"),
				// Bundles
				mavenBundle("org.eclipse.osgi", "services").version("3.3.0-v20110523"),
				mavenBundle("org.eclipse.equinox", "region").version("1.0.0.v20110518"),
				mavenBundle("org.eclipse.equinox", "org.eclipse.equinox.resolver").version("1.0.0-SNAPSHOT"),
				mavenBundle("org.apache.aries.testsupport", "org.apache.aries.testsupport.unit"),
				mavenBundle("org.apache.aries.application", "org.apache.aries.application.api"),
				mavenBundle("org.apache.aries", "org.apache.aries.util"),
				mavenBundle("org.apache.aries.application", "org.apache.aries.application.utils"),
				mavenBundle("org.apache.felix", "org.apache.felix.bundlerepository"),
				mavenBundle("org.eclipse.equinox", "coordinator"),
				mavenBundle("org.eclipse.equinox", "org.eclipse.equinox.event"),
				mavenBundle("org.apache.aries.subsystem", "org.apache.aries.subsystem.api"),
				mavenBundle("org.apache.aries.subsystem", "org.apache.aries.subsystem.core"),
				mavenBundle("org.apache.aries.subsystem", "org.apache.aries.subsystem.executor"),
//				org.ops4j.pax.exam.container.def.PaxRunnerOptions.vmOption("-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"),
				PaxRunnerOptions.rawPaxRunnerOption("config", "classpath:ss-runner.properties"),
				equinox().version("3.8.0-SNAPSHOT"));
		options = updateOptions(options);
		return options;
	}
	
	protected final SubsystemEventHandler subsystemEvents = new SubsystemEventHandler();
	protected final SubsystemEventHandler subsystemInternalEvents = new SubsystemEventHandler();
	
	protected Subsystem rootSubsystem;
	
	private ServiceRegistration<Repository> repositoryReg;
	private ServiceRegistration<EventHandler> subsystemEventsReg;
	private ServiceRegistration<EventHandler> subsystemInternalEventsReg;
	
	public void setUp() {
		super.setUp();
		new RepositoryGenerator(bundleContext).generateOBR();
		repositoryReg = bundleContext.registerService(Repository.class, new RepositoryAdminRepository(getOsgiService(RepositoryAdmin.class)), null);
		Dictionary<String, Object> d = new Hashtable<String, Object>();
		d.put(EventConstants.EVENT_TOPIC, new String[]{"org/osgi/service/Subsystem/*"});
		subsystemEventsReg = bundleContext.registerService(EventHandler.class, subsystemEvents, d);
		d.put(EventConstants.EVENT_TOPIC, new String[]{"org/osgi/service/SubsystemInternals/*"});
		subsystemInternalEventsReg = bundleContext.registerService(EventHandler.class, subsystemInternalEvents, d);
		rootSubsystem = getOsgiService(Subsystem.class);
		assertSubsystemNotNull(rootSubsystem);
	}
	
	public void tearDown() {
		Utils.unregisterQuietly(subsystemInternalEventsReg);
		Utils.unregisterQuietly(subsystemEventsReg);
		Utils.unregisterQuietly(repositoryReg);
		super.tearDown();
	}
	
	protected void assertChild(Subsystem parent, Subsystem child) {
		Collection<Subsystem> children = new ArrayList<Subsystem>(1);
		children.add(child);
		assertChildren(parent, children);
	}
	
	protected void assertChildren(int size, Subsystem subsystem) {
		assertEquals("Wrong number of children", size, subsystem.getChildren().size());
	}
	
	protected void assertChildren(Subsystem parent, Collection<Subsystem> children) {
		assertTrue("Parent did not contain all children", parent.getChildren().containsAll(children));
	}
	
	protected void assertConstituent(Subsystem subsystem, String symbolicName, Version version, String type) {
		for (Resource resource : subsystem.getConstituents()) {
			if (symbolicName.equals(ResourceHelper.getSymbolicNameAttribute(resource))) {
				if (version != null)
					assertVersion(version, ResourceHelper.getVersionAttribute(resource));
				if (type != null)
					assertEquals("Wrong type", type, ResourceHelper.getTypeAttribute(resource));
				return;
			}
		}
		Assert.fail("Constituent not found: " + symbolicName);
	}
	
	protected void assertConstituents(int size, Subsystem subsystem) {
		assertEquals("Wrong number of constituents", size, subsystem.getConstituents().size());
	}
	
 	protected void assertDirectory(Subsystem subsystem) {
 		Bundle bundle = getSubsystemCoreBundle();
 		File file = bundle.getDataFile("subsystem" + subsystem.getSubsystemId());
 		assertNotNull("Subsystem data file was null", file);
 		assertTrue("Subsystem data file does not exist", file.exists());
 	}
 	
 	protected void assertNotDirectory(Subsystem subsystem) {
 		Bundle bundle = getSubsystemCoreBundle();
 		File file = bundle.getDataFile("subsystem" + subsystem.getSubsystemId());
 		assertNotNull("Subsystem data file was null", file);
 		assertFalse("Subsystem data file exists", file.exists());
 	}
 	
 	protected void assertEvent(Subsystem subsystem, Subsystem.State state, SubsystemConstants.EVENT_TYPE type) throws InterruptedException {
 		assertEvent(subsystem, state, type, 0);
 	}
 	
 	protected void assertEvent(Subsystem subsystem, Subsystem.State state, SubsystemConstants.EVENT_TYPE type, long timeout) throws InterruptedException {
 		assertEvent(subsystem, state, type, subsystemEvents.poll(subsystem.getSubsystemId(), timeout));
 	}
 	
 	protected void assertEvent(Subsystem subsystem, Subsystem.State state, SubsystemConstants.EVENT_TYPE type, Event event) {
		assertEvent(subsystem, state, type, event, null);
	}
	
	protected void assertEvent(Subsystem subsystem, Subsystem.State state, SubsystemConstants.EVENT_TYPE type, Event event, Throwable throwable) {
		assertNotNull("The event was null", event);
		assertTrue("Wrong topic: " + event.getTopic(), event.getTopic().endsWith(type.name()));
		assertEquals("Wrong ID", subsystem.getSubsystemId(), event.getProperty(SubsystemConstants.EVENT_SUBSYSTEM_ID));
		assertEquals("Wrong location", subsystem.getLocation(), event.getProperty(SubsystemConstants.EVENT_SUBSYSTEM_LOCATION));
		if (!EVENT_TYPE.INSTALLING.equals(type)) {
			assertEquals("Wrong symbolic name", subsystem.getSymbolicName(), event.getProperty(SubsystemConstants.SUBSYSTEM_SYMBOLICNAME));
			assertEquals("Wrong version", String.valueOf(subsystem.getVersion()), event.getProperty(SubsystemConstants.SUBSYSTEM_VERSION));
		}
		assertEquals("Wrong state", String.valueOf(state), event.getProperty("subsystem.state"));
		assertNotNull("Missing timestamp", event.getProperty(EventConstants.TIMESTAMP));
		if (throwable == null) {
			assertNull("Exception not expected", event.getProperty(EventConstants.EXCEPTION));
			assertNull("Exception class not expected", event.getProperty(EventConstants.EXCEPTION_CLASS));
			assertNull("Exception message not expected", event.getProperty(EventConstants.EXCEPTION_MESSAGE));
		}
		else {
			assertTrue("Wrong exception", event.getProperty(EventConstants.EXCEPTION) instanceof Throwable);
			assertEquals("Wrong exception class", throwable.getClass().getName(), event.getProperty(EventConstants.EXCEPTION_CLASS));
			assertTrue("Wrong message", ((String)event.getProperty(EventConstants.EXCEPTION_MESSAGE)).indexOf(throwable.getMessage()) != -1);
		}
	}
	
	protected void assertId(Subsystem subsystem) {
		assertId(subsystem.getSubsystemId());
	}
	
	protected void assertId(Long id) {
		assertTrue("Subsystem ID was not a positive integer: " + id, id > 0);
	}
	
	protected void assertLocation(String expected, String actual) {
		assertTrue("Wrong location: " + actual, actual.indexOf(expected) != -1);
	}
	
	protected void assertLocation(String expected, Subsystem subsystem) {
		assertLocation(expected, subsystem.getLocation());
	}
	
	protected void assertNotChild(Subsystem parent, Subsystem child) {
		assertFalse("Parent contained child", parent.getChildren().contains(child));
	}
	
	protected void assertParent(Subsystem expected, Subsystem subsystem) {
		assertEquals("Wrong parent", expected, subsystem.getParent());
	}
	
	protected void assertState(State expected, State actual) {
		assertState(EnumSet.of(expected), actual);
	}
	
	protected void assertState(EnumSet<State> expected, State actual) {
		assertTrue("Wrong state: " + actual, expected.contains(actual));
	}
	
	protected void assertState(State expected, Subsystem subsystem) {
		assertState(expected, subsystem.getState());
	}
	
	protected void assertState(EnumSet<State> expected, Subsystem subsystem) {
		assertState(expected, subsystem.getState());
	}
	
	protected Subsystem assertSubsystemLifeCycle(File file) throws Exception {
		Subsystem rootSubsystem = getOsgiService(Subsystem.class);
        assertNotNull("Root subsystem was null", rootSubsystem);
        Subsystem subsystem = rootSubsystem.install(file.toURI().toURL().toExternalForm());
        assertNotNull("The subsystem was null", subsystem);
        assertState(EnumSet.of(State.INSTALLING, State.INSTALLED), subsystem.getState());
		assertEvent(subsystem, Subsystem.State.INSTALLING, SubsystemConstants.EVENT_TYPE.INSTALLING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.INSTALLED, SubsystemConstants.EVENT_TYPE.INSTALLED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertChild(rootSubsystem, subsystem);
        subsystem.start();
        assertState(EnumSet.of(State.RESOLVING, State.RESOLVED, State.STARTING, State.ACTIVE), subsystem.getState());
		assertEvent(subsystem, Subsystem.State.RESOLVING, SubsystemConstants.EVENT_TYPE.RESOLVING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.RESOLVED, SubsystemConstants.EVENT_TYPE.RESOLVED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.STARTING, SubsystemConstants.EVENT_TYPE.STARTING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.ACTIVE, SubsystemConstants.EVENT_TYPE.STARTED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		subsystem.stop();
		assertState(EnumSet.of(State.STOPPING, State.RESOLVED), subsystem.getState());
		assertEvent(subsystem, Subsystem.State.STOPPING, SubsystemConstants.EVENT_TYPE.STOPPING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.RESOLVED, SubsystemConstants.EVENT_TYPE.STOPPED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		// TODO Add update.
		subsystem.uninstall();
		assertState(EnumSet.of(State.UNINSTALLING, State.UNINSTALLED), subsystem.getState());
		assertEvent(subsystem, Subsystem.State.UNINSTALLING, SubsystemConstants.EVENT_TYPE.UNINSTALLING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.UNINSTALLED, SubsystemConstants.EVENT_TYPE.UNINSTALLED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertNotChild(rootSubsystem, subsystem);
		return subsystem;
	}
	
	protected void assertSubsystemNotNull(Subsystem subsystem) {
		assertNotNull("Subsystem was null", subsystem);
	}
	
	protected void assertSymbolicName(String expected, Subsystem subsystem) {
		assertEquals("Wrong symbolic name: " + subsystem.getSymbolicName(), expected, subsystem.getSymbolicName());
	}
	
	protected void assertVersion(String expected, Subsystem subsystem) {
		assertVersion(Version.parseVersion(expected), subsystem);
	}
	
	protected void assertVersion(Version expected, Subsystem subsystem) {
		assertVersion(expected, subsystem.getVersion());
	}
	
	protected void assertVersion(Version expected, Version actual) {
		assertEquals("Wrong version", expected, actual);
	}
	
	protected Bundle getSubsystemCoreBundle() {
		return findBundleBySymbolicName("org.apache.aries.subsystem.core");
	}
	
	protected Subsystem installSubsystemFromFile(Subsystem parent, String fileName) throws Exception {
		return installSubsystemFromFile(parent, new File(fileName));
	}
	
	protected Subsystem installSubsystemFromFile(String fileName) throws Exception {
		return installSubsystemFromFile(new File(fileName));
	}
	
	protected Subsystem installSubsystemFromFile(Subsystem parent, File file) throws Exception {
		return installSubsystem(parent, file.toURI().toURL().toExternalForm());
	}
	
	protected Subsystem installSubsystemFromFile(File file) throws Exception {
		return installSubsystem(rootSubsystem, file.toURI().toURL().toExternalForm());
	}
	
	protected Subsystem installSubsystem(String location) throws Exception {
		return installSubsystem(rootSubsystem, location);
	}
	
	protected Subsystem installSubsystem(String location, InputStream content) throws Exception {
		return installSubsystem(rootSubsystem, location, content);
	}
	
	protected Subsystem installSubsystem(Subsystem parent, String location) throws Exception {
		return installSubsystem(parent, location, new URL(location).openStream());
	}
	
	protected Subsystem installSubsystem(Subsystem parent, String location, InputStream content) throws Exception {
		subsystemEvents.clear();
		Subsystem subsystem = rootSubsystem.install(location, content);
		assertSubsystemNotNull(subsystem);
		assertEvent(subsystem, Subsystem.State.INSTALLING, SubsystemConstants.EVENT_TYPE.INSTALLING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.INSTALLED, SubsystemConstants.EVENT_TYPE.INSTALLED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertChild(parent, subsystem);
		assertLocation(location, subsystem);
		assertParent(parent, subsystem);
		assertState(EnumSet.of(State.INSTALLED, State.INSTALLING), subsystem);
		assertLocation(location, subsystem);
		assertId(subsystem);
		assertDirectory(subsystem);
		assertState(State.INSTALLED, subsystem);
		return subsystem;
	}
	
	protected void startSubsystem(Subsystem subsystem) throws Exception {
		assertState(State.INSTALLED, subsystem);
		subsystemEvents.clear();
		subsystem.start();
		assertState(EnumSet.of(State.RESOLVING, State.RESOLVED, State.STARTING, State.ACTIVE), subsystem);
		assertEvent(subsystem, Subsystem.State.RESOLVING, SubsystemConstants.EVENT_TYPE.RESOLVING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.RESOLVED, SubsystemConstants.EVENT_TYPE.RESOLVED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.STARTING, SubsystemConstants.EVENT_TYPE.STARTING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.ACTIVE, SubsystemConstants.EVENT_TYPE.STARTED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertState(State.ACTIVE, subsystem);
	}
	
	protected void stopSubsystem(Subsystem subsystem) throws Exception {
		assertState(State.ACTIVE, subsystem);
		subsystemEvents.clear();
		subsystem.stop();
		assertState(EnumSet.of(State.STOPPING, State.RESOLVED), subsystem);
		assertEvent(subsystem, Subsystem.State.STOPPING, SubsystemConstants.EVENT_TYPE.STOPPING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.RESOLVED, SubsystemConstants.EVENT_TYPE.STOPPED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertState(State.RESOLVED, subsystem);
	}
	
	protected void uninstallSubsystem(Subsystem subsystem) throws Exception {
		assertState(EnumSet.of(State.INSTALLED, State.RESOLVED), subsystem);
		subsystemEvents.clear();
		Subsystem parent = subsystem.getParent();
		subsystem.uninstall();
		assertState(EnumSet.of(State.UNINSTALLED, State.UNINSTALLING), subsystem);
		assertEvent(subsystem, Subsystem.State.UNINSTALLING, SubsystemConstants.EVENT_TYPE.UNINSTALLING, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertEvent(subsystem, Subsystem.State.UNINSTALLED, SubsystemConstants.EVENT_TYPE.UNINSTALLED, subsystemEvents.poll(subsystem.getSubsystemId(), 5000));
		assertState(State.UNINSTALLED, subsystem);
		assertConstituents(0, subsystem);
		assertNotChild(parent, subsystem);
		assertNotDirectory(subsystem);
	}
}