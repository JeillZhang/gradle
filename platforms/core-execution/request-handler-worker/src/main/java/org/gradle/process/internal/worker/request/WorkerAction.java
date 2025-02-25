/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.process.internal.worker.request;

import org.gradle.api.Action;
import org.gradle.api.internal.initialization.loadercache.ModelClassLoaderFactory;
import org.gradle.api.internal.provider.PropertyInternal;
import org.gradle.api.problems.internal.DefaultProblems;
import org.gradle.api.problems.internal.ExceptionProblemRegistry;
import org.gradle.api.problems.internal.InternalProblems;
import org.gradle.cache.Cache;
import org.gradle.cache.internal.ClassCacheFactory;
import org.gradle.cache.internal.MapBackedCache;
import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classloader.FilteringClassLoader;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.dispatch.StreamCompletion;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.instantiation.PropertyRoleAnnotationHandler;
import org.gradle.internal.instantiation.generator.DefaultInstantiatorFactory;
import org.gradle.internal.operations.CurrentBuildOperationRef;
import org.gradle.internal.remote.ObjectConnection;
import org.gradle.internal.remote.internal.hub.StreamFailureHandler;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.state.ModelObject;
import org.gradle.process.internal.worker.RequestHandler;
import org.gradle.process.internal.worker.WorkerProcessContext;
import org.gradle.process.internal.worker.child.WorkerLogEventListener;
import org.gradle.process.internal.worker.problem.WorkerProblemEmitter;
import org.gradle.tooling.internal.provider.serialization.ClassLoaderCache;
import org.gradle.tooling.internal.provider.serialization.DefaultPayloadClassLoaderRegistry;
import org.gradle.tooling.internal.provider.serialization.PayloadSerializer;
import org.gradle.tooling.internal.provider.serialization.WellKnownClassLoaderRegistry;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Worker-side implementation of {@link RequestProtocol} executing actions.
 */
public class WorkerAction implements Action<WorkerProcessContext>, Serializable, RequestProtocol, StreamFailureHandler, Stoppable, StreamCompletion {
    private final String workerImplementationName;
    private transient CountDownLatch completed;
    private transient ResponseProtocol responder;
    private transient WorkerLogEventListener workerLogEventListener;
    private transient RequestHandler<Object, Object> implementation;
    private transient InstantiatorFactory instantiatorFactory;
    private transient Exception failure;

    public WorkerAction(Class<?> workerImplementation) {
        this.workerImplementationName = workerImplementation.getName();
    }

    @Nonnull
    private static PayloadSerializer createPayloadSerializer() {
        ClassLoaderCache classLoaderCache = new ClassLoaderCache();

        ClassLoader parent = WorkerAction.class.getClassLoader();
        FilteringClassLoader.Spec filterSpec = new FilteringClassLoader.Spec();
        FilteringClassLoader modelClassLoader = new FilteringClassLoader(parent, filterSpec);

        return new PayloadSerializer(
            new WellKnownClassLoaderRegistry(
                new DefaultPayloadClassLoaderRegistry(
                    classLoaderCache,
                    new ModelClassLoaderFactory(modelClassLoader)
                )
            )
        );
    }

    @Override
    public void execute(WorkerProcessContext workerProcessContext) {
        completed = new CountDownLatch(1);

        ObjectConnection connection = workerProcessContext.getServerConnection();
        connection.addIncoming(RequestProtocol.class, this);
        responder = connection.addOutgoing(ResponseProtocol.class);
        workerLogEventListener = workerProcessContext.getServiceRegistry().get(WorkerLogEventListener.class);

        RequestArgumentSerializers argumentSerializers = new RequestArgumentSerializers();
        try {
            ServiceRegistry parentServices = workerProcessContext.getServiceRegistry();
            if (instantiatorFactory == null) {
                instantiatorFactory = new DefaultInstantiatorFactory(new BasicClassCacheFactory(), Collections.emptyList(), new BasicPropertyRoleAnnotationHandler());
            }
            ServiceRegistry serviceRegistry = ServiceRegistryBuilder.builder()
                .displayName("worker action services")
                .parent(parentServices)
                .provider(registration -> {
                    // Make the argument serializers available so work implementations can register their own serializers
                    registration.add(RequestArgumentSerializers.class, argumentSerializers);
                    registration.add(InstantiatorFactory.class, instantiatorFactory);
                    // TODO we should inject a worker-api specific implementation of InternalProblems here
                    registration.add(InternalProblems.class, new DefaultProblems(
                        new WorkerProblemEmitter(responder),
                        null,
                        CurrentBuildOperationRef.instance(),
                        new ExceptionProblemRegistry(),
                        null,
                        instantiatorFactory.decorateLenient(),
                        createPayloadSerializer()));
                })
                .build();
            Class<?> workerImplementation = Class.forName(workerImplementationName);
            implementation = Cast.uncheckedNonnullCast(instantiatorFactory.inject(serviceRegistry).newInstance(workerImplementation));
        } catch (Exception e) {
            failure = e;
        }

        if (failure == null) {
            connection.useParameterSerializers(RequestSerializerRegistry.create(this.getClass().getClassLoader(), argumentSerializers));
        } else {
            // Discard incoming requests, as the serializers may not have been configured
            connection.useParameterSerializers(RequestSerializerRegistry.createDiscardRequestArg());
            // Notify the client
            responder.infrastructureFailed(failure);
        }

        connection.connect();

        try {
            completed.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void stop() {
        completed.countDown();
        CurrentBuildOperationRef.instance().clear();
    }

    @Override
    public void endStream() {
        // This happens when the connection between the worker and the build daemon is closed for some reason,
        // possibly because the build daemon died unexpectedly.
        stop();
    }

    @Override
    public void runThenStop(Request request) {
        try {
            run(request);
        } finally {
            stop();
        }
    }

    @Override
    public void run(Request request) {
        if (failure != null) {
            // Ignore
            return;
        }
        CurrentBuildOperationRef.instance().with(request.getBuildOperation(), () -> {
            try {
                Object result;
                try {
                    // We want to use the responder as the logging protocol object here because log messages from the
                    // action will have the build operation associated.  By using the responder, we ensure that all
                    // messages arrive on the same incoming queue in the build process and the completed message will only
                    // arrive after all log messages have been processed.
                    result = workerLogEventListener.withWorkerLoggingProtocol(responder, new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            return implementation.run(request.getArg());
                        }
                    });
                } catch (Throwable failure) {
                    if (failure instanceof NoClassDefFoundError) {
                        // Assume an infrastructure problem
                        responder.infrastructureFailed(failure);
                    } else {
                        responder.failed(failure);
                    }
                    return;
                }
                responder.completed(result);
            } catch (Throwable t) {
                responder.infrastructureFailed(t);
            }
        });
    }

    @Override
    public void handleStreamFailure(Throwable t) {
        responder.failed(t);
    }

    /**
     * A {@link ClassCacheFactory} that holds strong references to all keys and values. This simple
     * implementation differs from that of the Gradle Daemon, as the lifecycle for a worker process
     * is much simpler than that of the daemon.
     */
    private static class BasicClassCacheFactory implements ClassCacheFactory {

        @Override
        public <V> Cache<Class<?>, V> newClassCache() {
            return new MapBackedCache<>(new ConcurrentHashMap<>());
        }

        @Override
        public <V> Cache<Class<?>, V> newClassMap() {
            return new MapBackedCache<>(new ConcurrentHashMap<>());
        }

    }

    private static class BasicPropertyRoleAnnotationHandler implements PropertyRoleAnnotationHandler {
        @Override
        public Set<Class<? extends Annotation>> getAnnotationTypes() {
            return Collections.emptySet();
        }

        @Override
        public void applyRoleTo(ModelObject owner, Object target) {
            if (target instanceof PropertyInternal) {
                ((PropertyInternal<?>) target).attachProducer(owner);
            }
        }
    }

}
