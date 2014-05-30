/*
 * Copyright (C) 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.gmavenplus.mojo;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.gmavenplus.util.ClassWrangler;
import org.codehaus.gmavenplus.util.FileUtils;
import org.codehaus.gmavenplus.util.NoExitSecurityManager;
import org.codehaus.gmavenplus.util.ReflectionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;


/**
 * Executes Groovy scripts (in the pom or external), bound to the current project.
 * Note that this mojo requires Groovy >= 1.5.0.
 * Note that it references the plugin classloader to pull in dependencies
 * Groovy didn't include (for things like Ant for AntBuilder, Ivy for @grab,
 * and Jansi for Groovysh).
 *
 * @author Keegan Witt
 * @since 1.0-beta-1
 *
 * @goal execute
 * @configurator include-project-test-dependencies
 * @requiresDependencyResolution test
 * @threadSafe
 */
public class ExecuteMojo extends AbstractToolsMojo {

    /**
     * Groovy scripts to run (in order).  Can be an actual Groovy script or a
     * {@link java.net.URL URL} to a Groovy script (local or remote).
     *
     * @parameter
     * @required
     */
    protected String[] scripts;

    /**
     * Whether to continue executing remaining scripts when a script fails.
     *
     * @parameter default-value="false"
     */
    protected boolean continueExecuting;

    /**
     * The encoding of script files.
     * @since 1.0-beta-2
     *
     * @parameter default-value="${project.build.sourceEncoding}"
     */
    protected String sourceEncoding;

    /**
     * Executes this mojo.
     *
     * @throws MojoExecutionException If an unexpected problem occurs. Throwing this exception causes a "BUILD ERROR" message to be displayed
     * @throws MojoFailureException If an expected problem (such as a compilation failure) occurs. Throwing this exception causes a "BUILD FAILURE" message to be displayed
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        doExecute();
    }

    /**
     * Does the actual execution.
     *
     * @throws MojoExecutionException If an unexpected problem occurs. Throwing this exception causes a "BUILD ERROR" message to be displayed
     * @throws MojoFailureException If an expected problem (such as a invocation failure) occurs. Throwing this exception causes a "BUILD FAILURE" message to be displayed
     */
    protected synchronized void doExecute() throws MojoExecutionException, MojoFailureException {
        classWrangler = new ClassWrangler(Thread.currentThread().getContextClassLoader(), getLog());

        if (groovyVersionSupportsAction()) {
            classWrangler.logGroovyVersion(mojoExecution.getMojoDescriptor().getGoal());
            logPluginClasspath();
            if (getLog().isDebugEnabled()) {
                try {
                    getLog().debug("Project test classpath:\n" + project.getTestClasspathElements());
                } catch (DependencyResolutionRequiredException e) {
                    getLog().warn("Unable to log project test classpath", e);
                }
            }

            if (scripts == null || scripts.length == 0) {
                getLog().info("No scripts specified for execution.  Skipping.");
                return;
            }

            final SecurityManager sm = System.getSecurityManager();
            try {
                if (!allowSystemExits) {
                    System.setSecurityManager(new NoExitSecurityManager());
                }

                // get classes we need with reflection
                Class groovyShellClass = classWrangler.getClass("groovy.lang.GroovyShell");

                // create a GroovyShell to run scripts in
                Object shell = setupShell(groovyShellClass);

                // run the scripts
                executeScripts(groovyShellClass, shell);
            } catch (ClassNotFoundException e) {
                throw new MojoExecutionException("Unable to get a Groovy class from classpath.  Do you have Groovy as a compile dependency in your project or the plugin?", e);
            } catch (InvocationTargetException e) {
                throw new MojoExecutionException("Error occurred while calling a method on a Groovy class from classpath.", e);
            } catch (InstantiationException e) {
                throw new MojoExecutionException("Error occurred while instantiating a Groovy class from classpath.", e);
            } catch (IllegalAccessException e) {
                throw new MojoExecutionException("Unable to access a method on a Groovy class from classpath.", e);
            } finally {
                System.setSecurityManager(sm);
            }
        } else {
            getLog().error("Your Groovy version (" + classWrangler.getGroovyVersion() + ") doesn't support script execution.  The minimum version of Groovy required is " + minGroovyVersion + ".  Skipping script execution.");
        }
    }

    /**
     * Creates the GroovyShell shell to use to execute scripts.
     *
     * @param groovyShellClass the GroovyShell class
     * @return the GroovyShell shell to use to execute scripts
     * @throws InstantiationException when a class needed for script execution cannot be instantiated
     * @throws IllegalAccessException when a method needed for script execution cannot be accessed
     * @throws InvocationTargetException when a reflection invocation needed for script execution cannot be completed
     */
    protected Object setupShell(final Class groovyShellClass) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        Object shell = ReflectionUtils.invokeConstructor(ReflectionUtils.findConstructor(groovyShellClass));
        initializeProperties();
        if (bindPropertiesToSeparateVariables) {
            for (Object k : properties.keySet()) {
                ReflectionUtils.invokeMethod(ReflectionUtils.findMethod(groovyShellClass, "setProperty", String.class, Object.class), shell, k, properties.get(k));
            }
        } else {
            ReflectionUtils.invokeMethod(ReflectionUtils.findMethod(groovyShellClass, "setProperty", String.class, Object.class), shell, "properties", properties);
        }

        return shell;
    }

    /**
     * Executes the scripts using the GroovyShell.
     *
     * @param groovyShellClass the GroovyShell class
     * @param shell the shell to use for script execution
     * @throws IllegalAccessException when a method needed for script execution cannot be accessed
     * @throws InvocationTargetException when a reflection invocation needed for script execution cannot be completed
     * @throws MojoExecutionException when an error occurs during script execution
     */
    protected void executeScripts(final Class groovyShellClass, final Object shell) throws InvocationTargetException, IllegalAccessException, MojoExecutionException {
        int scriptNum = 1;
        for (String script : scripts) {
            try {
                BufferedReader reader = null;
                try {
                    URL url = new URL(script);
                    // it's a URL to a script
                    getLog().info("Fetching Groovy script from " + url.toString() + ".");
                    if (sourceEncoding != null) {
                        reader = new BufferedReader(new InputStreamReader(url.openStream(), sourceEncoding));
                    } else {
                        reader = new BufferedReader(new InputStreamReader(url.openStream()));
                    }
                    StringBuilder scriptSource = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        scriptSource.append(line).append("\n");
                    }
                    if (scriptSource.toString().length() != 0) {
                        ReflectionUtils.invokeMethod(ReflectionUtils.findMethod(groovyShellClass, "evaluate", String.class), shell, scriptSource.toString());
                    }
                } catch (MalformedURLException e) {
                    // it's not a URL to a script, treat as a script body
                    ReflectionUtils.invokeMethod(ReflectionUtils.findMethod(groovyShellClass, "evaluate", String.class), shell, script);
                } finally {
                    FileUtils.closeQuietly(reader);
                }
            } catch (IOException ioe) {
                if (continueExecuting) {
                    getLog().error("An Exception occurred while executing script " + scriptNum + ".  Continuing to execute remaining scripts.", ioe);
                } else {
                    throw new MojoExecutionException("An Exception occurred while executing script " + scriptNum + ".", ioe);
                }
            }
            scriptNum++;
        }
    }

}
