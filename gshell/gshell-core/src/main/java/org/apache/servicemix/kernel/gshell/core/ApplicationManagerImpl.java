/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.servicemix.kernel.gshell.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import javax.annotation.PostConstruct;

import org.apache.geronimo.gshell.application.ApplicationManager;
import org.apache.geronimo.gshell.application.ApplicationConfiguration;
import org.apache.geronimo.gshell.application.Application;
import org.apache.geronimo.gshell.application.ApplicationSecurityManager;
import org.apache.geronimo.gshell.shell.Shell;
import org.apache.geronimo.gshell.spring.BeanContainerAware;
import org.apache.geronimo.gshell.spring.BeanContainer;
import org.apache.geronimo.gshell.wisdom.application.ShellCreatedEvent;
import org.apache.geronimo.gshell.event.EventPublisher;
import org.apache.geronimo.gshell.io.SystemOutputHijacker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApplicationManagerImpl implements ApplicationManager, ApplicationContextAware {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private Application application;

    private ApplicationContext applicationContext;

    private boolean createLocalShell = true;

    @PostConstruct
    public void init() throws Exception {
        if (!SystemOutputHijacker.isInstalled()) {
            SystemOutputHijacker.install();
        }
        SystemOutputHijacker.register(application.getIo().outputStream, application.getIo().errorStream);
        if (createLocalShell) {
            new Thread("localShell") {
                public void run() {
                    Shell shell;
                    try {
                        shell = create();
                        try {
                            shell.run();
                        } catch (Exception e2) {
                            log.error("Error running shell", e2);
                        }
                    } catch (Exception e1) {
                        log.error("Error creating shell", e1);
                    }
                }
            }.start();
        }
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void configure(ApplicationConfiguration applicationConfiguration) throws Exception {
        throw new UnsupportedOperationException();
    }

    public Application getApplication() {
        return application;
    }

    public Shell create() throws Exception {
        final Shell shell = (Shell) getBean(Shell.class);

        log.debug("Created shell instance: {}", shell);

        InvocationHandler handler = new InvocationHandler()
        {
            //
            // FIXME: Need to resolve how to handle the security manager for the application,
            //        the SM is not thread-specific, but VM specific... so not sure this is
            //        the right approache at all :-(
            //

            private final ApplicationSecurityManager sm = new ApplicationSecurityManager();

            public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
                assert proxy != null;
                assert method != null;
                // args may be null

                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(this, args);
                }

                //
                // TODO: This would be a good place to inject the shell or the shell context into a thread holder
                //

                final SecurityManager prevSM = System.getSecurityManager();
                System.setSecurityManager(sm);
                try {
                    return method.invoke(shell, args);
                }
                catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
                finally {
                    System.setSecurityManager(prevSM);
                }
            }
        };

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Shell proxy = (Shell) Proxy.newProxyInstance(cl, new Class[] { Shell.class }, handler);

        log.debug("Create shell proxy: {}", proxy);

        eventPublisher.publish(new ShellCreatedEvent(proxy));

        return proxy;
    }

    public <T> T getBean(Class<T> type) {
        assert type != null;

        log.trace("Getting bean of type: {}", type);

        String[] names = applicationContext.getBeanNamesForType(type);

        if (names.length == 0) {
            throw new NoSuchBeanDefinitionException(type, "No bean defined for type: " + type);
        }
        if (names.length > 1) {
            throw new NoSuchBeanDefinitionException(type, "No unique bean defined for type: " + type + ", found matches: " + Arrays.asList(names));
        }

        return getBean(names[0], type);
    }

    public <T> T getBean(String name, Class<T> requiredType) {
        assert name != null;
        assert requiredType != null;

        log.trace("Getting bean named '{}' of type: {}", name, requiredType);

        return (T) applicationContext.getBean(name, requiredType);
    }

}
