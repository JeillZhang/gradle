/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.api.internal.plugins

import org.gradle.jvm.application.scripts.JavaAppStartScriptGenerationDetails
import org.gradle.util.internal.TextUtil
import spock.lang.Issue
import spock.lang.Specification

class WindowsStartScriptGeneratorTest extends Specification {

    WindowsStartScriptGenerator generator = new WindowsStartScriptGenerator()

    def "classpath for windows script uses backslash as path separator and windows line separator"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(null, 'bin')
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        destination.toString().contains("set CLASSPATH=%APP_HOME%\\path\\to\\Jar.jar")
    }

    def "windows script uses windows line separator"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(null, 'bin')
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        def scriptText = destination.toString()
        def carriageLineEndings = scriptText.split('\r').length - 1 // over counts the final line
        def newlineEndings = scriptText.split('\n').length
        def windowsLineEndings = scriptText.split(TextUtil.windowsLineSeparator).length

        // Windows line endings are made up of two characters,
        // we should see an equal number of lines unless
        // the generator is using the wrong line ending entirely
        // or has generated some lines with one or the other character
        carriageLineEndings == newlineEndings
        windowsLineEndings == newlineEndings
    }

    def "defaultJvmOpts is expanded properly in windows script"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(['-Dfoo=bar', '-Xint'], 'bin')
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        destination.toString().contains('set DEFAULT_JVM_OPTS="-Dfoo=bar" "-Xint"')
    }

    def "defaultJvmOpts is expanded properly in windows script -- spaces"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(['-Dfoo=bar baz', '-Xint'], 'bin')
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        destination.toString().contains(/set DEFAULT_JVM_OPTS="-Dfoo=bar baz" "-Xint"/)
    }

    def "defaultJvmOpts is expanded properly in windows script -- double quotes"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(['-Dfoo=b"ar baz', '-Xi""nt', '-Xpatho\\"logical'], 'bin')
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        destination.toString().contains(/set DEFAULT_JVM_OPTS="-Dfoo=b\"ar baz" "-Xi\"\"nt" "-Xpatho\\\"logical"/)
    }

    def "defaultJvmOpts is expanded properly in windows script -- backslashes and shell metacharacters"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(['-Dfoo=b\\ar baz', '-Xint%PATH%'], 'bin')
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        destination.toString().contains(/set DEFAULT_JVM_OPTS="-Dfoo=b\ar baz" "-Xint%%PATH%%"/)
    }

    def "determines application-relative path"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(null, 'bin/sample/start')
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        destination.toString().contains('set APP_HOME=%DIRNAME%..\\..')
    }

    def "generates correct output for #type entry point"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(null, 'bin/sample/start', entryPoint)
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        def appExecutionLine = destination.toString().readLines().find { it.startsWith('"%JAVA_EXE%"') }
        appExecutionLine.contains(entryPointArgs)

        where:
        type                          | entryPoint                                        | entryPointArgs
        "main class"                  | new MainClass("com.example.Main")                 | 'com.example.Main'
        "executable jar"              | new ExecutableJar("example.jar")                  | '-jar "%APP_HOME%\\example.jar"'
        "main module"                 | new MainModule("com.example", null)               | '--module com.example'
        "main module with main class" | new MainModule("com.example", "com.example.Main") | '--module com.example/com.example.Main'
    }

    @Issue("https://github.com/gradle/gradle/issues/33415")
    def "Do not set classpath if it is empty"() {
        given:
        JavaAppStartScriptGenerationDetails details = createScriptGenerationDetails(null, 'bin', new MainClass(""), classpath)
        Writer destination = new StringWriter()

        when:
        generator.generateScript(details, destination)

        then:
        destination.toString().contains(text) == result

        where:
        classpath             | text                       | result
        []                    | 'set CLASSPATH'            | false
        []                    | '-classpath "%CLASSPATH%"' | false
        ['path\\to\\Jar.jar'] | 'set CLASSPATH'            | true
        ['path\\to\\Jar.jar'] | '-classpath "%CLASSPATH%"' | true
    }

    private JavaAppStartScriptGenerationDetails createScriptGenerationDetails(
        List<String> defaultJvmOpts,
        String scriptRelPath,
        AppEntryPoint appEntryPoint = new MainClass(""),
        List<String> classpath = ['path/to/Jar.jar']
    ) {
        final String applicationName = 'TestApp'
        return new DefaultJavaAppStartScriptGenerationDetails(applicationName, null, null, appEntryPoint, defaultJvmOpts, classpath, [], scriptRelPath, null)
    }
}
