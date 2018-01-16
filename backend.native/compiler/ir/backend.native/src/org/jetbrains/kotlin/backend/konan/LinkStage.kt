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

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.konan.exec.runTool
import java.lang.ProcessBuilder
import java.lang.ProcessBuilder.Redirect
import org.jetbrains.kotlin.konan.file.*
import org.jetbrains.kotlin.konan.properties.*
import org.jetbrains.kotlin.konan.target.*

typealias BitcodeFile = String
typealias ObjectFile = String
typealias ExecutableFile = String
typealias StaticLibrary = String

// Use "clang -v -save-temps" to write linkCommand() method 
// for another implementation of this class.
internal abstract class PlatformFlags(val properties: KonanProperties) {
    val llvmLtoNooptFlags = properties.llvmLtoNooptFlags
    val llvmLtoOptFlags = properties.llvmLtoOptFlags
    val llvmLtoFlags = properties.llvmLtoFlags
    val llvmLtoDynamicFlags = properties.llvmLtoDynamicFlags
    val entrySelector = properties.entrySelector
    val linkerOptimizationFlags = properties.linkerOptimizationFlags
    val linkerKonanFlags = properties.linkerKonanFlags
    val linkerNoDebugFlags = properties.linkerNoDebugFlags
    val linkerDynamicFlags = properties.linkerDynamicFlags
    val llvmDebugOptFlags = properties.llvmDebugOptFlags
    val s2wasmFlags = properties.s2wasmFlags
    val targetToolchain = properties.absoluteTargetToolchain
    val targetSysRoot = properties.absoluteTargetSysRoot

    val targetLibffi = properties.libffiDir ?.let { listOf("${properties.absoluteLibffiDir}/lib/libffi.a") } ?: emptyList()

    open val useCompilerDriverAsLinker: Boolean get() = false // TODO: refactor.

    abstract fun linkCommand(objectFiles: List<ObjectFile>,
                             executable: ExecutableFile, optimize: Boolean, debug: Boolean, dynamic: Boolean, staticLibraries: List<StaticLibrary>): Command

    open fun linkCommandSuffix(): List<String> = emptyList()

    protected fun propertyTargetString(name: String)
        = properties.targetString(name)!!
    protected fun propertyTargetList(name: String)
        = properties.targetList(name)

    abstract fun filterStaticLibraries(binaries: List<String>): List<String>

    open fun linkStaticLibraries(binaries: List<String>): List<String> {
        val libraries = filterStaticLibraries(binaries)
        // Let's just pass them as absolute paths
        return libraries
    }

}

internal open class Command(tool:String) {
    private val opts = mutableListOf(tool)
    val libs = mutableListOf<String>()
    operator fun String.unaryPlus():Command {
        opts += this
        return this@Command
    }

    operator fun List<String>.unaryPlus():Command {
        opts.addAll(this)
        return this@Command
    }

    open fun execute() = runTool(*opts.toTypedArray())

    fun externalLibraries(deps: List<String>) {
        libs.addAll(deps)
    }
}

internal open class AndroidPlatform(distribution: Distribution)
    : PlatformFlags(distribution.targetProperties) {

    private val prefix = "$targetToolchain/bin/"
    private val clang = "$prefix/clang"

    override val useCompilerDriverAsLinker: Boolean get() = true

    override fun filterStaticLibraries(binaries: List<String>)
        = binaries.filter { it.isUnixStaticLib }

    override fun linkCommand(objectFiles: List<ObjectFile>, executable: ExecutableFile, optimize: Boolean, debug: Boolean, dynamic: Boolean, staticLibraries: List<StaticLibrary>): Command {
        // liblog.so must be linked in, as we use its functionality in runtime.
        return Command(clang).apply {
            + "-o"
            + executable
            + "-fPIC"
            + "-shared"
            + "-llog"
            + objectFiles
            if (optimize) + linkerOptimizationFlags
            if (!debug) + linkerNoDebugFlags
            if (dynamic) + linkerDynamicFlags
            + linkerKonanFlags
        }
    }
}

internal open class MacOSBasedPlatform(distribution: Distribution)
    : PlatformFlags(distribution.targetProperties) {

    private val linker = "$targetToolchain/usr/bin/ld"
    internal val dsymutil = "${distribution.llvmBin}/llvm-dsymutil"
    internal val libLTO = distribution.libLTO

    open val osVersionMin by lazy {
        listOf(
                propertyTargetString("osVersionMinFlagLd"),
                properties.osVersionMin!! + ".0")
    }

    override fun filterStaticLibraries(binaries: List<String>)
        = binaries.filter { it.isUnixStaticLib }

    override fun linkCommand(objectFiles: List<ObjectFile>, executable: ExecutableFile, optimize: Boolean, debug: Boolean, dynamic: Boolean, staticLibraries: List<StaticLibrary>): Command {
        return object : Command(linker){
            override fun execute() {
                super.execute()
                if (debug)
                    runTool(*dsymutilCommand(executable).toTypedArray())
            }
        }.apply {
            + "-demangle"
            + listOf("-object_path_lto", "temporary.o", "-lto_library", libLTO)
            + listOf("-dynamic", "-arch", propertyTargetString("arch"))
            + osVersionMin
            + listOf("-syslibroot", targetSysRoot, "-o", executable)
            + objectFiles
            + staticLibraries.map { listOf("-force_load", it) }.flatten()
            if (optimize) + linkerOptimizationFlags
            if (!debug) + linkerNoDebugFlags
            if (dynamic) + linkerDynamicFlags
            + linkerKonanFlags
            + "-lSystem"
        }
    }

    open fun dsymutilCommand(executable: ExecutableFile): List<String> = listOf(dsymutil, executable)

    open fun dsymutilDryRunVerboseCommand(executable: ExecutableFile): List<String> =
            listOf(dsymutil, "-dump-debug-map" ,executable)
}

internal open class LinuxBasedPlatform(val distribution: Distribution)
    : PlatformFlags(distribution.targetProperties) {

    private val llvmLib = distribution.llvmLib
    private val libGcc = "$targetSysRoot/${propertyTargetString("libGcc")}"
    private val linker = "$targetToolchain/bin/ld.gold"
    private val pluginOptimizationFlags = propertyTargetList("pluginOptimizationFlags")
    private val specificLibs
        = propertyTargetList("abiSpecificLibraries").map { "-L${targetSysRoot}/$it" }

    override fun filterStaticLibraries(binaries: List<String>)
        = binaries.filter { it.isUnixStaticLib }

    override fun linkCommand(objectFiles: List<ObjectFile>, executable: ExecutableFile, optimize: Boolean, debug: Boolean, dynamic: Boolean, staticLibraries: List<StaticLibrary>): Command {
        val isMips = (distribution.target == KonanTarget.LINUX_MIPS32 ||
                distribution.target == KonanTarget.LINUX_MIPSEL32)
        // TODO: Can we extract more to the konan.properties?
        return Command(linker).apply {
            + "--sysroot=${targetSysRoot}"
            + "-export-dynamic"
            + "-z"
            + "relro"
            + "--build-id"
            + "--eh-frame-hdr"
            // + "-m"
            // + "elf_x86_64",
            + "-dynamic-linker"
            + propertyTargetString("dynamicLinker")
            + "-o"
            + executable
            + staticLibraries.map { listOf("--whole-archive", it) }.flatten()
            if (!dynamic) + "$targetSysRoot/usr/lib64/crt1.o"
            + "$targetSysRoot/usr/lib64/crti.o"
            if (dynamic)
                + "$libGcc/crtbeginS.o"
            else
                + "$libGcc/crtbegin.o"
            + "-L$llvmLib"
            + "-L$libGcc"
            if (!isMips) + "--hash-style=gnu" // MIPS doesn't support hash-style=gnu
            + specificLibs
            + listOf("-L$targetSysRoot/../lib", "-L$targetSysRoot/lib", "-L$targetSysRoot/usr/lib")
            if (optimize) {
                + "-plugin"
                +"$llvmLib/LLVMgold.so"
                + pluginOptimizationFlags
            }
            if (optimize) + linkerOptimizationFlags
            if (!debug) + linkerNoDebugFlags
            if (dynamic) + linkerDynamicFlags
            + objectFiles
            + linkerKonanFlags
            + listOf("-lgcc", "--as-needed", "-lgcc_s", "--no-as-needed",
                    "-lc", "-lgcc", "--as-needed", "-lgcc_s", "--no-as-needed")
            if (dynamic)
                + "$libGcc/crtendS.o"
            else
                + "$libGcc/crtend.o"
            + "$targetSysRoot/usr/lib64/crtn.o"
        }
    }
}

internal open class MingwPlatform(distribution: Distribution)
    : PlatformFlags(distribution.targetProperties) {

    private val linker = "$targetToolchain/bin/clang++"

    override val useCompilerDriverAsLinker: Boolean get() = true

    override fun filterStaticLibraries(binaries: List<String>)
        = binaries.filter { it.isWindowsStaticLib || it.isUnixStaticLib }

    override fun linkCommand(objectFiles: List<ObjectFile>, executable: ExecutableFile, optimize: Boolean, debug: Boolean, dynamic: Boolean, staticLibraries: List<StaticLibrary>): Command {
        return Command(linker).apply {
            + listOf("-o", executable)
            + objectFiles
            if (optimize) + linkerOptimizationFlags
            if (!debug)  + linkerNoDebugFlags
            if (dynamic) + linkerDynamicFlags
        }
    }

    override fun linkCommandSuffix() = linkerKonanFlags
}

internal open class WasmPlatform(distribution: Distribution)
    : PlatformFlags(distribution.targetProperties) {

    private val clang = "clang"

    override val useCompilerDriverAsLinker: Boolean get() = false

    override fun filterStaticLibraries(binaries: List<String>)
        = emptyList<String>()

    override fun linkCommand(objectFiles: List<ObjectFile>, executable: ExecutableFile, optimize: Boolean, debug: Boolean, dynamic: Boolean, staticLibraries: List<StaticLibrary>): Command {
        return object: Command("") {
            override fun execute() {
                val src = File(objectFiles.single())
                val dst = File(executable)
                src.recursiveCopyTo(dst)
                javaScriptLink(libs.filter{it.isJavaScript}, executable)
            }

            private fun javaScriptLink(jsFiles: List<String>, executable: String): String {
                val linkedJavaScript = File("$executable.js")

                val jsLibsExceptLauncher = jsFiles.filter { it != "launcher.js" }.map { it.removeSuffix(".js") }

                val linkerStub = "var konan = { libraries: [] };\n"

                linkedJavaScript.writeBytes(linkerStub.toByteArray());

                jsFiles.forEach {
                    linkedJavaScript.appendBytes(File(it).readBytes())
                }
                return linkedJavaScript.name
            }
        }
    }
}

interface CommandExecutor {
    fun hostLlvmTool(tool: String, args: List<String>)

    fun targetTool(tool: String, vararg arg: String)
}

internal class ContextCommandExecutor(val context: Context, val platform: PlatformFlags) : CommandExecutor {
    val distribution: Distribution = context.config.distribution

    override fun hostLlvmTool(tool: String, args: List<String>) {
        val absoluteToolName = "${distribution.llvmBin}/$tool"
        val command = listOf(absoluteToolName) + args
        runTool(*command.toTypedArray())
    }

    override fun targetTool(tool: String, vararg arg: String) {
        val absoluteToolName = "${platform.targetToolchain}/bin/$tool"
        runTool(absoluteToolName, *arg)
    }

    private fun runTool(vararg command: String) {
        val code = executeCommand(*command)
        if (code != 0) throw KonanExternalToolFailure("The ${command[0]} command returned non-zero exit code: $code.")
    }

    private fun executeCommand(vararg command: String): Int {

        context.log{""}
        context.log{command.asList().joinToString(" ")}

        val builder = ProcessBuilder(command.asList())

        // Inherit main process output streams.
        val isDsymUtil = platform is MacOSBasedPlatform && command[0] == platform.dsymutil

        builder.redirectOutput(Redirect.INHERIT)
        builder.redirectInput(Redirect.INHERIT)
        if (!isDsymUtil)
            builder.redirectError(Redirect.INHERIT)


        val process = builder.start()
        if (isDsymUtil) {
            /**
             * llvm-lto has option -alias that lets tool to know which symbol we use instead of _main,
             * llvm-dsym doesn't have such a option, so we ignore annoying warning manually.
             */
            val errorStream = process.errorStream
            val outputStream = bufferedReader(errorStream)
            while (true) {
                val line = outputStream.readLine() ?: break
                if (!line.contains("warning: could not find object file symbol for symbol _main"))
                    System.err.println(line)
            }
            outputStream.close()
        }
        val exitCode = process.waitFor()
        return exitCode
    }
}

/**
 * Contains information required by compilation and link stages
 */
internal class BackendSetup(val context: Context) {
    val config = context.config.configuration
    val target = context.config.targetManager.target

    val distribution = context.config.distribution
    val optimize = config.get(KonanConfigKeys.OPTIMIZATION) ?: false
    val debug = config.get(KonanConfigKeys.DEBUG) ?: false

    val platform = when (target) {
        KonanTarget.LINUX, KonanTarget.RASPBERRYPI,
        KonanTarget.LINUX_MIPS32, KonanTarget.LINUX_MIPSEL32 ->
            LinuxBasedPlatform(distribution)
        KonanTarget.MACBOOK, KonanTarget.IPHONE, KonanTarget.IPHONE_SIM ->
            MacOSBasedPlatform(distribution)
        KonanTarget.ANDROID_ARM32, KonanTarget.ANDROID_ARM64 ->
            AndroidPlatform(distribution)
        KonanTarget.MINGW ->
            MingwPlatform(distribution)
        KonanTarget.WASM32 ->
            WasmPlatform(distribution)
    }
}

internal class CompilationStage(setup: BackendSetup):
        CommandExecutor by ContextCommandExecutor(setup.context, setup.platform) {

    val context = setup.context
    val platform = setup.platform
    val target = setup.target
    val optimize = setup.optimize
    val debug = setup.debug
    val config = setup.config

    fun produceObjectFiles(bitcodeFiles: List<BitcodeFile>): List<ObjectFile> {
        return listOf(
                when {
                    target == KonanTarget.WASM32 -> bitcodeToWasm(bitcodeFiles)
                    bitcodeFiles.size == 1 -> llc(opt(bitcodeFiles[0]))
                    else -> llc(opt(link(bitcodeFiles)))
                }
        )
    }

    fun produceStaticLibrary(bitcodeFiles: List<BitcodeFile>): StaticLibrary =
            bitcodeFiles.map {
                when (target) {
                    KonanTarget.WASM32 -> bitcodeToWasm(bitcodeFiles)
                    else -> llc(opt(it))
                }
            }.let { llvmAr(it) }

//    private fun MutableList<String>.addNonEmpty(elements: List<String>) {
//        addAll(elements.filter { !it.isEmpty() })
//    }

//    private fun llvmLto(files: List<BitcodeFile>): ObjectFile {
//        val combined = temporary("combined", ".o")
//
//        val args = mutableListOf("-o", combined)
//        args.addNonEmpty(platform.llvmLtoFlags)
//        when {
//            optimize -> args.addNonEmpty(platform.llvmLtoOptFlags)
//            debug    -> args.addNonEmpty(platform.llvmDebugOptFlags)
//            else     -> args.addNonEmpty(platform.llvmLtoNooptFlags)
//        }
//        args.addNonEmpty(platform.llvmLtoDynamicFlags)
//        args.addNonEmpty(files)
//        hostLlvmTool("llvm-lto", args)
//        return combined
//    }

    private fun link(files: List<BitcodeFile>): BitcodeFile {
        val linked = temporary("linked", ".o")
        val args = listOf(*files.toTypedArray(), "-o", linked)
        hostLlvmTool("llvm-link", args)
        return linked
    }

    private fun llc(file: BitcodeFile): ObjectFile {
        val compiled = temporary("compiled", ".o")
        val flags = when {
            optimize -> platform.llvmLtoOptFlags
            debug    -> platform.llvmDebugOptFlags
            else     -> platform.llvmLtoNooptFlags
        }
        val args = listOf(file, *flags.toTypedArray(), "-filetype=obj", "-o", compiled)
        hostLlvmTool("llc", args)
        return compiled
    }

    private fun opt(file: BitcodeFile): BitcodeFile {
        val optimized = temporary("optimized", ".bc")
        val flags = when {
            optimize    -> "-O3"
            debug       -> "-O0"
            else        -> "-O1"
        }
        val args = listOf(file, flags, "-o", optimized)
        hostLlvmTool("opt", args)
        return optimized
    }

    // TODO: what are the benefits of packing .bc instead of .o?
    private fun llvmAr(files: List<ObjectFile>): StaticLibrary {
        val output = createTempFileName("lib", ".a").absolutePath
        val args = listOf("rcs", output, *files.toTypedArray())
        hostLlvmTool("llvm-ar", args)
        return output
    }

    private fun temporary(name: String, suffix: String): String {
        val temporaryFile = createTempFile(name, suffix)
        temporaryFile.deleteOnExit()
        return temporaryFile.absolutePath
    }

    fun bitcodeToWasm(bitcodeFiles: List<BitcodeFile>): String {
        val combinedBc = temporary("combined", ".bc")
        hostLlvmTool("llvm-link", bitcodeFiles + listOf("-o", combinedBc))

        val combinedS = temporary("combined", ".s")
        targetTool("llc", combinedBc, "-o", combinedS)

        val s2wasmFlags = platform.s2wasmFlags.toTypedArray()
        val combinedWast = temporary( "combined", ".wast")
        targetTool("s2wasm", combinedS, "-o", combinedWast, *s2wasmFlags)

        val combinedWasm = temporary( "combined", ".wasm")
        val combinedSmap = temporary( "combined", ".smap")
        targetTool("wasm-as", combinedWast, "-o", combinedWasm, "-g", "-s", combinedSmap)
        return combinedWasm
    }
}


internal fun compileObjectFiles(context: Context, bitcodeFiles: List<BitcodeFile>): List<ObjectFile> =
        CompilationStage(BackendSetup(context)).produceObjectFiles(bitcodeFiles)


internal fun compileStaticLibrary(context: Context, bitcodeFiles: List<BitcodeFile>): StaticLibrary =
        CompilationStage(BackendSetup(context)).produceStaticLibrary(bitcodeFiles)


internal class LinkStage(setup: BackendSetup) {

    val context = setup.context
    val platform = setup.platform
    val target = setup.target
    val optimize = setup.optimize
    val debug = setup.debug
    val config = setup.config

    private val dynamic = context.config.produce == CompilerOutputKind.DYNAMIC ||
            context.config.produce == CompilerOutputKind.FRAMEWORK

    private val nomain = config.get(KonanConfigKeys.NOMAIN) ?: false
    private val libraries = context.llvm.librariesToLink

    private fun asLinkerArgs(args: List<String>): List<String> {
        if (platform.useCompilerDriverAsLinker) {
            return args
        }
        val result = mutableListOf<String>()
        for (arg in args) {
            // If user passes compiler arguments to us - transform them to linker ones.
            if (arg.startsWith("-Wl,")) {
                result.addAll(arg.substring(4).split(','))
            } else {
                result.add(arg)
            }
        }
        return result
    }

    // Ideally we'd want to have 
    //      #pragma weak main = Konan_main
    // in the launcher.cpp.
    // Unfortunately, anything related to weak linking on MacOS
    // only seems to be working with dynamic libraries.
    // So we stick to "-alias _main _konan_main" on Mac.
    // And just do the same on Linux.
    private val entryPointSelector: List<String>
        get() = if (nomain || dynamic) emptyList() else platform.entrySelector

    private fun link(objectFiles: List<ObjectFile>, defaultLibs: List<String>, userProvidedLibs: List<String>, libraryProvidedLinkerFlags: List<String>): ExecutableFile? {
        val frameworkLinkerArgs: List<String>
        val executable: String

        if (context.config.produce != CompilerOutputKind.FRAMEWORK) {
            frameworkLinkerArgs = emptyList()
            executable = context.config.outputFile
        } else {
            val framework = File(context.config.outputFile)
            val dylibName = framework.name.removeSuffix(".framework")
            val dylibRelativePath = when (target) {
                KonanTarget.IPHONE, KonanTarget.IPHONE_SIM -> dylibName
                KonanTarget.MACBOOK -> "Versions/A/$dylibName"
                else -> error(target)
            }
            frameworkLinkerArgs = listOf("-install_name", "@rpath/${framework.name}/$dylibRelativePath")
            val dylibPath = framework.child(dylibRelativePath)
            dylibPath.parentFile.mkdirs()
            executable = dylibPath.absolutePath
        }

        try {
            val staticLibs = platform.linkStaticLibraries(includedBinaries)
            platform.linkCommand(objectFiles, executable, optimize, debug, dynamic, staticLibs).apply {
                + platform.targetLibffi
                + asLinkerArgs(config.getNotNull(KonanConfigKeys.LINKER_ARGS))
                + entryPointSelector
                + frameworkLinkerArgs
                + platform.linkCommandSuffix()
                + libraryProvidedLinkerFlags
//                externalLibraries(includedBinaries)
            }.execute()
        } catch (e: KonanExternalToolFailure) {
            context.reportCompilationError("linker invocation reported errors")
            return null
        }
        return executable
    }

    fun linkStage(objectFiles: List<String>) {
        context.log{"# Compiler root: ${context.config.distribution.konanHome}"}

        val includedBinaries =
            libraries.map{ it.includedPaths }.flatten()

        val (defalutLibs, userProvidedLibs) = libraries.partition { it.isDefaultLibrary }

        val libraryProvidedLinkerFlags =
            libraries.map{ it.linkerOpts }.flatten()

        link(
                objectFiles,
                defalutLibs.map { it.includedPaths }.flatten(),
                userProvidedLibs.map { it.includedPaths }.flatten(),
                libraryProvidedLinkerFlags
        )
    }
}

