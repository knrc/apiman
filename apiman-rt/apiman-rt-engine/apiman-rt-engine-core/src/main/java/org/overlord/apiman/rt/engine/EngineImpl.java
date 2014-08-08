/*
 * Copyright 2014 JBoss Inc
 *
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
package org.overlord.apiman.rt.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import org.overlord.apiman.rt.engine.async.AsyncResultImpl;
import org.overlord.apiman.rt.engine.async.IAsyncHandler;
import org.overlord.apiman.rt.engine.async.IAsyncResult;
import org.overlord.apiman.rt.engine.beans.Application;
import org.overlord.apiman.rt.engine.beans.Contract;
import org.overlord.apiman.rt.engine.beans.Policy;
import org.overlord.apiman.rt.engine.beans.PolicyFailure;
import org.overlord.apiman.rt.engine.beans.Service;
import org.overlord.apiman.rt.engine.beans.ServiceRequest;
import org.overlord.apiman.rt.engine.beans.ServiceResponse;
import org.overlord.apiman.rt.engine.beans.exceptions.PublishingException;
import org.overlord.apiman.rt.engine.beans.exceptions.RegistrationException;
import org.overlord.apiman.rt.engine.policy.IPolicy;
import org.overlord.apiman.rt.engine.policy.IPolicyContext;
import org.overlord.apiman.rt.engine.policy.IPolicyFactory;
import org.overlord.apiman.rt.engine.policy.PolicyChainImpl;
import org.overlord.apiman.rt.engine.policy.PolicyContextImpl;
import org.overlord.apiman.rt.engine.policy.PolicyWithConfiguration;

/**
 * The implementation of the API Management runtime engine.
 *
 * @author eric.wittmann@redhat.com
 */
public class EngineImpl implements IEngine {

    private IRegistry registry;
    private IComponentRegistry componentRegistry;
    private IConnectorFactory connectorFactory;
    private IPolicyFactory policyFactory;

    /**
     * Constructor.
     * @param registry
     * @param componentRegistry
     * @param connectorFactory
     * @param policyFactory
     */
    public EngineImpl(final IRegistry registry, final IComponentRegistry componentRegistry,
            final IConnectorFactory connectorFactory, final IPolicyFactory policyFactory) {
        setRegistry(registry);
        setComponentRegistry(componentRegistry);
        setConnectorFactory(connectorFactory);
        setPolicyFactory(policyFactory);
    }

    /**
     * @see org.overlord.apiman.rt.engine.IEngine#getVersion()
     */
    @Override
    public String getVersion() {
        return Version.get().getVersionString();
    }

    /**
     * @see org.overlord.apiman.rt.engine.IEngine#execute(org.overlord.apiman.rt.engine.beans.ServiceRequest, org.overlord.apiman.rt.engine.async.IAsyncHandler)
     */
    @Override
    public void execute(final ServiceRequest request, final IAsyncHandler<EngineResult> handler) {
        final Contract contract = getContract(request);
        final IPolicyContext context = new PolicyContextImpl(getComponentRegistry());
        final List<PolicyWithConfiguration> policies = getPolicies(contract);
        final PolicyChainImpl chain = new PolicyChainImpl(policies, context);
        chain.setInboundHandler(new IAsyncHandler<ServiceRequest>() {
            @Override
            public void handle(IAsyncResult<ServiceRequest> result) {
                // The chain has discovered that all of the policies have been applied
                // to the inbound request (or possibly an exception has been thrown).
                
                // If success, proxy the request to the back-end system asynchronously.
                // If error, propagate to the caller.
                if (result.isSuccess()) {
                    try {
                        final Service service = registry.getService(contract);
                        IServiceConnector connector = getConnectorFactory().createConnector(request, service);
                        connector.invoke(request, new IAsyncHandler<ServiceResponse>() {
                            @Override
                            public void handle(IAsyncResult<ServiceResponse> result) {
                                if (result.isSuccess()) {
                                    ServiceResponse response = result.getResult();
                                    chain.doApply(response);
                                } else {
                                    handler.handle(AsyncResultImpl.<EngineResult>create(result.getError()));
                                }
                            }
                        });
                    } catch (Throwable e) {
                        handler.handle(AsyncResultImpl.<EngineResult>create(e));
                    }
                } else {
                    handler.handle(AsyncResultImpl.<EngineResult>create(result.getError()));
                }
            }
        });
        chain.setOutboundHandler(new IAsyncHandler<ServiceResponse>() {
            @Override
            public void handle(IAsyncResult<ServiceResponse> result) {
                // The chain has discovered that all of the policies have been applied
                // to the outbound response (or possibly an exception has been thrown).
                
                // If success, send the service response to the caller
                // If failure, send the exception to the caller
                if (result.isSuccess()) {
                    EngineResult er = new EngineResult(result.getResult());
                    handler.handle(AsyncResultImpl.create(er));
                } else {
                    handler.handle(AsyncResultImpl.<EngineResult>create(result.getError()));
                }
            }
        });
        chain.setPolicyFailureHandler(new IAsyncHandler<PolicyFailure>() {
            @Override
            public void handle(IAsyncResult<PolicyFailure> result) {
                // One of the policies has triggered a failure.  At this
                // point we should stop processing and send the failure to
                // the client for appropriate handling.
                EngineResult er = new EngineResult(result.getResult());
                handler.handle(AsyncResultImpl.create(er));
            }
        });
        chain.doApply(request);
        return;
    }

    /**
     * @see org.overlord.apiman.rt.engine.IEngine#execute(org.overlord.apiman.rt.engine.beans.ServiceRequest)
     */
    @Override
    public Future<IAsyncResult<EngineResult>> execute(final ServiceRequest request) {
        EngineResultFuture future = new EngineResultFuture();
        execute(request, future);
        return future;
    }
    
    /**
     * @see org.overlord.apiman.rt.engine.IEngine#publishService(org.overlord.apiman.rt.engine.beans.Service)
     */
    @Override
    public void publishService(final Service service) throws PublishingException {
        getRegistry().publishService(service);
    }
    
    /**
     * @see org.overlord.apiman.rt.engine.IEngine#retireService(org.overlord.apiman.rt.engine.beans.Service)
     */
    @Override
    public void retireService(final Service service) throws PublishingException {
        getRegistry().retireService(service);
    }
    
    /**
     * @see org.overlord.apiman.rt.engine.IEngine#registerApplication(org.overlord.apiman.rt.engine.beans.Application)
     */
    @Override
    public void registerApplication(final Application application) throws RegistrationException {
        getRegistry().registerApplication(application);
    }
    
    /**
     * @see org.overlord.apiman.rt.engine.IEngine#unregisterApplication(org.overlord.apiman.rt.engine.beans.Application)
     */
    @Override
    public void unregisterApplication(final Application application) throws RegistrationException {
        getRegistry().unregisterApplication(application);
    }

    /**
     * Gets the service contract to use for the given request. 
     * @param request
     */
    private Contract getContract(ServiceRequest request) {
        return this.registry.getContract(request);
    }

    /**
     * Creates the policies that should be applied for this service invokation.  This is
     * achieved by using the policy information set on the contract.
     * @param contract
     */
    private List<PolicyWithConfiguration> getPolicies(Contract contract) {
        List<PolicyWithConfiguration> policies = new ArrayList<PolicyWithConfiguration>();
        for (Policy policy : contract.getPolicies()) {
            IPolicy policyImpl = this.getPolicyFactory().getPolicy(policy.getPolicyImpl());
            // TODO cache the parsed policy config - perhaps in the Policy object itself as a transient?
            Object policyConfig = policyImpl.parseConfiguration(policy.getPolicyJsonConfig());
            PolicyWithConfiguration pwc = new PolicyWithConfiguration(policyImpl, policyConfig);
            policies.add(pwc);
        }
        return policies;
    }

    /**
     * @return the registry
     */
    public IRegistry getRegistry() {
        return registry;
    }

    /**
     * @param registry the registry to set
     */
    public void setRegistry(final IRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return the connectorFactory
     */
    public IConnectorFactory getConnectorFactory() {
        return connectorFactory;
    }

    /**
     * @param connectorFactory the connectorFactory to set
     */
    public void setConnectorFactory(final IConnectorFactory connectorFactory) {
        this.connectorFactory = connectorFactory;
    }

    /**
     * @return the policyFactory
     */
    public IPolicyFactory getPolicyFactory() {
        return policyFactory;
    }

    /**
     * @param policyFactory the policyFactory to set
     */
    public void setPolicyFactory(IPolicyFactory policyFactory) {
        this.policyFactory = policyFactory;
    }

    /**
     * @return the componentRegistry
     */
    public IComponentRegistry getComponentRegistry() {
        return componentRegistry;
    }

    /**
     * @param componentRegistry the componentRegistry to set
     */
    public void setComponentRegistry(IComponentRegistry componentRegistry) {
        this.componentRegistry = componentRegistry;
    }

}
