/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.konan.target

import org.jetbrains.kotlin.konan.file.*
import org.jetbrains.kotlin.konan.properties.*
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.konan.util.visibleName
import org.jetbrains.kotlin.konan.util.DependencyProcessor

class Distribution(
        private val onlyDefaultProfiles: Boolean = false,
        private val configDirOverride: String? = null,
        private val runtimeFileOverride: String? = null) {

    val localKonanDir =File(File.userHome, ".konan")

    private fun findKonanHome(): String {
        val value = System.getProperty("konan.home", "dist")
        val path = File(value).absolutePath
        return path
    }

    val konanHome = findKonanHome()
    val configDir = configDirOverride ?: "$konanHome/konan"
    val mainPropertyFileName = "$configDir/konan.properties"

    fun preconfiguredPropertyFiles(genericName: String): List<File> =
            File(this.configDir, "platforms/$genericName").listFiles

    fun userPropertyFiles(genericName: String): List<File> =
            localKonanDir?.let { File(it, "platforms/$genericName").listFiles } ?: emptyList()

    fun additionalPropertyFiles(genericName: String) =
            preconfiguredPropertyFiles(genericName) + userPropertyFiles(genericName)

    val properties by lazy {
        val loaded = File(mainPropertyFileName).loadProperties()
        HostManager.knownTargetTemplates.forEach {
            additionalPropertyFiles(it).forEach {
                println("### loading ${it.absolutePath}")
                val additional = it.loadProperties()
                loaded.putAll(additional)
            }
        }
        if (onlyDefaultProfiles) {
            loaded.keepOnlyDefaultProfiles()
        }
        loaded
    }

    val klib = "$konanHome/klib"
    val stdlib = "$klib/common/stdlib"

    val additionalPlatformDefinitions by lazy {
        val userPlatforms = localKonanDir?.let { File(it, "config/platforms").listFiles }
        val localPlatforms = File(configDir, "platforms").listFiles
        localPlatforms + userPlatforms.orEmpty()
    }

    fun defaultNatives(target: KonanTarget) = "$konanHome/konan/targets/${target.visibleName}/native"

    fun runtime(target: KonanTarget) = runtimeFileOverride ?: "$stdlib/targets/${target.visibleName}/native/runtime.bc"

    val dependenciesDir = DependencyProcessor.defaultDependenciesRoot.absolutePath

    fun availableSubTarget(genericName: String) =
            additionalPropertyFiles(genericName).map { it.name }
}

fun Properties.keepOnlyDefaultProfiles() {
    val DEPENDENCY_PROFILES_KEY = "dependencyProfiles"
    val dependencyProfiles = this.getProperty(DEPENDENCY_PROFILES_KEY)
    if (dependencyProfiles != "default alt")
        error("unexpected $DEPENDENCY_PROFILES_KEY value: expected 'default alt', got '$dependencyProfiles'")

    // Force build to use only 'default' profile:
    this.setProperty(DEPENDENCY_PROFILES_KEY, "default")
    // Force build to use fixed Xcode version:
    this.setProperty("useFixedXcodeVersion", "9.2")
    // TODO: it actually affects only resolution made in :dependencies,
    // that's why we assume that 'default' profile comes first (and check this above).
}

fun buildDistribution(localConfigDir: String) = Distribution(true, localConfigDir, null)

fun customerDistribution(konanHome: String? = null) = konanHome?.let { Distribution(false, "$it/konan", null) }
        ?: Distribution()
