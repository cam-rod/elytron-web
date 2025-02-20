/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.elytron.web.undertow.server.servlet;

import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;

import static java.security.AccessController.doPrivileged;

import java.io.Serializable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.config.AuthConfigFactory;
import javax.security.auth.message.config.AuthConfigProvider;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.security.auth.message.config.ServerAuthContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.jboss.logging.Logger;
import org.wildfly.elytron.web.undertow.server.SecurityContextImpl;
import org.wildfly.security.auth.jaspi.impl.JaspiAuthenticationContext;
import org.wildfly.security.auth.jaspi.impl.ServletMessageInfo;
import org.wildfly.security.cache.CachedIdentity;

import io.undertow.security.api.SecurityContext;
import io.undertow.server.HttpServerExchange;

/**
 * An extension of {@link SecurityContextImpl} to add JASPIC / Servlet Profile Support.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class ServletSecurityContextImpl extends SecurityContextImpl {

    private static final Logger log = Logger.getLogger("org.wildfly.security.http.servlet");

    private static final String AUTH_TYPE = "javax.servlet.http.authType";
    private static final String DEFAULT_JASPI_MECHANISM = "JASPI";
    private static final String MANDATORY = "javax.security.auth.message.MessagePolicy.isMandatory";
    private static final String REGISTER_SESSION = "javax.servlet.http.registerSession";

    private static final String SERVLET_MESSAGE_LAYER = "HttpServlet";
    private static final String IDENTITY_KEY = IdentityContainer.class.getName();

    private final boolean enableJaspi;
    private final boolean integratedJaspi;
    private final String applicationContext;
    private final RequestResponseAccessor requestResponseAccessor;

    /*
     * Although added for JASPIC if any other servlet specific behaviour is required it can be overridden here.
     */

    ServletSecurityContextImpl(Builder builder) {
        super(builder);

        this.enableJaspi = builder.enableJaspi;
        this.integratedJaspi = builder.integratedJaspi;
        this.applicationContext = builder.applicationContext;
        this.requestResponseAccessor = builder.requestResponseAccessor;
        log.tracef("Created ServletSecurityContextImpl enableJapi=%b, integratedJaspi=%b, applicationContext=%s", enableJaspi, integratedJaspi, applicationContext);
    }

    @Override
    public boolean authenticate() {
        if (isAuthenticated()) {
            return true;
        }

        // If JASPI do JASPI
        if (enableJaspi) {
            AuthConfigFactory authConfigFactory = getAuthConfigFactory();
            if (authConfigFactory != null) {
                AuthConfigProvider configProvider = authConfigFactory.getConfigProvider(SERVLET_MESSAGE_LAYER, applicationContext, null);
                if (configProvider != null) {
                    try {
                        return authenticate(configProvider);
                    } catch (AuthException | SecurityException e) {
                        log.trace("Authentication failed.", e);
                        exchange.setStatusCode(INTERNAL_SERVER_ERROR);

                        return false;
                    }
                } else {
                    log.tracef("No AuthConfigProvider for layer=%s, appContext=%s", SERVLET_MESSAGE_LAYER, applicationContext);
                }
            } else {
                log.trace("No AuthConfigFactory available.");
            }
        }

        log.trace("JASPIC Unavailable, using HTTP authentication.");
        return super.authenticate();
    }

    private static AuthConfigFactory getAuthConfigFactory() {
        try {
            if (System.getSecurityManager() != null) {
                return doPrivileged(AuthConfigFactory::getFactory);
            } else {
                return AuthConfigFactory.getFactory();
            }
        } catch (Exception e) {
            // Logged at TRACE as this will be per request.
            log.trace("Unable to get AuthConfigFactory", e);
        }

        return null;
    }

    private boolean authenticate(AuthConfigProvider authConfigProvider) throws AuthException, SecurityException {
        final HttpServletRequest httpServletRequest = requestResponseAccessor.getHttpServletRequest();

        HttpSession session = httpServletRequest.getSession(false);
        if (session != null) {
            IdentityContainer identityContainer = (IdentityContainer) session.getAttribute(IDENTITY_KEY);
            if (identityContainer != null) {
                CachedIdentity securityIdentity = identityContainer.getSecurityIdentity();
                String authType = identityContainer.getAuthType();
                if (securityIdentity != null) {
                    log.trace("SecurityIdentity restored from HttpSession");
                    authenticationComplete(securityIdentity.getSecurityIdentity(), authType != null ? authType : getMechanismName());
                    return true;
                }
            } else {
                session.removeAttribute(IDENTITY_KEY);
            }
        }

        // TODO A lot of the initialisation could have happened in advance if it wasn't for the CallbackHandler, maybe
        // we can use some form of contextual handler associated with the thread and a delegate.
        JaspiAuthenticationContext authenticationContext = doPrivileged((PrivilegedAction<JaspiAuthenticationContext>) () -> JaspiAuthenticationContext.newInstance(securityDomain, integratedJaspi));

        // TODO - PermissionCheck
        ServerAuthConfig serverAuthConfig = authConfigProvider.getServerAuthConfig(SERVLET_MESSAGE_LAYER, applicationContext,
                authenticationContext.createCallbackHandler());

        final HttpServletResponse httpServletResponse = requestResponseAccessor.getHttpServletResponse();
        // This is the stage where it is expected we become per-request.
        MessageInfo messageInfo = new ServletMessageInfo();
        messageInfo.setRequestMessage(httpServletRequest);
        messageInfo.setResponseMessage(httpServletResponse);
        if (isAuthenticationRequired()) {
            messageInfo.getMap().put(MANDATORY, Boolean.TRUE.toString());
        }

        // TODO Should be possible to pass this in somehow.
        final Subject serverSubject = null;

        final String authContextId = serverAuthConfig.getAuthContextID(messageInfo);
        // TODO Configured properties.
        final ServerAuthContext serverAuthContext = serverAuthConfig.getAuthContext(authContextId, null, Collections.emptyMap());

        if (serverAuthContext == null) {
            log.trace("No ServerAuthContext returned, JASPI authentication can not proceed.");
            return false;
        }

        final Subject clientSubject = new Subject();
        AuthStatus authStatus = serverAuthContext.validateRequest(messageInfo, clientSubject, serverSubject);
        log.tracef("ServerAuthContext.validateRequest returned AuthStatus=%s", authStatus);
        registerCleanUpTask(exchange, serverAuthContext, messageInfo, serverSubject);

        Map options = messageInfo.getMap();
        boolean registerSession = options.containsKey(REGISTER_SESSION) && Boolean.parseBoolean(String.valueOf(options.get(REGISTER_SESSION)));
        if ((authStatus == AuthStatus.SUCCESS || (authStatus == AuthStatus.SEND_SUCCESS && registerSession))) {
            String authType = options.containsKey(AUTH_TYPE) ? String.valueOf(options.get(AUTH_TYPE)) : getMechanismName(DEFAULT_JASPI_MECHANISM);
            CachedIdentity cachedIdentity = authenticationContext.getAuthorizedIdentity() != null ? new CachedIdentity(DEFAULT_JASPI_MECHANISM, true, authenticationContext.getAuthorizedIdentity()) : null;
            if (registerSession) {
                log.trace("Storing SecurityIdentity in HttpSession");
                session = httpServletRequest.getSession(true);
                session.setAttribute(IDENTITY_KEY, new IdentityContainer(cachedIdentity, authType));
            }
            if (authStatus == AuthStatus.SUCCESS) {
                HttpServletRequest newHttpServletRequest = (HttpServletRequest) messageInfo.getRequestMessage();
                if (httpServletRequest != newHttpServletRequest) {
                    requestResponseAccessor.setHttpServletRequest(newHttpServletRequest);
                }
                HttpServletResponse newHttpServletResponse = (HttpServletResponse) messageInfo.getResponseMessage();
                if (httpServletResponse != newHttpServletResponse) {
                    requestResponseAccessor.setHttpServletResponse(newHttpServletResponse);
                }

                boolean success = false;
                if (cachedIdentity != null) {
                    authenticationComplete(cachedIdentity.getSecurityIdentity(), authType);
                    success = true;
                }

                success = success || !isAuthenticationRequired();

                if (success) {
                    setLogoutHandler(new Runnable() {

                        @Override
                        public void run() {
                            HttpSession session = httpServletRequest.getSession(false);
                            if (session != null) {
                                session.removeAttribute(IDENTITY_KEY);
                            }
                            try {
                                serverAuthContext.cleanSubject(messageInfo, clientSubject);
                            } catch (AuthException e) {
                                log.debug("Unable to cleanSubject", e);
                            }
                        }
                    });
                }

                return success;
            }
        }

        return false;
    }

    private String getMechanismName(final String defaultMechanimsName) {
        String mechanimsName = super.getMechanismName();
        return getMechanismName() != null ? mechanimsName : defaultMechanimsName;
    }

    private void registerCleanUpTask(final HttpServerExchange exchange, final ServerAuthContext serverAuthContext, final MessageInfo messageInfo, final Subject serviceSubject) {
        exchange.putAttachment(CleanUpTask.ATTACHMENT_KEY, new CleanUpTask() {

            @Override
            public void cleanUp(HttpServerExchange exchange) throws Exception {
                final HttpServletRequest httpServletRequest = (HttpServletRequest) messageInfo.getRequestMessage();
                final HttpServletResponse httpServletResponse = (HttpServletResponse) messageInfo.getResponseMessage();

                serverAuthContext.secureResponse(messageInfo, serviceSubject);

                // Restore the request / response objects if an unwrapping occured.
                HttpServletRequest newHttpServletRequest = (HttpServletRequest) messageInfo.getRequestMessage();
                if (httpServletRequest != newHttpServletRequest) {
                    requestResponseAccessor.setHttpServletRequest(newHttpServletRequest);
                }
                HttpServletResponse newHttpServletResponse = (HttpServletResponse) messageInfo.getResponseMessage();
                if (httpServletResponse != newHttpServletResponse) {
                    requestResponseAccessor.setHttpServletResponse(newHttpServletResponse);
                }
            }
        });
    }

    private static <T> T doPrivileged(final PrivilegedAction<T> action) {
        return System.getSecurityManager() != null ? AccessController.doPrivileged(action) : action.run();
    }

    static Builder builder() {
        return new Builder();
    }

    static class Builder extends org.wildfly.elytron.web.undertow.server.SecurityContextImpl.Builder {

        private boolean enableJaspi = true;
        private boolean integratedJaspi = true;
        private String applicationContext;
        private RequestResponseAccessor requestResponseAccessor;

        Builder setEnableJaspi(boolean enableJaspi) {
            this.enableJaspi = enableJaspi;

            return this;
        }

        Builder setIntegratedJaspi(boolean integratedJaspi) {
            this.integratedJaspi = integratedJaspi;

            return this;
        }

        Builder setApplicationContext(final String applicationContext) {
            this.applicationContext = applicationContext;

            return this;
        }

        Builder setRequestResponseAccessor(final RequestResponseAccessor requestResponseAccessor) {
            this.requestResponseAccessor = requestResponseAccessor;

            return this;
        }

        @Override
        public SecurityContext build() {
            return new ServletSecurityContextImpl(this);
        }

    }

    public static class IdentityContainer implements Serializable {

        private static final long serialVersionUID = 812605442632466511L;

        private final CachedIdentity securityIdentity;
        private final String authType;

        public IdentityContainer(final CachedIdentity securityIdentity, final String authType) {
            this.securityIdentity = securityIdentity;
            this.authType = authType;
        }

        public CachedIdentity getSecurityIdentity() {
            return securityIdentity;
        }

        public String getAuthType() {
            return authType;
        }
    }
}
