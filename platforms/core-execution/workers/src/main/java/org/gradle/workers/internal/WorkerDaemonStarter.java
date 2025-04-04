/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.workers.internal;

import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.logging.LoggingManager;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classloader.VisitableURLClassLoader;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.gradle.process.internal.JavaExecHandleBuilder;
import org.gradle.process.internal.worker.MultiRequestClient;
import org.gradle.process.internal.worker.MultiRequestWorkerProcessBuilder;
import org.gradle.process.internal.worker.WorkerProcess;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.util.internal.CollectionUtils;

import java.io.File;
import java.net.URISyntaxException;

public class WorkerDaemonStarter {
    private final static Logger LOG = Logging.getLogger(WorkerDaemonStarter.class);
    private final WorkerProcessFactory workerDaemonProcessFactory;
    private final LoggingManager loggingManager;
    private final ClassPathRegistry classPathRegistry;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;

    public WorkerDaemonStarter(WorkerProcessFactory workerDaemonProcessFactory, LoggingManager loggingManager, ClassPathRegistry classPathRegistry, ActionExecutionSpecFactory actionExecutionSpecFactory) {
        this.workerDaemonProcessFactory = workerDaemonProcessFactory;
        this.loggingManager = loggingManager;
        this.classPathRegistry = classPathRegistry;
        this.actionExecutionSpecFactory = actionExecutionSpecFactory;
    }

    WorkerDaemonClient startDaemon(DaemonForkOptions forkOptions) {
        LOG.debug("Starting Gradle worker daemon with fork options {}.", forkOptions);
        Timer clock = Time.startTimer();
        MultiRequestWorkerProcessBuilder<TransportableActionExecutionSpec, DefaultWorkResult> builder = workerDaemonProcessFactory.multiRequestWorker(WorkerDaemonServer.class);
        builder.setBaseName("Gradle Worker Daemon");
        builder.setLogLevel(loggingManager.getLevel()); // NOTE: might make sense to respect per-compile-task log level
        builder.sharedPackages("org.gradle", "javax.inject");

        // We know the exact minimal classpath for the WorkerDaemonServer.
        // Do not use the automatic implementation classpath.
        builder.withoutAutomaticImplementationClasspath();
        builder.applicationClasspath(classPathRegistry.getClassPath("DAEMON_SERVER_WORKER").getAsFiles());

        // For flat classloaders, we include the work classpath along with the WorkerDaemonServer implementation.
        // As a consequence of a flat classloader, the work is able to see the classes of the WorkerDaemonServer
        // at runtime.
        // This is fine for internal work, but for user-provided work, we serialize the work classpath and load
        // it on the worker side.
        // We primarily use a flat classloader for Java compilation workers, as using a hierarchical classloader
        // caused performance regressions. The Java compiler seems to hammer the classloader, and performance
        // is better with a flat classloader. A hierarchical classloader should be preferred when classloader
        // performance is not a concern.
        if (forkOptions.getClassLoaderStructure() instanceof FlatClassLoaderStructure) {
            FlatClassLoaderStructure flatClassLoaderStructure = (FlatClassLoaderStructure) forkOptions.getClassLoaderStructure();
            Iterable<File> workClasspath = toFiles(flatClassLoaderStructure.getSpec());
            builder.applicationClasspath(workClasspath);
        }

        JavaExecHandleBuilder javaCommand = builder.getJavaCommand();
        forkOptions.copyTo(javaCommand);
        builder.registerArgumentSerializer(TransportableActionExecutionSpec.class, new TransportableActionExecutionSpecSerializer());
        MultiRequestClient<TransportableActionExecutionSpec, DefaultWorkResult> workerDaemonProcess = builder.build();
        WorkerProcess workerProcess = workerDaemonProcess.start();

        WorkerDaemonClient client = new WorkerDaemonClient(forkOptions, workerDaemonProcess, workerProcess, loggingManager.getLevel(), actionExecutionSpecFactory);

        LOG.info("Started Gradle worker daemon ({}) with fork options {}.", clock.getElapsed(), forkOptions);

        return client;
    }

    private static Iterable<File> toFiles(VisitableURLClassLoader.Spec spec) {
        return CollectionUtils.collect(spec.getClasspath(), url -> {
            try {
                return new File(url.toURI());
            } catch (URISyntaxException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        });
    }
}
