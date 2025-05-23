/*
 * Copyright 2025 the original author or authors.
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

package org.gradle.integtests.fixtures

import org.gradle.api.JavaVersion
import org.gradle.test.fixtures.VersionCoverage

import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.notNullValue
import static org.junit.Assume.assumeThat

class ScalaCoverage {

    static final List<String> SCALA_2 = [
        "2.11.12",
        "2.12.19",
        "2.13.16",
    ]
    static final List<String> SCALA_3 = [
        "3.1.3",
        "3.2.2",
        "3.3.5",
        "3.6.3",
    ]


    static final Set<String> SUPPORTED_BY_JDK = scalaVersionsSupportedByJdk(JavaVersion.current())
    static final Set<String> LATEST_IN_MAJOR = SUPPORTED_BY_JDK.findAll {
        it == SCALA_2.last() || it == SCALA_3.last()
    }

    static String getLatestSupportedScala2Version() {
        def latestScala2Version = SCALA_2.reverse().find { SUPPORTED_BY_JDK.contains(it) }
        assumeThat("No supported Scala 2 version found", latestScala2Version, is(notNullValue()))
        return latestScala2Version
    }

    static String getLatestSupportedScala3Version() {
        def latestScala3Version = SCALA_3.reverse().find { SUPPORTED_BY_JDK.contains(it) }
        assumeThat("No supported Scala 3 version found", latestScala3Version, is(notNullValue()))
        return latestScala3Version
    }

    static Set<String> scalaVersionsSupportedByJdk(JavaVersion javaVersion) {
        return scala2VersionsSupportedByJdk(javaVersion) + scala3VersionsSupportedByJdk(javaVersion)
    }

    private static Set<String> scala2VersionsSupportedByJdk(JavaVersion javaVersion) {
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_24)) {
            return VersionCoverage.versionsAtLeast(SCALA_2, "2.13.17") // Tentative, not released yet. The version here also needs to not trigger the Unsafe warnings (https://openjdk.org/jeps/498).
        }
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_1_9)) {
            // All latest patches of 2.13 work on Java 9+
            // 2.12 in theory supports it, but doesn't actually take it as a -target so we can't use it
            return VersionCoverage.versionsAtLeast(SCALA_2, "2.13.0")
        }
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_1_8)) {
            // Java 8 support not dropped yet
            return SCALA_2
        }
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_1_6)) {
            // 2.12+ requires Java 8
            return VersionCoverage.versionsBetweenExclusive(SCALA_2, "2.11.0", "2.12.0")
        }
        throw new IllegalArgumentException("Unsupported Java version for Scala 2: " + javaVersion)
    }

    private static Set<String> scala3VersionsSupportedByJdk(JavaVersion javaVersion) {
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_24)) {
            return VersionCoverage.versionsAtLeast(SCALA_3, "3.6.4") // The version here also needs to not trigger the Unsafe warnings (https://openjdk.org/jeps/498).
        }
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_20)) {
            // Latest patches of 3.3.x work on Java 20+
            return VersionCoverage.versionsAtLeast(SCALA_3, "3.3.0")
        }
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_19)) {
            return VersionCoverage.versionsAtLeast(SCALA_3, "3.2.2")
        }
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_18)) {
            return VersionCoverage.versionsAtLeast(SCALA_3, "3.1.3")
        }
        if (javaVersion.isCompatibleWith(JavaVersion.VERSION_1_8)) {
            return SCALA_3
        }
        throw new IllegalArgumentException("Unsupported Java version for Scala 3: " + javaVersion)
    }

}
