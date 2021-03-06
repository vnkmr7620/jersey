/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2015 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.servlet;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.RuntimeType;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.glassfish.jersey.internal.ServiceFinderBinder;
import org.glassfish.jersey.internal.inject.Providers;
import org.glassfish.jersey.internal.inject.ReferencingFactory;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.internal.util.collection.Ref;
import org.glassfish.jersey.internal.util.collection.Value;
import org.glassfish.jersey.internal.util.collection.Values;
import org.glassfish.jersey.message.internal.HeaderValueException;
import org.glassfish.jersey.message.internal.MediaTypes;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.internal.InternalServerProperties;
import org.glassfish.jersey.server.internal.RuntimeExecutorsBinder;
import org.glassfish.jersey.server.spi.RequestScopedInitializer;
import org.glassfish.jersey.servlet.internal.LocalizationMessages;
import org.glassfish.jersey.servlet.internal.PersistenceUnitBinder;
import org.glassfish.jersey.servlet.internal.ResponseWriter;
import org.glassfish.jersey.servlet.internal.ServletContainerProviderFactory;
import org.glassfish.jersey.servlet.internal.Utils;
import org.glassfish.jersey.servlet.internal.spi.ServletContainerProvider;
import org.glassfish.jersey.servlet.spi.AsyncContextDelegate;
import org.glassfish.jersey.servlet.spi.AsyncContextDelegateProvider;
import org.glassfish.jersey.uri.UriComponent;

import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.TypeLiteral;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

import jersey.repackaged.com.google.common.base.Predicate;
import jersey.repackaged.com.google.common.collect.Collections2;

/**
 * An common Jersey web component that may be extended by a Servlet and/or
 * Filter implementation, or encapsulated by a Servlet or Filter implementation.
 *
 * @author Paul Sandoz
 * @author Jakub Podlesak (jakub.podlesak at oracle.com)
 * @author Marek Potociar (marek.potociar at oracle.com)
 * @author Martin Matula
 * @author Libor Kramolis (libor.kramolis at oracle.com)
 */
public class WebComponent {

    private static final Logger LOGGER = Logger.getLogger(WebComponent.class.getName());

    private final Type REQUEST_TYPE = (new TypeLiteral<Ref<HttpServletRequest>>() {}).getType();
    private final Type RESPONSE_TYPE = (new TypeLiteral<Ref<HttpServletResponse>>() {}).getType();

    private static final AsyncContextDelegate DEFAULT_ASYNC_DELEGATE = new AsyncContextDelegate() {

        @Override
        public void suspend() throws IllegalStateException {
            throw new UnsupportedOperationException(LocalizationMessages.ASYNC_PROCESSING_NOT_SUPPORTED());
        }

        @Override
        public void complete() {
        }
    };

    /**
     * Return the first found {@link AsyncContextDelegateProvider}
     * (via {@link Providers#getAllProviders(org.glassfish.hk2.api.ServiceLocator, Class)}) or {@code #DEFAULT_ASYNC_DELEGATE} if
     * other delegate cannot be found.
     *
     * @return a non-null AsyncContextDelegateProvider.
     */
    private AsyncContextDelegateProvider getAsyncExtensionDelegate() {
        final Iterator<AsyncContextDelegateProvider> providers = Providers.getAllProviders(appHandler.getServiceLocator(),
                AsyncContextDelegateProvider.class).iterator();
        if (providers.hasNext()) {
            return providers.next();
        }

        return new AsyncContextDelegateProvider() {

            @Override
            public AsyncContextDelegate createDelegate(final HttpServletRequest request, final HttpServletResponse response) {
                return DEFAULT_ASYNC_DELEGATE;
            }
        };
    }

    @SuppressWarnings("JavaDoc")
    private static class HttpServletRequestReferencingFactory extends ReferencingFactory<HttpServletRequest> {

        @Inject
        public HttpServletRequestReferencingFactory(final Provider<Ref<HttpServletRequest>> referenceFactory) {
            super(referenceFactory);
        }
    }

    @SuppressWarnings("JavaDoc")
    private static class HttpServletResponseReferencingFactory extends ReferencingFactory<HttpServletResponse> {

        @Inject
        public HttpServletResponseReferencingFactory(final Provider<Ref<HttpServletResponse>> referenceFactory) {
            super(referenceFactory);
        }
    }

    private final class WebComponentBinder extends AbstractBinder {

        private final Map<String, Object> applicationProperties;

        /**
         * Create binder for {@link WebComponent} passing a map of properties to determine whether certain features are allowed or
         * not.
         *
         * @param applicationProperties map of properties to determine whether certain features are allowed or not.
         */
        private WebComponentBinder(final Map<String, Object> applicationProperties) {
            this.applicationProperties = applicationProperties;
        }

        @Override
        protected void configure() {
            bindFactory(HttpServletRequestReferencingFactory.class).to(HttpServletRequest.class)
                    .proxy(true).proxyForSameScope(false).in(RequestScoped.class);
            bindFactory(ReferencingFactory.<HttpServletRequest>referenceFactory())
                    .to(new TypeLiteral<Ref<HttpServletRequest>>() {}).in(RequestScoped.class);

            bindFactory(HttpServletResponseReferencingFactory.class).to(HttpServletResponse.class)
                    .proxy(true).proxyForSameScope(false).in(RequestScoped.class);
            bindFactory(ReferencingFactory.<HttpServletResponse>referenceFactory())
                    .to(new TypeLiteral<Ref<HttpServletResponse>>() {}).in(RequestScoped.class);

            bindFactory(new Factory<ServletContext>() {
                @Override
                public ServletContext provide() {
                    return webConfig.getServletContext();
                }

                @Override
                public void dispose(final ServletContext instance) {
                    //not used
                }
            }).to(ServletContext.class).in(Singleton.class);

            final ServletConfig servletConfig = webConfig.getServletConfig();
            if (webConfig.getConfigType() == WebConfig.ConfigType.ServletConfig) {
                bindFactory(new Factory<ServletConfig>() {
                    @Override
                    public ServletConfig provide() {
                        return servletConfig;
                    }

                    @Override
                    public void dispose(final ServletConfig instance) {
                        //not used
                    }
                }).to(ServletConfig.class).in(Singleton.class);

                // @PersistenceUnit
                final Enumeration initParams = servletConfig.getInitParameterNames();
                while (initParams.hasMoreElements()) {
                    final String initParamName = (String) initParams.nextElement();

                    if (initParamName.startsWith(PersistenceUnitBinder.PERSISTENCE_UNIT_PREFIX)) {
                        install(new PersistenceUnitBinder());
                        break;
                    }
                }
            } else {
                bindFactory(new Factory<FilterConfig>() {
                    @Override
                    public FilterConfig provide() {
                        return webConfig.getFilterConfig();
                    }

                    @Override
                    public void dispose(final FilterConfig instance) {
                        //not used
                    }
                }).to(FilterConfig.class).in(Singleton.class);
            }

            bindFactory(new Factory<WebConfig>() {
                @Override
                public WebConfig provide() {
                    return webConfig;
                }

                @Override
                public void dispose(final WebConfig instance) {
                    //not used
                }
            }).to(WebConfig.class).in(Singleton.class);

            install(new ServiceFinderBinder<>(AsyncContextDelegateProvider.class, applicationProperties, RuntimeType.SERVER));
        }
    }

    /**
     * Jersey application handler.
     */
    final ApplicationHandler appHandler;

    /**
     * Jersey background task scheduler - used for scheduling request timeout event handling tasks.
     */
    final ScheduledExecutorService backgroundTaskScheduler;

    /**
     * Web component configuration.
     */
    final WebConfig webConfig;

    /**
     * If {@code true} and deployed as filter, the unmatched requests will be forwarded.
     */
    final boolean forwardOn404;

    /**
     * Cached value of configuration property
     * {@link org.glassfish.jersey.server.ServerProperties#RESPONSE_SET_STATUS_OVER_SEND_ERROR}.
     * If {@code true} method {@link HttpServletResponse#setStatus} is used over {@link HttpServletResponse#sendError}.
     */
    final boolean configSetStatusOverSendError;

    /**
     * Asynchronous context delegate provider.
     */
    private final AsyncContextDelegateProvider asyncExtensionDelegate;

    /**
     * Flag whether query parameters should be kept as entity form params if a servlet filter consumes entity and
     * Jersey has to retrieve form params from servlet request parameters.
     */
    private final boolean queryParamsAsFormParams;

    /**
     * Create and initialize new web component instance.
     *
     * @param webConfig      we component configuration.
     * @param resourceConfig Jersey application configuration.
     * @throws ServletException in case the Jersey application cannot be created from the supplied
     *                          resource configuration.
     */
    public WebComponent(final WebConfig webConfig, ResourceConfig resourceConfig) throws ServletException {

        this.webConfig = webConfig;

        if (resourceConfig == null) {
            resourceConfig = createResourceConfig(webConfig);
        }

        // SPI/extension hook to configure ResourceConfig
        configure(resourceConfig);

        final AbstractBinder webComponentBinder = new WebComponentBinder(resourceConfig.getProperties());
        resourceConfig.register(webComponentBinder);

        final ServiceLocator locator = (ServiceLocator) webConfig.getServletContext()
                .getAttribute(ServletProperties.SERVICE_LOCATOR);

        this.appHandler = new ApplicationHandler(resourceConfig, webComponentBinder, locator);

        this.asyncExtensionDelegate = getAsyncExtensionDelegate();
        this.forwardOn404 = webConfig.getConfigType().equals(WebConfig.ConfigType.FilterConfig)
                && resourceConfig.isProperty(ServletProperties.FILTER_FORWARD_ON_404);
        this.queryParamsAsFormParams = !resourceConfig.isProperty(ServletProperties.QUERY_PARAMS_AS_FORM_PARAMS_DISABLED);
        this.configSetStatusOverSendError = ServerProperties.getValue(resourceConfig.getProperties(),
                ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, false, Boolean.class);
        this.backgroundTaskScheduler = appHandler.getServiceLocator()
                .getService(ScheduledExecutorService.class, new RuntimeExecutorsBinder.BackgroundSchedulerLiteral());
    }

    /**
     * Dispatch client requests to a resource class.
     *
     * @param baseUri         the base URI of the request.
     * @param requestUri      the URI of the request.
     * @param servletRequest  the {@link javax.servlet.http.HttpServletRequest} object that
     *                        contains the request the client made to
     *                        the Web component.
     * @param servletResponse the {@link javax.servlet.http.HttpServletResponse} object that
     *                        contains the response the Web component returns
     *                        to the client.
     * @return lazily initialized response status code {@link Value value provider}.
     * @throws java.io.IOException            if an input or output error occurs
     *                                        while the Web component is handling the
     *                                        HTTP request.
     * @throws javax.servlet.ServletException if the HTTP request cannot
     *                                        be handled.
     */
    public Value<Integer> service(
            final URI baseUri,
            final URI requestUri,
            final HttpServletRequest servletRequest,
            final HttpServletResponse servletResponse) throws ServletException, IOException {

        final ContainerRequest requestContext = new ContainerRequest(baseUri, requestUri,
                servletRequest.getMethod(), getSecurityContext(servletRequest), new ServletPropertiesDelegate(servletRequest));
        requestContext.setEntityStream(servletRequest.getInputStream());
        addRequestHeaders(servletRequest, requestContext);

        try {
            // Check if any servlet filters have consumed a request entity
            // of the media type application/x-www-form-urlencoded
            // This can happen if a filter calls request.getParameter(...)
            filterFormParameters(servletRequest, requestContext);

            final ResponseWriter responseWriter = new ResponseWriter(
                    forwardOn404,
                    configSetStatusOverSendError,
                    servletResponse,
                    asyncExtensionDelegate.createDelegate(servletRequest, servletResponse),
                    backgroundTaskScheduler);

            requestContext.setRequestScopedInitializer(new RequestScopedInitializer() {
                @Override
                public void initialize(final ServiceLocator locator) {
                    locator.<Ref<HttpServletRequest>>getService(REQUEST_TYPE).set(servletRequest);
                    locator.<Ref<HttpServletResponse>>getService(RESPONSE_TYPE).set(servletResponse);
                }
            });
            requestContext.setWriter(responseWriter);

            appHandler.handle(requestContext);

            return Values.lazy(new Value<Integer>() {
                @Override
                public Integer get() {
                    return responseWriter.getResponseStatus();
                }
            });
        } catch (final HeaderValueException hve) {
            final Response.Status status = Response.Status.BAD_REQUEST;
            if (configSetStatusOverSendError) {
                servletResponse.reset();
                //noinspection deprecation
                servletResponse.setStatus(status.getStatusCode(), status.getReasonPhrase());
            } else {
                servletResponse.sendError(status.getStatusCode(), status.getReasonPhrase());
            }

            return Values.of(status.getStatusCode());
        } catch (final Exception e) {
            throw new ServletException(e);
        }

    }

    /**
     * Get default {@link SecurityContext} for given {@code request}.
     *
     * @param request http servlet request to create a security context for.
     * @return a non-null security context instance.
     */
    private SecurityContext getSecurityContext(final HttpServletRequest request) {
        return new SecurityContext() {

            @Override
            public Principal getUserPrincipal() {
                return request.getUserPrincipal();
            }

            @Override
            public boolean isUserInRole(final String role) {
                return request.isUserInRole(role);
            }

            @Override
            public boolean isSecure() {
                return request.isSecure();
            }

            @Override
            public String getAuthenticationScheme() {
                return request.getAuthType();
            }
        };
    }

    /**
     * Create a {@link ResourceConfig} instance from given {@link WebConfig}.
     *
     * @param config web config to create resource config from.
     * @return resource config instance.
     * @throws ServletException if an error has occurred.
     */
    private static ResourceConfig createResourceConfig(final WebConfig config) throws ServletException {
        final ServletContext servletContext = config.getServletContext();

        // check if ResourceConfig has already been created, if so use it
        ResourceConfig resourceConfig = Utils.retrieve(config.getServletContext());
        if (resourceConfig != null) {
            return resourceConfig;
        }

        final Map<String, Object> initParams = getInitParams(config);
        final Map<String, Object> contextParams = Utils.getContextParams(servletContext);

        // check if the JAX-RS application config class property is present
        final String jaxrsApplicationClassName = config.getInitParameter(ServletProperties.JAXRS_APPLICATION_CLASS);

        if (jaxrsApplicationClassName == null) {
            // If no resource config class property is present, create default config
            resourceConfig = new ResourceConfig().addProperties(initParams).addProperties(contextParams);

            final String webApp = config.getInitParameter(ServletProperties.PROVIDER_WEB_APP);
            if (webApp != null && !"false".equals(webApp)) {
                resourceConfig.registerFinder(new WebAppResourcesScanner(servletContext));
            }
            return resourceConfig;
        }

        try {
            final Class<? extends javax.ws.rs.core.Application> jaxrsApplicationClass = AccessController.doPrivileged(
                    ReflectionHelper.<javax.ws.rs.core.Application>classForNameWithExceptionPEA(jaxrsApplicationClassName)
            );

            if (javax.ws.rs.core.Application.class.isAssignableFrom(jaxrsApplicationClass)) {
                return ResourceConfig.forApplicationClass(jaxrsApplicationClass)
                        .addProperties(initParams).addProperties(contextParams);
            } else {
                throw new ServletException(LocalizationMessages.RESOURCE_CONFIG_PARENT_CLASS_INVALID(
                        jaxrsApplicationClassName, javax.ws.rs.core.Application.class));
            }
        } catch (final PrivilegedActionException e) {
            throw new ServletException(
                    LocalizationMessages.RESOURCE_CONFIG_UNABLE_TO_LOAD(jaxrsApplicationClassName), e.getCause());
        } catch (final ClassNotFoundException e) {
            throw new ServletException(LocalizationMessages.RESOURCE_CONFIG_UNABLE_TO_LOAD(jaxrsApplicationClassName), e);
        }
    }

    /**
     * SPI/extension hook to configure ResourceConfig.
     *
     * @param resourceConfig Jersey application configuration.
     * @throws ServletException if an error has occurred.
     */
    private static void configure(final ResourceConfig resourceConfig) throws ServletException {
        final ServletContainerProvider[] allServletContainerProviders = //TODO check if META-INF/services lookup is enabled
                ServletContainerProviderFactory.getAllServletContainerProviders();
        for (final ServletContainerProvider servletContainerProvider : allServletContainerProviders) {
            servletContainerProvider.configure(resourceConfig);
        }
    }

    /**
     * Copy request headers present in {@code request} into {@code requestContext} ignoring {@code null} values.
     *
     * @param request http servlet request to copy headers from.
     * @param requestContext container request to copy headers to.
     */
    @SuppressWarnings("unchecked")
    private void addRequestHeaders(final HttpServletRequest request, final ContainerRequest requestContext) {
        final Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            final String name = names.nextElement();

            final Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                final String value = values.nextElement();
                if (value != null) { // filter out null values
                    requestContext.header(name, value);
                }
            }
        }
    }

    /**
     * Extract init params from {@link WebConfig}.
     *
     * @param webConfig actual servlet context.
     * @return map representing current init parameters.
     */
    private static Map<String, Object> getInitParams(final WebConfig webConfig) {
        final Map<String, Object> props = new HashMap<>();
        final Enumeration names = webConfig.getInitParameterNames();
        while (names.hasMoreElements()) {
            final String name = (String) names.nextElement();
            props.put(name, webConfig.getInitParameter(name));
        }
        return props;
    }

    /**
     * Extract parameters contained in {@link HttpServletRequest servlet request} and put them into
     * {@link ContainerRequest container request} under
     * {@value org.glassfish.jersey.server.internal.InternalServerProperties#FORM_DECODED_PROPERTY} property (as {@link Form}
     * instance).
     *
     * @param servletRequest http servlet request to extract params from.
     * @param containerRequest container request to put {@link Form} property to.
     */
    private void filterFormParameters(final HttpServletRequest servletRequest, final ContainerRequest containerRequest) {
        if (MediaTypes.typeEqual(MediaType.APPLICATION_FORM_URLENCODED_TYPE, containerRequest.getMediaType())
                && !containerRequest.hasEntity()) {
            final Form form = new Form();
            final Enumeration parameterNames = servletRequest.getParameterNames();

            final String queryString = servletRequest.getQueryString();
            final List<String> queryParams = queryString != null ? getDecodedQueryParamList(queryString)
                    : Collections.<String>emptyList();

            final boolean keepQueryParams = queryParamsAsFormParams || queryParams.isEmpty();
            final MultivaluedMap<String, String> formMap = form.asMap();

            while (parameterNames.hasMoreElements()) {
                final String name = (String) parameterNames.nextElement();
                final List<String> values = Arrays.asList(servletRequest.getParameterValues(name));

                formMap.put(name, keepQueryParams ? values : filterQueryParams(name, values, queryParams));
            }

            if (!formMap.isEmpty()) {
                containerRequest.setProperty(InternalServerProperties.FORM_DECODED_PROPERTY, form);

                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, LocalizationMessages.FORM_PARAM_CONSUMED(containerRequest.getRequestUri()));
                }
            }
        }
    }

    private List<String> getDecodedQueryParamList(final String queryString) {
        final List<String> params = new ArrayList<>();
        for (final String param : queryString.split("&")) {
            params.add(UriComponent.decode(param, UriComponent.Type.QUERY_PARAM));
        }
        return params;
    }

    /**
     * From given list of values remove values that represents values of query params of the same name as the processed form
     * parameter.
     *
     * @param name name of form/query parameter.
     * @param values values of form/query parameter.
     * @param params collection of unprocessed query parameters.
     * @return list of form param values for given name without values of query param of the same name.
     */
    private List<String> filterQueryParams(final String name, final List<String> values, final Collection<String> params) {
        return new ArrayList<>(Collections2.filter(values, new Predicate<String>() {
            @Override
            public boolean apply(final String input) {
                return !params.remove(name + "=" + input)
                        && !params.remove(name + "[]=" + input);
            }
        }));
    }

    /**
     * Get {@link ApplicationHandler} used by this web component.
     *
     * @return The application handler
     */
    public ApplicationHandler getAppHandler() {
        return appHandler;
    }
}
