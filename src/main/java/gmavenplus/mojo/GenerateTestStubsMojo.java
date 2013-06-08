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

package gmavenplus.mojo;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;


/**
 * Generates stubs for the test sources.
 * Note that this mojo requires Groovy >= 1.7.0.
 *
 * @author Keegan Witt
 *
 * @goal testGenerateStubs
 * @phase generate-test-sources
 * @threadSafe
 */
public class GenerateTestStubsMojo extends AbstractGenerateStubsMojo {

    /**
     * Flag to allow test compilation to be skipped
     *
     * @parameter expression="${maven.test.skip}" default-value="false"
     */
    protected boolean skip;

    /**
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!skip) {
            if (groovyVersionSupportsAction()) {
                logGroovyVersion("generateTestStubs");

                try {
                    doStubGeneration(getTestSources(), testStubsOutputDirectory);
                    resetStubModifiedDates(getTestStubs());

                    // log generated stubs
                    int stubCount = getTestStubs().size();
                    getLog().info("Generated " + stubCount + " stubs.");
                } catch (ClassNotFoundException e) {
                    throw new MojoExecutionException("Unable to get a Groovy class from classpath.  Do you have Groovy as a compile dependency in your project?", e);
                } catch (InvocationTargetException e) {
                    throw new MojoExecutionException("Error occurred while calling a method on a Groovy class from classpath.", e);
                } catch (InstantiationException e) {
                    throw new MojoExecutionException("Error occurred while instantiating a Groovy class from classpath.", e);
                } catch (IllegalAccessException e) {
                    throw new MojoExecutionException("Unable to access a method on a Groovy class from classpath.", e);
                }
            } else {
                getLog().error("Your Groovy version (" + getGroovyVersion() + ") doesn't support stub generation.  Skipping stub generation.");
            }
        } else {
            getLog().info("Skipping generation of test stubs because ${maven.test.skip} was set to true.");
        }
    }

}
