/*
 * Copyright 2011 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.server;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EventObject;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import javax.portlet.PortletSession;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import com.vaadin.LegacyApplication;
import com.vaadin.annotations.PreserveOnRefresh;
import com.vaadin.data.util.converter.Converter;
import com.vaadin.data.util.converter.ConverterFactory;
import com.vaadin.data.util.converter.DefaultConverterFactory;
import com.vaadin.event.EventRouter;
import com.vaadin.server.VaadinService.VaadinServiceData;
import com.vaadin.ui.AbstractField;
import com.vaadin.ui.Table;
import com.vaadin.ui.UI;
import com.vaadin.ui.Window;
import com.vaadin.util.CurrentInstance;
import com.vaadin.util.ReflectTools;

/**
 * Contains everything that Vaadin needs to store for a specific user. This is
 * typically stored in a {@link HttpSession} or {@link PortletSession}, but
 * others storage mechanisms might also be used.
 * <p>
 * Everything inside a {@link VaadinSession} should be serializable to ensure
 * compatibility with schemes using serialization for persisting the session
 * data.
 * 
 * @author Vaadin Ltd
 * @since 7.0.0
 */
@SuppressWarnings("serial")
public class VaadinSession implements HttpSessionBindingListener, Serializable {

    /**
     * The name of the parameter that is by default used in e.g. web.xml to
     * define the name of the default {@link UI} class.
     */
    public static final String UI_PARAMETER = "UI";

    private static final Method BOOTSTRAP_FRAGMENT_METHOD = ReflectTools
            .findMethod(BootstrapListener.class, "modifyBootstrapFragment",
                    BootstrapFragmentResponse.class);
    private static final Method BOOTSTRAP_PAGE_METHOD = ReflectTools
            .findMethod(BootstrapListener.class, "modifyBootstrapPage",
                    BootstrapPageResponse.class);

    private final Lock lock = new ReentrantLock();

    /**
     * An event sent to {@link #start(SessionStartEvent)} when a new Application
     * is being started.
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public static class SessionStartEvent implements Serializable {
        private final URL applicationUrl;

        private final DeploymentConfiguration configuration;

        private final AbstractCommunicationManager communicationManager;

        /**
         * @param applicationUrl
         *            the URL the application should respond to.
         * @param configuration
         *            the deployment configuration for the session.
         * @param communicationManager
         *            the communication manager for the session.
         */
        public SessionStartEvent(URL applicationUrl,
                DeploymentConfiguration configuration,
                AbstractCommunicationManager communicationManager) {
            this.applicationUrl = applicationUrl;
            this.configuration = configuration;
            this.communicationManager = communicationManager;
        }

        /**
         * Gets the URL the application should respond to.
         * 
         * @return the URL the application should respond to or
         *         <code>null</code> if the URL is not defined.
         * 
         * @see VaadinSession#getURL()
         */
        public URL getApplicationUrl() {
            return applicationUrl;
        }

        /**
         * Returns the deployment configuration used by this session.
         * 
         * @return the deployment configuration.
         */
        public DeploymentConfiguration getConfiguration() {
            return configuration;
        }

        /**
         * Gets the communication manager for this application.
         * 
         * @return the communication manager for this application.
         * 
         * @see VaadinSession#getCommunicationManager
         */
        public AbstractCommunicationManager getCommunicationManager() {
            return communicationManager;
        }
    }

    /**
     * Configuration for the session.
     */
    private DeploymentConfiguration configuration;

    /**
     * The application's URL.
     */
    private URL applicationUrl;

    /**
     * Default locale of the session.
     */
    private Locale locale;

    /**
     * Session wide error handler which is used by default if an error is left
     * unhandled.
     */
    private Terminal.ErrorListener errorHandler = new DefaultErrorListener();

    /**
     * The converter factory that is used to provide default converters for the
     * session.
     */
    private ConverterFactory converterFactory = new DefaultConverterFactory();

    private LinkedList<RequestHandler> requestHandlers = new LinkedList<RequestHandler>();

    private int nextUIId = 0;
    private Map<Integer, UI> uIs = new HashMap<Integer, UI>();

    private final Map<String, Integer> retainOnRefreshUIs = new HashMap<String, Integer>();

    private final EventRouter eventRouter = new EventRouter();

    private GlobalResourceHandler globalResourceHandler;

    protected WebBrowser browser = new WebBrowser();

    private AbstractCommunicationManager communicationManager;

    private long totalSessionTime = 0;

    private long lastRequestTime = -1;

    private transient WrappedSession session;

    private final Map<String, Object> attributes = new HashMap<String, Object>();

    private Map<String, VaadinServiceData> serviceData = new HashMap<String, VaadinServiceData>();

    /**
     * @see javax.servlet.http.HttpSessionBindingListener#valueBound(HttpSessionBindingEvent)
     */
    @Override
    public void valueBound(HttpSessionBindingEvent arg0) {
        // We are not interested in bindings
    }

    /**
     * @see javax.servlet.http.HttpSessionBindingListener#valueUnbound(HttpSessionBindingEvent)
     */
    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        // If we are going to be unbound from the session, the session must be
        // closing
        // Notify all services that have used this session.
        for (VaadinServiceData vaadinServiceData : serviceData.values()) {
            vaadinServiceData.getService().fireSessionDestroy(this);
        }
    }

    /**
     * Get the web browser associated with this session.
     * 
     * @return
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public WebBrowser getBrowser() {
        return browser;
    }

    /**
     * @return The total time spent servicing requests in this session.
     */
    public long getTotalSessionTime() {
        return totalSessionTime;
    }

    /**
     * Sets the time spent servicing the last request in the session and updates
     * the total time spent servicing requests in this session.
     * 
     * @param time
     *            the time spent in the last request.
     */
    public void setLastRequestTime(long time) {
        lastRequestTime = time;
        totalSessionTime += time;
    }

    /**
     * @return the time spent servicing the last request in this session.
     */
    public long getLastRequestTime() {
        return lastRequestTime;
    }

    /**
     * Gets the underlying session to which this vaadin session is currently
     * associated.
     * 
     * @return the wrapped session for this context
     */
    public WrappedSession getSession() {
        return session;
    }

    /**
     * @return
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public AbstractCommunicationManager getCommunicationManager() {
        return communicationManager;
    }

    /**
     * Gets the URL of the application.
     * 
     * <p>
     * This is the URL what can be entered to a browser window to start the
     * application. Navigating to the application URL shows the main window (
     * {@link #getMainWindow()}) of the application. Note that the main window
     * can also be shown by navigating to the window url (
     * {@link com.vaadin.ui.Window#getURL()}).
     * </p>
     * 
     * @return the application's URL.
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public URL getURL() {
        return applicationUrl;
    }

    /**
     * @param underlyingSession
     * @return
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public static VaadinSession getForSession(WrappedSession underlyingSession) {
        Object attribute = underlyingSession.getAttribute(VaadinSession.class
                .getName());
        if (attribute instanceof VaadinSession) {
            VaadinSession vaadinSession = (VaadinSession) attribute;
            vaadinSession.session = underlyingSession;
            return vaadinSession;
        }

        return null;
    }

    /**
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public void removeFromSession() {
        assert (getForSession(session) == this);

        session.setAttribute(VaadinSession.class.getName(), null);
    }

    /**
     * @param session
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public void storeInSession(WrappedSession session) {
        session.setAttribute(VaadinSession.class.getName(), this);
        this.session = session;
    }

    /**
     * Starts the application on the given URL.
     * 
     * <p>
     * This method is called by Vaadin framework when a user navigates to the
     * application. After this call the application corresponds to the given URL
     * and it will return windows when asked for them. There is no need to call
     * this method directly.
     * </p>
     * 
     * <p>
     * Application properties are defined by servlet configuration object
     * {@link javax.servlet.ServletConfig} and they are overridden by
     * context-wide initialization parameters
     * {@link javax.servlet.ServletContext}.
     * </p>
     * 
     * @param event
     *            the application start event containing details required for
     *            starting the application.
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public void start(SessionStartEvent event) {
        applicationUrl = event.getApplicationUrl();
        configuration = event.getConfiguration();
        communicationManager = event.getCommunicationManager();
    }

    /**
     * Gets the configuration for this session
     * 
     * @return the deployment configuration
     */
    public DeploymentConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Gets the default locale for this session.
     * 
     * By default this is the preferred locale of the user using the session. In
     * most cases it is read from the browser defaults.
     * 
     * @return the locale of this session.
     */
    public Locale getLocale() {
        if (locale != null) {
            return locale;
        }
        return Locale.getDefault();
    }

    /**
     * Sets the default locale for this session.
     * 
     * By default this is the preferred locale of the user using the
     * application. In most cases it is read from the browser defaults.
     * 
     * @param locale
     *            the Locale object.
     * 
     */
    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    /**
     * Window detach event.
     * 
     * This event is sent each time a window is removed from the application
     * with {@link com.vaadin.server.VaadinSession#removeWindow(Window)}.
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public static class WindowDetachEvent extends EventObject {

        private final Window window;

        /**
         * Creates a event.
         * 
         * @param application
         *            the application to which the detached window belonged.
         * @param window
         *            the Detached window.
         */
        public WindowDetachEvent(VaadinSession application, Window window) {
            super(application);
            this.window = window;
        }

        /**
         * Gets the detached window.
         * 
         * @return the detached window.
         */
        public Window getWindow() {
            return window;
        }

        /**
         * Gets the application from which the window was detached.
         * 
         * @return the Application.
         */
        public VaadinSession getApplication() {
            return (VaadinSession) getSource();
        }
    }

    /**
     * Window attach event.
     * 
     * This event is sent each time a window is attached tothe application with
     * {@link com.vaadin.server.VaadinSession#addWindow(Window)}.
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public static class WindowAttachEvent extends EventObject {

        private final Window window;

        /**
         * Creates a event.
         * 
         * @param application
         *            the application to which the detached window belonged.
         * @param window
         *            the Attached window.
         */
        public WindowAttachEvent(VaadinSession application, Window window) {
            super(application);
            this.window = window;
        }

        /**
         * Gets the attached window.
         * 
         * @return the attached window.
         */
        public Window getWindow() {
            return window;
        }

        /**
         * Gets the application to which the window was attached.
         * 
         * @return the Application.
         */
        public VaadinSession getApplication() {
            return (VaadinSession) getSource();
        }
    }

    /**
     * Window attach listener interface.
     */
    public interface WindowAttachListener extends Serializable {

        /**
         * Window attached
         * 
         * @param event
         *            the window attach event.
         */
        public void windowAttached(WindowAttachEvent event);
    }

    /**
     * Window detach listener interface.
     */
    public interface WindowDetachListener extends Serializable {

        /**
         * Window detached.
         * 
         * @param event
         *            the window detach event.
         */
        public void windowDetached(WindowDetachEvent event);
    }

    /**
     * Gets the session's error handler.
     * 
     * @return the current error handler
     */
    public Terminal.ErrorListener getErrorHandler() {
        return errorHandler;
    }

    /**
     * Sets the session error handler.
     * 
     * @param errorHandler
     */
    public void setErrorHandler(Terminal.ErrorListener errorHandler) {
        this.errorHandler = errorHandler;
    }

    /**
     * Gets the {@link ConverterFactory} used to locate a suitable
     * {@link Converter} for fields in the session.
     * 
     * See {@link #setConverterFactory(ConverterFactory)} for more details
     * 
     * @return The converter factory used in the session
     */
    public ConverterFactory getConverterFactory() {
        return converterFactory;
    }

    /**
     * Sets the {@link ConverterFactory} used to locate a suitable
     * {@link Converter} for fields in the session.
     * <p>
     * The {@link ConverterFactory} is used to find a suitable converter when
     * binding data to a UI component and the data type does not match the UI
     * component type, e.g. binding a Double to a TextField (which is based on a
     * String).
     * </p>
     * <p>
     * The {@link Converter} for an individual field can be overridden using
     * {@link AbstractField#setConverter(Converter)} and for individual property
     * ids in a {@link Table} using
     * {@link Table#setConverter(Object, Converter)}.
     * </p>
     * <p>
     * The converter factory must never be set to null.
     * 
     * @param converterFactory
     *            The converter factory used in the session
     */
    public void setConverterFactory(ConverterFactory converterFactory) {
        this.converterFactory = converterFactory;
    }

    /**
     * Application error is an error message defined on the application level.
     * 
     * When an error occurs on the application level, this error message4 type
     * should be used. This indicates that the problem is caused by the
     * application - not by the user.
     */
    public class ApplicationError implements Terminal.ErrorEvent {
        private final Throwable throwable;

        public ApplicationError(Throwable throwable) {
            this.throwable = throwable;
        }

        @Override
        public Throwable getThrowable() {
            return throwable;
        }

    }

    /**
     * Adds a request handler to this session. Request handlers can be added to
     * provide responses to requests that are not handled by the default
     * functionality of the framework.
     * <p>
     * Handlers are called in reverse order of addition, so the most recently
     * added handler will be called first.
     * </p>
     * 
     * @param handler
     *            the request handler to add
     * 
     * @see #removeRequestHandler(RequestHandler)
     * 
     * @since 7.0
     */
    public void addRequestHandler(RequestHandler handler) {
        requestHandlers.addFirst(handler);
    }

    /**
     * Removes a request handler from the session.
     * 
     * @param handler
     *            the request handler to remove
     * 
     * @since 7.0
     */
    public void removeRequestHandler(RequestHandler handler) {
        requestHandlers.remove(handler);
    }

    /**
     * Gets the request handlers that are registered to the session. The
     * iteration order of the returned collection is the same as the order in
     * which the request handlers will be invoked when a request is handled.
     * 
     * @return a collection of request handlers, with the iteration order
     *         according to the order they would be invoked
     * 
     * @see #addRequestHandler(RequestHandler)
     * @see #removeRequestHandler(RequestHandler)
     * 
     * @since 7.0
     */
    public Collection<RequestHandler> getRequestHandlers() {
        return Collections.unmodifiableCollection(requestHandlers);
    }

    /**
     * Gets the currently used session. The current session is automatically
     * defined when processing requests to the server and in threads started at
     * a point when the current session is defined (see
     * {@link InheritableThreadLocal}). In other cases, (e.g. from background
     * threads started in some other way), the current session is not
     * automatically defined.
     * 
     * @return the current session instance if available, otherwise
     *         <code>null</code>
     * 
     * @see #setCurrent(VaadinSession)
     * 
     * @since 7.0
     */
    public static VaadinSession getCurrent() {
        return CurrentInstance.get(VaadinSession.class);
    }

    /**
     * Sets the thread local for the current session. This method is used by the
     * framework to set the current session whenever a new request is processed
     * and it is cleared when the request has been processed.
     * <p>
     * The application developer can also use this method to define the current
     * session outside the normal request handling and treads started from
     * request handling threads, e.g. when initiating custom background threads.
     * </p>
     * 
     * @param session
     * 
     * @see #getCurrent()
     * @see ThreadLocal
     * 
     * @since 7.0
     */
    public static void setCurrent(VaadinSession session) {
        CurrentInstance.setInheritable(VaadinSession.class, session);
    }

    /**
     * Gets all the UIs of this session. This includes UIs that have been
     * requested but not yet initialized. UIs that receive no heartbeat requests
     * from the client are eventually removed from the session.
     * 
     * @return a collection of UIs belonging to this application
     * 
     * @since 7.0
     */
    public Collection<UI> getUIs() {
        return Collections.unmodifiableCollection(uIs.values());
    }

    private int connectorIdSequence = 0;

    /**
     * Generate an id for the given Connector. Connectors must not call this
     * method more than once, the first time they need an id.
     * 
     * @param connector
     *            A connector that has not yet been assigned an id.
     * @return A new id for the connector
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public String createConnectorId(ClientConnector connector) {
        return String.valueOf(connectorIdSequence++);
    }

    private static final Logger getLogger() {
        return Logger.getLogger(VaadinSession.class.getName());
    }

    /**
     * Returns a UI with the given id.
     * <p>
     * This is meant for framework internal use.
     * </p>
     * 
     * @param uiId
     *            The UI id
     * @return The UI with the given id or null if not found
     */
    public UI getUIById(int uiId) {
        return uIs.get(uiId);
    }

    /**
     * Adds a listener that will be invoked when the bootstrap HTML is about to
     * be generated. This can be used to modify the contents of the HTML that
     * loads the Vaadin application in the browser and the HTTP headers that are
     * included in the response serving the HTML.
     * 
     * @see BootstrapListener#modifyBootstrapFragment(BootstrapFragmentResponse)
     * @see BootstrapListener#modifyBootstrapPage(BootstrapPageResponse)
     * 
     * @param listener
     *            the bootstrap listener to add
     */
    public void addBootstrapListener(BootstrapListener listener) {
        eventRouter.addListener(BootstrapFragmentResponse.class, listener,
                BOOTSTRAP_FRAGMENT_METHOD);
        eventRouter.addListener(BootstrapPageResponse.class, listener,
                BOOTSTRAP_PAGE_METHOD);
    }

    /**
     * Remove a bootstrap listener that was previously added.
     * 
     * @see #addBootstrapListener(BootstrapListener)
     * 
     * @param listener
     *            the bootstrap listener to remove
     */
    public void removeBootstrapListener(BootstrapListener listener) {
        eventRouter.removeListener(BootstrapFragmentResponse.class, listener,
                BOOTSTRAP_FRAGMENT_METHOD);
        eventRouter.removeListener(BootstrapPageResponse.class, listener,
                BOOTSTRAP_PAGE_METHOD);
    }

    /**
     * Fires a bootstrap event to all registered listeners. There are currently
     * two supported events, both inheriting from {@link BootstrapResponse}:
     * {@link BootstrapFragmentResponse} and {@link BootstrapPageResponse}.
     * 
     * @param response
     *            the bootstrap response event for which listeners should be
     *            fired
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public void modifyBootstrapResponse(BootstrapResponse response) {
        eventRouter.fireEvent(response);
    }

    /**
     * Removes all those UIs from the session for which {@link #isUIAlive}
     * returns false. Cleanup events are fired for the removed UIs.
     * <p>
     * Called by the framework at the end of every request.
     * 
     * @see UI.CleanupEvent
     * @see UI.CleanupListener
     * @see #isUIAlive(UI)
     * 
     * @since 7.0.0
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    public void cleanupInactiveUIs() {
        for (UI ui : new ArrayList<UI>(uIs.values())) {
            if (!isUIAlive(ui)) {
                cleanupUI(ui);
                getLogger().fine(
                        "Closed UI #" + ui.getUIId() + " due to inactivity");
            }
        }
    }

    /**
     * Called by the framework to remove an UI instance because it has been
     * inactive.
     * 
     * @param ui
     *            the UI to remove
     * 
     * @deprecated Method is declared as public only to support
     *             {@link LegacyApplication#close()} and will be removed when
     *             LegacyApplciation support is removed.
     */
    @Deprecated
    public void cleanupUI(UI ui) {
        Integer id = Integer.valueOf(ui.getUIId());
        uIs.remove(id);
        retainOnRefreshUIs.values().remove(id);
        ui.fireCleanupEvent();
    }

    /**
     * Returns the number of seconds that must pass without a valid heartbeat or
     * UIDL request being received from a UI before that UI is removed from the
     * application. This is a lower bound; it might take longer to close an
     * inactive UI. Returns a negative number if heartbeat is disabled and
     * timeout never occurs.
     * 
     * @see #getUidlRequestTimeout()
     * @see #cleanupInactiveUIs()
     * @see DeploymentConfiguration#getHeartbeatInterval()
     * 
     * @since 7.0.0
     * 
     * @return The heartbeat timeout in seconds or a negative number if timeout
     *         never occurs.
     */
    protected int getHeartbeatTimeout() {
        // Permit three missed heartbeats before closing the UI
        return (int) (configuration.getHeartbeatInterval() * (3.1));
    }

    /**
     * Returns the number of seconds that must pass without a valid UIDL request
     * being received from a UI before the UI is removed from the session, even
     * though heartbeat requests are received. This is a lower bound; it might
     * take longer to close an inactive UI. Returns a negative number if
     * <p>
     * This timeout only has effect if cleanup of inactive UIs is enabled;
     * otherwise heartbeat requests are enough to extend UI lifetime
     * indefinitely.
     * 
     * @see DeploymentConfiguration#isIdleUICleanupEnabled()
     * @see #getHeartbeatTimeout()
     * @see #cleanupInactiveUIs()
     * 
     * @since 7.0.0
     * 
     * @return The UIDL request timeout in seconds, or a negative number if
     *         timeout never occurs.
     */
    protected int getUidlRequestTimeout() {
        return configuration.isIdleUICleanupEnabled() ? getSession()
                .getMaxInactiveInterval() : -1;
    }

    /**
     * Returns whether the given UI is alive (the client-side actively
     * communicates with the server) or whether it can be removed from the
     * session and eventually collected.
     * 
     * @since 7.0.0
     * 
     * @deprecated Might be refactored or removed before 7.0.0
     * 
     * @param ui
     *            The UI whose status to check
     * @return true if the UI is alive, false if it could be removed.
     * 
     * @deprecated might be refactored or removed before 7.0.0
     */
    @Deprecated
    protected boolean isUIAlive(UI ui) {
        long now = System.currentTimeMillis();
        if (getHeartbeatTimeout() >= 0
                && now - ui.getLastHeartbeatTime() > 1000 * getHeartbeatTimeout()) {
            return false;
        }
        if (getUidlRequestTimeout() >= 0
                && now - ui.getLastUidlRequestTime() > 1000 * getUidlRequestTimeout()) {
            return false;
        }
        return true;
    }

    /**
     * Gets this session's global resource handler that takes care of serving
     * connector resources that are not served by any single connector because
     * e.g. because they are served with strong caching or because of legacy
     * reasons.
     * 
     * @param createOnDemand
     *            <code>true</code> if a resource handler should be initialized
     *            if there is no handler associated with this application.
     *            </code>false</code> if </code>null</code> should be returned
     *            if there is no registered handler.
     * @return this session's global resource handler, or <code>null</code> if
     *         there is no handler and the createOnDemand parameter is
     *         <code>false</code>.
     * 
     * @since 7.0.0
     */
    public GlobalResourceHandler getGlobalResourceHandler(boolean createOnDemand) {
        if (globalResourceHandler == null && createOnDemand) {
            globalResourceHandler = new GlobalResourceHandler();
            addRequestHandler(globalResourceHandler);
        }

        return globalResourceHandler;
    }

    /**
     * Gets the lock that should be used to synchronize usage of data inside
     * this session.
     * 
     * @return the lock that should be used for synchronization
     */
    public Lock getLock() {
        return lock;
    }

    /**
     * Stores a value in this vaadin session. This can be used to associate data
     * with the current user so that it can be retrieved at a later point from
     * some other part of the application. Setting the value to
     * <code>null</code> clears the stored value.
     * 
     * @see #getAttribute(String)
     * 
     * @param name
     *            the name to associate the value with, can not be
     *            <code>null</code>
     * @param value
     *            the value to associate with the name, or <code>null</code> to
     *            remove a previous association.
     */
    public void setAttribute(String name, Object value) {
        if (name == null) {
            throw new IllegalArgumentException("name can not be null");
        }
        if (value != null) {
            attributes.put(name, value);
        } else {
            attributes.remove(name);
        }
    }

    /**
     * Stores a value in this vaadin session. This can be used to associate data
     * with the current user so that it can be retrieved at a later point from
     * some other part of the application. Setting the value to
     * <code>null</code> clears the stored value.
     * <p>
     * The fully qualified name of the type is used as the name when storing the
     * value. The outcome of calling this method is thus the same as if calling<br />
     * <br />
     * <code>setAttribute(type.getName(), value);</code>
     * 
     * @see #getAttribute(Class)
     * @see #setAttribute(String, Object)
     * 
     * @param type
     *            the type that the stored value represents, can not be null
     * @param value
     *            the value to associate with the type, or <code>null</code> to
     *            remove a previous association.
     */
    public <T> void setAttribute(Class<T> type, T value) {
        if (type == null) {
            throw new IllegalArgumentException("type can not be null");
        }
        if (value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("value of type "
                    + type.getName() + " expected but got "
                    + value.getClass().getName());
        }
        setAttribute(type.getName(), value);
    }

    /**
     * Gets a stored attribute value. If a value has been stored for the
     * session, that value is returned. If no value is stored for the name,
     * <code>null</code> is returned.
     * 
     * @see #setAttribute(String, Object)
     * 
     * @param name
     *            the name of the value to get, can not be <code>null</code>.
     * @return the value, or <code>null</code> if no value has been stored or if
     *         it has been set to null.
     */
    public Object getAttribute(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name can not be null");
        }
        return attributes.get(name);
    }

    /**
     * Gets a stored attribute value. If a value has been stored for the
     * session, that value is returned. If no value is stored for the name,
     * <code>null</code> is returned.
     * <p>
     * The fully qualified name of the type is used as the name when getting the
     * value. The outcome of calling this method is thus the same as if calling<br />
     * <br />
     * <code>getAttribute(type.getName());</code>
     * 
     * @see #setAttribute(Class, Object)
     * @see #getAttribute(String)
     * 
     * @param type
     *            the type of the value to get, can not be <code>null</code>.
     * @return the value, or <code>null</code> if no value has been stored or if
     *         it has been set to null.
     */
    public <T> T getAttribute(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type can not be null");
        }
        Object value = getAttribute(type.getName());
        if (value == null) {
            return null;
        } else {
            return type.cast(value);
        }
    }

    /**
     * Checks whether there this session has any Vaadin service data for a
     * particular Vaadin service.
     * 
     * @see #addVaadinServiceData(VaadinServiceData)
     * @see VaadinServiceData
     * 
     * @param vaadinService
     *            the Vaadin service to check for
     * @return <code>true</code> if there is a Vaadin service data object for
     *         the passed Vaadin service; otherwise <code>false</code>
     */
    public boolean hasVaadinServiceData(VaadinService vaadinService) {
        return getServiceData(vaadinService) != null;
    }

    /**
     * Gets the data stored for the passed Vaadin service.
     * 
     * @see #addVaadinServiceData(VaadinServiceData)
     * @see VaadinServiceData
     * 
     * @param vaadinService
     *            the Vaadin service to get the data for
     * @return the Vaadin service data for the provided Vaadin service; or
     *         <code>null</code> if there is no data for the service
     */
    public VaadinServiceData getServiceData(VaadinService vaadinService) {
        return serviceData.get(getServiceKey(vaadinService));
    }

    /**
     * Adds Vaadin service specific data to this session.
     * 
     * @see #getServiceData(VaadinService)
     * @see VaadinServiceData
     * 
     * @param serviceData
     *            the Vaadin service data to add
     */
    public void addVaadinServiceData(VaadinServiceData serviceData) {
        VaadinService vaadinService = serviceData.getService();
        assert !hasVaadinServiceData(vaadinService);

        this.serviceData.put(getServiceKey(vaadinService), serviceData);
    }

    private static String getServiceKey(VaadinService vaadinService) {
        String serviceKey = vaadinService.getClass().getName() + "."
                + vaadinService.getServiceName();
        return serviceKey;
    }

    /**
     * Creates a new unique id for a UI.
     * 
     * @return a unique UI id
     */
    public int getNextUIid() {
        return nextUIId++;
    }

    /**
     * Gets the mapping from <code>window.name</code> to UI id for UIs that are
     * should be retained on refresh.
     * 
     * @see VaadinService#preserveUIOnRefresh(VaadinRequest, UI, UIProvider)
     * @see PreserveOnRefresh
     * 
     * @return the mapping between window names and UI ids for this session.
     */
    public Map<String, Integer> getPreserveOnRefreshUIs() {
        return retainOnRefreshUIs;
    }

    /**
     * Adds an initialized UI to this session.
     * 
     * @param ui
     *            the initialized UI to add.
     */
    public void addUI(UI ui) {
        if (ui.getUIId() == -1) {
            throw new IllegalArgumentException(
                    "Can not add an UI that has not been initialized.");
        }
        if (ui.getSession() != this) {
            throw new IllegalArgumentException(
                    "The UI belongs to a different session");
        }

        uIs.put(Integer.valueOf(ui.getUIId()), ui);
    }

}
